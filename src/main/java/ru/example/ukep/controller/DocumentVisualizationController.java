package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentVisualization;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.SignatureVisualizationService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Визуализация подписи: генерация PDF со штампом в колонтитуле.
 * Только для админа — клиент визуализацию не видит.
 */
@Controller
@RequestMapping("/admin/documents/{id}")
public class DocumentVisualizationController {

    private static final Logger log = LoggerFactory.getLogger(DocumentVisualizationController.class);
    private static final String CONTENT_DISP = "attachment; filename*=UTF-8''";

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final SignatureVisualizationService visualizationService;
    private final AuditService audit;
    private final ru.example.ukep.service.SignatureVerifier signatureVerifier;
    private final ru.example.ukep.service.PowerOfAttorneyService poaService;

    public DocumentVisualizationController(DocumentRepository documentRepository,
                                            DocumentService documentService,
                                            SignatureVisualizationService visualizationService,
                                            AuditService audit,
                                            ru.example.ukep.service.SignatureVerifier signatureVerifier,
                                            ru.example.ukep.service.PowerOfAttorneyService poaService) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.visualizationService = visualizationService;
        this.audit = audit;
        this.signatureVerifier = signatureVerifier;
        this.poaService = poaService;
    }

    /** Скачать существующую или сгенерировать визуализацию и сразу отдать. */
    @GetMapping("/visualization")
    public Object download(@PathVariable Long id,
                           @RequestParam(defaultValue = "false") boolean regen,
                           java.security.Principal principal) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));

        try {
            DocumentVisualization vis;
            var existing = visualizationService.findFor(doc);
            if (existing.isPresent() && !regen) {
                vis = existing.get();
            } else {
                vis = visualizationService.generate(doc);
                audit.event("VISUALIZATION_GENERATE", "SUCCESS",
                        principal != null ? principal.getName() : "unknown",
                        "DOCUMENT", String.valueOf(id), null,
                        "version=" + vis.getVersion());
            }

            byte[] bytes = visualizationService.loadBytes(vis);
            String fileName = stripExtension(doc.getOriginalName()) + "_signed.pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISP +
                            URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                    .body(bytes);
        } catch (IllegalStateException e) {
            log.warn("Визуализация недоступна для doc {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        } catch (IOException e) {
            log.error("Ошибка визуализации doc {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"Ошибка генерации: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /** Пересоздать визуализацию (после добавления подписи / смены POA). */
    @PostMapping("/visualization/regenerate")
    public String regenerate(@PathVariable Long id,
                             java.security.Principal principal,
                             RedirectAttributes ra) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        Long ownerId = doc.getOwner().getId();
        try {
            DocumentVisualization vis = visualizationService.generate(doc);
            audit.event("VISUALIZATION_REGENERATE", "SUCCESS",
                    principal != null ? principal.getName() : "unknown",
                    "DOCUMENT", String.valueOf(id), null,
                    "version=" + vis.getVersion());
            ra.addFlashAttribute("ok", "Визуализация обновлена (v" + vis.getVersion() + ")");
        } catch (Exception e) {
            log.error("Regenerate failed for doc {}", id, e);
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return "redirect:/admin/users/" + ownerId;
    }

    /** Toggle: применить доверенность к визуализации (или нет). */
    @PostMapping("/apply-poa")
    @ResponseBody
    public ResponseEntity<?> applyPoa(@PathVariable Long id,
                                       @RequestParam boolean apply,
                                       java.security.Principal principal) {
        Document doc = documentRepository.findByIdWithSignatures(id)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        doc.setApplyPoa(apply);
        documentRepository.save(doc);

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("applyPoa", apply);

        if (apply && !doc.getSignatures().isEmpty()) {
            var first = doc.getSignatures().get(0);
            String fio = extractCn(first.getSignerSubject());
            response.put("certFio", fio);
            var poa = poaService.findEffectiveByFio(fio);
            response.put("poaFound", poa.isPresent());
            if (poa.isPresent()) {
                response.put("poaNumber", poa.get().getStampNumber());
                response.put("poaValidUntil",
                        poa.get().getValidUntil() != null ? poa.get().getValidUntil().toString() : null);
            } else {
                response.put("message",
                        "Доверенность для ФИО '" + fio + "' не найдена. "
                        + "Проверьте, что ФИО в сертификате совпадает с ФИО в доверенности.");
            }
        }

        audit.event("DOC_APPLY_POA", "SUCCESS",
                principal != null ? principal.getName() : "unknown",
                "DOCUMENT", String.valueOf(id), null,
                apply ? "Доверенность применяется" : "Доверенность отключена");

        try {
            if (visualizationService.findFor(doc).isPresent()) {
                visualizationService.generate(doc);
            }
        } catch (Exception e) {
            log.warn("Не удалось пересоздать визуализацию: {}", e.getMessage());
            response.put("regenError", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    /** Загрузка .sig файла и проверка соответствия документу. */
    @PostMapping("/upload-signature")
    public String uploadSignature(@PathVariable Long id,
                                   @org.springframework.web.bind.annotation.RequestParam("file")
                                   org.springframework.web.multipart.MultipartFile file,
                                   java.security.Principal principal,
                                   org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        Document doc = documentRepository.findByIdWithSignatures(id)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        Long ownerId = doc.getOwner().getId();

        try {
            if (file.isEmpty()) throw new IllegalArgumentException("Файл пустой");
            byte[] sigBytes = file.getBytes();
            String sigBase64 = java.util.Base64.getEncoder().encodeToString(sigBytes);

            // Верификация: подпись должна соответствовать содержимому документа
            java.util.Map<String, Object> result = signatureVerifier.verifyDetached(
                    documentService.getBytes(doc), sigBase64);
            String subject = String.valueOf(result.getOrDefault("signerSubject", "—"));
            String serial = String.valueOf(result.getOrDefault("signerSerial", "—"));

            // signer = null (не привязываем к пользователю — это подпись из файла)
            documentService.addSignature(id, null, sigBase64, subject, serial);
            audit.documentUploadSignature(
                    principal != null ? principal.getName() : "unknown", id, subject);

            // Пересоздать визуализацию
            try {
                visualizationService.generate(doc);
            } catch (Exception e) {
                log.warn("Регенерация визуализации после upload-signature: {}", e.getMessage());
            }

            ra.addFlashAttribute("ok",
                    "Подпись проверена и добавлена. Подписант: " + subject
                    + (serial != null && !"null".equals(serial) ? " (serial " + serial + ")" : ""));

        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", "Подпись не принята: " + e.getMessage());
        } catch (Exception e) {
            log.error("upload-signature failed for doc {}", id, e);
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return "redirect:/admin/users/" + ownerId;
    }

    private static String extractCn(String subject) {
        if (subject == null) return "—";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(subject);
        return m.find() ? m.group(1).replace("\"", "").trim() : subject;
    }

    private static String stripExtension(String name) {
        if (name == null) return "file";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
