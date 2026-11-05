package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentFolder;
import ru.example.ukep.entity.DocumentTemplate;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.DocumentTemplateService;
import ru.example.ukep.service.FolderService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиентский API для работы с шаблонами документов.
 * Доступно авторизованным пользователям.
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateApiController {

    private static final Logger log = LoggerFactory.getLogger(TemplateApiController.class);
    private static final String K_ERROR = "error";

    private final DocumentTemplateService templateService;
    private final DocumentService documentService;
    private final FolderService folderService;
    private final UserRepository userRepository;
    private final PiiEncryptor pii;
    private final AuditService audit;

    public TemplateApiController(DocumentTemplateService templateService,
                                 DocumentService documentService,
                                 FolderService folderService,
                                 UserRepository userRepository,
                                 PiiEncryptor pii,
                                 AuditService audit) {
        this.templateService = templateService;
        this.documentService = documentService;
        this.folderService = folderService;
        this.userRepository = userRepository;
        this.pii = pii;
        this.audit = audit;
    }

    private User current(UserDetails p) {
        return userRepository.findByEmailHash(pii.hash(p.getUsername())).orElseThrow();
    }

    /** Список активных шаблонов с полями. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@AuthenticationPrincipal UserDetails p) {
        if (p == null) return ResponseEntity.status(401).build();
        List<DocumentTemplate> list = templateService.listActive();
        var dtos = list.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("fields", templateService.parseFields(t));
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("templates", dtos));
    }

    /** Генерация документа из шаблона. */
    @PostMapping("/{id}/generate")
    public ResponseEntity<Map<String, Object>> generate(@PathVariable Long id,
                                                        @RequestBody GenerateRequest req,
                                                        @AuthenticationPrincipal UserDetails p) {
        if (p == null) return ResponseEntity.status(401).build();
        User user = current(p);

        try {
            DocumentTemplate template = templateService.getById(id);
            if (!template.isActive()) {
                return ResponseEntity.badRequest().body(Map.of(
                        K_ERROR, "Шаблон недоступен"));
            }

            if (req.folderId == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        K_ERROR, "Не выбран раздел"));
            }
            DocumentFolder folder = folderService.getOwned(req.folderId, user);

            if (req.values == null || req.values.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        K_ERROR, "Не заполнены поля"));
            }

            // Проверка обязательных полей
            for (var f : templateService.parseFields(template)) {
                if (f.required()) {
                    String v = req.values.get(f.key());
                    if (v == null || v.isBlank()) {
                        return ResponseEntity.badRequest().body(Map.of(
                                K_ERROR, "Не заполнено обязательное поле: " + f.label()));
                    }
                }
            }

            byte[] pdf = templateService.renderPdf(template, req.values);

            String stamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy_HHmm"));
            String fileName = template.getName() + "_" + stamp + ".pdf";

            Document doc = documentService.createDocument(
                    pdf, fileName, "application/pdf", user, folder);

            audit.event("DOC_FROM_TEMPLATE", "SUCCESS",
                    user.getEmail(), "DOCUMENT", String.valueOf(doc.getId()),
                    template.getName(), "Сгенерирован из шаблона id=" + id);

            log.info("Document generated from template: docId={}, template={}, user={}",
                    doc.getId(), template.getName(), user.getEmail());

            return ResponseEntity.ok(Map.of(
                    "documentId", doc.getId(),
                    "fileName", fileName,
                    "size", pdf.length));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(K_ERROR, e.getMessage()));
        } catch (Exception e) {
            log.error("Generate from template failed (template={})", id, e);
            return ResponseEntity.status(500).body(Map.of(
                    K_ERROR, "Ошибка генерации: " + e.getMessage()));
        }
    }

    /** DTO для тела запроса. */
    public static class GenerateRequest {
        public Long folderId;
        public Map<String, String> values;

        public Long getFolderId() { return folderId; }
        public void setFolderId(Long folderId) { this.folderId = folderId; }
        public Map<String, String> getValues() { return values; }
        public void setValues(Map<String, String> values) { this.values = values; }
    }
}
