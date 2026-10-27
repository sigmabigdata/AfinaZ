package ru.example.ukep.service;


import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import ru.example.ukep.security.PiiEncryptor;

import java.util.Properties;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    /** Fallback: Spring Boot JavaMailSender из application-prod.yml (.env.prod) */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final SettingsService settings;
    private final PiiEncryptor pii;
    private final AuditService audit;

    @Value("${app.mail.mode:log}")
    private String mode;

    @Value("${app.mail.from:noreply@example.com}")
    private String fromEnv;

    public EmailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                        SettingsService settings,
                        PiiEncryptor pii,
                        AuditService audit) {
        this.mailSenderProvider = mailSenderProvider;
        this.settings = settings;
        this.pii = pii;
        this.audit = audit;
    }

    public void sendLoginLink(String to, String loginUrl) {
        String subject = "ПАО \"НБД-Банк\" · Ссылка для входа";
        String body = """
                Здравствуйте!

                Для входа в личный кабинет ПАО "НБД-Банк" перейдите по ссылке:
                %s

                Ссылка действует 10 часов и может быть использована только один раз.
                Если вы не запрашивали вход — просто проигнорируйте это письмо.
                """.formatted(loginUrl);

        send(to, subject, body);
    }

    public void sendAlert(String to, int failCount, String reason) {
        String subject = "ПАО \"НБД-Банк\" · Сервис недоступен";
        String body = """
                Обнаружена проблема с сервисом ПАО "НБД-Банк".

                Причина: %s
                Сбоев подряд: %d

                Проверьте сервер:
                  ssh afina-vps
                  cd /opt/afina
                  docker compose -f docker-compose-prod.yml --env-file .env.prod ps
                  docker compose -f docker-compose-prod.yml --env-file .env.prod logs app --tail 50
                """.formatted(reason, failCount);

        send(to, subject, body);
    }

    /** Тестовое письмо — для кнопки "Проверить" в UI. */
    public void sendTest(String to) {
        String subject = "ПАО \"НБД-Банк\" · Тестовое письмо";
        String body = """
                Это тестовое письмо от сервиса ПАО "НБД-Банк".

                Если вы его видите — SMTP настроен корректно.
                Отправлено: %s
                """.formatted(java.time.LocalDateTime.now());

        // Тестовое письмо всегда уходит, даже в режиме log
        sendWithOverride(to, subject, body, true);
    }

    // ==================== ядро ====================

    private void send(String to, String subject, String body) {
        sendWithOverride(to, subject, body, false);
    }

    private void sendWithOverride(String to, String subject, String body, boolean forceSend) {
        JavaMailSender sender = resolveSender(forceSend);

        if (sender == null) {
            log.warn("""
                    
                    ============================================================
                    [DEV-MAIL] Кому : {}
                    [DEV-MAIL] Тема : {}
                    [DEV-MAIL] Тело :
                    {}
                    ============================================================
                    """, to, subject, body);
            audit.emailSent(to, subject, "dev-mode");
            return;
        }

        String from = resolveFrom();
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(msg);
            log.info("Email sent to {} (from {})", to, from);
            audit.emailSent(to, subject);
        } catch (Exception e) {
            log.warn("[FALLBACK-MAIL] Кому: {}, subject: {}\n{}", to, subject, body);
            audit.emailFail(to, subject, e.getMessage());
            // Не логируем e.error-ом здесь: throw ниже отдаст исключение
            // наверх, там его залогирует вызывающий код. Так избегаем
            // двойного логирования одной и той же ошибки (S2139).
            throw new IllegalStateException(
                    "SMTP: не удалось отправить письмо на " + to
                    + " (subject: " + subject + "): " + e.getMessage(), e);
        }
    }

    /** Возвращает JavaMailSender: из БД (если настроен) или spring-boot fallback. */
    private JavaMailSender resolveSender(boolean force) {
        if (settings.isSmtpConfigured()) {
            return buildFromSettings();
        }
        // Fallback на spring.mail.* из env
        if (!force && !"smtp".equalsIgnoreCase(mode)) {
            return null;
        }
        return mailSenderProvider.getIfAvailable();
    }

    private JavaMailSenderImpl buildFromSettings() {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(settings.getOrDefault("smtp.host", ""));
        impl.setPort(settings.getIntOrDefault("smtp.port", 465));
        impl.setUsername(settings.getOrDefault("smtp.username", ""));
        String pwd = settings.getSmtpPasswordDecrypted(pii);
        impl.setPassword(pwd);
        impl.setDefaultEncoding("UTF-8");

        boolean ssl = settings.getBoolOrDefault("smtp.ssl", true);
        Properties props = impl.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        if (ssl) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        return impl;
    }

    private String resolveFrom() {
        String fromDb = settings.getOrDefault("smtp.from", "");
        return fromDb.isBlank() ? fromEnv : fromDb;
    }
}
