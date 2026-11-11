#!/bin/bash
# ============================================================
# AfinaZ · Entrypoint
# ============================================================
# Просто exec java. Применение .env.prod.new выполняет
# host-side setup-applier.sh (systemd unit afina-setup-applier.service),
# потому что:
#   • .env.prod не смонтирован в контейнер
#   • restart: unless-stopped не перечитывает env_file
#   • --force-recreate умеет — но только с хоста
# ============================================================
set -euo pipefail

log() { echo "[entrypoint] $*"; }

if [ "${APP_SETUP_MODE:-false}" = "true" ]; then
    log "Режим Setup Mode — wizard доступен на /setup"
fi

log "Запуск: java $JAVA_OPTS -jar /app/app.jar"
exec java $JAVA_OPTS -jar /app/app.jar
