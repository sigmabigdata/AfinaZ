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
        // Pre-check ДО криптопроверки: если уже подписал — отказываем мгновенно
        if (documentService.hasSignatureByUser(doc.getId(), user.getId())) {
            audit.signFail(safeOwnerEmail(user), doc.getId(), "Повторное подписание (user)");
            return ResponseEntity.status(409).body(Map.of(
                K_ERROR, "Вы уже подписали этот документ. Повторное подписание невозможно."));
        }
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
        // Pre-check ДО криптопроверки: если админ уже подписал — отказываем мгновенно
        if (documentService.hasSignatureByUser(doc.getId(), admin.getId())) {
            audit.signFail(safeOwnerEmail(admin), doc.getId(), "Повторное подписание (admin)");
            return ResponseEntity.status(409).body(Map.of(
                K_ERROR, "Вы уже подписали этот документ. Повторное подписание невозможно."));
        }
        // signer = admin (кто подписывает), owner = doc.getOwner() (чей документ)
        return doAccept(doc, admin, doc.getOwner(), req.getSignatureBase64());
    }

    /** Batch: клиент подписывает несколько своих документов одним запросом. */
    @PostMapping("/accept-batch")
    public ResponseEntity<Map<String, Object>> acceptUserBatch(
            @RequestBody SignRequest req,
            @AuthenticationPrincipal UserDetails p) {
        User user = current(p);
        if (req.getDocumentIds() == null || req.getDocumentIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(K_ERROR, "Список документов пуст"));
        }
        return doAcceptBatch(req.getDocumentIds(), user, user, req.getSignatureBase64(), false);
    }

    /** Batch: админ подписывает несколько документов. */
    @PostMapping("/admin/accept-batch")
    public ResponseEntity<Map<String, Object>> acceptAdminBatch(
            @RequestBody SignRequest req,
            @AuthenticationPrincipal UserDetails p) {
        User admin = current(p);
        if (admin.getRole() != ru.example.ukep.entity.Role.ROLE_ADMIN) {
            return ResponseEntity.status(403).body(Map.of(K_ERROR, "Только для администратора"));
        }
        if (req.getDocumentIds() == null || req.getDocumentIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(K_ERROR, "Список документов пуст"));
        }
        return doAcceptBatch(req.getDocumentIds(), admin, null, req.getSignatureBase64(), true);
    }

    private ResponseEntity<Map<String, Object>> doAcceptBatch(
            java.util.List<Long> ids, User signer, User owner,
            String sig, boolean asAdmin) {
        var results = new java.util.ArrayList<Map<String, Object>>();
        int ok = 0, fail = 0;
        for (Long id : ids) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("documentId", id);
            try {
                Document doc = asAdmin
                        ? documentRepository.findByIdWithOwner(id).orElseThrow(
                                () -> new IllegalArgumentException("Документ не найден"))
                        : documentService.getOwned(id, signer);
                if (documentService.hasSignatureByUser(doc.getId(), signer.getId())) {
                    r.put("ok", false);
                    r.put("error", "Уже подписан");
                    fail++;
                } else {
                    var resp = doAccept(doc, signer, asAdmin ? doc.getOwner() : owner, sig);
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        r.put("ok", true);
                        ok++;
                    } else {
                        r.put("ok", false);
                        r.put("error", resp.getBody());
                        fail++;
                    }
                }
            } catch (IllegalArgumentException e) {
                r.put("ok", false);
                r.put("error", e.getMessage());
                fail++;
            } catch (Exception e) {
                r.put("ok", false);
                r.put("error", "Ошибка: " + e.getMessage());
                fail++;
            }
            results.add(r);
        }
        return ResponseEntity.ok(Map.of(
                "ok", ok,
                "fail", fail,
                "total", ids.size(),
                "results", results));
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
