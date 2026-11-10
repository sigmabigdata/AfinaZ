#!/usr/bin/env bash
# Восстановление storage/ (файлы документов + визуализации) из архива.
# Поддерживает:
#   *.tar.gz.enc  — зашифрованный архив (текущий формат)
#   *.tar.gz      — plaintext (legacy)
#
# Работает так:
#   1. Спрашивает RESTORE
#   2. Останавливает app (чтобы файлы не менялись во время распаковки)
#   3. Safety-бэкап текущего storage/
#   4. Расшифровывает и распаковывает в storage/documents/
#   5. Запускает app
set -euo pipefail
cd "$(dirname "$0")"

PASS_FILE="./secrets/backup.pass"

if [ -z "${1:-}" ] || [ ! -f "$1" ]; then
    echo "Использование: $0 backups/storage_YYYYMMDD_HHMMSS.tar.gz.enc"
    echo ""
    echo "Доступные архивы storage:"
    ls -lh backups/storage_*.tar.gz.enc 2>/dev/null || echo "  (нет)"
    exit 1
fi

FILE="$1"

echo "⚠️  Восстановление storage из: $FILE"
echo "    Текущие файлы в storage/documents будут заменены содержимым архива."
echo "    Приложение будет остановлено на 1–2 минуты."
echo ""
read -p "Введи 'RESTORE' для подтверждения: " ans
if [ "$(echo "$ans" | tr '[:lower:]' '[:upper:]')" != "RESTORE" ]; then
    echo "Отменено."
    exit 1
fi

STAMP=$(date +%Y%m%d_%H%M%S)
SAFETY="backups/storage_safety_${STAMP}.tar.gz.enc"

echo ""
echo "▶ Останавливаю app"
docker compose -f docker-compose-prod.yml --env-file .env.prod stop app

echo "▶ Safety-бэкап текущего storage/documents"
if [ -d storage/documents ] && [ -n "$(ls -A storage/documents 2>/dev/null)" ]; then
    [ -r "$PASS_FILE" ] || { echo "✗ Нет $PASS_FILE"; docker compose -f docker-compose-prod.yml --env-file .env.prod start app; exit 1; }
    tar czf - -C . storage/documents/ 2>/dev/null \
        | openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt \
            -pass file:"$PASS_FILE" -out "$SAFETY"
    echo "  → $SAFETY ($(du -h "$SAFETY" | cut -f1))"
else
    echo "  (storage/documents пуст — safety-бэкап не нужен)"
    SAFETY=""
fi

echo "▶ Расшифровываю и распаковываю"
case "$FILE" in
    *.tar.gz.enc)
        [ -r "$PASS_FILE" ] || { echo "✗ Нет $PASS_FILE"; docker compose -f docker-compose-prod.yml --env-file .env.prod start app; exit 1; }
        openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
            -pass file:"$PASS_FILE" -in "$FILE" \
            | tar xzf - -C .
        ;;
    *.tar.gz)
        echo "  (plaintext-архив, устаревший формат)"
        tar xzf "$FILE" -C .
        ;;
    *)
        echo "✗ Неизвестный формат: $FILE"
        docker compose -f docker-compose-prod.yml --env-file .env.prod start app
        exit 1
        ;;
esac

echo "▶ Права на storage"
chown -R 999:999 storage 2>/dev/null || true
chmod -R 775 storage 2>/dev/null || true

echo "▶ Запускаю app"
docker compose -f docker-compose-prod.yml --env-file .env.prod start app

echo ""
echo "✅ Готово"
if [ -n "$SAFETY" ]; then
    echo "   Safety-backup: $SAFETY"
fi
echo "   Проверь:  afina status && afina logs app | tail -20"
