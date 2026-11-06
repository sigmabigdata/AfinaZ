package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.SignatureVerifier;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Публичная верификация подписи — БЕЗ авторизации.
 *
 * Используется:
 *   - по QR-коду на визуализации/справке (GET /api/verify/{uuid})
 *   - на странице /verify для проверки .sig (POST /api/verify/sig)
 *
 * Персональные данные не раскрываются больше, чем есть
 * в самом сертификате (ФИО, организация, серийник).
 */
@RestController
@RequestMapping("/api/verify")
public class PublicVerificationController {

    private static final Logger log = LoggerFactory.getLogger(PublicVerificationController.class);

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final SignatureVerifier signatureVerifier;

    public PublicVerificationController(DocumentRepository documentRepository,
                                        DocumentService documentService,
                                        SignatureVerifier signatureVerifier) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.signatureVerifier = signatureVerifier;
    }

    /** Публичная информация о документе и его подписях по UUID. */
    @GetMapping("/{uuid}")
    public ResponseEntity<Map<String, Object>> verifyByUuid(@PathVariable String uuid) {
        java.util.UUID parsed;
        try {
            parsed = java.util.UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Неверный формат UUID"));
        }

        Optional<Document> opt = documentRepository.findByPublicUuid(parsed);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Document doc = opt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("documentId", doc.getId());
        result.put("documentName", doc.getOriginalName());
        result.put("uploadedAt", doc.getUploadedAt());
        result.put("signed", doc.isSigned());

        var signatures = doc.getSignatures().stream().map(this::sigDto).toList();
        result.put("signatures", signatures);
        result.put("signatureCount", signatures.size());

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> sigDto(DocumentSignature s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("signedAt", s.getSignedAt());
        m.put("subject", s.getSignerSubject());
        m.put("serial", s.getSignerSerial());
        m.put("applyPoa", s.isApplyPoa());
        m.put("signerUserId", s.getSignerUser() != null ? s.getSignerUser().getId() : null);
        return m;
    }

    /** Публичная проверка отдельного .sig + файла (multipart, оба base64 в JSON). */
    @PostMapping("/sig")
    public ResponseEntity<Map<String, Object>> verifySig(@RequestBody Map<String, String> body) {
        String contentB64 = body.get("contentBase64");
        String sigB64 = body.get("signatureBase64");
        if (contentB64 == null || contentB64.isBlank()
                || sigB64 == null || sigB64.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Нужны contentBase64 и signatureBase64"));
        }

        try {
            byte[] content = ru.example.ukep.service.SignatureVerifier.decodeBase64Tolerant(contentB64);
            Map<String, Object> result = signatureVerifier.verifyDetachedBasic(content, sigB64);
            result.put("valid", true);
            return ResponseEntity.ok(result);
        } catch (SignatureVerifier.SignatureMismatchException e) {
            return ResponseEntity.ok(Map.of("valid", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Public verify sig failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("valid", false,
                    "error", "Не удалось проверить: " + e.getMessage()));
        }
    }

    private static String stripWhitespace(String s) {
        return s.replaceAll("\\s+", "");
    }
}
