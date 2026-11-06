package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.SignatureVerifier;

import java.util.*;

/**
 * Публичная страница информации о подписании документа.
 * Доступна по QR-коду из PDF-визуализации и справки.
 *
 * Не является полноценной криптографической верификацией:
 *   - не проверяет цепочку сертификатов
 *   - не проверяет truststore / CRL / EKU
 * Для юридически значимой проверки — аккредитованный УЦ / КриптоПро / Госуслуги.
 *
 * Показывает:
 *   - реквизиты документа (название, размер, SHA-256, дата загрузки)
 *   - подписантов (ФИО, серийник, дата подписания)
 *   - результат базовой криптопроверки каждой подписи (verifyDetachedBasic)
 */
@Controller
public class PublicInfoController {

    private static final Logger log = LoggerFactory.getLogger(PublicInfoController.class);

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final SignatureVerifier signatureVerifier;

    public PublicInfoController(DocumentRepository documentRepository,
                                DocumentService documentService,
                                SignatureVerifier signatureVerifier) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.signatureVerifier = signatureVerifier;
    }

    /** HTML-страница информации. */
    @GetMapping("/info/{uuid}")
    public String infoPage(@PathVariable String uuid, Model model) {
        model.addAttribute("uuid", uuid);
        return "info-document";
    }

    /** JSON для страницы (fetch). */
    @GetMapping("/api/info/{uuid}")
    @ResponseBody
    public ResponseEntity<?> infoJson(@PathVariable String uuid) {
        UUID parsed;
        try {
            parsed = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Неверный формат UUID"));
        }

        Optional<Document> opt = documentRepository.findByPublicUuid(parsed);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Document doc = opt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", doc.getId());
        result.put("documentName", doc.getOriginalName());
        result.put("sizeBytes", doc.getSize());
        result.put("uploadedAt", doc.getUploadedAt());
        result.put("sha256", doc.getFileSha256());

        // Базовая криптопроверка — для отображения статуса каждой подписи.
        // Цепочка/CRL/EKU не проверяются (это информационная страница).
        byte[] content = null;
        try {
            content = documentService.getBytes(doc);
        } catch (Exception e) {
            log.warn("infoJson: cannot read document bytes for id={}: {}", doc.getId(), e.getMessage());
        }

        List<Map<String, Object>> signatures = new ArrayList<>();
        for (DocumentSignature sig : doc.getSignatures()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", sig.getSignerSubject());
            m.put("serial", sig.getSignerSerial());
            m.put("signedAt", sig.getSignedAt());

            if (content != null && sig.getSignatureBase64() != null) {
                try {
                    var vr = signatureVerifier.verifyDetachedBasic(content, sig.getSignatureBase64());
                    m.put("cryptoOk", Boolean.TRUE.equals(vr.get("valid")));
                    m.put("cryptoMessage", null);
                } catch (SignatureVerifier.SignatureMismatchException e) {
                    m.put("cryptoOk", false);
                    m.put("cryptoMessage", "Подпись не соответствует документу");
                } catch (Exception e) {
                    m.put("cryptoOk", false);
                    m.put("cryptoMessage", "Не удалось проверить: " + e.getMessage());
                }
            } else {
                m.put("cryptoOk", false);
                m.put("cryptoMessage", "Не удалось получить содержимое документа");
            }
            signatures.add(m);
        }
        result.put("signatures", signatures);
        result.put("signatureCount", signatures.size());

        return ResponseEntity.ok(result);
    }
}
