-- ============================================================
-- Дополнительные настройки очистки (tmp, journal)
-- ============================================================
INSERT INTO app_settings (key, value, description) VALUES
    ('cleanup.tmp_max_age_days',    '7',   'Удалять наши файлы из /tmp старше N дней'),
    ('cleanup.journal_max_mb',      '200', 'Максимальный размер journald (MB)')
ON CONFLICT (key) DO NOTHING;
