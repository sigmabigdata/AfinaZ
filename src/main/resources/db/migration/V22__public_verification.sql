-- ============================================================
-- Публичная верификация подписи
-- ============================================================
-- Каждый документ получает UUID, по которому можно открыть
-- публичную страницу проверки подписи (без авторизации).
-- ============================================================

ALTER TABLE documents
    ADD COLUMN public_uuid UUID;

-- Заполняем для существующих документов
UPDATE documents
   SET public_uuid = gen_random_uuid()
 WHERE public_uuid IS NULL;

-- Для новых документов — автогенерация по умолчанию
ALTER TABLE documents
    ALTER COLUMN public_uuid SET DEFAULT gen_random_uuid(),
    ALTER COLUMN public_uuid SET NOT NULL;

CREATE UNIQUE INDEX idx_documents_public_uuid
    ON documents (public_uuid);

COMMENT ON COLUMN documents.public_uuid IS 'UUID для публичной страницы верификации подписи';

-- Индекс для быстрого поиска событий аудита по документу (timeline)
CREATE INDEX IF NOT EXISTS idx_audit_target_doc
    ON audit_events (target_type, target_id)
    WHERE target_type = 'DOCUMENT';
