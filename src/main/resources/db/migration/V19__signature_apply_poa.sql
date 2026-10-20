-- ============================================================
-- Доверенность применяется на уровне ПОДПИСИ, не документа
-- ============================================================
-- У каждого подписанта может быть своя доверенность (или её нет).
-- Кнопка "Применить доверенность" должна быть под каждой подписью.

ALTER TABLE document_signatures ADD COLUMN IF NOT EXISTS apply_poa BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: если у документа было apply_poa=true, применить ко всем его подписям
UPDATE document_signatures s
   SET apply_poa = TRUE
 WHERE s.document_id IN (SELECT id FROM documents WHERE apply_poa = TRUE);

-- Старое поле documents.apply_poa больше не используется — оставляем для совместимости
COMMENT ON COLUMN documents.apply_poa IS 'DEPRECATED: перенесено в document_signatures.apply_poa';
COMMENT ON COLUMN document_signatures.apply_poa IS 'Применять доверенность подписанта в визуализации';
