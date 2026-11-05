package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.DocumentTemplate;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.DocumentTemplateService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Админский UI для управления шаблонами документов.
 */
@Controller
@RequestMapping("/admin/templates")
public class AdminTemplateController {

    private static final Logger log = LoggerFactory.getLogger(AdminTemplateController.class);
    private static final String REDIR = "redirect:/admin/templates";

    private final DocumentTemplateService service;
    private final AuditService audit;

    public AdminTemplateController(DocumentTemplateService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    public String list(Model model) {
        List<DocumentTemplate> templates = service.listAll();
        model.addAttribute("templates", templates);
        model.addAttribute("total", templates.size());
        model.addAttribute("activeCount", templates.stream()
                .filter(DocumentTemplate::isActive).count());
        return "admin-templates";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("mode", "create");
        return "admin-template-edit";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        DocumentTemplate t = service.getById(id);
        model.addAttribute("mode", "edit");
        model.addAttribute("template", t);
        model.addAttribute("fields", service.parseFields(t));
        model.addAttribute("placeholders", service.extractPlaceholders(t.getHtmlContent()));
        return "admin-template-edit";
    }

    @PostMapping
    public String create(@RequestParam String name,
                          @RequestParam(required = false) String description,
                          @RequestParam String htmlContent,
                          @RequestParam(required = false) String fieldsJson,
                          @RequestParam(defaultValue = "0") int sortOrder,
                          java.security.Principal principal,
                          RedirectAttributes ra) {
        try {
            DocumentTemplate t = service.create(name, description, htmlContent,
                    fieldsJson, sortOrder,
                    principal != null ? principal.getName() : "unknown");
            audit.event("TEMPLATE_CREATE", "SUCCESS",
                    principal != null ? principal.getName() : "unknown",
                    "TEMPLATE", String.valueOf(t.getId()), t.getName(), null);
            ra.addFlashAttribute("ok", "Шаблон создан: " + t.getName());
            return REDIR;
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return "redirect:/admin/templates/new";
        }
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                          @RequestParam String name,
                          @RequestParam(required = false) String description,
                          @RequestParam String htmlContent,
                          @RequestParam(required = false) String fieldsJson,
                          @RequestParam(defaultValue = "0") int sortOrder,
                          @RequestParam(defaultValue = "false") boolean active,
                          java.security.Principal principal,
                          RedirectAttributes ra) {
        try {
            service.update(id, name, description, htmlContent, fieldsJson, sortOrder, active);
            audit.event("TEMPLATE_UPDATE", "SUCCESS",
                    principal != null ? principal.getName() : "unknown",
                    "TEMPLATE", String.valueOf(id), name, null);
            ra.addFlashAttribute("ok", "Шаблон сохранён");
            return REDIR;
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return "redirect:/admin/templates/" + id + "/edit";
        }
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                          java.security.Principal principal,
                          RedirectAttributes ra) {
        try {
            service.toggleActive(id);
            audit.event("TEMPLATE_TOGGLE", "SUCCESS",
                    principal != null ? principal.getName() : "unknown",
                    "TEMPLATE", String.valueOf(id), null, null);
            ra.addFlashAttribute("ok", "Статус изменён");
        } catch (Exception e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                          java.security.Principal principal,
                          RedirectAttributes ra) {
        try {
            DocumentTemplate t = service.getById(id);
            service.delete(id);
            audit.event("TEMPLATE_DELETE", "SUCCESS",
                    principal != null ? principal.getName() : "unknown",
                    "TEMPLATE", String.valueOf(id), t.getName(), null);
            ra.addFlashAttribute("ok", "Шаблон удалён");
        } catch (Exception e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return REDIR;
    }

    /** Превью PDF — рендерит с примерами значений (для просмотра в iframe). */
    @GetMapping("/{id}/preview.pdf")
    public ResponseEntity<byte[]> previewPdf(@PathVariable Long id) {
        try {
            DocumentTemplate t = service.getById(id);
            Map<String, String> demo = demoValues(t);
            byte[] pdf = service.renderPdf(t, demo);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''"
                            + URLEncoder.encode("preview-" + id + ".pdf", StandardCharsets.UTF_8))
                    .body(pdf);
        } catch (Exception e) {
            log.error("Preview PDF failed for template {}", id, e);
            return ResponseEntity.status(500).build();
        }
    }

    /** Рендер «на лету» из HTML (без сохранения шаблона) — для кнопки «Превью» в редакторе. */
    @PostMapping("/render-preview")
    @ResponseBody
    public ResponseEntity<byte[]> renderPreview(@RequestParam String htmlContent,
                                                 @RequestParam(required = false) String fieldsJson) {
        try {
            DocumentTemplate temp = new DocumentTemplate();
            temp.setId(0L);
            temp.setName("Превью");
            temp.setHtmlContent(htmlContent);
            temp.setFieldsJson(fieldsJson);
            Map<String, String> demo = demoValues(temp);
            byte[] pdf = service.renderPdf(temp, demo);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (Exception e) {
            log.error("Render preview failed", e);
            return ResponseEntity.status(500).build();
        }
    }

    /** Демо-значения для плейсхолдеров (чтобы превью было информативным). */
    private Map<String, String> demoValues(DocumentTemplate t) {
        Map<String, String> values = new HashMap<>();
        List<DocumentTemplateService.TemplateField> fields = service.parseFields(t);
        for (var f : fields) {
            String key = f.key();
            String label = f.label() == null ? key : f.label();
            if (f.isDate()) {
                values.put(key, java.time.LocalDate.now().toString());
            } else if (key.contains("email")) {
                values.put(key, "client@example.ru");
            } else if (key.contains("phone")) {
                values.put(key, "+7 (999) 123-45-67");
            } else if (key.contains("full_name") || key.contains("fio")) {
                values.put(key, "Иванов Иван Иванович");
            } else if (!f.defaultOr().isBlank()) {
                values.put(key, f.defaultOr());
            } else {
                values.put(key, "[" + label + "]");
            }
        }
        // Плейсхолдеры, которых нет в fields_json, тоже заполним демо
        for (String key : service.extractPlaceholders(t.getHtmlContent())) {
            values.putIfAbsent(key, "[" + key + "]");
        }
        return values;
    }
}
