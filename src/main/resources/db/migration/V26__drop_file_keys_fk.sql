-- Убираем FK documents.key_version → file_keys.version.
-- Причина: file_keys — историческая метатаблица, в неё никто не пишет
-- при ротации ключей. FK блокирует ротацию (Key (key_version)=(v2) not present).
-- Валидация версий обеспечивается FileEncryptor (in-memory Map<Integer, SecretKey>):
-- при decrypt с неизвестной версией выбрасывается IllegalStateException.

ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_key_version_fkey;

COMMENT ON COLUMN documents.key_version IS 'Версия ключа шифрования (v1, v2, ...). FK на file_keys удалён в V26.';
