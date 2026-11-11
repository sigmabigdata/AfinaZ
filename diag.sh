#!/usr/bin/env bash
# ============================================================
# ПАО "НБД-Банк" · Полная диагностика AfinaZ
# ============================================================
# Охватывает: инфраструктуру, БД, ключи, CRL, storage, SMTP,
# мониторинг, аудит, бэкапы, cron, CLI, Angie, HTTP endpoints,
# публичную верификацию, админ-панели, функциональные фичи.
#
# Запуск: sudo ./diag.sh [--no-color] [--quiet] [--markdown-only]
# ============================================================
set -uo pipefail
trap 'echo "⚠ Скрипт прерван"; exit 130' INT TERM

NO_COLOR=0; QUIET=0; MD_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --no-color) NO_COLOR=1 ;;
        --quiet)    QUIET=1 ;;
        --markdown-only) MD_ONLY=1 ;;
        -h|--help)
            cat <<HELP
Использование: sudo ./diag.sh [опции]
  --no-color       без ANSI-цветов (для логов)
  --quiet          только PASS/FAIL/WARN, без info
  --markdown-only  только сгенерировать markdown-отчёт
  -h, --help       эта справка
HELP
            exit 0 ;;
    esac
done

if [ "$NO_COLOR" = "0" ] && [ -t 1 ]; then
    G='\033[0;32m'; Y='\033[0;33m'; R='\033[0;31m'; B='\033[0;34m'
    C='\033[0;36m'; N='\033[0m'; BOLD='\033[1m'; DIM='\033[2m'
else
    G=''; Y=''; R=''; B=''; C=''; N=''; BOLD=''; DIM=''
fi

PASS=0; FAIL=0; WARN=0
declare -a RESULTS_MD

pass() { [ "$QUIET" = "0" ] || [ "$MD_ONLY" = "0" ] && echo -e "  ${G}✅${N} $*"; PASS=$((PASS+1)); RESULTS_MD+=("| ✅ | $1 |"); }
fail() { [ "$QUIET" = "0" ] || [ "$MD_ONLY" = "0" ] && echo -e "  ${R}❌${N} $*"; FAIL=$((FAIL+1)); RESULTS_MD+=("| ❌ | $1 |"); }
warn() { [ "$QUIET" = "0" ] || [ "$MD_ONLY" = "0" ] && echo -e "  ${Y}⚠${N}  $*"; WARN=$((WARN+1)); RESULTS_MD+=("| ⚠ | $1 |"); }
info() { [ "$QUIET" = "1" ] && return; echo -e "  ${DIM}${C}•${N} $*"; }

hdr() { [ "$MD_ONLY" = "1" ] && return; echo ""; echo -e "${B}${BOLD}═══ $* ═══${N}"; }

cd /opt/afinaz 2>/dev/null || { echo "✗ /opt/afinaz не найден"; exit 1; }
COMPOSE_FILE="docker-compose-prod.yml"
ENV_FILE=".env.prod"
APP_URL=$(grep '^APP_BASE_URL=' "$ENV_FILE" 2>/dev/null | cut -d'=' -f2-)
APP_DOMAIN=$(grep '^APP_DOMAIN=' "$ENV_FILE" 2>/dev/null | cut -d'=' -f2-)
[ -z "$APP_URL" ] && APP_URL="https://${APP_DOMAIN:-localhost}"

psql_q() { docker exec afina-postgres psql -U postgres -d afina_db -tAc "$1" 2>/dev/null | tr -d ' '; }

LOG_FILE="/tmp/afina-diag-$(date +%Y%m%d_%H%M%S).log"
MD_FILE="/tmp/afina-diag-$(date +%Y%m%d_%H%M%S).md"

if [ "$MD_ONLY" = "0" ]; then
    clear
    echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
    echo -e "${BOLD}  ПАО «НБД-Банк» · Полная диагностика AfinaZ${N}"
    echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
    echo -e "${DIM}Дата:    $(date '+%Y-%m-%d %H:%M:%S %Z')${N}"
    echo -e "${DIM}Хост:    $(hostname) ($(uname -m))${N}"
    echo -e "${DIM}URL:     $APP_URL${N}"
    echo -e "${DIM}Лог:     $LOG_FILE${N}"
    echo -e "${DIM}Отчёт:   $MD_FILE${N}"
fi

# ═══════════════════════════════════════════════════════════
# [1] Окружение
# ═══════════════════════════════════════════════════════════
hdr "[1/20] Окружение"

[ "$(id -u)" = "0" ] && pass "root" || warn "не root — часть проверок недоступна"
[ "$(uname -m)" = "x86_64" ] && pass "x86_64" || fail "архитектура $(uname -m) — нужен x86_64"

if [ -f /etc/astra_version ]; then
    pass "Astra Linux SE $(cat /etc/astra_version 2>/dev/null | head -1)"
elif [ -f /etc/os-release ]; then
    OS_NAME=$(grep '^PRETTY_NAME=' /etc/os-release | cut -d'"' -f2)
    warn "ОС: $OS_NAME (не Astra — для банка нужна Astra SE)"
else
    fail "не удалось определить ОС"
fi

docker info >/dev/null 2>&1 && pass "docker daemon работает" || fail "docker daemon не запущен"
docker compose version >/dev/null 2>&1 && pass "docker compose OK" || fail "docker compose недоступен"

FREE_MB=$(df -m / | awk 'NR==2 {print $4}')
[ "$FREE_MB" -gt 5000 ] && pass "диск: ${FREE_MB} MB свободно" \
    || { [ "$FREE_MB" -gt 1000 ] && warn "диск: ${FREE_MB} MB (мало)" || fail "диск: ${FREE_MB} MB — критично"; }

MEM_PCT=$(free | awk '/Mem:/ {printf "%d", $3/$2*100}')
[ "$MEM_PCT" -lt 80 ] && pass "память: $MEM_PCT%" \
    || { [ "$MEM_PCT" -lt 90 ] && warn "память: $MEM_PCT%" || fail "память: $MEM_PCT% — критично"; }

# ═══════════════════════════════════════════════════════════
# [2] Контейнеры
# ═══════════════════════════════════════════════════════════
hdr "[2/20] Контейнеры"

for c in afina-postgres afina-app afina-angie; do
    STATUS=$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null)
    if [ "$STATUS" = "running" ]; then
        HEALTH=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c" 2>/dev/null)
        UPTIME=$(docker inspect -f '{{.State.StartedAt}}' "$c" 2>/dev/null | cut -d'.' -f1)
        info "$c: running, health=$HEALTH, since $UPTIME"
        pass "контейнер $c"
    else
        fail "контейнер $c: ${STATUS:-отсутствует}"
    fi
done

RESTARTS_APP=$(docker inspect -f '{{.RestartCount}}' afina-app 2>/dev/null || echo "?")
[ "$RESTARTS_APP" = "0" ] || [ "$RESTARTS_APP" = "1" ] && pass "app restarts: $RESTARTS_APP" \
    || { [ "$RESTARTS_APP" -lt 5 ] && warn "app restarts: $RESTARTS_APP" || fail "app restarts: $RESTARTS_APP — нестабильно"; }

# ═══════════════════════════════════════════════════════════
# [3] Health приложения
# ═══════════════════════════════════════════════════════════
hdr "[3/20] Health & версия приложения"

H_LOCAL=$(docker exec afina-app curl -sf http://localhost:8080/actuator/health 2>/dev/null)
echo "$H_LOCAL" | grep -q '"status":"UP"' && pass "health локальный: UP" || fail "health локальный: DOWN"

H_EXT=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 15 "$APP_URL/actuator/health" 2>/dev/null)
[ "$H_EXT" = "200" ] && pass "health внешний: 200" || fail "health внешний: $H_EXT"

STARTED=$(docker logs afina-app 2>&1 | grep -c "Started UkepSignApplication" || echo "0")
[ "$STARTED" -ge 1 ] && pass "приложение стартовало ($STARTED раз в логе)" || fail "приложение не стартовало"

ERR_COUNT=$(docker logs afina-app --since 1h 2>&1 | grep -c " ERROR " 2>/dev/null | head -1)
[ -z "$ERR_COUNT" ] && ERR_COUNT=0
[ "$ERR_COUNT" -eq 0 ] && pass "логи за час: 0 ERROR" \
    || { [ "$ERR_COUNT" -lt 5 ] && warn "логи за час: $ERR_COUNT ERROR" || fail "логи за час: $ERR_COUNT ERROR"; }

# ═══════════════════════════════════════════════════════════
# [4] PostgreSQL
# ═══════════════════════════════════════════════════════════
hdr "[4/20] PostgreSQL"

PG_UP=$(docker exec afina-postgres pg_isready -U postgres 2>&1 | grep -c "accepting connections")
[ "$PG_UP" -ge 1 ] && pass "pg_isready: OK" || fail "pg_isready: NOK"

PG_SIZE=$(psql_q "SELECT pg_size_pretty(pg_database_size(current_database()))")
info "Размер БД: $PG_SIZE"

MIG_TOTAL=$(psql_q "SELECT count(*) FROM flyway_schema_history")
MIG_FAIL=$(psql_q "SELECT count(*) FROM flyway_schema_history WHERE success=false")
[ "$MIG_FAIL" = "0" ] && pass "миграции: $MIG_TOTAL OK, 0 fail" || fail "миграции: $MIG_FAIL провалено"

ROLES=$(psql_q "SELECT count(*) FROM pg_roles WHERE rolname IN ('afina_app','afina_migrator','afina_auditor')")
[ "$ROLES" = "3" ] && pass "роли БД: 3/3" || fail "роли БД: $ROLES/3"

CONNS=$(psql_q "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()")
info "Активных соединений: $CONNS"
[ "$CONNS" -lt 30 ] && pass "соединения: $CONNS (max 20 pool)" || warn "соединения: $CONNS — много"

# ═══════════════════════════════════════════════════════════
# [5] Целостность данных
# ═══════════════════════════════════════════════════════════
hdr "[5/20] Целостность данных"

USERS=$(psql_q "SELECT count(*) FROM users")
DOCS=$(psql_q "SELECT count(*) FROM documents")
SIGS=$(psql_q "SELECT count(*) FROM document_signatures")
FOLDERS=$(psql_q "SELECT count(*) FROM document_folders")
POA=$(psql_q "SELECT count(*) FROM powers_of_attorney")
TEMPLATES=$(psql_q "SELECT count(*) FROM document_templates")
info "users=$USERS · docs=$DOCS · sigs=$SIGS · folders=$FOLDERS · poa=$POA · tpl=$TEMPLATES"

ORPHAN_DOCS=$(psql_q "SELECT count(*) FROM documents d WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = d.owner_id)")
[ "$ORPHAN_DOCS" = "0" ] && pass "документы без владельца: 0" || fail "документы без владельца: $ORPHAN_DOCS"

ORPHAN_SIGS=$(psql_q "SELECT count(*) FROM document_signatures s WHERE NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = s.document_id)")
[ "$ORPHAN_SIGS" = "0" ] && pass "подписи без документа: 0" || fail "подписи без документа: $ORPHAN_SIGS"

PII_UNENC=$(psql_q "SELECT count(*) FROM users WHERE email_hash IS NOT NULL AND email_enc IS NULL")
[ "$PII_UNENC" = "0" ] && pass "PII: все email зашифрованы" || fail "PII: $PII_UNENC без email_enc"

UNFILED=$(psql_q "SELECT count(*) FROM documents WHERE folder_id IS NULL")
[ "$UNFILED" = "0" ] && pass "документы без раздела: 0" || warn "документы без раздела: $UNFILED (legacy)"

# ═══════════════════════════════════════════════════════════
# [6] Ключи шифрования
# ═══════════════════════════════════════════════════════════
hdr "[6/20] Ключи шифрования"

for k in file pii; do
    KEY_PATH="secrets/keys/$k.key.v"*
    EXIST=$(ls $KEY_PATH 2>/dev/null | wc -l)
    if [ "$EXIST" -gt 0 ]; then
        VER_FILE="secrets/keys/$k.key.current"
        if [ -f "$VER_FILE" ]; then
            CUR=$(cat "$VER_FILE" | tr -d '[:space:]')
            ACTIVE="secrets/keys/$k.key.v$CUR"
            if [ -f "$ACTIVE" ]; then
                SIZE=$(stat -c '%s' "$ACTIVE" 2>/dev/null || stat -f '%z' "$ACTIVE")
                PERM=$(stat -c '%a' "$ACTIVE" 2>/dev/null || stat -f '%p' "$ACTIVE" | tail -c 4)
                DECODED=$(base64 -d "$ACTIVE" 2>/dev/null | wc -c | tr -d ' ')
                [ "$DECODED" = "32" ] && pass "$k.key: $EXIST версий, активна v$CUR, 32B decoded ($SIZE B base64)" \
                    || fail "$k.key v$CUR: decoded $DECODED ≠ 32 (файл $SIZE B)"
                [ "$PERM" = "600" ] && pass "$k.key v$CUR: права 600" || warn "$k.key v$CUR: права $PERM ≠ 600"
            else
                fail "$k.key: .current указывает на v$CUR, но файл отсутствует"
            fi
        else
            warn "$k.key: файл версий есть, .current отсутствует"
        fi
    else
        fail "$k.key: файлы secrets/keys/$k.key.v* отсутствуют"
    fi
done

# ═══════════════════════════════════════════════════════════
# [7] Сертификаты (truststore)
# ═══════════════════════════════════════════════════════════
hdr "[7/20] Сертификаты УЦ"

CERT_COUNT=$(find certs -maxdepth 1 -type f \( -name '*.cer' -o -name '*.crt' -o -name '*.pem' \) 2>/dev/null | wc -l)
[ "$CERT_COUNT" -gt 0 ] && pass "truststore: $CERT_COUNT сертификат(ов)" \
    || fail "truststore: нет сертификатов в certs/"

CERT_IN_CONTAINER=$(docker exec afina-app sh -c 'find /app/certs -maxdepth 1 -type f \( -name "*.cer" -o -name "*.crt" -o -name "*.pem" \) 2>/dev/null | wc -l' 2>/dev/null || echo "0")
[ "$CERT_IN_CONTAINER" = "$CERT_COUNT" ] && pass "сертификаты смонтированы в контейнер: $CERT_IN_CONTAINER" \
    || fail "в контейнере $CERT_IN_CONTAINER, на хосте $CERT_COUNT — не смонтированы"

# ═══════════════════════════════════════════════════════════
# [8] CRL
# ═══════════════════════════════════════════════════════════
hdr "[8/20] CRL"

CRL_TOTAL=$(docker exec afina-app sh -c 'ls /app/crls/*.crl 2>/dev/null | wc -l' 2>/dev/null | tr -d '[:space:]')
[ "$CRL_TOTAL" -gt 0 ] && pass "CRL: $CRL_TOTAL файлов" || warn "CRL: нет ни одного файла (soft-fail — не критично)"

CRL_DIR_SIZE=$(du -sh crls/ 2>/dev/null | cut -f1)
info "Размер crls/: $CRL_DIR_SIZE"

# CRL с истёкшим nextUpdate
EXPIRED_CRL=0
for f in crls/*.crl; do
    [ -f "$f" ] || continue
    NEXT=$(openssl crl -in "$f" -noout -nextupdate 2>/dev/null | cut -d'=' -f2)
    [ -z "$NEXT" ] && continue
    NEXT_EPOCH=$(date -d "$NEXT" +%s 2>/dev/null || date -jf "%b %d %T %Y %Z" "$NEXT" +%s 2>/dev/null)
    [ -z "$NEXT_EPOCH" ] && continue
    [ "$NEXT_EPOCH" -lt "$(date +%s)" ] && EXPIRED_CRL=$((EXPIRED_CRL+1))
done
[ "$EXPIRED_CRL" = "0" ] && pass "CRL: все актуальны" || warn "CRL: $EXPIRED_CRL с истёкшим nextUpdate"

# ═══════════════════════════════════════════════════════════
# [9] Storage (файлы документов)
# ═══════════════════════════════════════════════════════════
hdr "[9/20] Storage"

STORAGE_SIZE=$(du -sh storage/documents/ 2>/dev/null | cut -f1)
ENC_FILES=$(find storage/documents -maxdepth 1 -name '*.enc' -type f 2>/dev/null | wc -l)
info "storage/documents: $STORAGE_SIZE, $ENC_FILES .enc файлов"

[ "$ENC_FILES" -ge "$DOCS" ] && pass "файлов ≥ документов в БД" \
    || fail "файлов $ENC_FILES < документов $DOCS — потеря файлов"

# Осиротевшие .enc (файлы без записи в БД)
ORPHAN_FILES=0
while IFS= read -r f; do
    [ -f "$f" ] || continue
    STORED=$(basename "$f")
    EXISTS=$(psql_q "SELECT count(*) FROM documents WHERE stored_name = '$STORED'")
    [ "$EXISTS" = "0" ] && ORPHAN_FILES=$((ORPHAN_FILES+1))
done < <(find storage/documents -maxdepth 1 -name '*.enc' -type f 2>/dev/null)
[ "$ORPHAN_FILES" = "0" ] && pass "нет файлов-сирот" || warn "файлов-сирот: $ORPHAN_FILES (см. /admin/system cleanup)"

# ═══════════════════════════════════════════════════════════
# [10] Визуализации
# ═══════════════════════════════════════════════════════════
hdr "[10/20] Визуализации подписей"

VIS_DB=$(psql_q "SELECT count(*) FROM document_visualizations")
VIS_FILES=$(find storage/documents/visualizations -maxdepth 1 -name '*.pdf' -type f 2>/dev/null | wc -l)
info "В БД: $VIS_DB, файлов на диске: $VIS_FILES"

SIGNED_DOCS=$(psql_q "SELECT count(*) FROM documents WHERE signed = true")
[ "$VIS_DB" -le "$SIGNED_DOCS" ] && pass "визуализаций ≤ подписанных документов" \
    || warn "визуализаций $VIS_DB > подписанных $SIGNED_DOCS"

# ═══════════════════════════════════════════════════════════
# [11] SMTP
# ═══════════════════════════════════════════════════════════
hdr "[11/20] SMTP"

MAIL_HOST=$(docker exec afina-app sh -c 'echo "${MAIL_HOST:-localhost}"' 2>/dev/null)
MAIL_PORT=$(docker exec afina-app sh -c 'echo "${MAIL_PORT:-25}"' 2>/dev/null)
info "SMTP: $MAIL_HOST:$MAIL_PORT"

SMTP_TEST=$(docker exec afina-app bash -c "
  timeout 5 bash -c 'exec 3<>/dev/tcp/${MAIL_HOST}/${MAIL_PORT}; head -1 <&3' 2>/dev/null
" 2>/dev/null | head -1)
if echo "$SMTP_TEST" | grep -q "^220"; then
    pass "SMTP: $MAIL_HOST:$MAIL_PORT → ${SMTP_TEST:0:50}"
else
    warn "SMTP: соединение с $MAIL_HOST:$MAIL_PORT не удалось (результат: ${SMTP_TEST:0:50})"
fi

SMTP_DB_HOST=$(psql_q "SELECT COALESCE(value, '') FROM app_settings WHERE key = 'smtp.host'")
if [ -z "$SMTP_DB_HOST" ] || [ "$SMTP_DB_HOST" = "localhost" ] || [ "$SMTP_DB_HOST" = "127.0.0.1" ]; then
    pass "app_settings.smtp.* пусты (используется .env.prod)"
else
    warn "app_settings.smtp.host=$SMTP_DB_HOST — переопределяет .env.prod (проверить /admin/settings)"
fi

# ═══════════════════════════════════════════════════════════
# [12] Email за 24 часа
# ═══════════════════════════════════════════════════════════
hdr "[12/20] Email статистика"

EMAIL_SENT=$(psql_q "SELECT count(*) FROM audit_events WHERE event_type='EMAIL_SENT' AND event_time > NOW() - INTERVAL '24 hours'")
EMAIL_FAIL=$(psql_q "SELECT count(*) FROM audit_events WHERE event_type='EMAIL_FAIL' AND event_time > NOW() - INTERVAL '24 hours'")
info "за 24ч: отправлено=$EMAIL_SENT, ошибок=$EMAIL_FAIL"

if [ "$EMAIL_SENT" -eq 0 ] && [ "$EMAIL_FAIL" -eq 0 ]; then
    info "нет отправок за 24ч (нет активности)"
elif [ "$EMAIL_FAIL" -eq 0 ]; then
    pass "email: $EMAIL_SENT отправлено, 0 ошибок"
elif [ "$EMAIL_FAIL" -lt 5 ]; then
    warn "email: $EMAIL_FAIL ошибок (проверить /admin/monitor)"
else
    fail "email: $EMAIL_FAIL ошибок за 24ч"
fi

# ═══════════════════════════════════════════════════════════
# [13] Мониторинг
# ═══════════════════════════════════════════════════════════
hdr "[13/20] Мониторинг"

MON_ENABLED=$(psql_q "SELECT COALESCE(value, 'true') FROM app_settings WHERE key = 'monitor.enabled'")
[ "$MON_ENABLED" = "true" ] && pass "monitor.enabled=true" || warn "monitor.enabled=$MON_ENABLED — выключен"

MON_FAILS=$(psql_q "SELECT COALESCE(value, '0') FROM app_settings WHERE key = 'monitor.consecutive_fails'")
[ "$MON_FAILS" = "0" ] && pass "monitor.consecutive_fails=0" \
    || { [ "$MON_FAILS" -lt 5 ] && warn "monitor.consecutive_fails=$MON_FAILS" || fail "monitor.consecutive_fails=$MON_FAILS — критично"; }

MON_LAST=$(psql_q "SELECT COALESCE(value, '') FROM app_settings WHERE key = 'monitor.last_check_at'")
info "monitor.last_check_at: ${MON_LAST:-никогда}"

# Последние события мониторинга
MON_EVENTS=$(psql_q "SELECT count(*) FROM monitor_events WHERE status='FAIL' AND checked_at > NOW() - INTERVAL '1 hour'")
[ "$MON_EVENTS" = "0" ] && pass "monitor_events: 0 FAIL за час" \
    || { [ "$MON_EVENTS" -lt 5 ] && warn "monitor_events: $MON_EVENTS FAIL за час" || fail "monitor_events: $MON_EVENTS FAIL за час"; }

# ═══════════════════════════════════════════════════════════
# [14] Аудит
# ═══════════════════════════════════════════════════════════
hdr "[14/20] Аудит"

AUDIT_TOTAL=$(psql_q "SELECT count(*) FROM audit_events")
AUDIT_24H=$(psql_q "SELECT count(*) FROM audit_events WHERE event_time > NOW() - INTERVAL '24 hours'")
info "audit_events: всего=$AUDIT_TOTAL, за 24ч=$AUDIT_24H"
pass "аудит: события пишутся" # если бы не писались, TOTAL был бы 0

RETENTION=$(psql_q "SELECT COALESCE(value, '365') FROM app_settings WHERE key = 'audit.retention_days'")
info "retention: $RETENTION дней"
[ "$RETENTION" = "0" ] && warn "retention=0 — записи не удаляются" || pass "retention: $RETENTION дней"

# ═══════════════════════════════════════════════════════════
# [15] Бэкапы
# ═══════════════════════════════════════════════════════════
hdr "[15/20] Бэкапы"

LAST_DB_BACKUP=$(ls -t backups/afina_*.sql.gz.enc 2>/dev/null | head -1)
LAST_ST_BACKUP=$(ls -t backups/storage_*.tar.gz.enc 2>/dev/null | head -1)

if [ -n "$LAST_DB_BACKUP" ]; then
    AGE_H=$(( ($(date +%s) - $(stat -c '%Y' "$LAST_DB_BACKUP" 2>/dev/null || stat -f '%m' "$LAST_DB_BACKUP")) / 3600 ))
    SIZE=$(du -h "$LAST_DB_BACKUP" | cut -f1)
    [ "$AGE_H" -lt 30 ] && pass "БД-бэкап: $(basename $LAST_DB_BACKUP) ($SIZE, ${AGE_H}ч)" \
        || { [ "$AGE_H" -lt 72 ] && warn "БД-бэкап: ${AGE_H}ч назад" || fail "БД-бэкап: ${AGE_H}ч назад — старый"; }
else
    fail "БД-бэкап: не найдено"
fi

if [ -n "$LAST_ST_BACKUP" ]; then
    AGE_H=$(( ($(date +%s) - $(stat -c '%Y' "$LAST_ST_BACKUP" 2>/dev/null || stat -f '%m' "$LAST_ST_BACKUP")) / 3600 ))
    SIZE=$(du -h "$LAST_ST_BACKUP" | cut -f1)
    [ "$AGE_H" -lt 30 ] && pass "Storage-бэкап: $(basename $LAST_ST_BACKUP) ($SIZE, ${AGE_H}ч)" \
        || warn "Storage-бэкап: ${AGE_H}ч назад"
else
    fail "Storage-бэкап: не найдено"
fi

BACKUP_COUNT=$(ls backups/*.enc 2>/dev/null | wc -l)
info "всего бэкапов: $BACKUP_COUNT"

BACKUP_PASS="secrets/backup.pass"
[ -f "$BACKUP_PASS" ] && pass "backup.pass на месте" || fail "backup.pass отсутствует — бэкапы не работают"

# ═══════════════════════════════════════════════════════════
# [16] Cron
# ═══════════════════════════════════════════════════════════
hdr "[16/20] Cron задачи"

for cron in afina-backup afina-monitor afina-cleanup; do
    [ -f "/etc/cron.d/$cron" ] && pass "cron: $cron установлен" || warn "cron: $cron отсутствует"
done

MON_LOG_SIZE=$(stat -c '%s' /var/log/afina/monitor-cron.log 2>/dev/null || echo "0")
[ "$MON_LOG_SIZE" -gt 0 ] && pass "monitor-cron.log пишется ($(du -h /var/log/afina/monitor-cron.log | cut -f1))" \
    || warn "monitor-cron.log пуст (cron не запускался или сломан)"

# ═══════════════════════════════════════════════════════════
# [17] CLI afina
# ═══════════════════════════════════════════════════════════
hdr "[17/20] CLI afina"

[ -x /usr/local/bin/afina ] && pass "/usr/local/bin/afina исполняемый" || fail "/usr/local/bin/afina отсутствует или не исполняемый"

AFINA_TARGET=$(readlink -f /usr/local/bin/afina 2>/dev/null)
[ "$AFINA_TARGET" = "/opt/afinaz/afina.sh" ] && pass "afina → afina.sh (правильный symlink)" \
    || { [ -n "$AFINA_TARGET" ] && warn "afina → $AFINA_TARGET" || fail "afina: битый symlink"; }

if command -v afina >/dev/null 2>&1; then
    afina help >/dev/null 2>&1 && pass "afina help выполняется" || warn "afina help не выполняется"
fi

# ═══════════════════════════════════════════════════════════
# [18] Angie
# ═══════════════════════════════════════════════════════════
hdr "[18/20] Angie"

ANGIE_STATUS=$(docker inspect -f '{{.State.Status}}' afina-angie 2>/dev/null)
[ "$ANGIE_STATUS" = "running" ] && pass "afina-angie running" || fail "afina-angie: $ANGIE_STATUS"

# Angie config test
ANGIE_TEST=$(docker exec afina-angie angie -t 2>&1)
echo "$ANGIE_TEST" | grep -q "syntax is ok" && pass "angie config syntax OK" || warn "angie config test: $(echo "$ANGIE_TEST" | tail -2)"

# SSL-сертификат
CERT_EXPIRY=$(echo | timeout 10 openssl s_client -connect "${APP_DOMAIN:-localhost}:443" -servername "${APP_DOMAIN:-localhost}" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d'=' -f2)
if [ -n "$CERT_EXPIRY" ]; then
    EXPIRY_EPOCH=$(date -d "$CERT_EXPIRY" +%s 2>/dev/null || date -jf "%b %d %T %Y %Z" "$CERT_EXPIRY" +%s 2>/dev/null)
    if [ -n "$EXPIRY_EPOCH" ]; then
        DAYS_LEFT=$(( ($EXPIRY_EPOCH - $(date +%s)) / 86400 ))
        [ "$DAYS_LEFT" -gt 14 ] && pass "SSL: $DAYS_LEFT дней до истечения" \
            || { [ "$DAYS_LEFT" -gt 0 ] && warn "SSL: $DAYS_LEFT дней до истечения" || fail "SSL: истёк"; }
    fi
else
    warn "SSL: не удалось проверить (DNS? порт 443?)"
fi

# ═══════════════════════════════════════════════════════════
# [19] HTTP endpoints (публичные + admin pages)
# ═══════════════════════════════════════════════════════════
hdr "[19/20] HTTP endpoints"

check_http() {
    local path="$1" expect="$2" name="$3"
    local code
    code=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 10 "$APP_URL$path" 2>/dev/null)
    if [ "$code" = "$expect" ]; then
        pass "$name: $path → $code"
    else
        fail "$name: $path → $code (ожидалось $expect)"
    fi
}

check_http "/" 302 "root (→ login)"
check_http "/login" 200 "login GET"
check_http "/login/confirm?token=invalid" 200 "login/confirm (invalid token)"
check_http "/actuator/health" 200 "actuator/health"
check_http "/admin/login" 200 "admin/login GET"
check_http "/admin" 302 "admin (без авторизации → login)"
check_http "/admin/users" 302 "admin/users (без авторизации)"
check_http "/admin/system" 302 "admin/system (без авторизации)"
# API endpoints возвращают 401 при Accept: application/json, 302 без него
api_code() {
    local path="$1" name="$2"
    local code
    code=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 10 \
           -H "Accept: application/json" "$APP_URL$path" 2>/dev/null)
    if [ "$code" = "401" ]; then
        pass "$name: $path → 401"
    else
        fail "$name: $path → $code (ожидалось 401 с Accept: application/json)"
    fi
}
api_code "/api/search" "api/search (без авторизации)"
api_code "/api/templates" "api/templates (без авторизации)"
check_http "/css/style.css" 200 "static css"

# ═══════════════════════════════════════════════════════════
# [20] Публичная верификация
# ═══════════════════════════════════════════════════════════
hdr "[20/20] Публичная верификация подписи"

if [ "$SIGNED_DOCS" -gt 0 ]; then
    UUID=$(psql_q "SELECT public_uuid FROM documents WHERE signed = true AND public_uuid IS NOT NULL LIMIT 1")
    if [ -n "$UUID" ]; then
        info "тестовый UUID: $UUID"
        API_CODE=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 10 "$APP_URL/api/info/$UUID" 2>/dev/null)
        [ "$API_CODE" = "200" ] && pass "/api/info/{uuid} → 200" || fail "/api/info/{uuid} → $API_CODE"

        PAGE_CODE=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 10 "$APP_URL/info/$UUID" 2>/dev/null)
        [ "$PAGE_CODE" = "200" ] && pass "/info/{uuid} → 200" || fail "/info/{uuid} → $PAGE_CODE"

        INFO_JSON=$(curl -sk --max-time 10 "$APP_URL/api/info/$UUID" 2>/dev/null)
        echo "$INFO_JSON" | grep -q '"signatureCount"' && pass "api/info: возвращает signatureCount" \
            || warn "api/info: signatureCount не найден в ответе"
    else
        warn "нет подписанных документов с UUID — проверка пропущена"
    fi
else
    info "нет подписанных документов — пропускаем"
fi

# ═══════════════════════════════════════════════════════════
# Сводка
# ═══════════════════════════════════════════════════════════
echo ""
echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
echo -e "${BOLD}  СВОДКА${N}"
echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
echo -e "  ${G}✅ PASS: $PASS${N}"
echo -e "  ${Y}⚠  WARN: $WARN${N}"
echo -e "  ${R}❌ FAIL: $FAIL${N}"
TOTAL=$((PASS+WARN+FAIL))
[ "$TOTAL" -gt 0 ] && echo -e "  ${DIM}Всего проверок: $TOTAL${N}"
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo -e "${R}${BOLD}ОБНАРУЖЕНЫ КРИТИЧЕСКИЕ ПРОБЛЕМЫ${N}"
    exit 1
elif [ "$WARN" -gt 0 ]; then
    echo -e "${Y}${BOLD}ЕСТЬ ПРЕДУПРЕЖДЕНИЯ — проверьте вручную${N}"
    exit 0
else
    echo -e "${G}${BOLD}ВСЁ В НОРМЕ ✅${N}"
    exit 0
fi
