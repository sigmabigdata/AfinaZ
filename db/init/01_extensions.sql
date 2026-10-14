-- Расширения для безопасности и мониторинга.
-- Ставятся один раз при первом старте контейнера.
--
-- ВАЖНО: pgaudit в AfinaZ НЕ используется — в Astra Linux SE 1.8
-- его нет в репозитории. Вместо него — встроенная подсистема аудита
-- Astra (PARSEC) и log_statement=ddl в postgresql.conf.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;  -- мониторинг запросов
CREATE EXTENSION IF NOT EXISTS pgcrypto;            -- криптография
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";         -- UUID-генерация
