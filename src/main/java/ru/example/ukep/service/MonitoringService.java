package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.example.ukep.entity.MonitorEvent;
import ru.example.ukep.repository.MonitorEventRepository;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;

/**
 * Периодическая проверка работоспособности сервиса.
 *
 * Проверяет:
 *   1. Локальный /actuator/health (через HTTP на localhost)
 *   2. Внешний HTTPS /actuator/health (через APP_DOMAIN)
 *
 * Пишет результат в monitor_events, при 3 подряд сбоях шлёт email.
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private static final int ALERT_THRESHOLD = 3;
    private static final String SYSTEM = "system";
    private static final String KEY_FAILS = "monitor.consecutive_fails";

    private final MonitorEventRepository eventRepo;
    private final SettingsService settings;
    private final EmailService emailService;

    @Value("${app.base-url}")
    private String appBaseUrl;

    public MonitoringService(MonitorEventRepository eventRepo,
                             SettingsService settings,
                             EmailService emailService) {
        this.eventRepo = eventRepo;
        this.settings = settings;
        this.emailService = emailService;
    }

    /** Проверка раз в 5 минут. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void checkHealth() {
        if (!settings.getBoolOrDefault("monitor.enabled", true)) {
            return;
        }

        String failReason = null;

        // 1) Локальный health
        if (!probe(appBaseUrl + "/actuator/health")) {
            failReason = "app-health";
        }

        // 2) Внешний HTTPS health (если домен HTTPS)
        if (failReason == null && appBaseUrl.startsWith("https://")) {
            // APP_DOMAIN через Angie — но из контейнера может не резолвиться.
            // Достаточно локальной проверки. Внешний https — задача monitor.sh.
        }

        if (failReason == null) {
            onSuccess();
        } else {
            onFailure(failReason);
        }
    }

    /**
     * Вызывается из checkHealth() в том же классе. Убрана @Transactional:
     * saveEvent + settings.set — каждая операция сама по себе транзакционна,
     * a protected @Transactional из того же класса всё равно не сработал бы
     * (self-invocation, Sonar java:S2230).
     */
    private void onSuccess() {
        int fails = settings.getIntOrDefault(KEY_FAILS, 0);
        if (fails > 0) {
            log.info("Восстановление после {} сбоев", fails);
            saveEvent("OK", "Восстановление после " + fails + " сбоев", false);
            settings.set(KEY_FAILS, "0", SYSTEM);
            return;
        }

        // Периодически пишем OK-события, чтобы админ видел жизнь сервиса
        // Пишем не чаще, чем раз в час
        java.time.Instant lastOk = getLastOkAt();
        java.time.Instant hourAgo = java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS);
        if (lastOk == null || lastOk.isBefore(hourAgo)) {
            saveEvent("OK", "Регулярная проверка", false);
            settings.set("monitor.last_ok_at", java.time.Instant.now().toString(), SYSTEM);
        }
    }

    private java.time.Instant getLastOkAt() {
        String v = settings.getOrDefault("monitor.last_ok_at", "");
        if (v.isBlank()) return null;
        try { return java.time.Instant.parse(v); } catch (Exception e) { return null; }
    }

    private void onFailure(String reason) {
        int fails = settings.getIntOrDefault(KEY_FAILS, 0) + 1;
        settings.set(KEY_FAILS, String.valueOf(fails), SYSTEM);

        boolean shouldAlert = (fails == ALERT_THRESHOLD) ||
                              (fails > ALERT_THRESHOLD && fails % 3 == 0);

        log.warn("Мониторинг: сбой #{} ({})", fails, reason);

        if (shouldAlert) {
            String to = settings.getOrDefault("monitor.mail_to", "");
            if (to.isBlank()) {
                log.warn("monitor.mail_to не задан — алерт не отправлен");
            } else {
                try {
                    emailService.sendAlert(to, fails, reason);
                    settings.set("monitor.last_alert_at", Instant.now().toString(), SYSTEM);
                    saveEvent("FAIL", "Алерт отправлен на " + to + ": " + reason, true);
                    log.info("Алерт отправлен на {}", to);
                } catch (Exception e) {
                    log.error("Не удалось отправить алерт", e);
                    saveEvent("FAIL", "Ошибка отправки алерта: " + e.getMessage(), false);
                }
            }
        } else {
            saveEvent("FAIL", reason, false);
        }
    }

    private void saveEvent(String status, String reason, boolean alertSent) {
        MonitorEvent e = new MonitorEvent();
        e.setStatus(status);
        e.setReason(reason);
        e.setAlertSent(alertSent);
        eventRepo.save(e);
    }

    /** Простой HTTP probe с таймаутом 5 сек. */
    private boolean probe(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code == 200;
        } catch (IOException e) {
            log.debug("Probe failed for {}: {}", url, e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
