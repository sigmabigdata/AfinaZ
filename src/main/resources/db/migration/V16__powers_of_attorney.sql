-- ============================================================
-- Доверенности (ЭМЧД) — доверенности, выданные УЦ/нотариусом
-- ============================================================

CREATE TABLE powers_of_attorney (
    id                BIGSERIAL PRIMARY KEY,
    fio_enc           TEXT,                    -- ФИО представителя, AES-GCM
    fio_hash          VARCHAR(64),              -- SHA-256 от нормализованного ФИО
    snils_enc         TEXT,                    -- SNILS (AES-GCM, для аудита)
    inn_enc           TEXT,                    -- INN (AES-GCM)
    org_name          VARCHAR(500),             -- ПАО "НБД-Банк"
    org_inn           VARCHAR(20),
    doc_number        VARCHAR(100),             -- 41/26-319
    doc_guid          VARCHAR(100),             -- GUID из НомДовер
    doc_date          DATE,                     -- ДатаВыдДовер
    valid_from        DATE,
    valid_until       DATE NOT NULL,            -- СрокДейст
    notary_name       VARCHAR(300),
    xml_enc           TEXT,                     -- Исходный XML (AES-GCM, для аудита)
    file_sha256       VARCHAR(64),              -- SHA-256 исходного файла (дедуп)
    created_by        VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    notified_30d      BOOLEAN NOT NULL DEFAULT FALSE,
    notified_7d       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_poa_fio_hash ON powers_of_attorney(fio_hash) WHERE active = TRUE;
CREATE INDEX idx_poa_valid_until ON powers_of_attorney(valid_until) WHERE active = TRUE;
CREATE UNIQUE INDEX idx_poa_file_sha256 ON powers_of_attorney(file_sha256) WHERE file_sha256 IS NOT NULL;

COMMENT ON TABLE powers_of_attorney IS 'Доверенности (ЭМЧД), выданные УЦ/нотариусом';
COMMENT ON COLUMN powers_of_attorney.fio_hash IS 'SHA-256 от upper(ФИО) — для сопоставления с подписантом';
COMMENT ON COLUMN powers_of_attorney.valid_until IS 'Срок действия доверенности';

-- Признак «применить доверенность к документу» (для визуализации)
ALTER TABLE documents ADD COLUMN apply_poa BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN documents.apply_poa IS 'Применять доверенность в визуализации подписи';

GRANT SELECT, INSERT, UPDATE, DELETE ON powers_of_attorney TO afina_app;
GRANT SELECT ON powers_of_attorney TO afina_auditor;
GRANT USAGE, SELECT ON SEQUENCE powers_of_attorney_id_seq TO afina_app;
GRANT SELECT ON SEQUENCE powers_of_attorney_id_seq TO afina_auditor;
