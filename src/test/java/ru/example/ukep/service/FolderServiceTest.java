package ru.example.ukep.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.example.ukep.entity.DocumentFolder;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentFolderRepository;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.security.PiiEncryptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FolderServiceTest {

    @Mock DocumentFolderRepository folderRepository;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentService documentService;

    @TempDir Path tempDir;

    private PiiEncryptor pii;
    private FolderService service;

    private User client;
    private User admin;
    private User otherClient;

    @BeforeEach
    void setUp() throws Exception {
        Path keyPath = tempDir.resolve("pii.key");
        byte[] rawKey = new byte[32];
        new java.security.SecureRandom().nextBytes(rawKey);
        Files.writeString(keyPath, Base64.getEncoder().encodeToString(rawKey),
                StandardCharsets.UTF_8);
        pii = new PiiEncryptor(keyPath.toString());
        service = new FolderService(folderRepository, documentRepository, documentService, pii);

        client = new User();
        client.setId(1L);
        client.setEmail("client@example.com");
        client.setRole(Role.ROLE_USER);

        admin = new User();
        admin.setId(2L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ROLE_ADMIN);

        otherClient = new User();
        otherClient.setId(3L);
        otherClient.setEmail("other@example.com");
        otherClient.setRole(Role.ROLE_USER);
    }

    private DocumentFolder folderOwnedBy(User owner, Long id) {
        DocumentFolder f = new DocumentFolder();
        f.setId(id);
        f.setOwner(owner);
        return f;
    }

    // ============ create ============

    @Test
    @DisplayName("create — успех, сохраняет с hash")
    void createSuccess() {
        when(folderRepository.existsByOwnerAndNameHash(any(), any())).thenReturn(false);
        when(folderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DocumentFolder f = service.create(client, "  Contracts  ");
        assertThat(f.getName()).isEqualTo("Contracts");
        assertThat(f.getNameHash()).isNotNull().hasSize(64);
        assertThat(f.getOwner()).isEqualTo(client);
    }

    @Test
    @DisplayName("create с пустым именем → IllegalArgumentException")
    void createEmptyNameThrows() {
        assertThatThrownBy(() -> service.create(client, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create с дубликатом имени → IllegalArgumentException")
    void createDuplicateThrows() {
        when(folderRepository.existsByOwnerAndNameHash(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.create(client, "Contracts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже существует");
    }

    // ============ rename ============

    @Test
    @DisplayName("rename — успех, hash обновляется")
    void renameSuccess() {
        DocumentFolder existing = folderOwnedBy(client, 10L);
        existing.setName("Old");
        existing.setNameHash("oldhash");
        when(folderRepository.findByIdAndOwner(10L, client)).thenReturn(Optional.of(existing));
        when(folderRepository.existsByOwnerAndNameHash(any(), any())).thenReturn(false);
        when(folderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.rename(10L, client, "New");
        assertThat(existing.getName()).isEqualTo("New");
        assertThat(existing.getNameHash()).isNotEqualTo("oldhash");
    }

    @Test
    @DisplayName("rename несуществующего → IllegalArgumentException")
    void renameNotFoundThrows() {
        when(folderRepository.findByIdAndOwner(99L, client)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rename(99L, client, "X"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ============ delete — правила ============

    @Test
    @DisplayName("delete пустого раздела клиентом — OK")
    void deleteEmptyFolderByClient() throws Exception {
        DocumentFolder f = folderOwnedBy(client, 10L);
        when(folderRepository.findById(10L)).thenReturn(Optional.of(f));
        when(documentRepository.countByFolderAndSignedTrue(f)).thenReturn(0L);
        when(documentRepository.findAllByFolderOrderByUploadedAtDesc(f)).thenReturn(List.of());

        service.delete(10L, client);
        verify(folderRepository).delete(f);
    }

    @Test
    @DisplayName("delete раздела с подписанными клиентом → IAE, не удаляет")
    void deleteSignedFolderByClientThrows() {
        DocumentFolder f = folderOwnedBy(client, 10L);
        when(folderRepository.findById(10L)).thenReturn(Optional.of(f));
        when(documentRepository.countByFolderAndSignedTrue(f)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(10L, client))
                .isInstanceOf(IllegalArgumentException.class);

        verify(folderRepository, never()).delete(any());
    }

    @Test
    @DisplayName("delete раздела с подписанными админом — OK")
    void deleteSignedFolderByAdmin() throws Exception {
        DocumentFolder f = folderOwnedBy(client, 10L);
        when(folderRepository.findById(10L)).thenReturn(Optional.of(f));
        when(documentRepository.countByFolderAndSignedTrue(f)).thenReturn(2L);
        when(documentRepository.findAllByFolderOrderByUploadedAtDesc(f)).thenReturn(List.of());

        service.delete(10L, admin);
        verify(folderRepository).delete(f);
    }

    @Test
    @DisplayName("delete чужого раздела другим клиентом → IAE")
    void deleteSomeoneElsesFolderThrows() {
        DocumentFolder f = folderOwnedBy(client, 10L);
        when(folderRepository.findById(10L)).thenReturn(Optional.of(f));

        assertThatThrownBy(() -> service.delete(10L, otherClient))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete несуществующего → IAE")
    void deleteMissingThrows() {
        when(folderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(99L, client))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ============ countSignedInFolder ============

    @Test
    @DisplayName("countSignedInFolder возвращает число")
    void countSignedInFolder() {
        DocumentFolder f = folderOwnedBy(client, 10L);
        when(folderRepository.findByIdAndOwner(10L, client)).thenReturn(Optional.of(f));
        when(documentRepository.countByFolderAndSignedTrue(f)).thenReturn(5L);

        assertThat(service.countSignedInFolder(10L, client)).isEqualTo(5L);
    }

    // ============ getOwned ============

    @Test
    @DisplayName("getOwned чужого раздела → IAE")
    void getOwnedThrows() {
        when(folderRepository.findByIdAndOwner(10L, otherClient)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOwned(10L, otherClient))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ============ migrateUnfiled ============

    @Test
    @DisplayName("migrateUnfiled: legacy-документы → «Без раздела»")
    void migrateUnfiled() {
        var doc1 = new ru.example.ukep.entity.Document();
        doc1.setId(1L);
        var doc2 = new ru.example.ukep.entity.Document();
        doc2.setId(2L);

        when(documentRepository.findUnfiledByOwner(client)).thenReturn(List.of(doc1, doc2));
        when(folderRepository.findByOwnerAndNameHash(any(), any())).thenReturn(Optional.empty());
        when(folderRepository.save(any())).thenAnswer(inv -> {
            DocumentFolder f = inv.getArgument(0);
            f.setId(100L);
            return f;
        });
        when(documentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int moved = service.migrateUnfiled(client);
        assertThat(moved).isEqualTo(2);
        assertThat(doc1.getFolder()).isNotNull();
        assertThat(doc2.getFolder()).isNotNull();
    }

    @Test
    @DisplayName("migrateUnfiled: если нечего — 0, папка не создаётся")
    void migrateUnfiledEmpty() {
        when(documentRepository.findUnfiledByOwner(client)).thenReturn(List.of());
        assertThat(service.migrateUnfiled(client)).isZero();
        verify(folderRepository, never()).save(any());
    }
}
