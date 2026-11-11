#!/bin/bash
# ============================================================
# AfinaZ · Entrypoint wrapper
# ============================================================
# Обрабатывает переход Setup Mode → обычный режим.
#
# При старте контейнера проверяет:
#   1. Есть ли /app/setup-out/setup.complete (маркер от SetupController)
#   2. Есть ли /app/setup-out/.env.prod.new (новый конфиг)
# Если оба есть:
#   • Копирует .env.prod.new в /app/.env.prod (через host mount)
#   • Удаляет маркеры
#   • Логирует "Setup applied"
#   • exit 0 → docker restart: unless-stopped поднимет контейнер
#     уже с новым .env.prod (SPRING_PROFILES_ACTIVE=prod, APP_SETUP_MODE=false)
#
# Во всех остальных случаях:
#   • exec java ... (обычный старт)
# ============================================================
set -euo pipefail

SETUP_OUT="/app/setup-out"
SETUP_COMPLETE="$SETUP_OUT/setup.complete"
ENV_NEW="$SETUP_OUT/.env.prod.new"
ENV_APPLIED_MARKER="$SETUP_OUT/.env.prod.applied"

log() { echo "[entrypoint] $*"; }

# ─── Case: Setup completed, apply new env ───
if [ -f "$SETUP_COMPLETE" ] && [ -f "$ENV_NEW" ]; then
    log "════════ Setup Mode завершён ════════"
    log "Применяю новые настройки из $ENV_NEW"

    # Валидация: что в новом .env.prod обязательные переменные
    if ! grep -q '^APP_DOMAIN=' "$ENV_NEW"; then
        log "✗ .env.prod.new не содержит APP_DOMAIN — оставляю старый"
        rm -f "$SETUP_COMPLETE" "$ENV_NEW"
    else
        # Backup текущего .env.prod
        if [ -f /app/.env.prod ]; then
            cp /app/.env.prod "/app/.env.prod.bak.$(date +%s)" 2>/dev/null || true
        fi

        # Применяем новый
        cp "$ENV_NEW" /app/.env.prod
        chmod 600 /app/.env.prod

        # Маркер: применено (для диагностики)
        date -u +"applied at %Y-%m-%dT%H:%M:%SZ" > "$ENV_APPLIED_MARKER"

        # Удаляем маркеры setup, чтобы не зациклиться
        rm -f "$SETUP_COMPLETE" "$ENV_NEW"

        log "✓ .env.prod обновлён"
        log "✓ Рестарт в обычном режиме (SPRING_PROFILES_ACTIVE=prod)"
        log "════════════════════════════════════════"

        # Exit → docker restart: unless-stopped поднимет через ~2 сек
        # на этот раз setup.complete нет → обычный режим
        exit 0
    fi
fi

# ─── Case: Setup отменён/не завершён — продолжаем в setup-режиме ───
if [ -f /app/.env.prod ] && grep -q '^APP_SETUP_MODE=true' /app/.env.prod 2>/dev/null; then
    log "Режим Setup Mode (wizard на /setup)"
else
    log "Обычный режим"
fi

# ─── Обычный старт ───
log "Запуск: java $JAVA_OPTS -jar /app/app.jar"
exec java $JAVA_OPTS -jar /app/app.jar
