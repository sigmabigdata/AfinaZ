#!/bin/bash
# ============================================================
# AfinaZ · Setup Applier
# ============================================================
# Host-side watcher. Запускается как systemd-сервис, работает
# всегда (в обычном режиме тоже). Каждые 5s проверяет:
#   setup-out/setup.complete  — маркер от SetupService.finalizeSetup()
#   setup-out/.env.prod.new   — финальный конфиг от wizard'а
#
# Если оба есть:
#   1. Backup текущего .env.prod
#   2. cp .env.prod.new → .env.prod
#   3. docker compose up -d --force-recreate --no-deps app
#      (применяет новые env vars — restart этого не умеет)
#   4. rm setup-out/{setup.complete, .env.prod.new}
#
# При ошибке docker compose:
#   • восстановить .env.prod из backup
#   • удалить setup.complete (чтобы не зациклиться)
#   • залогировать ошибку
# ============================================================
set -uo pipefail

cd /opt/afinaz || { echo "✗ /opt/afinaz не найден"; exit 1; }

MARKER="setup-out/setup.complete"
ENV_NEW="setup-out/.env.prod.new"
LOG="/var/log/afina/setup-applier.log"

mkdir -p /var/log/afina
touch "$LOG"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG"; }

log "═══ setup-applier started (pid=$$) ═══"

while true; do
    if [ -f "$MARKER" ] && [ -f "$ENV_NEW" ]; then
        log "Detected setup.complete — applying new .env.prod"

        # ─── Валидация ───
        if ! grep -q '^APP_DOMAIN=' "$ENV_NEW" 2>/dev/null; then
            log "✗ .env.prod.new: нет APP_DOMAIN — откладываю в .invalid"
            mv "$ENV_NEW" "${ENV_NEW}.invalid.$(date +%s)" 2>/dev/null || true
            rm -f "$MARKER"
            sleep 5
            continue
        fi

        # ─── Backup старого .env.prod ───
        if [ -f .env.prod ]; then
            cp .env.prod ".env.prod.bak.$(date +%s)" 2>/dev/null || true
            log "✓ backup: .env.prod.bak.<ts>"
        fi

        # ─── Применить новый ───
        if ! cp "$ENV_NEW" .env.prod 2>/dev/null; then
            log "✗ не удалось cp .env.prod.new → .env.prod"
            sleep 30
            continue
        fi
        chmod 600 .env.prod 2>/dev/null || true
        log "✓ .env.prod обновлён (600)"

        # ─── Force-recreate app с новыми env vars ───
        if docker compose -f docker-compose-prod.yml --env-file .env.prod \
                up -d --force-recreate --no-deps app >> "$LOG" 2>&1; then
            log "✓ app пересоздан с новыми env vars"
            rm -f "$MARKER" "$ENV_NEW"

            # Ротация .env.prod.bak — оставляем последние 3
            ls -t .env.prod.bak.* 2>/dev/null | tail -n +4 | xargs -r rm -f
            log "✓ ротация .env.prod.bak (≤3)"

            log "✓ маркеры очищены — setup завершён"
        else
            log "✗ docker compose up failed — откат .env.prod"
            LATEST=$(ls -t .env.prod.bak.* 2>/dev/null | head -1)
            if [ -n "$LATEST" ]; then
                cp "$LATEST" .env.prod
                log "✓ откат из $LATEST"
            fi
            rm -f "$MARKER"
        fi
    fi
    sleep 5
done
