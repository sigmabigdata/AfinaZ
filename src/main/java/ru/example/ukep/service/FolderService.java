package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentFolder;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentFolderRepository;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.security.PiiEncryptor;

import java.io.IOException;
import java.util.List;

/**
 * Разделы (папки) документов клиента.
 *
 * <p>Правила:
 * <ul>
 *   <li>Имя раздела шифруется AES-GCM, хранится hash для unique-проверки
 *       в рамках одного клиента</li>
 *   <li>Создавать/переименовывать может владелец или админ</li>
 *   <li>Удалить раздел, в котором есть <b>подписанные</b> документы,
 *       может <b>только</b> администратор</li>
 *   <li>Удалить раздел с неподписанными документами может и клиент —
 *       вместе с документами и файлами</li>
 *   <li>Legacy-документы без папки автоматически попадают в «Без раздела»</li>
 * </ul></p>
 */
@Service
public class FolderService {

    private static final Logger log = LoggerFactory.getLogger(FolderService.class);
    private static final String DEFAULT_FOLDER_NAME = "Без раздела";

    private final DocumentFolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final PiiEncryptor pii;

    public FolderService(DocumentFolderRepository folderRepository,
                         DocumentRepository documentRepository,
                         DocumentService documentService,
                         PiiEncryptor pii) {
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.pii = pii;
    }

    // ==================== Чтение ====================

    public List<DocumentFolder> listForOwner(User owner) {
        return folderRepository.findAllByOwnerOrderByNameAsc(owner);
    }

    /** Получить раздел, проверив что он принадлежит owner. */
    public DocumentFolder getOwned(Long id, User owner) {
        return folderRepository.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new IllegalArgumentException("Раздел не найден"));
    }

    /** Для админа: получить раздел без проверки владельца. */
    public DocumentFolder getById(Long id) {
        return folderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Раздел не найден"));
    }

    // ==================== Создание / переименование ====================

    public DocumentFolder create(User owner, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название раздела не может быть пустым");
        }
        String trimmed = name.trim();
        String hash = pii.hash(trimmed);
        if (folderRepository.existsByOwnerAndNameHash(owner, hash)) {
            throw new IllegalArgumentException("Раздел с таким названием уже существует");
        }
        DocumentFolder folder = new DocumentFolder();
        folder.setName(trimmed);
        folder.setNameHash(hash);
        folder.setOwner(owner);
        return folderRepository.save(folder);
    }

    @Transactional
    public void rename(Long folderId, User owner, String newName) {
        DocumentFolder folder = getOwned(folderId, owner);
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Название раздела не может быть пустым");
        }
        String trimmed = newName.trim();
        String newHash = pii.hash(trimmed);
        if (!newHash.equals(folder.getNameHash())
                && folderRepository.existsByOwnerAndNameHash(owner, newHash)) {
            throw new IllegalArgumentException("Раздел с таким названием уже существует");
        }
        folder.setName(trimmed);
        folder.setNameHash(newHash);
        folderRepository.save(folder);
    }

    // ==================== Удаление ====================

    /**
     * Удалить раздел вместе со всеми документами.
     *
     * <p>Клиент не может удалить раздел с подписанными документами.
     * Админ может — все документы будут удалены (с записью в audit контроллером).</p>
     */
    @Transactional
    public void delete(Long folderId, User actor) {
        DocumentFolder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new IllegalArgumentException("Раздел не найден"));

        boolean isOwner = folder.getOwner().getId().equals(actor.getId());
        boolean isAdmin = actor.getRole() == Role.ROLE_ADMIN;
        if (!isOwner && !isAdmin) {
            throw new IllegalArgumentException("Нет доступа к разделу");
        }

        long signedCount = documentRepository.countByFolderAndSignedTrue(folder);
        if (signedCount > 0 && !isAdmin) {
            throw new IllegalArgumentException(
                    "В разделе " + signedCount + " подписанных документ(ов). "
                    + "Удалить раздел может только администратор.");
        }

        List<Document> docs = documentRepository.findAllByFolderOrderByUploadedAtDesc(folder);
        for (Document doc : docs) {
            try {
                if (isAdmin) {
                    documentService.deleteAsAdmin(doc.getId());
                } else {
                    documentService.delete(doc.getId(), folder.getOwner());
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Не удалось удалить документ id=" + doc.getId() + ": " + e.getMessage(), e);
            }
        }

        folderRepository.delete(folder);
        log.info("Раздел удалён: id={}, name='{}', документов={}, actor={}",
                folderId, folder.getName(), docs.size(), actor.getEmail());
    }

    // ==================== Legacy-документы без папки ====================

    /** Получить (или создать) раздел «Без раздела» для legacy-документов. */
    @Transactional
    public DocumentFolder ensureDefault(User owner) {
        String hash = pii.hash(DEFAULT_FOLDER_NAME);
        return folderRepository.findByOwnerAndNameHash(owner, hash).orElseGet(() -> {
            DocumentFolder f = new DocumentFolder();
            f.setName(DEFAULT_FOLDER_NAME);
            f.setNameHash(hash);
            f.setOwner(owner);
            DocumentFolder saved = folderRepository.save(f);
            log.info("Создан раздел '{}' для {}", DEFAULT_FOLDER_NAME, owner.getEmail());
            return saved;
        });
    }

    /**
     * Перенести legacy-документы клиента (folder_id = NULL) в «Без раздела».
     * Вызывать при открытии дашборда клиента / карточки админа.
     */
    @Transactional
    public int migrateUnfiled(User owner) {
        List<Document> unfiled = documentRepository.findUnfiledByOwner(owner);
        if (unfiled.isEmpty()) return 0;
        DocumentFolder def = ensureDefault(owner);
        unfiled.forEach(d -> d.setFolder(def));
        documentRepository.saveAll(unfiled);
        log.info("Legacy-документы перенесены в '{}': {} шт, клиент={}",
                DEFAULT_FOLDER_NAME, unfiled.size(), owner.getEmail());
        return unfiled.size();
    }
}
