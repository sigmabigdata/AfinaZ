package ru.example.ukep.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;
import ru.example.ukep.entity.AuditEvent;
import ru.example.ukep.repository.AuditEventRepository;
import ru.example.ukep.service.AuditCleanupService;
import ru.example.ukep.service.SettingsService;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
@RequestMapping("/admin/audit")
public class AuditController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuditController.class);

    private static final DateTimeFormatter CSV_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // Максимум загружаем в память для фильтрации
    private static final int MAX_LOAD = 10_000;
    private static final String K_RETENTION = "audit.retention_days";
    private static final String REDIR_AUDIT = "redirect:/admin/audit";

    private final AuditEventRepository repo;
    private final AuditCleanupService cleanupService;
    private final SettingsService settings;

    public AuditController(AuditEventRepository repo,
                           AuditCleanupService cleanupService,
                           SettingsService settings) {
        this.repo = repo;
        this.cleanupService = cleanupService;
        this.settings = settings;
    }

    @GetMapping
    public String page(@RequestParam(required = false) String type,
                       @RequestParam(required = false) String actor,
                       @RequestParam(required = false) String result,
                       @RequestParam(name = "from", required = false) String fromStr,
                       @RequestParam(name = "to", required = false) String toStr,
                       @RequestParam(defaultValue = "200") int limit,
                       Model model) {
        int safeLimit = Math.min(Math.max(limit, 10), 2000);

        LocalDate from = parseDate(fromStr);
        LocalDate to = parseDate(toStr);

        log.info("audit filter: type='{}' actor='{}' result='{}' from='{}'->{} to='{}'->{}",
                type, actor, result, fromStr, from, toStr, to);
        List<AuditEvent> filtered = filterInMemory(type, actor, result, from, to);
        List<AuditEvent> page = filtered.stream().limit(safeLimit).toList();

        model.addAttribute("events", page);
        model.addAttribute("eventTypes", repo.findDistinctEventTypes());
        model.addAttribute("type", type == null ? "" : type);
        model.addAttribute("actor", actor == null ? "" : actor);
        model.addAttribute("result", result == null ? "" : result);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("limit", safeLimit);
        model.addAttribute("totalFound", filtered.size());

        int retentionDays = settings.getIntOrDefault(K_RETENTION, 365);
        model.addAttribute("retentionDays", retentionDays);

        // Статистика: сколько всего событий и сколько из них старше retention
        long totalEvents = repo.count();
        java.time.Instant cutoff = java.time.Instant.now()
                .minus(retentionDays, java.time.temporal.ChronoUnit.DAYS);
        long oldEvents = (retentionDays > 0) ? repo.countByEventTimeBefore(cutoff) : 0L;
        model.addAttribute("totalEvents", totalEvents);
        model.addAttribute("oldEvents", oldEvents);

        // Автоочистка: cron 0 0 4 * * * (ежедневно в 04:00)
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        java.time.ZonedDateTime next = now.toLocalDate().atStartOfDay(now.getZone())
                .plusHours(4);
        if (!next.isAfter(now)) next = next.plusDays(1);
        model.addAttribute("nextCleanup", next);

        // Последняя очистка
        String lastCleanupStr = settings.getOrDefault("audit.last_cleanup_at", "");
        if (!lastCleanupStr.isBlank()) {
            try {
                model.addAttribute("lastCleanupAt", java.time.Instant.parse(lastCleanupStr));
                model.addAttribute("lastCleanupDeleted",
                        settings.getOrDefault("audit.last_cleanup_deleted", "0"));
            } catch (Exception ex) {
                log.debug("lastCleanup parse failed: {}", ex.getMessage());
            }
        }
        return "admin-audit";
    }

    @GetMapping("/export.csv")
    public void exportCsv(@RequestParam(required = false) String type,
                          @RequestParam(required = false) String actor,
                          @RequestParam(required = false) String result,
                          @RequestParam(name = "from", required = false) String fromStr,
                          @RequestParam(name = "to", required = false) String toStr,
                          HttpServletResponse resp) throws java.io.IOException {

        LocalDate from = parseDate(fromStr);
        LocalDate to = parseDate(toStr);

        List<AuditEvent> events = filterInMemory(type, actor, result, from, to);

        resp.setContentType("text/csv;charset=UTF-8");
        resp.setHeader("Content-Disposition",
                "attachment; filename=\"audit-" + LocalDate.now() + ".csv\"");

        PrintWriter w = resp.getWriter();
        w.write('\ufeff');
        w.println("event_time,event_type,result,actor_email,actor_role,actor_ip," +
                  "target_type,target_id,target_info,details");

        for (AuditEvent e : events) {
            w.println(String.join(",",
                    csv(CSV_DATE.format(e.getEventTime())),
                    csv(e.getEventType()),
                    csv(e.getResult()),
                    csv(e.getActorEmail()),
                    csv(e.getActorRole()),
                    csv(e.getActorIp()),
                    csv(e.getTargetType()),
                    csv(e.getTargetId()),
                    csv(e.getTargetInfo()),
                    csv(e.getDetails())));
        }
        w.flush();
    }

    /**
     * Фильтрация в памяти.
     * Hibernate 6 + PostgreSQL не могут типизировать null-параметры
     * (Instant / String) в JPQL, поэтому фильтруем после загрузки.
     */
    private List<AuditEvent> filterInMemory(String type, String actor, String result,
                                             LocalDate from, LocalDate to) {
        Instant fromI = from == null ? null
                : from.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant toI = to == null ? null
                : to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        String typeF = (type == null || type.isBlank()) ? null : type;
        String actorF = (actor == null || actor.isBlank()) ? null : actor.toLowerCase().trim();
        String resultF = (result == null || result.isBlank()) ? null : result;

        return repo.findAllByOrderByEventTimeDesc(PageRequest.of(0, MAX_LOAD))
                .stream()
                .filter(e -> typeF == null || typeF.equals(e.getEventType()))
                .filter(e -> resultF == null || resultF.equals(e.getResult()))
                .filter(e -> actorF == null
                        || (e.getActorEmail() != null
                            && e.getActorEmail().toLowerCase().contains(actorF)))
                .filter(e -> fromI == null || !e.getEventTime().isBefore(fromI))
                .filter(e -> toI == null || e.getEventTime().isBefore(toI))
                .toList();
    }

    @org.springframework.web.bind.annotation.PostMapping("/cleanup")
    public RedirectView cleanupNow(org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        int days = settings.getIntOrDefault(K_RETENTION, 365);
        if (days <= 0) {
            ra.addFlashAttribute("err", "Retention = 0, очистка отключена");
            return new RedirectView(REDIR_AUDIT);
        }
        try {
            long deleted = cleanupService.cleanup(days);
            log.info("Manual cleanup: удалено {} записей старше {} дней", deleted, days);
            ra.addFlashAttribute("ok", "Удалено записей: " + deleted + " (старше " + days + " дней)");
        } catch (Exception e) {
            log.error("cleanupNow failed", e);
            ra.addFlashAttribute("err", "Ошибка очистки: " + e.getMessage());
        }
        return new RedirectView(REDIR_AUDIT);
    }

    @org.springframework.web.bind.annotation.PostMapping("/retention")
    public RedirectView setRetention(@RequestParam int days,
                                     java.security.Principal auth,
                                     org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        if (days < 0 || days > 3650) {
            ra.addFlashAttribute("err", "Допустимо 0..3650 дней");
            return new RedirectView(REDIR_AUDIT);
        }
        settings.set(K_RETENTION, String.valueOf(days),
                auth != null ? auth.getName() : "admin");
        ra.addFlashAttribute("ok", "Retention: " + days + " дней");
        return new RedirectView(REDIR_AUDIT);
    }

    /** Парсит дату из query param (ISO format: yyyy-MM-dd). */
    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            log.warn("Не удалось распарсить дату '{}': {}", s, e.getMessage());
            return null;
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
