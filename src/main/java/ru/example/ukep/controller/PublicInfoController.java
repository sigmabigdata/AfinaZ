package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.entity.PowerOfAttorney;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.PowerOfAttorneyService;
import ru.example.ukep.service.SignatureVerifier;

import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Публичная страница информации о подписании документа.
 * Доступна по QR-коду из PDF-визуализации и справки.
 *
 * Не является полноценной криптографической верификацией:
 *   - не проверяет цепочку сертификатов
 *   - не проверяет truststore / CRL / EKU
 * Для юридически значимой проверки — аккредитованный УЦ / КриптоПро / Госуслуги.
 */
@Controller
public class PublicInfoController {

    private static final Logger log = LoggerFactory.getLogger(PublicInfoController.class);

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final SignatureVerifier signatureVerifier;
    private final PowerOfAttorneyService poaService;

    public PublicInfoController(DocumentRepository documentRepository,
                                DocumentService documentService,
                                SignatureVerifier signatureVerifier,
                                PowerOfAttorneyService poaService) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.signatureVerifier = signatureVerifier;
        this.poaService = poaService;
    }

    @GetMapping("/info/{uuid}")
    public String infoPage(@PathVariable String uuid, Model model) {
        model.addAttribute("uuid", uuid);
        return "info-document";
    }

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

        byte[] content = null;
        try {
            content = documentService.getBytes(doc);
        } catch (Exception e) {
            log.warn("infoJson: cannot read doc {}: {}", doc.getId(), e.getMessage());
        }

        List<Map<String, Object>> signatures = new ArrayList<>();
        for (DocumentSignature sig : doc.getSignatures()) {
            signatures.add(buildSignatureInfo(sig, content));
        }
        result.put("signatures", signatures);
        result.put("signatureCount", signatures.size());

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildSignatureInfo(DocumentSignature sig, byte[] content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", sig.getSignerSubject());
        m.put("serial", sig.getSignerSerial());
        m.put("signedAt", sig.getSignedAt());
        m.put("applyPoa", sig.isApplyPoa());

        // ─── Реквизиты сертификата подписанта (notBefore/notAfter) ───
        X509Certificate cert = signatureVerifier.extractSignerCert(sig.getSignatureBase64());
        if (cert != null) {
            Date notBefore = cert.getNotBefore();
            Date notAfter = cert.getNotAfter();
            Date now = new Date();
            m.put("certNotBefore", notBefore.toInstant().toString());
            m.put("certNotAfter", notAfter.toInstant().toString());
            m.put("certValidNow", now.after(notBefore) && now.before(notAfter));
        }

        // ─── Криптопроверка содержимого ───
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

        // ─── Доверенность (если applyPoa) ───
        if (sig.isApplyPoa()) {
            String fio = extractCn(sig.getSignerSubject());
            Optional<PowerOfAttorney> poa = poaService.findEffectiveByFio(fio);
            if (poa.isPresent()) {
                PowerOfAttorney p = poa.get();
                m.put("poaFound", true);
                m.put("poaStampNumber", p.getStampNumber());
                m.put("poaValidFrom", p.getValidFrom() != null ? p.getValidFrom().toString() : null);
                m.put("poaValidUntil", p.getValidUntil() != null ? p.getValidUntil().toString() : null);
                m.put("poaEffective", p.isEffective());
            } else {
                m.put("poaFound", false);
            }
        }

        return m;
    }

    private String extractCn(String subject) {
        if (subject == null) return "—";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(subject);
        return m.find() ? m.group(1).replace("\"", "").trim() : subject;
    }
}
