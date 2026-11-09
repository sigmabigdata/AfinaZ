package ru.example.ukep.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.ukep.entity.DocumentTemplate;
import ru.example.ukep.repository.DocumentTemplateRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CRUD шаблонов + генерация PDF из HTML-шаблона с плейсхолдерами {{key}}.
 */
@Service
public class DocumentTemplateService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTemplateService.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_CSS = """
            @page { size: A4; margin: 20mm 15mm; }
            body { font-family: "DejaVu Sans", Arial, sans-serif; font-size: 12pt;
                   line-height: 1.5; color: #1f2430; }
            h1 { font-size: 16pt; text-align: center; margin-bottom: 24pt; }
            .date { text-align: right; margin-bottom: 24pt; }
            p { margin: 0 0 12pt 0; text-align: justify; }
            table.sign { width: 100%; margin-top: 40pt; }
            table.sign td { padding: 8pt 0; }
            table.sign td:last-child { text-align: right; }
            b { font-weight: bold; }
            """;

    private final DocumentTemplateRepository repository;

    public DocumentTemplateService(DocumentTemplateRepository repository) {
        this.repository = repository;
    }

    public record TemplateField(String key, String label, String type,
                                boolean required, String defaultValue) {
        public String defaultOr() { return defaultValue == null ? "" : defaultValue; }
        public boolean isDate() { return "date".equalsIgnoreCase(type); }
        public boolean isTextarea() { return "textarea".equalsIgnoreCase(type); }
    }

    // ============ CRUD ============

    public List<DocumentTemplate> listAll() {
        return repository.findAllByOrderBySortOrderAscNameAsc();
    }

    public List<DocumentTemplate> listActive() {
        return repository.findAllByActiveTrueOrderBySortOrderAscNameAsc();
    }

    public DocumentTemplate getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден"));
    }

    @Transactional
    public DocumentTemplate create(String name, String description, String htmlContent,
                                   String fieldsJson, int sortOrder, String actor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название шаблона обязательно");
        }
        if (htmlContent == null || htmlContent.isBlank()) {
            throw new IllegalArgumentException("HTML-содержимое обязательно");
        }
        if (fieldsJson != null && !fieldsJson.isBlank()) {
            validateFieldsJson(fieldsJson);
        }
        DocumentTemplate t = new DocumentTemplate();
        t.setName(name.trim());
        t.setDescription(description);
        t.setHtmlContent(htmlContent);
        t.setFieldsJson(fieldsJson);
        t.setCreatedBy(actor);
        t.setSortOrder(sortOrder);
        t.setActive(true);
        return repository.save(t);
    }

    @Transactional
    public DocumentTemplate update(Long id, String name, String description, String htmlContent,
                                    String fieldsJson, int sortOrder, boolean active) {
        DocumentTemplate t = getById(id);
        if (name != null && !name.isBlank()) t.setName(name.trim());
        if (description != null) t.setDescription(description);
        if (htmlContent != null && !htmlContent.isBlank()) t.setHtmlContent(htmlContent);
        if (fieldsJson != null && !fieldsJson.isBlank()) {
            validateFieldsJson(fieldsJson);
            t.setFieldsJson(fieldsJson);
        }
        t.setSortOrder(sortOrder);
        t.setActive(active);
        t.setUpdatedAt(Instant.now());
        return repository.save(t);
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    @Transactional
    public void toggleActive(Long id) {
        DocumentTemplate t = getById(id);
        t.setActive(!t.isActive());
        t.setUpdatedAt(Instant.now());
        repository.save(t);
    }

    // ============ Поля ============

    /** Парсит fields_json в список полей. Ошибки — пустой список. */
    public List<TemplateField> parseFields(DocumentTemplate t) {
        if (t.getFieldsJson() == null || t.getFieldsJson().isBlank()) return List.of();
        try {
            return MAPPER.readValue(t.getFieldsJson(),
                    new TypeReference<List<TemplateField>>() {});
        } catch (Exception e) {
            log.warn("parseFields failed for template {}: {}", t.getId(), e.getMessage());
            return List.of();
        }
    }

    /** Валидирует JSON полей (бросает IllegalArgumentException при ошибке). */
    public void validateFieldsJson(String json) {
        try {
            List<TemplateField> fields = MAPPER.readValue(json,
                    new TypeReference<List<TemplateField>>() {});
            for (TemplateField f : fields) {
                if (f.key() == null || f.key().isBlank()) {
                    throw new IllegalArgumentException("Поле без key");
                }
                if (!f.key().matches("[a-zA-Z0-9_]+")) {
                    throw new IllegalArgumentException(
                            "Ключ поля должен содержать только a-z, 0-9, _: " + f.key());
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Некорректный JSON полей: " + e.getMessage(), e);
        }
    }

    /** Все плейсхолдеры, использованные в HTML-шаблоне. */
    public List<String> extractPlaceholders(String html) {
        if (html == null) return List.of();
        Matcher m = PLACEHOLDER.matcher(html);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            String k = m.group(1);
            if (!out.contains(k)) out.add(k);
        }
        return out;
    }

    // ============ Генерация PDF ============

    /** Подставляет значения в HTML и рендерит PDF. */
    public byte[] renderPdf(DocumentTemplate t, Map<String, String> values) throws IOException {
        String rawHtml = t.getHtmlContent();
        if (rawHtml == null || rawHtml.isBlank()) {
            rawHtml = "<p style='color:red;'>Ошибка: текст шаблона пуст.</p>";
        }
        String html = substitute(rawHtml, values);
        String wrapped = wrapWithHtml(html, t.getName());

        // openhtmltopdf строго требует XHTML. Прогоняем через jsoup для очистки.
        Document doc = Jsoup.parse(wrapped);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        doc.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
        doc.outputSettings().charset(java.nio.charset.StandardCharsets.UTF_8);
        String xhtml = doc.html();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            
            // Явно подключаем TTF-шрифты с поддержкой кириллицы
            java.io.File regularFont = new java.io.File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
            java.io.File boldFont = new java.io.File("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf");
            
            if (regularFont.exists()) {
                builder.useFont(regularFont, "DejaVu Sans");
            }
            if (boldFont.exists()) {
                builder.useFont(boldFont, "DejaVu Sans");
            }
            
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("renderPdf failed for template {}: {}", t.getId(), e.getMessage(), e);
            throw new IOException("Ошибка генерации PDF: " + e.getMessage(), e);
        }
    }

    /** Заменяет все {{key}} на значения (с HTML-эскейпом). */
    private String substitute(String html, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String raw = values.get(key);
            String safe = raw == null ? "" : escapeHtml(raw);
            m.appendReplacement(sb, Matcher.quoteReplacement(safe));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String wrapWithHtml(String body, String title) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>"
                + escapeHtml(title == null ? "Документ" : title)
                + "</title><style>" + DEFAULT_CSS + "</style></head><body>"
                + body + "</body></html>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
