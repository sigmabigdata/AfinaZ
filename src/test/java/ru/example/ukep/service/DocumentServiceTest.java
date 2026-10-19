package ru.example.ukep.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentFolder;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.DocumentSignatureRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock DocumentSignatureRepository signatureRepository;

    @TempDir Path tempDir;

    private FileEncryptor fileEncryptor;
    private DocumentService service;
    private User owner;
    private DocumentFolder folder;

    @BeforeEach
    void setUp() throws Exception {
        Path keyPath = tempDir.resolve("file.key");
        byte[] rawKey = new byte[32];
        new java.security.SecureRandom().nextBytes(rawKey);
        Files.writeString(keyPath, Base64.getEncoder().encodeToString(rawKey),
                StandardCharsets.UTF_8);
        fileEncryptor = new FileEncryptor(keyPath.toString());

        Path storage = tempDir.resolve("storage");
        Files.createDirectories(storage);
        service = new DocumentService(documentRepository, signatureRepository,
                fileEncryptor, storage.toString());

        owner = new User();
        owner.setId(1L);
        owner.setEmail("client@example.com");
        owner.setRole(Role.ROLE_USER);

        folder = new DocumentFolder();
        folder.setId(10L);
        folder.setOwner(owner);
    }

    @Test
    @DisplayName("upload — успех, файл сохранён как .enc")
    void uploadSuccess() throws Exception {
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "PDF content".getBytes());

        Document doc = service.upload(file, owner, folder);

        assertThat(doc.getOriginalName()).isEqualTo("doc.pdf");
        assertThat(doc.getStoredName()).endsWith(".enc");
        assertThat(doc.isEncrypted()).isTrue();
        assertThat(doc.isSigned()).isFalse();
        assertThat(doc.getOwner()).isEqualTo(owner);
        assertThat(doc.getFileSha256()).hasSize(64);
        assertThat(Files.exists(tempDir.resolve("storage").resolve(doc.getStoredName()))).isTrue();
    }

    @Test
    @DisplayName("upload пустого файла → IllegalArgumentException")
    void uploadEmptyThrows() {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> service.upload(empty, owner, folder))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пустой");
    }

    @Test
    @DisplayName("upload с folder=null → IllegalArgumentException")
    void uploadNullFolderThrows() {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", "x".getBytes());
        assertThatThrownBy(() -> service.upload(file, owner, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("раздел");
    }

    @Test
    @DisplayName("upload в чужой folder → IllegalArgumentException")
    void uploadForeignFolderThrows() {
        User other = new User();
        other.setId(2L);
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", "x".getBytes());

        assertThatThrownBy(() -> service.upload(file, other, folder))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("другому");
    }

    @Test
    @DisplayName("delete подписанного клиентом → IllegalArgumentException")
    void deleteSignedByClientThrows() throws Exception {
        Document doc = new Document();
        doc.setId(5L);
        doc.setSigned(true);
        doc.setOwner(owner);
        doc.setStoredName("stored.enc");
        when(documentRepository.findByIdAndOwner(5L, owner)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.delete(5L, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("подписанный");
    }

    @Test
    @DisplayName("deleteAsAdmin подписанного — OK, файл удалён")
    void deleteAsAdminSigned() throws Exception {
        Document doc = new Document();
        doc.setId(5L);
        doc.setSigned(true);
        doc.setOwner(owner);
        doc.setStoredName("stored.enc");
        Path file = tempDir.resolve("storage").resolve("stored.enc");
        Files.write(file, new byte[]{1, 2, 3});

        when(documentRepository.findById(5L)).thenReturn(Optional.of(doc));
        when(signatureRepository.countByDocumentId(5L)).thenReturn(1L);

        service.deleteAsAdmin(5L);

        verify(documentRepository).delete(doc);
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("addSignature первый — устанавливает signed=true")
    void addSignatureFirst() {
        Document doc = new Document();
        doc.setId(5L);
        doc.setOwner(owner);
        doc.setSigned(false);
        when(documentRepository.findById(5L)).thenReturn(Optional.of(doc));
        when(signatureRepository.existsByDocumentIdAndSignerUserId(5L, 1L)).thenReturn(false);
        when(signatureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DocumentSignature sig = service.addSignature(5L, owner, "base64sig", "CN=Ivanov", "AA01");
        assertThat(sig.getSignatureBase64()).isEqualTo("base64sig");
        assertThat(doc.isSigned()).isTrue();
        assertThat(doc.getSignerSubject()).isEqualTo("CN=Ivanov");
    }

    @Test
    @DisplayName("addSignature повторно тем же пользователем → IllegalArgumentException")
    void addSignatureDuplicateThrows() {
        Document doc = new Document();
        doc.setId(5L);
        doc.setOwner(owner);
        when(documentRepository.findById(5L)).thenReturn(Optional.of(doc));
        when(signatureRepository.existsByDocumentIdAndSignerUserId(5L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.addSignature(5L, owner, "sig", "CN", "AA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже подписали");
    }

    @Test
    @DisplayName("deleteSignature запрещён (юридически значимая информация)")
    void deleteSignatureThrows() {
        assertThatThrownBy(() -> service.deleteSignature(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("невозможно");
        verify(signatureRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("getBytes — расшифровывает обратно в plaintext")
    void getBytesRoundtrip() throws Exception {
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        byte[] content = "roundtrip content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("f", "t.txt", "text/plain", content);

        Document doc = service.upload(file, owner, folder);
        byte[] decrypted = service.getBytes(doc);
        assertThat(decrypted).isEqualTo(content);
    }
}
