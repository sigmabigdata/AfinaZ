-- ============================================================
-- Номер доверенности для штампа (GUID из НомДовер)
-- ============================================================
-- В ЭМЧД два номера:
--   ВнНомДовер — внутренний номер банка (41/26-47) — справочно
--   НомДовер   — GUID ЭМЧД (39725C00-69A5-...) — для штампа в визуализации
-- ============================================================

ALTER TABLE powers_of_attorney ADD COLUMN IF NOT EXISTS stamp_number VARCHAR(100);

-- Backfill: у существующих записей GUID уже сохранён в doc_guid
UPDATE powers_of_attorney
   SET stamp_number = doc_guid
 WHERE stamp_number IS NULL AND doc_guid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_poa_stamp_number ON powers_of_attorney(stamp_number);

COMMENT ON COLUMN powers_of_attorney.stamp_number IS 'GUID доверенности (НомДовер) — для штампа в визуализации подписи';
COMMENT ON COLUMN powers_of_attorney.doc_number IS 'Внутренний номер банка (ВнНомДовер) — справочно';
