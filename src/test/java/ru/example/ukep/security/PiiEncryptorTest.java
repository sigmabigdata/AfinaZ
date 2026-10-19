package ru.example.ukep.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiEncryptorTest {

    @TempDir Path tempDir;
    private PiiEncryptor encryptor;

    @BeforeEach
    void setUp() throws Exception {
        Path keyPath = tempDir.resolve("pii.key");
        byte[] rawKey = new byte[32];
        new java.security.SecureRandom().nextBytes(rawKey);
        Files.writeString(keyPath, Base64.getEncoder().encodeToString(rawKey),
                StandardCharsets.UTF_8);
        encryptor = new PiiEncryptor(keyPath.toString());
    }

    @Test
    @DisplayName("encrypt -> decrypt возвращает исходную строку")
    void encryptDecryptRoundtrip() {
        String plaintext = "user@example.com";
        String encrypted = encryptor.encrypt(plaintext);
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(encryptor.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Два encrypt одного plaintext дают разный ciphertext")
    void encryptIsNotDeterministic() {
        String plaintext = "Иванов Иван Иванович";
        String enc1 = encryptor.encrypt(plaintext);
        String enc2 = encryptor.encrypt(plaintext);
        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(encryptor.decrypt(enc1)).isEqualTo(plaintext);
        assertThat(encryptor.decrypt(enc2)).isEqualTo(plaintext);
    }

    @Test
    void encryptNullOrEmpty() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.encrypt("")).isEmpty();
    }

    @Test
    void decryptNullOrEmpty() {
        assertThat(encryptor.decrypt(null)).isNull();
        assertThat(encryptor.decrypt("")).isEmpty();
    }

    @Test
    void decryptLegacyPlaintextReturnsAsIs() {
        String legacy = "plain-text-from-old-version@example.com";
        assertThat(encryptor.decrypt(legacy)).isEqualTo(legacy);
        assertThat(encryptor.decrypt("YWJj")).isEqualTo("YWJj");
    }

    @Test
    void hashIsStable() {
        String h1 = encryptor.hash("user@example.com");
        String h2 = encryptor.hash("user@example.com");
        assertThat(h1).isEqualTo(h2).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void hashNormalizes() {
        assertThat(encryptor.hash("  User@Example.com  "))
                .isEqualTo(encryptor.hash("user@example.com"));
    }

    @Test
    void hashNullReturnsNull() {
        assertThat(encryptor.hash(null)).isNull();
    }

    @Test
    void hashPhoneIgnoresNonDigits() {
        assertThat(encryptor.hashPhone("+7 (999) 123-45-67"))
                .isEqualTo(encryptor.hashPhone("79991234567"));
    }

    @Test
    void hashPhoneEdgeCases() {
        assertThat(encryptor.hashPhone(null)).isNull();
        assertThat(encryptor.hashPhone("---")).isNull();
    }

    @Test
    void invalidKeyLengthThrows() throws Exception {
        Path badKey = tempDir.resolve("bad.key");
        Files.writeString(badKey, Base64.getEncoder().encodeToString(new byte[16]),
                StandardCharsets.UTF_8);
        String badKeyPath = badKey.toString();
        assertThatThrownBy(() -> new PiiEncryptor(badKeyPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 байта");
    }

    @Test
    void missingKeyThrows() {
        String absentPath = tempDir.resolve("absent.key").toString();
        assertThatThrownBy(() -> new PiiEncryptor(absentPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не найден");
    }
}
