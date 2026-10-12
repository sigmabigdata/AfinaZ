#!/usr/bin/env bash
# Восстановление БД Афина (prod).
# Поддерживает:
#   *.sql.gz.enc  — зашифрованный дамп (текущий формат)
#   *.sql.gz      — старый plaintext (миграционный период)
set -euo pipefail
cd "$(dirname "$0")"

PASS_FILE="./secrets/backup.pass"

if [ -z "${1:-}" ] || [ ! -f "$1" ]; then
    echo "Использование: $0 backups/afina_YYYYMMDD_HHMMSS.sql.gz.enc"
    echo ""
    echo "Доступные бэкапы:"
    ls -lh backups/ 2>/dev/null || echo "  (нет)"
    exit 1
fi

FILE="$1"

echo "⚠️  Восстановление из: $FILE"
echo "    Все данные в afina_db будут заменены."
read -p "Введи 'RESTORE' для подтверждения: " ans
[ "$ans" = "RESTORE" ] || { echo "Отменено."; exit 1; }

echo "▶ Останавливаю app"
docker compose -f docker-compose-prod.yml --env-file .env.prod stop app

echo "▶ Расшифровываю и восстанавливаю БД"
# Восстанавливаем под СУПЕРЮЗЕРОМ: в дампе есть команды, которые
# afina_migrator выполнить не может (COMMENT ON EXTENSION uuid-ossp,
# ALTER SCHEMA OWNER и т.п.) — раньше это роняло транзакцию с
# "must be owner of extension" и весь restore откатывался.
case "$FILE" in
    *.sql.gz.enc)
        [ -r "$PASS_FILE" ] || { echo "✗ Нет $PASS_FILE"; exit 1; }
        openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
            -pass file:"$PASS_FILE" -in "$FILE" \
            | gunzip \
            | docker exec -i afina-postgres psql \
                -U postgres -d afina_db -q
        ;;
    *.sql.gz)
        echo "  (plaintext-бэкап, устаревший формат)"
        gunzip -c "$FILE" \
            | docker exec -i afina-postgres psql \
                -U postgres -d afina_db -q
        ;;
    *)
        echo "✗ Неизвестный формат: $FILE"; exit 1 ;;
esac

echo "▶ Восстанавливаю владельцев"
docker exec -i afina-postgres psql -U postgres -d afina_db -q \
    < db/fix-ownership.sql 2>/dev/null || true

echo "▶ Запускаю app"
docker compose -f docker-compose-prod.yml --env-file .env.prod start app

echo "✅ Готово"
echo "   Проверь:  afina health && afina logs app | tail -20"
