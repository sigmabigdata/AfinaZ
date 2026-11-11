ALTER TABLE users ADD COLUMN snils_enc TEXT;
ALTER TABLE users ADD COLUMN snils_hash VARCHAR(64);
CREATE INDEX idx_users_snils_hash ON users (snils_hash) WHERE snils_hash IS NOT NULL;
COMMENT ON COLUMN users.snils_enc IS 'СНИЛС сотрудника (AES-GCM)';
COMMENT ON COLUMN users.snils_hash IS 'SHA-256 от СНИЛС (для поиска)';
