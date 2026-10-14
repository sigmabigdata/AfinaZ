#!/usr/bin/env bash
# ============================================================
# Афина · Уборщик мусора
# ============================================================
# Что чистит:
#   1. crls/  — auto-*.crl старше N дней + невалидные
#   2. logs/  — app.log старше N дней (архивирует и удаляет)
#   3. backups/ — старые бэкапы БД/storage (30/90 дней)
#   4. Docker  — неиспользуемые образы и build-кэш
#
# Запуск: ./cleanup-all.sh
# Cron: раз в сутки ночью
# ============================================================
set -e
cd "$(dirname "$0")"

# ---- Настройки (можно переопределить через env) ----
CRL_MAX_AGE_DAYS="${CRL_MAX_AGE_DAYS:-90}"
LOG_MAX_AGE_DAYS="${LOG_MAX_AGE_DAYS:-30}"
BACKUP_DB_MAX_AGE_DAYS="${BACKUP_DB_MAX_AGE_DAYS:-30}"
BACKUP_STORAGE_MAX_AGE_DAYS="${BACKUP_STORAGE_MAX_AGE_DAYS:-90}"

LOG_PREFIX="[$(date '+%Y-%m-%d %H:%M:%S')]"
DRY_RUN=0
[ "$1" = "--dry-run" ] && DRY_RUN=1

log()  { echo "$LOG_PREFIX $*"; }
info() { echo "$LOG_PREFIX $*"; }

# ============================================================
# 1. CRL
# ============================================================
log "─── 1. CRL ───"
if [ -d crls ]; then
    before=$(find crls -maxdepth 1 -name 'auto-*.crl' -type f 2>/dev/null | wc -l)
    before_size=$(du -sh crls/ 2>/dev/null | cut -f1)

    if [ "$DRY_RUN" = "0" ]; then
        find crls -maxdepth 1 -name 'auto-*.crl' -type f -mtime +"$CRL_MAX_AGE_DAYS" -delete 2>/dev/null || true
    fi

    after=$(find crls -maxdepth 1 -name 'auto-*.crl' -type f 2>/dev/null | wc -l)
    after_size=$(du -sh crls/ 2>/dev/null | cut -f1)
    log "auto-*.crl: $before → $after файлов, размер $before_size → $after_size"
else
    log "crls/ не найден"
fi

# ============================================================
# 2. Логи
# ============================================================
log "─── 2. Логи ───"
if [ -d logs ]; then
    before_size=$(du -sh logs/ 2>/dev/null | cut -f1)

    # Ротация app.log — если больше 100 MB, сжать в gz
    if [ -f logs/app.log ]; then
        size_mb=$(du -m logs/app.log 2>/dev/null | cut -f1)
        if [ "$size_mb" -gt 100 ]; then
            archive="logs/app-$(date +%Y%m%d_%H%M%S).log.gz"
            if [ "$DRY_RUN" = "0" ]; then
                gzip -c logs/app.log > "$archive"
                : > logs/app.log
                log "app.log ($size_mb MB) → $archive"
            else
                log "[dry-run] app.log ($size_mb MB) → $archive"
            fi
        fi
    fi

    # Удалить старые gz
    if [ "$DRY_RUN" = "0" ]; then
        find logs -maxdepth 1 -name '*.log.gz' -mtime +"$LOG_MAX_AGE_DAYS" -delete 2>/dev/null || true
    fi

    after_size=$(du -sh logs/ 2>/dev/null | cut -f1)
    log "logs/: $before_size → $after_size"
else
    log "logs/ не найден"
fi

# ============================================================
# 3. Бэкапы
# ============================================================
log "─── 3. Бэкапы ───"
if [ -d backups ]; then
    before_size=$(du -sh backups/ 2>/dev/null | cut -f1)
    before_count=$(ls backups/*.gz 2>/dev/null | wc -l)

    if [ "$DRY_RUN" = "0" ]; then
        find backups -maxdepth 1 -name 'afina_*.sql.gz'    -mtime +"$BACKUP_DB_MAX_AGE_DAYS"      -delete 2>/dev/null || true
        find backups -maxdepth 1 -name 'storage_*.tar.gz'  -mtime +"$BACKUP_STORAGE_MAX_AGE_DAYS" -delete 2>/dev/null || true
        # старые test-*
        find backups -maxdepth 1 -name 'test_*.sql.gz'     -mtime +7 -delete 2>/dev/null || true
    fi

    after_size=$(du -sh backups/ 2>/dev/null | cut -f1)
    after_count=$(ls backups/*.gz 2>/dev/null | wc -l)
    log "backups/: $before_count → $after_count файлов, $before_size → $after_size"
else
    log "backups/ не найден"
fi

# ============================================================
# 4. Docker (только если запущен)
# ============================================================
log "─── 4. Docker ───"
if docker info >/dev/null 2>&1; then
    if [ "$DRY_RUN" = "0" ]; then
        before=$(docker system df --format '{{.Size}}' 2>/dev/null | head -1)
        log "Docker до очистки: $before"

        # Build cache — самый прожорливый (может быть >10 GB)
        cache_freed=$(docker builder prune -a -f 2>&1 | grep -oE "Total reclaimed space: .*" | cut -d' ' -f4-)
        log "Build cache: освобождено $cache_freed"

        # Dangling + неиспользуемые образы (кроме активных)
        images_freed=$(docker image prune -a -f 2>&1 | grep -oE "Total reclaimed space: .*" | cut -d' ' -f4-)
        log "Images: освобождено $images_freed"

        # Stopped containers
        docker container prune -f >/dev/null 2>&1 || true

        # ВАЖНО: не трогаем volumes — там БД и angie
        after=$(docker system df --format '{{.Size}}' 2>/dev/null | head -1)
        log "Docker после очистки: $after (volumes НЕ тронуты)"
    else
        log "[dry-run] пропускаем docker prune"
    fi
else
    log "Docker не запущен — пропускаем"
fi

# ============================================================
# 5. Общий диск
# ============================================================
log "─── 5. Диск ───"
df -h / | awk 'NR==2 {print "Диск /: " $3 " / " $2 " (" $5 ")"}'
free -m | awk '/Mem:/ {print "RAM: " $3 " MB / " $2 " MB"}'

log "Готово"
