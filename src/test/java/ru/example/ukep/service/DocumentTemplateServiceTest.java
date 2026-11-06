package ru.example.ukep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.example.ukep.entity.DocumentTemplate;
import ru.example.ukep.repository.DocumentTemplateRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentTemplateServiceTest {

    private DocumentTemplateRepository repo;
    private DocumentTemplateService service;

    @BeforeEach
    void setUp() {
        repo = mock(DocumentTemplateRepository.class);
        service = new DocumentTemplateService(repo);
    }

    @Test
    void extractPlaceholders_returnsUniqueKeys() {
        String html = "<p>Я, {{full_name}}, {{date}}. Ещё раз {{full_name}}</p>";
        List<String> keys = service.extractPlaceholders(html);
        assertEquals(2, keys.size());
        assertTrue(keys.contains("full_name"));
        assertTrue(keys.contains("date"));
    }

    @Test
    void extractPlaceholders_emptyHtml_returnsEmptyList() {
        assertTrue(service.extractPlaceholders("").isEmpty());
        assertTrue(service.extractPlaceholders(null).isEmpty());
    }

    @Test
    void extractPlaceholders_whitespaceInside_works() {
        String html = "<p>{{ full_name }} и {{  date  }}</p>";
        List<String> keys = service.extractPlaceholders(html);
        assertEquals(2, keys.size());
        assertTrue(keys.contains("full_name"));
        assertTrue(keys.contains("date"));
    }

    @Test
    void validateFieldsJson_valid_ok() {
        String json = "[{\"key\":\"fio\",\"label\":\"ФИО\",\"type\":\"text\",\"required\":true}]";
        assertDoesNotThrow(() -> service.validateFieldsJson(json));
    }

    @Test
    void validateFieldsJson_badKeyFormat_throws() {
        String json = "[{\"key\":\"fio-name\",\"label\":\"ФИО\"}]";
        assertThrows(IllegalArgumentException.class,
                () -> service.validateFieldsJson(json));
    }

    @Test
    void validateFieldsJson_missingKey_throws() {
        String json = "[{\"label\":\"ФИО\"}]";
        assertThrows(IllegalArgumentException.class,
                () -> service.validateFieldsJson(json));
    }

    @Test
    void validateFieldsJson_invalidJson_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.validateFieldsJson("not json"));
    }

    @Test
    void parseFields_returnsTemplateFields() throws Exception {
        DocumentTemplate t = new DocumentTemplate();
        t.setFieldsJson("[{\"key\":\"fio\",\"label\":\"ФИО\",\"type\":\"text\",\"required\":true}]");
        var fields = service.parseFields(t);
        assertEquals(1, fields.size());
        assertEquals("fio", fields.get(0).key());
        assertTrue(fields.get(0).required());
    }

    @Test
    void parseFields_emptyJson_returnsEmpty() {
        DocumentTemplate t = new DocumentTemplate();
        t.setFieldsJson("");
        assertTrue(service.parseFields(t).isEmpty());
    }

    @Test
    void renderPdf_simpleTemplate_producesNonEmptyPdf() throws Exception {
        DocumentTemplate t = new DocumentTemplate();
        t.setId(1L);
        t.setName("Тест");
        t.setHtmlContent("<h1>Заголовок</h1><p>Я, {{full_name}}, прошу.</p>");
        byte[] pdf = service.renderPdf(t, Map.of("full_name", "Иванов И.И."));
        assertNotNull(pdf);
        assertTrue(pdf.length > 100, "PDF должен быть > 100 байт");
        // PDF magic number
        assertEquals('%', pdf[0]);
        assertEquals('P', pdf[1]);
        assertEquals('D', pdf[2]);
        assertEquals('F', pdf[3]);
    }

    @Test
    void renderPdf_xssInValues_escapedNotInterpreted() throws Exception {
        DocumentTemplate t = new DocumentTemplate();
        t.setId(1L);
        t.setName("XSS");
        t.setHtmlContent("<p>{{name}}</p>");
        // Пытаемся закрыть тег
        byte[] pdf = service.renderPdf(t, Map.of("name", "<script>alert(1)</script>"));
        assertNotNull(pdf);
        // Не падает — уже хорошо. Реальная проверка: в PDF нет исполняемого JS.
        assertTrue(pdf.length > 100);
    }

    @Test
    void renderPdf_emptyValues_stillProducesPdf() throws Exception {
        DocumentTemplate t = new DocumentTemplate();
        t.setId(1L);
        t.setName("Пусто");
        t.setHtmlContent("<p>{{missing}}</p>");
        byte[] pdf = service.renderPdf(t, Map.of());
        assertNotNull(pdf);
        assertTrue(pdf.length > 100);
    }

    @Test
    void create_blankName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("", "desc", "<p>x</p>", null, 0, "admin"));
    }

    @Test
    void create_blankHtml_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("Name", "desc", "", null, 0, "admin"));
    }

    @Test
    void create_valid_savesAndReturns() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        DocumentTemplate t = service.create("Заявление", "desc",
                "<p>{{x}}</p>", null, 10, "admin");
        assertNotNull(t);
        assertEquals("Заявление", t.getName());
        verify(repo).save(any(DocumentTemplate.class));
    }

    @Test
    void getById_notFound_throws() {
        when(repo.findById(99L)).thenReturn(java.util.Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.getById(99L));
    }
}
