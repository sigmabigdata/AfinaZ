package ru.example.ukep.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import ru.example.ukep.entity.AuditEvent;
import ru.example.ukep.repository.AuditEventRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentTimelineServiceTest {

    private AuditEventRepository repo;
    private DocumentTimelineService service;

    @BeforeEach
    void setUp() {
        repo = mock(AuditEventRepository.class);
        service = new DocumentTimelineService(repo);
    }

    @Test
    void timeline_nullDocId_returnsEmpty() {
        assertTrue(service.timeline(null, 10).isEmpty());
        verifyNoInteractions(repo);
    }

    @Test
    void timeline_emptyEvents_returnsEmptyList() {
        when(repo.findByTargetTypeAndTargetIdOrderByEventTimeDesc(
                anyString(), anyString(), any(PageRequest.class)))
                .thenReturn(List.of());
        assertTrue(service.timeline(1L, 10).isEmpty());
    }

    @Test
    void timeline_mapsAuditEventCorrectly() {
        AuditEvent e = new AuditEvent();
        e.setEventTime(Instant.parse("2026-01-15T10:00:00Z"));
        e.setEventType("DOC_UPLOAD");
        e.setResult("SUCCESS");
        e.setActorEmail("user@test.ru");
        e.setTargetInfo("file.pdf");
        when(repo.findByTargetTypeAndTargetIdOrderByEventTimeDesc(
                eq("DOCUMENT"), eq("42"), any(PageRequest.class)))
                .thenReturn(List.of(e));

        List<Map<String, Object>> result = service.timeline(42L, 10);
        assertEquals(1, result.size());

        Map<String, Object> m = result.get(0);
        assertEquals("DOC_UPLOAD", m.get("type"));
        assertEquals("SUCCESS", m.get("result"));
        assertEquals("user@test.ru", m.get("actor"));
        assertEquals("file.pdf", m.get("info"));
        assertEquals("📤", m.get("icon"));
        assertEquals("Загружен", m.get("label"));
    }

    @Test
    void timeline_knownEventTypes_haveIconsAndLabels() {
        String[][] cases = {
                {"DOC_UPLOAD", "📤"},
                {"SIGN_SUCCESS", "✍"},
                {"DOC_UPLOAD_SIG", "✍"},
                {"DOC_DELETE", "🗑"},
                {"VISUALIZATION_GENERATE", "📄"},
                {"SIG_APPLY_POA", "🏛"}
        };
        for (String[] c : cases) {
            AuditEvent e = new AuditEvent();
            e.setEventType(c[0]);
            when(repo.findByTargetTypeAndTargetIdOrderByEventTimeDesc(
                    anyString(), anyString(), any(PageRequest.class)))
                    .thenReturn(List.of(e));
            List<Map<String, Object>> r = service.timeline(1L, 10);
            assertEquals(c[1], r.get(0).get("icon"), "icon for " + c[0]);
        }
    }

    @Test
    void timeline_clampsLimit() {
        when(repo.findByTargetTypeAndTargetIdOrderByEventTimeDesc(
                anyString(), anyString(), any(PageRequest.class)))
                .thenReturn(List.of());
        service.timeline(1L, 10000);  // должно ограничиться до 500
        verify(repo).findByTargetTypeAndTargetIdOrderByEventTimeDesc(
                eq("DOCUMENT"), eq("1"), argThat(p -> p.getPageSize() <= 500));
    }
}
