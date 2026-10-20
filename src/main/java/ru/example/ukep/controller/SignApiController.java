package ru.example.ukep.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.dto.SignRequest;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.SignatureVerifier;

import java.util.Map;

@RestController
@RequestMapping("/api/sign")
public class SignApiController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SignApiController.class);
    private static final String K_ERROR = "error";

    private final DocumentService documentService;
    private final SignatureVerifier signatureVerifier;
    private final AuditService audit;
    private final UserRepository userRepository;
    private final PiiEncryptor pii;
    private final DocumentRepository documentRepository;
    private final ru.example.ukep.service.SignatureVisualizationService visualizationService;

    public SignApiController(DocumentService documentService,
                             SignatureVerifier signatureVerifier,
                             AuditService audit,
                             UserRepository userRepository,
                             DocumentRepository documentRepository,
                             PiiEncryptor pii,
                             ru.example.ukep.service.SignatureVisualizationService visualizationService) {
        this.documentService = documentService;
        this.signatureVerifier = signatureVerifier;
        this.audit = audit;
        this.userRepository = userRepository;
        this.pii = pii;
        this.documentRepository = documentRepository;
        this.visualizationService = visualizationService;
    }

    private User current(UserDetails p) {
        return userRepository.findByEmailHash(pii.hash(p.getUsername())).orElseThrow();
    }

    /** Пользователь подписывает свой документ. */
    @PostMapping("/accept")
    public ResponseEntity<Map<String, Object>> acceptUser(@RequestBody SignRequest req,
                                        @AuthenticationPrincipal UserDetails p) {
        User user = current(p);
        Document doc = documentService.getOwned(req.getDocumentId(), user);
        return doAccept(doc, user, user, req.getSignatureBase64());
    }

    /** Админ подписывает документ любого клиента. */
    @PostMapping("/admin/accept")
    public ResponseEntity<Map<String, Object>> acceptAdmin(@RequestBody SignRequest req,
                                         @AuthenticationPrincipal UserDetails p) {
        User admin = current(p);
        if (admin.getRole() != ru.example.ukep.entity.Role.ROLE_ADMIN) {
            return ResponseEntity.status(403).body(Map.of(K_ERROR, "Только для администратора"));
        }
        Document doc = documentRepository.findByIdWithOwner(req.getDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        // signer = admin (кто подписывает), owner = doc.getOwner() (чей документ)
        return doAccept(doc, admin, doc.getOwner(), req.getSignatureBase64());
    }

    /**
     * @param doc    документ
     * @param signer кто подписывает (клиент или админ) — привязывается к подписи
     * @param owner  владелец документа — для аудита
     * @param sig    base64 CAdES-BES
     */
    private ResponseEntity<Map<String, Object>> doAccept(Document doc, User signer, User owner, String sig) {
        String signerEmail = safeOwnerEmail(signer);
        String signerRole = signer != null && signer.getRole() != null ? signer.getRole().name() : "?";
        try {
            Map<String, Object> result = signatureVerifier.verifyDetached(
                    documentService.getBytes(doc), sig);
            String subject = String.valueOf(result.getOrDefault("signerSubject", ""));
            String serial = String.valueOf(result.getOrDefault("signerSerial", ""));
            // signer — не owner! у админа и клиента разные подписи
            documentService.addSignature(doc.getId(), signer, sig, subject, serial);
            audit.signSuccess(signerEmail, doc.getId(), subject + " (" + signerRole + ")");

            // Пересоздать визуализацию: новый штамп сверху
            try {
                if (visualizationService.findFor(doc).isPresent()) {
                    visualizationService.generate(doc);
                    log.info("Визуализация документа {} пересоздана после подписи", doc.getId());
                }
            } catch (Exception e) {
                log.warn("Не удалось пересоздать визуализацию: {}", e.getMessage());
            }

            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "signersCount", result.get("signersCount"),
                    "signersInfo", result.get("signersInfo")));
        } catch (IllegalArgumentException e) {
            audit.signFail(signerEmail, doc.getId(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(K_ERROR, e.getMessage()));
        } catch (Exception e) {
            audit.signFail(signerEmail, doc.getId(), e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of(K_ERROR, "Ошибка проверки подписи: " + e.getMessage()));
        }
    }

    /** Безопасно получить email владельца (не падать при LazyInit). */
    private String safeOwnerEmail(User owner) {
        try {
            return owner.getEmail();
        } catch (Exception e) {
            // LazyInit / detached-сущность: getId() обычно доступен без обращения к БД
            Long id;
            try {
                id = owner.getId();
            } catch (Exception inner) {
                id = null;
            }
            return "user#" + (id != null ? id : "?");
        }
    }
}
