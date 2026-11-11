-- Модернизация мониторинга: покомпонентные проверки
ALTER TABLE monitor_events ADD COLUMN component VARCHAR(50);

CREATE INDEX idx_monitor_events_component
    ON monitor_events (component, checked_at DESC)
    WHERE component IS NOT NULL;

COMMENT ON COLUMN monitor_events.component IS 'db | smtp | storage | keys | disk | memory | admins | alert | app';

-- Настройки
INSERT INTO app_settings (key, value, description) VALUES
    ('monitor.components_state', '{}', 'JSON: component→healthy (последний снимок)'),
    ('monitor.alert_recovery', 'true', 'Отправлять email при восстановлении'),
    ('monitor.test_email', '', 'Email для тестовых/покомпонентных алертов (если пусто → monitor.mail_to)'),
    ('monitor.disk_path', '/', 'Путь для проверки свободного места'),
    ('monitor.disk_threshold_pct', '90', 'Порог алерта по диску (%)'),
    ('monitor.memory_threshold_pct', '90', 'Порог алерта по памяти JVM (%)')
ON CONFLICT (key) DO NOTHING;
