-- ============================================================
-- Разделы (папки) для документов
-- ============================================================
-- Один уровень вложенности: документ → папка → клиент.
-- Имя папки шифруется AES-GCM (как originalName документов).
-- Удаление папки с документами заблокировано (ON DELETE RESTRICT).
-- ============================================================

CREATE TABLE document_folders (
    id           BIGSERIAL PRIMARY KEY,
    name_enc     TEXT,
    name_hash    VARCHAR(64),
    owner_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_folders_owner_name_hash
    ON document_folders(owner_id, name_hash)
    WHERE name_hash IS NOT NULL;

CREATE INDEX idx_folders_owner ON document_folders(owner_id);

COMMENT ON TABLE document_folders IS 'Разделы документов клиента';
COMMENT ON COLUMN document_folders.name_enc IS 'Название раздела, AES-256-GCM';
COMMENT ON COLUMN document_folders.name_hash IS 'SHA-256 от lower(name) — для unique';

-- ============ Добавляем folder_id к documents ============
-- ON DELETE RESTRICT: БД сама запретит удалить папку, пока внутри есть документы.
ALTER TABLE documents ADD COLUMN folder_id BIGINT
    REFERENCES document_folders(id) ON DELETE RESTRICT;
CREATE INDEX idx_documents_folder ON documents(folder_id);

COMMENT ON COLUMN documents.folder_id IS 'Раздел, к которому относится документ';

-- ============ Права ролям ============
GRANT SELECT, INSERT, UPDATE, DELETE ON document_folders TO afina_app;
GRANT USAGE, SELECT ON SEQUENCE document_folders_id_seq TO afina_app;
GRANT SELECT ON document_folders TO afina_auditor;
GRANT SELECT ON SEQUENCE document_folders_id_seq TO afina_auditor;
