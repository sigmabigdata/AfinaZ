package ru.example.ukep.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.MonitorEvent;
import ru.example.ukep.repository.AuditEventRepository;
import ru.example.ukep.repository.MonitorEventRepository;
import ru.example.ukep.service.MonitoringService;
import ru.example.ukep.service.SettingsService;

import java.util.List;

@Controller
@RequestMapping("/admin/monitor")
public class MonitorController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MonitorController.class);

    private final MonitorEventRepository events;
    private final SettingsService settings;
    private final MonitoringService monitoring;
    private final AuditEventRepository auditEvents;

    public MonitorController(MonitorEventRepository events,
                             SettingsService settings,
                             MonitoringService monitoring,
                             AuditEventRepository auditEvents) {
        this.events = events;
        this.settings = settings;
        this.monitoring = monitoring;
        this.auditEvents = auditEvents;
    }

    @GetMapping
    public String page(Model model) {
        List<MonitorEvent> recent = events.findAllByOrderByCheckedAtDesc(PageRequest.of(0, 100));
        model.addAttribute("events", recent);
        model.addAttribute("fails", settings.getIntOrDefault("monitor.consecutive_fails", 0));
        model.addAttribute("lastAlert", settings.getOrDefault("monitor.last_alert_at", "—"));
        model.addAttribute("mailTo", settings.getOrDefault("monitor.mail_to", ""));
        model.addAttribute("enabled", settings.getBoolOrDefault("monitor.enabled", true));
        model.addAttribute("totalFail", events.countByStatus("FAIL"));
        model.addAttribute("totalOk", events.countByStatus("OK"));

        // Статистика email за 24 часа
        java.time.Instant dayAgo = java.time.Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS);
        long sent = auditEvents.countByEventTypeAndEventTimeAfter("EMAIL_SENT", dayAgo);
        long failed = auditEvents.countByEventTypeAndResultAndEventTimeAfter("EMAIL_FAIL", "FAIL", dayAgo);
        model.addAttribute("emailSent24h", sent);
        model.addAttribute("emailFailed24h", failed);

        // Последние 10 ошибок email
        java.util.List<ru.example.ukep.entity.AuditEvent> lastErrors = auditEvents
                .findRecentByTypeAndResult("EMAIL_FAIL", "FAIL",
                        org.springframework.data.domain.PageRequest.of(0, 10));
        model.addAttribute("emailErrors", lastErrors);
        // Фактическое время последней проверки (обновляется каждые 5 минут, независимо от
        // того, пишется ли OK-событие в историю). Читаем из настроек, не из monitor_events.
        String lastCheckStr = settings.getOrDefault("monitor.last_check_at", "");
        if (!lastCheckStr.isBlank()) {
            try {
                model.addAttribute("lastCheckAt", java.time.Instant.parse(lastCheckStr));
            } catch (Exception ex) {
                log.debug("lastCheckAt parse failed: {}", ex.getMessage());
            }
        }
        return "admin-monitor";
    }

    @PostMapping("/check-now")
    public String checkNow(RedirectAttributes ra) {
        try {
            monitoring.checkHealth();
            ra.addFlashAttribute("ok", "Проверка выполнена, результат в истории");
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return "redirect:/admin/monitor";
    }

    @PostMapping("/reset-counter")
    public String resetCounter(RedirectAttributes ra) {
        settings.set("monitor.consecutive_fails", "0", "admin");
        ra.addFlashAttribute("ok", "Счётчик сбоев сброшен");
        return "redirect:/admin/monitor";
    }
}
