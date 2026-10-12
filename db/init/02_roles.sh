#!/bin/bash
# ============================================================
# Афина · Роли, права, владение схемой (init-скрипт)
# ============================================================
# Выполняется Postgres-контейнером при ПЕРВОМ старте (пустая pgdata).
# Пароли ролей берутся из env (.env.prod / docker-compose).
# Идемпотентно.
# ============================================================
set -euo pipefail

: "${DB_MIGRATOR_PASSWORD:?DB_MIGRATOR_PASSWORD не задан}"
: "${DB_PASSWORD:?DB_PASSWORD не задан}"
: "${DB_AUDITOR_PASSWORD:?DB_AUDITOR_PASSWORD не задан}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname   "$POSTGRES_DB" <<-EOSQL

    -- ============ Роли ============
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'afina_migrator') THEN
            CREATE ROLE afina_migrator LOGIN PASSWORD '${DB_MIGRATOR_PASSWORD}';
        END IF;
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'afina_app') THEN
            CREATE ROLE afina_app LOGIN PASSWORD '${DB_PASSWORD}';
        END IF;
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'afina_auditor') THEN
            CREATE ROLE afina_auditor LOGIN PASSWORD '${DB_AUDITOR_PASSWORD}';
        END IF;
    END
    \$\$;

    -- ============ Владение схемой ============
    -- Мигратор — владелец public, чтобы Flyway мог применять DDL
    -- (включая COMMENT ON SCHEMA).
    ALTER SCHEMA public OWNER TO afina_migrator;

    -- ============ Права на БД и схему ============
    GRANT CONNECT ON DATABASE "${POSTGRES_DB}"
        TO afina_migrator, afina_app, afina_auditor;
    GRANT USAGE ON SCHEMA public TO afina_app, afina_auditor;
    GRANT CREATE, USAGE ON SCHEMA public TO afina_migrator;

    -- ============ Права по умолчанию для будущих объектов ============
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO afina_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT SELECT ON TABLES TO afina_auditor;
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO afina_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT SELECT ON SEQUENCES TO afina_auditor;
EOSQL

echo "✓ Роли + права + владение схемой настроены"
