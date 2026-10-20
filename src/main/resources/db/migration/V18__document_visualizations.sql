-- ============================================================
-- Визуализации подписи документа (штампы в колонтитулах)
-- ============================================================
-- Для каждого документа хранится PDF с наложенными штампами.
-- Пересоздаётся при добавлении новой подписи / смене доверенности.
-- ============================================================

CREATE TABLE document_visualizations (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    stored_name     VARCHAR(500) NOT NULL,
    size            BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version         INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT uq_doc_vis UNIQUE (document_id)
);

CREATE INDEX idx_doc_vis_document ON document_visualizations(document_id);

COMMENT ON TABLE document_visualizations IS 'PDF с визуализацией подписей (штампы в колонтитулах)';
COMMENT ON COLUMN document_visualizations.version IS 'Инкрементируется при пересоздании визуализации';

GRANT SELECT, INSERT, UPDATE, DELETE ON document_visualizations TO afina_app;
GRANT SELECT ON document_visualizations TO afina_auditor;
GRANT USAGE, SELECT ON SEQUENCE document_visualizations_id_seq TO afina_app;
GRANT SELECT ON SEQUENCE document_visualizations_id_seq TO afina_auditor;
