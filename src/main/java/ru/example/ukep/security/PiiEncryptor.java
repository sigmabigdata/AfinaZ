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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Шифрование PII-полей (email, phone, originalName, signerSubject).
 * AES-256-GCM, формат: [IV 12 байт][ciphertext + tag].
 * Ключ — отдельный pii.key, не пересекается с file.key.
 *
 * Также даёт SHA-256 hash для exact-поиска (email, phone).
 */
@Component
public class PiiEncryptor {

    private static final Logger log = LoggerFactory.getLogger(PiiEncryptor.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public PiiEncryptor(
            @Value("${app.pii-key-path:/opt/afinaz/secrets/pii.key}") String keyPath) {
        try {
            Path p = Path.of(keyPath);
            if (!Files.isReadable(p)) {
                throw new IllegalStateException("PII ключ не найден: " + keyPath);
            }
            byte[] raw = Base64.getDecoder().decode(
                    Files.readString(p, StandardCharsets.UTF_8).trim());
            if (raw.length != KEY_LENGTH) {
                throw new IllegalStateException("PII ключ должен быть 32 байта, получено " + raw.length);
            }
            this.key = new SecretKeySpec(raw, "AES");
            log.info("PiiEncryptor: ключ загружен из {}", keyPath);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать PII ключ", e);
        }
    }

    /** Шифрует строку, возвращает base64 от [IV||ct||tag]. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LENGTH + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(ct, 0, out, IV_LENGTH, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка шифрования PII", e);
        }
    }

    /** Расшифровывает base64-строку. Если не похоже на шифротекст — возвращает как есть (legacy). */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) return ciphertext;
        // Heuristic: наш формат всегда base64 минимум 20 символов и содержит +/= 
        // Пробуем расшифровать; если не получится — считаем legacy plaintext.
        try {
            byte[] raw = Base64.getDecoder().decode(ciphertext);
            if (raw.length < IV_LENGTH + 16) return ciphertext;
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[raw.length - IV_LENGTH];
            System.arraycopy(raw, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Не наш формат — это legacy plaintext
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

    /** Hash от телефона — только цифры. */
    public String hashPhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        return hash(digits);
    }
}
