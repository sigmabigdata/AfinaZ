-- ============================================================
-- Настройки очистки мусора
-- ============================================================
INSERT INTO app_settings (key, value, description) VALUES
    ('cleanup.crls_max_age_days',    '90', 'Удалять auto-*.crl старше N дней'),
    ('cleanup.backups_max_age_days', '30', 'Удалять бэкапы старше N дней'),
    ('cleanup.logs_max_age_days',    '30', 'Удалять *.log.gz старше N дней')
ON CONFLICT (key) DO NOTHING;
