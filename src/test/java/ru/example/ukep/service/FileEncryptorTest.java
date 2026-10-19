package ru.example.ukep.service;

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

class FileEncryptorTest {

    @TempDir Path tempDir;
    private FileEncryptor encryptor;
    private byte[] plaintext;

    @BeforeEach
    void setUp() throws Exception {
        Path keyPath = tempDir.resolve("file.key");
        byte[] rawKey = new byte[32];
        new java.security.SecureRandom().nextBytes(rawKey);
        Files.writeString(keyPath, Base64.getEncoder().encodeToString(rawKey),
                StandardCharsets.UTF_8);
        encryptor = new FileEncryptor(keyPath.toString());
        plaintext = "Документ с содержимым".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("encrypt → decrypt возвращает исходные байты")
    void roundtrip() {
        FileEncryptor.Encrypted enc = encryptor.encrypt(plaintext);
        assertThat(enc.bytes()).isNotEqualTo(plaintext);
        assertThat(encryptor.decrypt(enc.bytes())).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("Два encrypt одного plaintext дают разный ciphertext (случайный IV)")
    void encryptIsNotDeterministic() {
        FileEncryptor.Encrypted a = encryptor.encrypt(plaintext);
        FileEncryptor.Encrypted b = encryptor.encrypt(plaintext);
        assertThat(a.bytes()).isNotEqualTo(b.bytes());
        assertThat(a.ivBase64()).isNotEqualTo(b.ivBase64());
    }

    @Test
    @DisplayName("decrypt короткого (< IV+tag) → IllegalStateException с cause")
    void decryptTooShortThrows() {
        byte[] tooShort = new byte[10];
        assertThatThrownBy(() -> encryptor.decrypt(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ошибка расшифрования")
                .hasRootCauseMessage("Файл повреждён (слишком короткий)");
    }

    @Test
    @DisplayName("decrypt повреждённого ciphertext → IllegalStateException (auth tag)")
    void decryptTamperedThrows() {
        FileEncryptor.Encrypted enc = encryptor.encrypt(plaintext);
        byte[] tampered = enc.bytes().clone();
        tampered[tampered.length - 1] ^= 0x01;
        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Неверный размер ключа → IllegalStateException")
    void invalidKeySizeThrows() throws Exception {
        Path badKey = tempDir.resolve("bad.key");
        Files.writeString(badKey, Base64.getEncoder().encodeToString(new byte[16]),
                StandardCharsets.UTF_8);
        String badKeyPath = badKey.toString();
        assertThatThrownBy(() -> new FileEncryptor(badKeyPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 байта");
    }

    @Test
    @DisplayName("Отсутствующий ключ → IllegalStateException")
    void missingKeyThrows() {
        String absentPath = tempDir.resolve("absent.key").toString();
        assertThatThrownBy(() -> new FileEncryptor(absentPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не найден");
    }
}
