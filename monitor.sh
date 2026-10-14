#!/usr/bin/env bash
# ============================================================
# Афина · Мониторинг доступности сервиса
# ============================================================
# Проверяет:
#   1. HTTP-ответ /actuator/health (локально)
#   2. HTTPS-ответ снаружи
#   3. Все контейнеры running + healthy
#
# Если 3 проверки подряд неудачны → письмо на MAIL_TO.
# Состояние хранится в logs/monitor.state.
# ============================================================
set -e
cd "$(dirname "$0")"

# Загрузить .env.prod
set -a
# shellcheck disable=SC1091
source .env.prod
set +a

LOG="logs/monitor.log"
STATE="logs/monitor.state"
DOMAIN="${APP_DOMAIN:-}"

# Email для алертов: сначала из БД (app_settings), потом из .env
MAIL_TO=""
if docker exec afina-postgres psql -U postgres -d afina_db -t -A \
        -c "SELECT value FROM app_settings WHERE key = 'monitor.mail_to'" 2>/dev/null \
        | grep -q '@'; then
    MAIL_TO=$(docker exec afina-postgres psql -U postgres -d afina_db -t -A \
        -c "SELECT value FROM app_settings WHERE key = 'monitor.mail_to'" 2>/dev/null \
        | tr -d '[:space:]')
fi
[ -z "$MAIL_TO" ] && MAIL_TO="${MONITOR_MAIL_TO:-$MAIL_FROM}"

mkdir -p logs

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG"; }

# --- Проверки ---
fail_reasons=""

# 1. Локальный health через контейнер (не зависит от Angie/сети)
if ! docker exec afina-app sh -c 'curl -fsS http://localhost:8080/actuator/health 2>/dev/null' \
        | grep -q '"status":"UP"'; then
    fail_reasons="${fail_reasons}app-health "
fi

# 2. HTTPS снаружи (проверка Angie + TLS + DNS)
if [ -n "$DOMAIN" ]; then
    if ! curl -fsS --max-time 10 "https://${DOMAIN}/actuator/health" \
            | grep -q '"status":"UP"'; then
        fail_reasons="${fail_reasons}https-external "
    fi
fi

# 3. Контейнеры
running=$(docker compose -f docker-compose-prod.yml --env-file .env.prod ps \
    --status running --services 2>/dev/null | sort | tr '\n' ' ')
for need in app angie postgres; do
    if ! echo "$running" | grep -qw "$need"; then
        fail_reasons="${fail_reasons}container-${need} "
    fi
done

# --- Обработка результата ---
if [ -z "$fail_reasons" ]; then
    # OK — сбрасываем счётчик
    if [ -f "$STATE" ] && [ "$(cat "$STATE")" != "0" ]; then
        log "✓ Восстановление после сбоя"
    fi
    echo "0" > "$STATE"
    exit 0
fi

# Не OK — увеличиваем счётчик
fails=$(cat "$STATE" 2>/dev/null || echo 0)
fails=$((fails + 1))
echo "$fails" > "$STATE"

log "✗ Сбой #${fails}: ${fail_reasons}"

# Алерт после 3 подряд
if [ "$fails" -eq 3 ]; then
    log "▶ Отправка алерта на ${MAIL_TO}"
    MAIL_TO="$MAIL_TO" FAIL_REASONS="$fail_reasons" python3 - <<PYEOF >> "$LOG" 2>&1
import smtplib, ssl, sys, os
from email.mime.text import MIMEText

host = os.environ.get("MAIL_HOST")
port = int(os.environ.get("MAIL_PORT", "465"))
user = os.environ.get("MAIL_USERNAME")
pwd  = os.environ.get("MAIL_PASSWORD")
from_ = os.environ.get("MAIL_FROM")
to   = os.environ.get("MAIL_TO") or from_
domain = os.environ.get("APP_DOMAIN", "?")
reasons = os.environ.get("FAIL_REASONS", "?")

subject = f"[Афина] Сервис недоступен: {domain}"
body = f"""Обнаружена проблема с сервисом.

Домен:    {domain}
Проблемы: {reasons}
Проверок подряд провалено: 3

Проверьте сервер:
  ssh afina-vps
  cd /opt/afina
  docker compose -f docker-compose-prod.yml --env-file .env.prod ps
  docker compose -f docker-compose-prod.yml --env-file .env.prod logs app --tail 50
"""

msg = MIMEText(body, "plain", "utf-8")
msg["Subject"] = subject
msg["From"] = from_
msg["To"] = to

# Внутренний SMTP с самоподписанным сертификатом — отключаем верификацию
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
with smtplib.SMTP_SSL(host, port, context=ctx, timeout=15) as s:
    s.login(user, pwd)
    s.send_message(msg)
print("Email sent to", to)
PYEOF
fi

# Повторный алерт при 6, 12, 24... (каждые 3 следующих)
if [ "$fails" -ge 6 ] && [ $((fails % 3)) -eq 0 ]; then
    log "▶ Повторный алерт #${fails}"
fi

exit 1
