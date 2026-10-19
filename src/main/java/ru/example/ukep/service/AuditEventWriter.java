package ru.example.ukep.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.example.ukep.entity.AuditEvent;
import ru.example.ukep.repository.AuditEventRepository;

/**
 * Отдельный бин для записи аудит-событий в транзакции REQUIRES_NEW.
 *
 * Зачем отдельный бин: Spring AOP обрабатывает @Transactional только на
 * внешних вызовах. Если бы write() был в том же классе, что и вызывающий
 * метод, REQUIRES_NEW не срабатывал бы при self-invocation, и Sonar
 * справедливо ругался (java:S2229).
 */
@Service
public class AuditEventWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditEventWriter.class);

    private final AuditEventRepository repo;

    public AuditEventWriter(AuditEventRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditEntry entry) {
        try {
            AuditEvent e = new AuditEvent();
            e.setEventType(entry.type());
            e.setResult(entry.result());
            e.setActorEmail(entry.actorEmail());
            e.setActorRole(entry.actorRole());
            e.setTargetType(entry.targetType());
            e.setTargetId(entry.targetId());
            e.setTargetInfo(truncate(entry.targetInfo(), 500));
            e.setDetails(entry.details());

            HttpServletRequest req = currentRequest();
            if (req != null) {
                e.setActorIp(clientIp(req));
                e.setUserAgent(truncate(req.getHeader("User-Agent"), 500));
            }

            repo.save(e);
            if (log.isDebugEnabled()) {
                log.debug("audit: {} {}", entry.type(), entry.result());
            }
        } catch (Exception ex) {
            log.warn("audit failed: {} — {}", entry.type(), ex.getMessage());
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return req.getRemoteAddr();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
