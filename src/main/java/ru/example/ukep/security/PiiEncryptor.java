package ru.example.ukep.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Шифрование PII-полей (email, phone, originalName, signerSubject).
 *
 * <p>Поддерживает ротацию ключей:</p>
 * <ul>
 *   <li>Ключи лежат в {@code secrets/keys/pii.key.v1}, {@code pii.key.v2}, …</li>
 *   <li>Текущая версия — в файле {@code secrets/keys/pii.key.current} (просто число).</li>
 *   <li>Шифротекст имеет формат {@code v<version>:<base64>}.</li>
 *   <li>При расшифровке версия берётся из префикса; если префикса нет — считаем v1.</li>
 * </ul>
 *
 * <p>Ротация: {@code afina keys rotate pii} генерирует новый ключ и обновляет {@code .current}.
 * Старые данные остаются расшифровываемыми старыми ключами.</p>
 */
@Component
public class PiiEncryptor {

    private static final Logger log = LoggerFactory.getLogger(PiiEncryptor.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;
    private static final Pattern VERSION_PREFIX = Pattern.compile("^v(\\d+):(.*)$", Pattern.DOTALL);

    private final Path keysDir;
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, SecretKey> keys = new ConcurrentHashMap<>();
    private volatile int currentVersion = 1;

    @org.springframework.beans.factory.annotation.Autowired
    public PiiEncryptor(
            @Value("${app.pii-key-path:/opt/afinaz/secrets/pii.key}") String oldKeyPath,
            @Value("${app.keys-dir:/opt/afinaz/secrets/keys}") String keysDirPath) {
        this.keysDir = Paths.get(keysDirPath);
        try {
            loadOrMigrateLegacy(oldKeyPath);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось инициализировать ключи PII", e);
        }
    }

    /** Back-compat для тестов: keysDir = соседняя папка "keys" рядом с oldKeyPath. */
    public PiiEncryptor(String oldKeyPath) {
        this(oldKeyPath, defaultKeysDir(oldKeyPath));
    }

    private static String defaultKeysDir(String oldKeyPath) {
        Path parent = Paths.get(oldKeyPath).toAbsolutePath().getParent();
        return (parent == null ? Paths.get(".") : parent).resolve("keys").toString();
    }

    private void loadOrMigrateLegacy(String oldKeyPath) throws IOException {
        Files.createDirectories(keysDir);

        // Если нет ни одного pii.key.vN — мигрируем старый единый ключ в v1
        boolean hasAny = Files.list(keysDir)
                .anyMatch(p -> p.getFileName().toString().startsWith("pii.key.v"));
        if (!hasAny) {
            Path legacy = Paths.get(oldKeyPath);
            if (Files.isReadable(legacy)) {
                Path v1 = keysDir.resolve("pii.key.v1");
                Files.copy(legacy, v1);
                Files.writeString(keysDir.resolve("pii.key.current"), "1");
                log.info("PiiEncryptor: миграция legacy-ключа {} → {}", legacy, v1);
            } else {
                throw new IOException("Нет ключей PII и нет legacy-ключа " + oldKeyPath);
            }
        }

        // Загружаем все версии
        try (Stream<Path> stream = Files.list(keysDir)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().startsWith("pii.key.v")).toList()) {
                String name = p.getFileName().toString();
                int ver = Integer.parseInt(name.substring("pii.key.v".length()));
                keys.put(ver, loadKey(p));
            }
        }

        // Определяем текущую версию
        Path cur = keysDir.resolve("pii.key.current");
        if (Files.isReadable(cur)) {
            currentVersion = Integer.parseInt(Files.readString(cur).trim());
        } else {
            currentVersion = keys.keySet().stream().max(Integer::compare).orElse(1);
            Files.writeString(cur, String.valueOf(currentVersion));
        }
        log.info("PiiEncryptor: загружено версий: {}, текущая: {}", keys.size(), currentVersion);
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
        Path newKeyPath = keysDir.resolve("pii.key.v" + next);
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
        Files.writeString(keysDir.resolve("pii.key.current"), String.valueOf(next));
        log.warn("PiiEncryptor: ротация ключа → v{}", next);
        return next;
    }

    /** Шифрует строку. Формат: v<currentVersion>:<base64>. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            SecretKey key = keys.get(currentVersion);
            if (key == null) throw new IllegalStateException("Нет ключа v" + currentVersion);

            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LENGTH + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(ct, 0, out, IV_LENGTH, ct.length);

            String b64 = Base64.getEncoder().encodeToString(out);
            return "v" + currentVersion + ":" + b64;
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка шифрования PII", e);
        }
    }

    /** Расшифровывает. Если префикса нет — считаем v1 (legacy). */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) return ciphertext;

        int version;
        String payload;
        Matcher m = VERSION_PREFIX.matcher(ciphertext);
        if (m.matches()) {
            version = Integer.parseInt(m.group(1));
            payload = m.group(2);
        } else {
            version = 1;
            payload = ciphertext;
        }

        try {
            SecretKey key = keys.get(version);
            if (key == null) {
                log.warn("PiiEncryptor: неизвестная версия ключа {} — возвращаем шифротекст", version);
                return ciphertext;
            }
            byte[] raw = Base64.getDecoder().decode(payload);
            if (raw.length < IV_LENGTH + 16) return ciphertext;
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[raw.length - IV_LENGTH];
            System.arraycopy(raw, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Fallback: возможно, legacy plaintext
            return ciphertext;
        }
    }

    /** SHA-256 hash от нормализованной строки (для exact-поиска). */
    public String hash(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(input.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 error", e);
        }
    }

    /** Hash от телефона — нормализуем в 11-значный формат (7XXXXXXXXXX). */
    public String hashPhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        if (digits.length() == 10) {
            digits = "7" + digits;
        } else if (digits.length() == 11 && digits.charAt(0) == '8') {
            digits = "7" + digits.substring(1);
        }
        return hash(digits);
    }
}
