package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ru.example.ukep.entity.AuditEvent;
import ru.example.ukep.repository.AuditEventRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Лента жизни документа: загрузка, просмотры, подписания,
 * визуализация, скачивания. Источник — audit_events с фильтром
 * по target_type='DOCUMENT' и target_id=docId.
 */
@Service
public class DocumentTimelineService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTimelineService.class);

    private final AuditEventRepository repo;

    public DocumentTimelineService(AuditEventRepository repo) {
        this.repo = repo;
    }

    public List<Map<String, Object>> timeline(Long docId, int limit) {
        if (docId == null) return List.of();
        int safe = Math.min(Math.max(limit, 1), 500);
        List<AuditEvent> events = repo.findByTargetTypeAndTargetIdOrderByEventTimeDesc(
                "DOCUMENT", String.valueOf(docId), PageRequest.of(0, safe));
        return events.stream().map(this::toDto).toList();
    }

    private Map<String, Object> toDto(AuditEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", e.getEventTime());
        m.put("type", e.getEventType());
        m.put("result", e.getResult());
        m.put("actor", e.getActorEmail());
        m.put("role", e.getActorRole());
        m.put("info", e.getTargetInfo());
        m.put("details", e.getDetails());
        m.put("icon", iconFor(e.getEventType()));
        m.put("label", labelFor(e.getEventType()));
        return m;
    }

    private static String iconFor(String type) {
        if (type == null) return "•";
        return switch (type) {
            case "DOC_UPLOAD" -> "📤";
            case "DOC_UPLOAD_SIG", "SIGN_SUCCESS" -> "✍";
            case "SIGN_FAIL" -> "⚠";
            case "DOC_DELETE" -> "🗑";
            case "VISUALIZATION_GENERATE", "VISUALIZATION_REGENERATE" -> "📄";
            case "SIG_APPLY_POA" -> "🏛";
            default -> "•";
        };
    }

    private static String labelFor(String type) {
        if (type == null) return "Событие";
        return switch (type) {
            case "DOC_UPLOAD" -> "Загружен";
            case "DOC_UPLOAD_SIG" -> "Подпись загружена (.sig)";
            case "SIGN_SUCCESS" -> "Подписан";
            case "SIGN_FAIL" -> "Отказ в подписании";
            case "DOC_DELETE" -> "Удалён";
            case "VISUALIZATION_GENERATE" -> "Визуализация сгенерирована";
            case "VISUALIZATION_REGENERATE" -> "Визуализация обновлена";
            case "SIG_APPLY_POA" -> "Доверенность применена";
            default -> type;
        };
    }
}
