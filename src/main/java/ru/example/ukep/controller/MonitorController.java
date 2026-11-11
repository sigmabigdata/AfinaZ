package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;
import ru.example.ukep.entity.MonitorEvent;
import ru.example.ukep.repository.AuditEventRepository;
import ru.example.ukep.repository.MonitorEventRepository;
import ru.example.ukep.service.MonitoringService;
import ru.example.ukep.service.SettingsService;

import java.util.List;

@Controller
@RequestMapping("/admin/monitor")
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);
    private static final String REDIR = "redirect:/admin/monitor";

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
        model.addAttribute("testEmail", settings.getOrDefault("monitor.test_email", ""));
        model.addAttribute("alertRecovery", settings.getBoolOrDefault("monitor.alert_recovery", true));
        model.addAttribute("enabled", settings.getBoolOrDefault("monitor.enabled", true));

        String lastCheckStr = settings.getOrDefault("monitor.last_check_at", "");
        if (!lastCheckStr.isBlank()) {
            try { model.addAttribute("lastCheckAt", java.time.Instant.parse(lastCheckStr)); }
            catch (Exception e) { log.debug("parse lastCheckAt failed: {}", e.getMessage()); }
        }

        // Живой прогон для отображения текущего состояния
        try {
            model.addAttribute("checks", monitoring.runAllChecks());
        } catch (Exception e) {
            log.warn("runAllChecks from UI failed: {}", e.getMessage());
            model.addAttribute("checks", List.of());
        }

        long totalFail = events.countByStatus("FAIL");
        long totalOk = events.countByStatus("OK");
        model.addAttribute("totalFail", totalFail);
        model.addAttribute("totalOk", totalOk);

        java.time.Instant dayAgo = java.time.Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS);
        long sent = auditEvents.countByEventTypeAndEventTimeAfter("EMAIL_SENT", dayAgo);
        long failed = auditEvents.countByEventTypeAndResultAndEventTimeAfter("EMAIL_FAIL", "FAIL", dayAgo);
        model.addAttribute("emailSent24h", sent);
        model.addAttribute("emailFailed24h", failed);

        var lastErrors = auditEvents.findRecentByTypeAndResult("EMAIL_FAIL", "FAIL", PageRequest.of(0, 10));
        model.addAttribute("emailErrors", lastErrors);

        return "admin-monitor";
    }

    @PostMapping("/check-now")
    public String checkNow(RedirectAttributes ra) {
        try {
            var results = monitoring.checkAll("manual");
            long failed = results.stream().filter(r -> !r.healthy()).count();
            if (failed == 0) {
                ra.addFlashAttribute("ok", "Проверка выполнена: все " + results.size() + " компонентов OK");
            } else {
                ra.addFlashAttribute("err", "Проверка: " + failed + " из " + results.size() + " с ошибками");
            }
        } catch (Exception e) {
            log.error("checkNow failed", e);
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/test-alert")
    public String testAlert(RedirectAttributes ra) {
        try {
            monitoring.sendTestAlert();
            ra.addFlashAttribute("ok", "Тестовый алерт отправлен на " + monitoring.alertEmail());
        } catch (Exception e) {
            log.error("testAlert failed", e);
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/save-settings")
    public String saveSettings(@RequestParam(required = false) String monitorMailTo,
                                @RequestParam(required = false) String testEmail,
                                @RequestParam(required = false) String alertRecovery,
                                @RequestParam(required = false) String enabled,
                                java.security.Principal auth,
                                RedirectAttributes ra) {
        String who = auth != null ? auth.getName() : "unknown";
        try {
            settings.set("monitor.mail_to", monitorMailTo == null ? "" : monitorMailTo.trim(), who);
            settings.set("monitor.test_email", testEmail == null ? "" : testEmail.trim(), who);
            settings.set("monitor.alert_recovery", alertRecovery != null ? "true" : "false", who);
            settings.set("monitor.enabled", enabled != null ? "true" : "false", who);
            ra.addFlashAttribute("ok", "Настройки алертов сохранены");
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/reset-counter")
    public String resetCounter(RedirectAttributes ra) {
        settings.set("monitor.consecutive_fails", "0", "admin");
        ra.addFlashAttribute("ok", "Счётчик сбоев сброшен");
        return REDIR;
    }
}
