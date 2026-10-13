#!/usr/bin/env bash
# Бэкап БД + файлов Афина (prod), с шифрованием AES-256-CBC.
#
# Пароль — secrets/backup.pass (0600, root). Если файла нет — отказ.
# Использует openssl enc -pbkdf2 -iter 100000 (PBKDF2-HMAC-SHA256).
# Итоговые файлы: *.sql.gz.enc, *.tar.gz.enc.
set -euo pipefail
cd "$(dirname "$0")"

PASS_FILE="./secrets/backup.pass"

if [ ! -r "$PASS_FILE" ]; then
    echo "✗ Пароль бэкапа не найден или недоступен: $PASS_FILE" >&2
    echo "  Создайте:  openssl rand -base64 32 | tr -d '\\n' > $PASS_FILE" >&2
    echo "             chmod 600 $PASS_FILE" >&2
    exit 1
fi

STAMP=$(date +%Y%m%d_%H%M%S)
mkdir -p backups

OPENSSL_ARGS=(-aes-256-cbc -pbkdf2 -iter 100000 -salt -pass file:"$PASS_FILE")

# --- 1. Дамп БД → gzip → AES-256 ---
DB_FILE="backups/afina_${STAMP}.sql.gz.enc"
docker exec afina-postgres pg_dump \
    -U afina_migrator -d afina_db \
    --clean --if-exists \
    --no-owner --no-privileges \
    | gzip \
    | openssl enc "${OPENSSL_ARGS[@]}" -out "$DB_FILE"
echo "[$(date)] БД: $DB_FILE ($(du -h "$DB_FILE" | cut -f1), AES-256)"

# --- 2. Storage → tar.gz → AES-256 ---
FILES_FILE="backups/storage_${STAMP}.tar.gz.enc"
tar czf - -C . storage/documents/ 2>/dev/null \
    | openssl enc "${OPENSSL_ARGS[@]}" -out "$FILES_FILE"
echo "[$(date)] Файлы: $FILES_FILE ($(du -h "$FILES_FILE" | cut -f1), AES-256)"

# --- 3. Ротация ---
find backups -name "afina_*.sql.gz.enc"    -mtime +30 -delete 2>/dev/null || true
find backups -name "storage_*.tar.gz.enc"  -mtime +30 -delete 2>/dev/null || true
find backups -name "afina_*.sql.gz"        -mtime +14 -delete 2>/dev/null || true
find backups -name "storage_*.tar.gz"      -mtime +14 -delete 2>/dev/null || true
find backups -name "test_*.sql.gz"         -mtime +7  -delete 2>/dev/null || true
