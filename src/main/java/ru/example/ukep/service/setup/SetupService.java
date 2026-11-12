package ru.example.ukep.service.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.example.ukep.config.setup.SetupModeProperties;
import ru.example.ukep.dto.setup.SetupFinalizeResponse;
import ru.example.ukep.dto.setup.SetupStateDto;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.SettingsService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Логика мастера первичной настройки (Setup Mode).
 *
 * <p><b>Draft</b> хранится в {@code app_settings} с ключами {@code setup.draft.*}.
 * Переживает рестарт контейнера. Очищается через {@link #reset()}.</p>
 *
 * <p><b>Finalize</b> пишет {@code setup-out/.env.prod.new} + {@code setup-out/setup.complete}
 * → {@code entrypoint-wrapper.sh} при следующем старте контейнера применит
 * новый .env.prod и удалит маркеры. Сам сервис вызывает {@code System.exit(0)}
 * через 2 секунды (даёт время отдать HTTP-ответ клиенту).</p>
 *
 * <p><b>Шаги wizard'а:</b></p>
 * <ol>
 *   <li>Домен + TLS (self-signed / custom)</li>
 *   <li>SMTP (log / local / external)</li>
 *   <li>Администратор (CN + SNILS)</li>
 *   <li>IP-whitelist для /admin/*</li>
 *   <li>Preflight (проверки, не сохраняется)</li>
 *   <li>Подтверждение + finalize</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(name = "app.setup.mode", havingValue = "true")
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private static final String DRAFT_PREFIX = "setup.draft.";

    private static final ScheduledExecutorService EXIT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "setup-exit");
                t.setDaemon(true);
                return t;
            });

    /** Обязательные поля для каждого шага (нельзя пропустить). */
    /**
     * Обязательные поля по шагам.
     * <b>LinkedHashMap</b> — для детерминированного порядка итерации
     * (Map.of не гарантирует порядок → getState() может вернуть шаг 4 вместо 1).
     */
    private static final Map<Integer, List<String>> REQUIRED_FIELDS = new java.util.LinkedHashMap<>();
    static {
        REQUIRED_FIELDS.put(1, List.of("domain", "tls"));
        REQUIRED_FIELDS.put(2, List.of("smtpMode"));
        REQUIRED_FIELDS.put(3, List.of("adminCn", "adminSnils"));
        REQUIRED_FIELDS.put(4, List.of("ipWhitelist"));
    }

    /** Поля, которые попадают в .env.prod.new на finalize. */
    private static final List<String> PERSISTED_FIELDS = List.of(
            "domain", "tls",
            "smtpMode", "smtpHost", "smtpPort", "smtpUser", "smtpPassword", "smtpFrom", "smtpSsl",
            "adminCn", "adminSnils",
            "ipWhitelist"
    );

    private final SetupModeProperties props;
    private final SettingsService settings;
    private final AuditService audit;

    public SetupService(SetupModeProperties props,
                        SettingsService settings,
                        AuditService audit) {
        this.props = props;
        this.settings = settings;
        this.audit = audit;
    }

    // ==================== STATE ====================

    /** Получить текущее состояние wizard'а. */
    public SetupStateDto getState() {
        SetupStateDto state = new SetupStateDto();
        state.setTokenConfigured(props.isTokenConfigured());
        state.setDomain(System.getenv().getOrDefault("APP_DOMAIN", ""));

        Map<String, String> draft = new LinkedHashMap<>();
        for (String field : PERSISTED_FIELDS) {
            String val = settings.getOrDefault(DRAFT_PREFIX + field, "");
            if (!val.isBlank()) {
                draft.put(field, val);
            }
        }
        state.setDraft(draft);

        // Первый незаполненный шаг
        int step = 1;
        for (var entry : REQUIRED_FIELDS.entrySet()) {
            List<String> required = entry.getValue();
            boolean allPresent = required.stream()
                    .allMatch(f -> draft.containsKey(f) && !draft.get(f).isBlank());
            if (!allPresent) {
                step = entry.getKey();
                break;
            }
            if (entry.getKey() == REQUIRED_FIELDS.size()) {
                step = 6;  // всё заполнено — на подтверждение
            } else {
                step = entry.getKey() + 1;
            }
        }
        state.setCurrentStep(step);
        state.setComplete(step == 6 && allStepsFilled(draft));
        return state;
    }

    private static boolean allStepsFilled(Map<String, String> draft) {
        for (List<String> required : REQUIRED_FIELDS.values()) {
            for (String f : required) {
                if (!draft.containsKey(f) || draft.get(f).isBlank()) return false;
            }
        }
        return true;
    }

    // ==================== DRAFT ====================

    /**
     * Сохранить данные шага в draft (merge: пустые поля не перезаписывают существующие).
     * Валидация: для шагов 1-4 обязательные поля должны быть непустыми.
     */
    public void saveStep(int step, Map<String, String> data, String actor) {
        List<String> required = REQUIRED_FIELDS.get(step);
        if (required != null) {
            for (String f : required) {
                String v = data.get(f);
                if (v == null || v.isBlank()) {
                    throw new IllegalArgumentException(
                            "Шаг " + step + ": поле '" + f + "' обязательно");
                }
            }
        }
        int saved = 0;
        for (var entry : data.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            if (!PERSISTED_FIELDS.contains(key)) continue;      // игнорим лишние
            if (val == null) continue;
            settings.set(DRAFT_PREFIX + key, val, actor);
            saved++;
        }
        log.info("setup: сохранён шаг {} ({} полей, actor={})", step, saved, actor);
    }

    /** Сбросить draft (не трогает ничего, кроме setup.draft.*). */
    public void reset(String actor) {
        for (String field : PERSISTED_FIELDS) {
            settings.set(DRAFT_PREFIX + field, "", actor);
        }
        log.info("setup: draft сброшен (actor={})", actor);
    }

    // ==================== FINALIZE ====================

    /**
     * Финализация: собрать .env.prod.new, записать setup.complete + admins.env,
     * через 2s — System.exit(0) → docker restart: unless-stopped поднимет
     * контейнер уже в обычном режиме (setup-out/setup.complete → entrypoint-wrapper
     * применит .env.prod.new → удалит маркеры).
     */
    public SetupFinalizeResponse finalizeSetup(String actor) {
        // 1. Валидация
        SetupStateDto state = getState();
        if (!state.isComplete()) {
            return new SetupFinalizeResponse(false,
                    "Не все шаги wizard'а заполнены (текущий шаг: " + state.getCurrentStep() + ")",
                    null);
        }
        Map<String, String> draft = state.getDraft();

        // 2. Preflight-проверки (серьёзные — блокирующие)
        String preflightError = validateBeforeFinalize(draft);
        if (preflightError != null) {
            return new SetupFinalizeResponse(false, "Preflight: " + preflightError, null);
        }

        try {
            // 3. Записать setup-out/.env.prod.new
            Path outDir = props.getOutDir();
            Files.createDirectories(outDir);
            Path envNew = outDir.resolve(".env.prod.new");
            String envContent = buildEnvProdContent(draft);
            Files.writeString(envNew, envContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("setup: записан {}", envNew);

            // 4. Записать setup.complete (последним!)
            Path complete = outDir.resolve("setup.complete");
            Files.writeString(complete, Instant.now().toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("setup: записан {}", complete);

            // 5. Обновить admins.env (CN|SNILS)
            updateAdminsEnv(draft.get("adminCn"), draft.get("adminSnils"));

            // 6. Маркер в secrets/
            try {
                Files.writeString(Path.of("/opt/afinaz/secrets/initialized"),
                        Instant.now().toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                log.warn("setup: не удалось записать secrets/initialized: {}", e.getMessage());
            }

            // 7. Аудит
            try {
                audit.event("SETUP_COMPLETE", "SUCCESS", actor,
                        "SYSTEM", null, null,
                        "domain=" + draft.get("domain")
                                + ", tls=" + draft.get("tls")
                                + ", admin=" + draft.get("adminCn"));
            } catch (Exception e) {
                log.warn("setup: audit failed: {}", e.getMessage());
            }

            // 8. System.exit через 2s
            log.warn("setup: приложение перезапустится через 2s");
            EXIT_EXECUTOR.schedule(() -> {
                log.warn("setup: System.exit(0) — entrypoint-wrapper применит настройки");
                System.exit(0);
            }, 2, TimeUnit.SECONDS);

            return new SetupFinalizeResponse(true,
                    "Настройки сохранены. Приложение перезапускается…",
                    "https://" + draft.get("domain") + "/admin/login");

        } catch (IOException e) {
            log.error("setup: finalize failed", e);
            return new SetupFinalizeResponse(false,
                    "Ошибка записи: " + e.getMessage(), null);
        }
    }

    /** Preflight на finalize: серьёзные проверки, блокирующие завершение. */
    private String validateBeforeFinalize(Map<String, String> draft) {
        String domain = draft.get("domain");
        if (domain == null || !domain.matches("^[a-zA-Z0-9.\\-]+$")) {
            return "некорректный домен: " + domain;
        }
        String snils = draft.get("adminSnils");
        if (snils == null || !snils.replaceAll("\\D", "").matches("^\\d{11}$")) {
            return "СНИЛС администратора должен содержать 11 цифр";
        }
        if ("external".equals(draft.get("smtpMode"))) {
            if (isBlank(draft.get("smtpHost")) || isBlank(draft.get("smtpFrom"))) {
                return "для внешнего SMTP нужны host и from";
            }
        }
        return null;
    }

    /**
     * Собрать содержимое .env.prod.new.
     *
     * <p>Берём <b>все</b> переменные из {@code System.getenv()} (DB-пароли
     * приходят через docker-compose env_file), перезаписываем только те, что
     * заданы в wizard'е, и принудительно ставим {@code APP_SETUP_MODE=false}
     * + {@code APP_SETUP_TOKEN=} (setup больше не нужен).</p>
     */
    private String buildEnvProdContent(Map<String, String> draft) {
        Map<String, String> vars = new LinkedHashMap<>();

        // ── Из текущего окружения (пришло через env_file) ──
        String[] fromEnv = {
                "DB_NAME", "DB_SUPERUSER", "DB_SUPERUSER_PASSWORD",
                "DB_USER", "DB_PASSWORD",
                "DB_MIGRATOR_USER", "DB_MIGRATOR_PASSWORD",
                "DB_AUDITOR_USER", "DB_AUDITOR_PASSWORD",
                "HTTP_PORT", "HTTPS_PORT"
        };
        for (String key : fromEnv) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) vars.put(key, v);
        }

        // ── Из wizard'а ──
        String domain = draft.get("domain");
        vars.put("APP_DOMAIN", domain);
        vars.put("APP_BASE_URL", "https://" + domain);
        vars.put("SECURE_COOKIES", "true");
        vars.put("TLS_MODE", draft.getOrDefault("tls", "self-signed"));
        vars.put("ADMIN_IP_WHITELIST", draft.getOrDefault("ipWhitelist",
                "127.0.0.1,::1,172.0.0.0/8"));

        // SMTP
        String smtpMode = draft.getOrDefault("smtpMode", "log");
        switch (smtpMode) {
            case "log" -> {
                vars.put("MAIL_MODE", "log");
                vars.put("MAIL_HOST", "");
                vars.put("MAIL_PORT", "");
                vars.put("MAIL_USERNAME", "");
                vars.put("MAIL_PASSWORD", "");
                vars.put("MAIL_FROM", "noreply@" + domain);
                vars.put("MAIL_SSL", "false");
            }
            case "local" -> {
                vars.put("MAIL_MODE", "smtp");
                vars.put("MAIL_HOST", draft.getOrDefault("smtpHost", domain));
                vars.put("MAIL_PORT", draft.getOrDefault("smtpPort", "25"));
                vars.put("MAIL_USERNAME", "");
                vars.put("MAIL_PASSWORD", "");
                vars.put("MAIL_FROM", draft.getOrDefault("smtpFrom", "noreply@" + domain));
                vars.put("MAIL_SSL", "false");
            }
            case "external" -> {
                vars.put("MAIL_MODE", "smtp");
                vars.put("MAIL_HOST", draft.get("smtpHost"));
                vars.put("MAIL_PORT", draft.getOrDefault("smtpPort", "587"));
                vars.put("MAIL_USERNAME", draft.getOrDefault("smtpUser", ""));
                vars.put("MAIL_PASSWORD", draft.getOrDefault("smtpPassword", ""));
                vars.put("MAIL_FROM", draft.get("smtpFrom"));
                vars.put("MAIL_SSL", draft.getOrDefault("smtpSsl", "false"));
            }
        }

        // ── Setup выключен ──
        vars.put("APP_SETUP_MODE", "false");
        vars.put("APP_SETUP_TOKEN", "");

        vars.put("CRYPTO_PRO_ENABLED", "true");

        // ── Собрать файл ──
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("# AfinaZ · .env.prod (wizard ").append(Instant.now()).append(")\n");
        sb.append("# Сгенерирован SetupService.finalizeSetup()\n");
        sb.append("# ============================================================\n\n");
        for (var e : vars.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** Обновить admins.env (формат: CN|SNILS). */
    private void updateAdminsEnv(String cn, String snils) throws IOException {
        if (isBlank(cn) || isBlank(snils)) {
            log.warn("setup: пропускаю admins.env (пустой CN или SNILS)");
            return;
        }
        Path adminsPath = Path.of("/app/admins.env");
        try {
            Files.writeString(adminsPath,
                    "# admins.env — автоматически управляется SetupService\n" + cn + "|" + snils + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("setup: admins.env обновлён (CN={})", cn);
        } catch (IOException e) {
            // не критично — admin может быть уже в файле от bootstrap
            log.warn("setup: не удалось записать admins.env: {}", e.getMessage());
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
