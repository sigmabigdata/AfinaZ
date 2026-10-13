package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.example.ukep.entity.AppSetting;
import ru.example.ukep.repository.AppSettingRepository;
import ru.example.ukep.security.PiiEncryptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Настройки приложения (key-value), редактируемые из админки.
 * Кэширует значения в памяти; сбрасывается при set().
 * При горизонтальном масштабировании заменить на pub/sub (Redis и т.п.).
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final AppSettingRepository repo;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SettingsService(AppSettingRepository repo) {
        this.repo = repo;
    }

    public List<AppSetting> getAll() {
        return repo.findAll();
    }

    public Optional<String> get(String key) {
        String cached = cache.get(key);
        if (cached != null) return Optional.of(cached);
        Optional<AppSetting> setting = repo.findById(key);
        setting.ifPresent(s -> cache.put(key, s.getValue() == null ? "" : s.getValue()));
        return setting.map(s -> s.getValue() == null ? "" : s.getValue());
    }

    public String getOrDefault(String key, String def) {
        return get(key).filter(v -> !v.isBlank()).orElse(def);
    }

    public int getIntOrDefault(String key, int def) {
        try {
            return Integer.parseInt(getOrDefault(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBoolOrDefault(String key, boolean def) {
        String v = getOrDefault(key, String.valueOf(def));
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    /**
     * Записать одну настройку.
     *
     * Без @Transactional: реальная работа идёт через repo.findById() и
     * repo.save() — каждый вызов JpaRepository сам оборачивается в
     * транзакцию. Внешний @Transactional здесь только вредит:
     *   - setSmtpPasswordEncrypted() вызывает set() через this.set() =
     *     self-invocation, @Transactional всё равно игнорируется;
     *   - Sonar (java:S2229) справедливо на это ругается.
     */
    public void set(String key, String value, String updatedBy) {
        writeSetting(key, value, updatedBy);
    }

    public void setAll(Map<String, String> updates, String updatedBy) {
        updates.forEach((k, v) -> writeSetting(k, v, updatedBy));
    }

    /** Общее тело записи. */
    private void writeSetting(String key, String value, String updatedBy) {
        AppSetting s = repo.findById(key).orElseGet(() -> {
            AppSetting ns = new AppSetting();
            ns.setKey(key);
            return ns;
        });
        s.setValue(value);
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(updatedBy);
        repo.save(s);
        cache.put(key, value == null ? "" : value);
        log.info("Setting updated: {} = '{}' (by {})", key,
                value != null && value.length() > 50 ? value.substring(0, 50) + "..." : value,
                updatedBy);
    }

    /** Программный сброс кэша (например, после миграции). */
    public void invalidateCache() {
        cache.clear();
    }

    // ==================== SMTP helpers ====================

    /** Вернуть пароль SMTP расшифрованным (он хранится AES-GCM). */
    public String getSmtpPasswordDecrypted(PiiEncryptor pii) {
        String stored = getOrDefault("smtp.password", "");
        if (stored.isBlank()) return "";
        return pii.decrypt(stored);
    }

    /** Сохранить пароль SMTP в зашифрованном виде. */
    public void setSmtpPasswordEncrypted(String plaintext, PiiEncryptor pii, String by) {
        if (plaintext == null) return;
        String enc = plaintext.isBlank() ? "" : pii.encrypt(plaintext);
        set("smtp.password", enc, by);
    }

    /** Проверить, заданы ли SMTP-настройки в БД (хотя бы host). */
    public boolean isSmtpConfigured() {
        return !getOrDefault("smtp.host", "").isBlank();
    }
}
