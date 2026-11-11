package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Шифрование файлов документов (AES-256-GCM).
 * Поддерживает версии ключей: {@code secrets/keys/file.key.vN}, текущая — в {@code .current}.
 * При расшифровке версия передаётся из {@link ru.example.ukep.entity.Document#getKeyVersion()}.
 */
@Service
public class FileEncryptor {

    private static final Logger log = LoggerFactory.getLogger(FileEncryptor.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;

    private final Path keysDir;
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, SecretKey> keys = new ConcurrentHashMap<>();
    private volatile int currentVersion = 1;

    @org.springframework.beans.factory.annotation.Autowired
    public FileEncryptor(
            @Value("${app.file-encryption-key-path:/opt/afinaz/secrets/file.key}") String oldKeyPath,
            @Value("${app.keys-dir:/opt/afinaz/secrets/keys}") String keysDirPath) {
        this.keysDir = Paths.get(keysDirPath);
        try {
            loadOrMigrateLegacy(oldKeyPath);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось инициализировать ключи файлов", e);
        }
    }

    /** Back-compat для тестов: keysDir = соседняя папка "keys" рядом с oldKeyPath. */
    public FileEncryptor(String oldKeyPath) {
        this(oldKeyPath, defaultKeysDir(oldKeyPath));
    }

    private static String defaultKeysDir(String oldKeyPath) {
        Path parent = Paths.get(oldKeyPath).toAbsolutePath().getParent();
        return (parent == null ? Paths.get(".") : parent).resolve("keys").toString();
    }

    private void loadOrMigrateLegacy(String oldKeyPath) throws IOException {
        Files.createDirectories(keysDir);

        boolean hasAny = Files.list(keysDir)
                .anyMatch(p -> p.getFileName().toString().startsWith("file.key.v"));
        if (!hasAny) {
            Path legacy = Paths.get(oldKeyPath);
            if (Files.isReadable(legacy)) {
                Path v1 = keysDir.resolve("file.key.v1");
                Files.copy(legacy, v1);
                Files.writeString(keysDir.resolve("file.key.current"), "1");
                log.info("FileEncryptor: миграция legacy-ключа {} → {}", legacy, v1);
            } else {
                throw new IOException("Нет ключей файлов и нет legacy-ключа " + oldKeyPath);
            }
        }

        try (Stream<Path> stream = Files.list(keysDir)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().startsWith("file.key.v")).toList()) {
                String name = p.getFileName().toString();
                int ver = Integer.parseInt(name.substring("file.key.v".length()));
                keys.put(ver, loadKey(p));
            }
        }

        Path cur = keysDir.resolve("file.key.current");
        if (Files.isReadable(cur)) {
            currentVersion = Integer.parseInt(Files.readString(cur).trim());
        } else {
            currentVersion = keys.keySet().stream().max(Integer::compare).orElse(1);
            Files.writeString(cur, String.valueOf(currentVersion));
        }
        log.info("FileEncryptor: загружено версий: {}, текущая: {}", keys.size(), currentVersion);
    }

    private SecretKey loadKey(Path p) throws IOException {
        byte[] raw = Base64.getDecoder().decode(Files.readString(p, StandardCharsets.UTF_8).trim());
        if (raw.length != KEY_LENGTH) {
            throw new IOException("Ключ " + p + " должен быть 32 байта, получено " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public synchronized int rotate() throws IOException {
        int next = keys.keySet().stream().max(Integer::compare).orElse(0) + 1;
        Path newKeyPath = keysDir.resolve("file.key.v" + next);
        byte[] raw = new byte[KEY_LENGTH];
        random.nextBytes(raw);
        Files.writeString(newKeyPath, Base64.getEncoder().encodeToString(raw));
        try {
            Files.setPosixFilePermissions(newKeyPath, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) { }

        keys.put(next, new SecretKeySpec(raw, "AES"));
        currentVersion = next;
        Files.writeString(keysDir.resolve("file.key.current"), String.valueOf(next));
        log.warn("FileEncryptor: ротация ключа → v{}", next);
        return next;
    }

    public Encrypted encrypt(byte[] plaintext) {
        return encrypt(plaintext, currentVersion);
    }

    public Encrypted encrypt(byte[] plaintext, int version) {
        try {
            SecretKey key = keys.get(version);
            if (key == null) throw new IllegalStateException("Нет ключа v" + version);
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_LENGTH + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(ct, 0, out, IV_LENGTH, ct.length);
            return new Encrypted(out, Base64.getEncoder().encodeToString(iv), version);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка шифрования файла", e);
        }
    }

    public byte[] decrypt(byte[] encrypted, int version) {
        try {
            SecretKey key = keys.get(version);
            if (key == null) throw new IllegalStateException("Нет ключа v" + version);
            if (encrypted.length < IV_LENGTH + 16) {
                throw new IllegalArgumentException("Файл повреждён (слишком короткий)");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(encrypted, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = new byte[encrypted.length - IV_LENGTH];
            System.arraycopy(encrypted, IV_LENGTH, ct, 0, ct.length);
            return cipher.doFinal(ct);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка расшифрования", e);
        }
    }

    /** Для совместимости со старым кодом: расшифровывает без версии (v1). */
    public byte[] decrypt(byte[] encrypted) {
        return decrypt(encrypted, 1);
    }

    /**
     * Результат шифрования: ciphertext + IV в Base64 + версия ключа.
     */
    public record Encrypted(byte[] bytes, String ivBase64, int version) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Encrypted other)) return false;
            return java.util.Arrays.equals(bytes, other.bytes)
                    && java.util.Objects.equals(ivBase64, other.ivBase64)
                    && version == other.version;
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(bytes)
                    + java.util.Objects.hashCode(ivBase64)
                    + version;
        }

        @Override
        public String toString() {
            return "Encrypted[bytes=" + bytes.length + "B, iv=" + ivBase64 + ", v" + version + "]";
        }
    }
}
