package ru.example.ukep.config.setup;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Конфигурация Setup Mode (wizard для первичной настройки).
 *
 * <p>Активна только когда {@code app.setup.mode=true} (в остальное время
 * контроллер и security-chain не создаются).</p>
 *
 * <p>Значения приходят из переменных окружения / .env.prod:
 * <ul>
 *   <li>{@code APP_SETUP_MODE} → {@link #mode}</li>
 *   <li>{@code APP_SETUP_TOKEN} → {@link #token} (32 hex chars от bootstrap.sh)</li>
 *   <li>{@code APP_SETUP_OUT_DIR} → {@link #outDir} (default /app/setup-out)</li>
 *   <li>{@code APP_SETUP_SESSION_TTL} → {@link #sessionTtlMinutes} (default 60)</li>
 * </ul>
 *
 * <p><b>Безопасность:</b> если {@link #token} пуст — setup-режим фактически
 * недоступен (все запросы на /setup/** получат 404). Это защита от случайного
 * запуска wizard в проде без токена.</p>
 */
@ConfigurationProperties(prefix = "app.setup")
public class SetupModeProperties {

    /** Активен ли Setup Mode. Из APP_SETUP_MODE (default false). */
    private boolean mode = false;

    /**
     * Одноразовый токен доступа к wizard.
     * Генерится bootstrap.sh (openssl rand -hex 32), попадает в .env.prod,
     * передаётся в URL: /setup?token=XXX.
     * Если пуст — setup заблокирован даже при mode=true.
     */
    private String token = "";

    /**
     * Директория, куда SetupService пишет .env.prod.new и setup.complete.
     * Монтируется из хоста в docker-compose (./setup-out:/app/setup-out:rw).
     * После записи entrypoint-wrapper.sh применит .env.prod.new.
     */
    private Path outDir = Paths.get("/app/setup-out");

    /**
     * TTL сессии setup (в минутах). После успешной проверки токена
     * создаётся HttpSession с SETUP_AUTHORIZED_AT=timestamp.
     * Если с момента авторизации прошло больше TTL — требуется токен заново.
     */
    private int sessionTtlMinutes = 60;

    // ==================== getters / setters ====================

    public boolean isMode() { return mode; }
    public void setMode(boolean mode) { this.mode = mode; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Path getOutDir() { return outDir; }
    public void setOutDir(Path outDir) { this.outDir = outDir; }

    public int getSessionTtlMinutes() { return sessionTtlMinutes; }
    public void setSessionTtlMinutes(int sessionTtlMinutes) {
        this.sessionTtlMinutes = sessionTtlMinutes;
    }

    /** true, если токен задан и не пуст — setup можно открыть. */
    public boolean isTokenConfigured() {
        return token != null && !token.isBlank();
    }
}
