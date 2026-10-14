#!/bin/bash
# ============================================================
# AfinaZ · PostgreSQL 15 entrypoint (Debian/Astra layout)
# ============================================================
# Аналог официального docker-entrypoint.sh от postgres:16, но с
# учётом Debian-style layout (config отдельно от data) и PARSEC.
# ============================================================
set -e

PGDATA="/var/lib/postgresql/15/main"
PGCONF="/etc/postgresql/15/main/postgresql.conf"
PGLOG="/var/lib/postgresql/15/main/pg_log"
PGBIN="/usr/lib/postgresql/15/bin"

POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_DB="${POSTGRES_DB:-postgres}"

if [ "${1:0:1}" = '-' ]; then
    set -- postgres "$@"
fi

# ============ 1. Инициализация кластера ============
if [ ! -s "$PGDATA/PG_VERSION" ] || [ ! -f "$PGCONF" ]; then
    echo "▶ Volume пустой — создаём кластер 15/main..."
    rm -rf "$PGDATA"/*
    pg_createcluster 15 main -- --encoding=UTF-8 --locale=C.UTF-8 \
        --auth-local=trust --auth-host=scram-sha-256
fi

mkdir -p "$PGLOG"
chown postgres:postgres "$PGLOG"

# ============ 2. Запуск временного сервера для init ============
if [ "$1" = 'postgres' ] && [ ! -f "$PGDATA/.init_done" ]; then
    echo "▶ Временный запуск сервера..."
    su - postgres -c "$PGBIN/pg_ctl -D $PGDATA \
        -o '-c config_file=$PGCONF -c listen_addresses= -c port=5432' \
        -l $PGLOG/init.log -w start"

    # Создаём $POSTGRES_DB, если её нет
    if [ "$POSTGRES_DB" != "postgres" ]; then
        if ! su - postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='$POSTGRES_DB'\"" | grep -q 1; then
            echo "▶ Создаём базу $POSTGRES_DB..."
            su - postgres -c "createdb $POSTGRES_DB"
        fi
    fi

    # Готовим env-строку для передачи в .sh скрипты
    ENV_STR="POSTGRES_USER=$POSTGRES_USER POSTGRES_DB=$POSTGRES_DB \
DB_PASSWORD=${DB_PASSWORD:-} DB_MIGRATOR_PASSWORD=${DB_MIGRATOR_PASSWORD:-} \
DB_AUDITOR_PASSWORD=${DB_AUDITOR_PASSWORD:-}"

    # Запускаем init-скрипты
    for f in /docker-entrypoint-initdb.d/*; do
        case "$f" in
            *.sh)
                echo "▶ init bash: $f"
                su postgres -c "env $ENV_STR bash $f"
                ;;
            *.sql)
                echo "▶ init sql:  $f"
                su postgres -c "psql -d $POSTGRES_DB -f $f"
                ;;
            *) ;;
        esac
    done

    echo "▶ Останавливаем временный сервер..."
    su - postgres -c "$PGBIN/pg_ctl -D $PGDATA -m fast stop"
    touch "$PGDATA/.init_done"
    echo "▶ Init-скрипты выполнены"
fi

# ============ 3. Foreground-запуск ============
echo "▶ Старт PostgreSQL 15 на 0.0.0.0:5432..."
exec su - postgres -c "exec $PGBIN/postgres -D $PGDATA -c config_file=$PGCONF -c listen_addresses='*'"
