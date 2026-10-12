package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.EmailService;
import ru.example.ukep.service.SettingsService;

@Controller
@RequestMapping("/admin/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private static final String K_SMTP_HOST = "smtp.host";
    private static final String K_SMTP_PORT = "smtp.port";
    private static final String K_SMTP_USER = "smtp.username";
    private static final String K_SMTP_PASS = "smtp.password";
    private static final String K_SMTP_SSL  = "smtp.ssl";
    private static final String UNKNOWN = "unknown";
    private static final String REDIR = "redirect:/admin/settings";
    private static final String FALSE = "false";

    private final SettingsService settings;
    private final AuditService audit;
    private final EmailService emailService;
    private final PiiEncryptor pii;

    // Значения из .env.prod — для отображения в форме как reference
    @org.springframework.beans.factory.annotation.Value("${spring.mail.host:}")
    private String envMailHost;
    @org.springframework.beans.factory.annotation.Value("${spring.mail.port:465}")
    private String envMailPort;
    @org.springframework.beans.factory.annotation.Value("${spring.mail.username:}")
    private String envMailUsername;
    @org.springframework.beans.factory.annotation.Value("${app.mail.from:}")
    private String envMailFrom;

    public SettingsController(SettingsService settings,
                              AuditService audit,
                              EmailService emailService,
                              PiiEncryptor pii) {
        this.settings = settings;
        this.audit = audit;
        this.emailService = emailService;
        this.pii = pii;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("settings", settings.getAll());
        // SMTP-значения для формы (пароль маскируем)
        model.addAttribute("smtpHost", settings.getOrDefault(K_SMTP_HOST, ""));
        model.addAttribute("smtpPort", settings.getOrDefault(K_SMTP_PORT, "465"));
        model.addAttribute("smtpUsername", settings.getOrDefault(K_SMTP_USER, ""));
        // smtp.from убран из UI — From задаётся только через .env.prod
        model.addAttribute("smtpSsl", settings.getBoolOrDefault(K_SMTP_SSL, true));
        boolean hasPassword = !settings.getOrDefault(K_SMTP_PASS, "").isBlank();
        model.addAttribute("smtpHasPassword", hasPassword);

        // Reference — текущие значения из .env.prod
        model.addAttribute("envMailHost", envMailHost);
        model.addAttribute("envMailPort", envMailPort);
        model.addAttribute("envMailUsername", envMailUsername);
        model.addAttribute("envMailFrom", envMailFrom);
        return "admin-settings";
    }

    @PostMapping
    public String save(@RequestParam(required = false) String monitorMailTo,
                       @RequestParam(required = false) String monitorEnabled,
                       @RequestParam(required = false) String revocationMode,
                       @RequestParam(required = false) String rateLimitLogin,
                       @RequestParam(required = false) String rateLimitWindow,
                       @RequestParam(required = false) String requireCrl,
                       @RequestParam(required = false) String smtpHost,
                       @RequestParam(required = false) String smtpPort,
                       @RequestParam(required = false) String smtpUsername,
                       @RequestParam(required = false) String smtpPassword,
                       @RequestParam(required = false) String smtpSsl,
                       Authentication auth,
                       RedirectAttributes ra) {
        String who = auth != null ? auth.getName() : UNKNOWN;
        try {
            // Основные
            settings.set("monitor.mail_to", monitorMailTo == null ? "" : monitorMailTo.trim(), who);
            settings.set("monitor.enabled", monitorEnabled != null ? "true" : FALSE, who);
            settings.set("revocation.mode", revocationMode == null ? "soft" : revocationMode, who);
            settings.set("auth.rate_limit.login", rateLimitLogin == null ? "5" : rateLimitLogin, who);
            settings.set("auth.rate_limit.window_min",
                    rateLimitWindow == null ? "15" : rateLimitWindow, who);
            settings.set("signature.require_crl", requireCrl != null ? "true" : FALSE, who);

            // SMTP
            settings.set(K_SMTP_HOST, smtpHost == null ? "" : smtpHost.trim(), who);
            settings.set(K_SMTP_PORT, smtpPort == null ? "465" : smtpPort.trim(), who);
            settings.set(K_SMTP_USER, smtpUsername == null ? "" : smtpUsername.trim(), who);
            settings.set(K_SMTP_SSL, smtpSsl != null ? "true" : FALSE, who);

            // Пароль — только если введён новый (не пустая строка)
            if (smtpPassword != null && !smtpPassword.isBlank()) {
                settings.setSmtpPasswordEncrypted(smtpPassword, pii, who);
            }

            audit.settingsUpdate(who, "monitor/revocation/rate-limit/smtp");
            ra.addFlashAttribute("ok", "Настройки сохранены");
            log.info("Settings saved by {}", who);
        } catch (Exception e) {
            log.error("Ошибка сохранения настроек", e);
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/smtp/reset-to-env")
    public String smtpResetToEnv(Authentication auth, RedirectAttributes ra) {
        String who = auth != null ? auth.getName() : UNKNOWN;
        try {
            settings.set(K_SMTP_HOST, "", who);
            settings.set(K_SMTP_PORT, "465", who);
            settings.set(K_SMTP_USER, "", who);
            settings.set(K_SMTP_PASS, "", who);
            settings.set(K_SMTP_SSL, "true", who);
            audit.settingsUpdate(who, "smtp-reset-to-env");
            ra.addFlashAttribute("ok", "SMTP-настройки сброшены к .env.prod");
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/test-email")
    public RedirectView testEmail(@RequestParam String to,
                                  Authentication auth,
                                  RedirectAttributes ra) {
        String who = auth != null ? auth.getName() : UNKNOWN;
        if (to == null || to.isBlank() || !to.contains("@")) {
            ra.addFlashAttribute("err", "Укажите корректный email");
            return new RedirectView(REDIR);
        }
        try {
            emailService.sendTest(to.trim());
            ra.addFlashAttribute("ok", "Тестовое письмо отправлено на " + to.trim());
            log.info("Test email sent to {} by {}", to, who);
        } catch (Exception e) {
            log.error("Test email failed", e);
            ra.addFlashAttribute("err", "Ошибка отправки: " + e.getMessage());
        }
        return new RedirectView(REDIR);
    }
}
