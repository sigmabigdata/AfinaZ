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
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class FileEncryptor {

    private static final Logger log = LoggerFactory.getLogger(FileEncryptor.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS  = 128;
    private static final int KEY_LENGTH = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public FileEncryptor(
            @Value("${app.file-encryption-key-path:/opt/afina/secrets/file.key}") String keyPath) {
        try {
            Path p = Path.of(keyPath);
            if (!Files.isReadable(p)) {
                throw new IllegalStateException("Ключ шифрования не найден: " + keyPath);
            }
            byte[] raw = Base64.getDecoder().decode(
                    Files.readString(p, StandardCharsets.UTF_8).trim());
            if (raw.length != KEY_LENGTH) {
                throw new IllegalStateException("Ключ должен быть 32 байта, получено " + raw.length);
            }
            this.key = new SecretKeySpec(raw, "AES");
            log.info("FileEncryptor: ключ загружен из {}", keyPath);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать ключ шифрования", e);
        }
    }

    public Encrypted encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_LENGTH + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(ct, 0, out, IV_LENGTH, ct.length);
            return new Encrypted(out, Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка шифрования файла", e);
        }
    }

    public byte[] decrypt(byte[] encrypted) {
        try {
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

    /**
     * Результат шифрования: ciphertext + IV в Base64.
     *
     * <p>Record + явные equals/hashCode по содержимому массива
     * (Sonar java:S6206 + java:S6218).</p>
     */
    public record Encrypted(byte[] bytes, String ivBase64) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Encrypted other)) return false;
            return java.util.Arrays.equals(bytes, other.bytes)
                    && java.util.Objects.equals(ivBase64, other.ivBase64);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(bytes)
                    + java.util.Objects.hashCode(ivBase64);
        }

        @Override
        public String toString() {
            return "Encrypted[bytes=" + bytes.length + "B, ivBase64=" + ivBase64 + "]";
        }
    }
}
