#!/usr/bin/env bash
# ============================================================
# Афина · Cron-мониторинг (app-level fallback)
# ============================================================
# Работает, когда приложение лежит и MonitoringService не может отправить алерт.
# Проверяет: контейнеры, порт 443, health endpoint, диск.
# 3 сбоя подряд → алерт через локальный Postfix (порт 25, без auth).
# ============================================================
set -e
cd "$(dirname "$0")"

set -a
# shellcheck disable=SC1091
source .env.prod
set +a

LOG="logs/monitor.log"
STATE="logs/monitor.state"
DOMAIN="${APP_DOMAIN:-}"

# Кому слать алерты: сначала из app_settings, потом MAIL_FROM
MAIL_TO=""
MAIL_TO=$(docker exec afina-postgres psql -U postgres -d afina_db -t -A \
    -c "SELECT COALESCE(NULLIF(value,''), '') FROM app_settings WHERE key = 'monitor.test_email'" 2>/dev/null \
    | tr -d '[:space:]')
if [ -z "$MAIL_TO" ]; then
    MAIL_TO=$(docker exec afina-postgres psql -U postgres -d afina_db -t -A \
        -c "SELECT COALESCE(NULLIF(value,''), '') FROM app_settings WHERE key = 'monitor.mail_to'" 2>/dev/null \
        | tr -d '[:space:]')
fi
[ -z "$MAIL_TO" ] && MAIL_TO="${MONITOR_MAIL_TO:-$MAIL_FROM}"

mkdir -p logs
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG"; }

fail_reasons=""

# 1. Контейнеры
running=$(docker compose -f docker-compose-prod.yml --env-file .env.prod ps \
    --status running --services 2>/dev/null | sort | tr '\n' ' ')
for need in app angie postgres; do
    echo "$running" | grep -qw "$need" || fail_reasons="${fail_reasons}container-${need} "
done

# 2. Health через контейнер app (локально)
if ! docker exec afina-app sh -c 'curl -fsS http://localhost:8080/actuator/health 2>/dev/null' \
        | grep -q '"status":"UP"' 2>/dev/null; then
    fail_reasons="${fail_reasons}app-health "
fi

# 3. HTTPS снаружи (Angie + TLS + DNS)
if [ -n "$DOMAIN" ]; then
    if ! curl -fsS --max-time 10 "https://${DOMAIN}/actuator/health" \
            | grep -q '"status":"UP"' 2>/dev/null; then
        fail_reasons="${fail_reasons}https-external "
    fi
fi

# 4. Диск > 95%
DISK_PCT=$(df / | awk 'NR==2 {gsub("%",""); print $5}')
[ "${DISK_PCT:-0}" -ge 95 ] && fail_reasons="${fail_reasons}disk-${DISK_PCT}% "

# 5. Postgres порт
docker exec afina-postgres pg_isready -q -U postgres 2>/dev/null || \
    fail_reasons="${fail_reasons}pg-not-ready "

# === Результат ===
if [ -z "$fail_reasons" ]; then
    if [ -f "$STATE" ] && [ "$(cat "$STATE")" != "0" ]; then
        log "✓ Восстановление после сбоя"
    else
        running_clean="${running% }"  # strip trailing space
        log "✓ OK (контейнеры: ${running_clean// /, }, disk=${DISK_PCT}%)"
    fi
    echo "0" > "$STATE"
    exit 0
fi

fails=$(cat "$STATE" 2>/dev/null || echo 0)
fails=$((fails + 1))
echo "$fails" > "$STATE"
log "✗ Сбой #${fails}: ${fail_reasons}"

# Алерт после 3 сбоев подряд, потом каждые 3
if [ "$fails" -ge 3 ] && [ $((fails % 3)) -eq 0 ]; then
    log "▶ Отправка алерта на ${MAIL_TO}"
    MAIL_TO="$MAIL_TO" FAIL_REASONS="$fail_reasons" FAILS="$fails" DOMAIN="${DOMAIN}" \
    MAIL_HOST="${MAIL_HOST:-localhost}" MAIL_PORT="${MAIL_PORT:-25}" \
    MAIL_FROM="${MAIL_FROM:-noreply@afinasystems.ru}" \
    python3 - <<'PYEOF' >> "$LOG" 2>&1
import smtplib, socket, os
from email.mime.text import MIMEText

host = os.environ.get("MAIL_HOST", "localhost")
port = int(os.environ.get("MAIL_PORT", "25"))
from_ = os.environ.get("MAIL_FROM")
to   = os.environ.get("MAIL_TO") or from_
domain = os.environ.get("DOMAIN", "?")
reasons = os.environ.get("FAIL_REASONS", "?")
fails = os.environ.get("FAILS", "0")

subject = f"[Афина CRON] Сервер недоступен: {domain} (#{fails})"
body = f"""Cron-мониторинг обнаружил проблему.

Домен:     {domain}
Сбоев:     {fails} подряд
Проблемы:  {reasons.strip()}

Проверьте сервер:
  ssh root@{socket.gethostbyname(domain) if domain != '?' else 'server'}
  cd /opt/afinaz
  docker compose -f docker-compose-prod.yml --env-file .env.prod ps
  docker compose -f docker-compose-prod.yml --env-file .env.prod logs app --tail 100

Открыть мониторинг после восстановления:
  https://{domain}/admin/monitor
"""

msg = MIMEText(body, "plain", "utf-8")
msg["Subject"] = subject
msg["From"] = from_
msg["To"] = to

# Plain SMTP на 25 без auth (через локальный Postfix, mynetworks)
with smtplib.SMTP(host, port, timeout=15) as s:
    s.ehlo()
    s.send_message(msg)
print("Email sent to", to)
PYEOF
fi

exit 1
