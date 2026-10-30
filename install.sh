#!/usr/bin/env bash
# ============================================================
# ПАО "НБД-Банк" · Универсальный установщик
# Поддерживает: Ubuntu, Debian, Astra Linux, RHEL/CentOS
# ============================================================
set -euo pipefail

G='\033[0;32m'; Y='\033[1;33m'; R='\033[0;31m'
B='\033[0;34m'; C='\033[0;36m'; N='\033[0m'; BOLD='\033[1m'; DIM='\033[2m'

STEP=0; FAILED=0
LOG="/tmp/afina-install-$(date +%Y%m%d_%H%M%S).log"

log()   { echo "[$(date '+%H:%M:%S')] $*" >> "$LOG"; }
ok()    { echo -e "  ${G}✓${N} $*"; log "OK: $*"; }
warn()  { echo -e "  ${Y}⚠${N} $*"; log "WARN: $*"; }
err()   { echo -e "  ${R}✗${N} $*"; log "ERR: $*"; FAILED=$((FAILED+1)); }
hdr()   { STEP=$((STEP+1)); echo -e "\n${C}${BOLD}[$STEP] $*${N}"; log "STEP $STEP: $*"; }
die()   { echo -e "\n${R}${BOLD}❌ ОШИБКА: $*${N}"; echo "Лог: $LOG"; exit 1; }
ask()   { local p="$1" d="$2" v; if [ -n "$d" ]; then read -p "  $p [$d]: " v; echo "${v:-$d}"; else read -p "  $p: " v; [ -z "$v" ] && die "Пустое значение"; echo "$v"; fi; }

# ─── Определение ОС ─────────────────────────────────────
detect_os() {
    OS_FAMILY="unknown"; OS_NAME="Unknown"
    if [ -f /etc/astra_version ]; then
        OS_FAMILY="astra"
        OS_NAME="Astra Linux $(cat /etc/astra_version 2>/dev/null | head -1)"
    elif [ -f /etc/os-release ]; then
        # shellcheck disable=SC1091
        . /etc/os-release
        OS_NAME="${PRETTY_NAME:-$NAME}"
        case "${ID:-}" in
            ubuntu|debian) OS_FAMILY="debian" ;;
            astra) OS_FAMILY="astra" ;;
            rhel|centos|fedora|almalinux|rocky) OS_FAMILY="rhel" ;;
            *) OS_FAMILY="unknown" ;;
        esac
    fi
}

# ─── Установка пакетов ─────────────────────────────────
pkg_install() {
    case "$OS_FAMILY" in
        debian|astra) DEBIAN_FRONTEND=noninteractive apt-get install -y "$@" 2>&1 | tail -3 ;;
        rhel) dnf install -y "$@" 2>&1 | tail -3 ;;
        *) die "Неизвестный пакетный менеджер. Установите вручную: $*" ;;
    esac
}

# ─── Освобождение порта ────────────────────────────────
free_port() {
    local port=$1
    ss -tlnp 2>/dev/null | grep -q ":${port} " || return 0
    warn "Порт ${port} занят, освобождаю..."
    for svc in nginx apache2 ihttpd httpd lighttpd; do
        systemctl stop $svc 2>/dev/null || true
        systemctl disable $svc 2>/dev/null || true
    done
    sleep 1
    if ss -tlnp 2>/dev/null | grep -q ":${port} "; then
        echo -e "  ${R}Порт ${port} всё ещё занят:${N}"
        ss -tlnp 2>/dev/null | grep ":${port} "
        return 1
    fi
    return 0
}

# ═══════════════════════════════════════════════════════
# СТАРТ
# ═══════════════════════════════════════════════════════
clear
echo -e "${BOLD}══════════════════════════════════════════════════${N}"
echo -e "${BOLD}   ПАО «НБД-Банк» · Установка сервиса УКЭП         ${N}"
echo -e "${BOLD}══════════════════════════════════════════════════${N}"
echo -e "${DIM}Лог: $LOG${N}"

# ─── Шаг 1: Окружение ─────────────────────────────────
hdr "Проверка окружения"
[ "$(id -u)" -eq 0 ] || die "Запустите под root: sudo ./install.sh"
ok "Запуск от root"

detect_os
log "OS: $OS_NAME, family=$OS_FAMILY"
case "$OS_FAMILY" in
    astra)  ok "Astra Linux — сертифицированная ОС" ;;
    debian) warn "$OS_NAME — не Astra, для тестов ок, для банка не подходит" ;;
    rhel)   warn "$OS_NAME — RHEL-совместимая, официально не тестировалась" ;;
    *)      warn "$OS_NAME — неизвестная ОС, продолжаем на свой риск" ;;
esac

[ "$(uname -m)" = "x86_64" ] || die "Требуется x86_64 (КриптоПро не работает на ARM)"
ok "Архитектура: x86_64"

[ -f docker-compose-prod.yml ] && [ -f Dockerfile ] || die "Файлы проекта не найдены. Запустите в папке проекта."
ok "Файлы проекта найдены"

# ─── Шаг 2: Обязательные файлы ────────────────────────
hdr "Проверка обязательных файлов"
[ -f admins.env ]              || die "Нет admins.env"
[ -f cryptopro-dist/linux-amd64_deb.tgz ] || die "Нет cryptopro-dist/linux-amd64_deb.tgz"
ls axiom-dist/*.tar.gz >/dev/null 2>&1 || warn "axiom-dist/*.tar.gz не найден (будет системный JDK)"
[ -d certs ] || mkdir -p certs
ok "Обязательные файлы на месте"

# ─── Шаг 3: Параметры ─────────────────────────────────
hdr "Параметры развертывания"
APP_DOMAIN=$(ask "Домен или IP" "")
APP_BASE_URL="https://${APP_DOMAIN}"
echo "  → BASE_URL: $APP_BASE_URL"

echo ""
echo -e "  ${BOLD}TLS:${N}"
echo "    1) Let's Encrypt (нужен домен + порт 80)"
echo "    2) Свой сертификат (angie-certs/) — авто-генерация, если нет"
TLS_CHOICE=$(ask "Выберите [1]" "1")

if [ "$TLS_CHOICE" = "2" ]; then
    TLS_MODE="custom"
    if [ ! -f angie-certs/fullchain.pem ] || [ ! -f angie-certs/privkey.pem ]; then
        warn "Сертификаты не найдены. Генерирую самоподписанный для ${APP_DOMAIN}..."
        mkdir -p angie-certs
        openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
          -keyout angie-certs/privkey.pem \
          -out angie-certs/fullchain.pem \
          -subj "/CN=${APP_DOMAIN}" 2>/dev/null
        chmod 600 angie-certs/privkey.pem
        chmod 644 angie-certs/fullchain.pem
        ok "Самоподписанный сертификат создан"
    fi
    ok "TLS: custom"
else
    TLS_MODE="letsencrypt"
    ok "TLS: Let's Encrypt"
fi

ADMIN_IP_WHITELIST=$(ask "IP-подсети админов" "127.0.0.1,::1,172.0.0.0/8")
echo "  → $ADMIN_IP_WHITELIST"

MAIL_MODE=$(ask "Режим email (log/smtp) [log]" "log")
if [ "$MAIL_MODE" = "smtp" ]; then
    MAIL_HOST=$(ask "SMTP host" "")
    MAIL_PORT=$(ask "SMTP port" "465")
    MAIL_USERNAME=$(ask "SMTP username" "")
    MAIL_PASSWORD=$(ask "SMTP password" "")
    MAIL_FROM=$(ask "From email" "$MAIL_USERNAME")
    MAIL_SSL=$(ask "SSL (true/false)" "true")
else
    MAIL_HOST=""; MAIL_PORT=""; MAIL_USERNAME=""; MAIL_PASSWORD=""; MAIL_FROM="noreply@bank.ru"; MAIL_SSL="true"
    ok "Email: log (только в логи)"
fi

echo ""
BUILD_MODE=$(ask "Установка образов (1=online, 2=offline)" "1")

# ─── Шаг 4: Секреты ──────────────────────────────────
hdr "Секреты"
mkdir -p secrets && chmod 700 secrets
if [ -f secrets/file.key ] && [ -f secrets/pii.key ] && [ -f secrets/backup.pass ]; then
    warn "Секреты уже существуют — НЕ перезаписываю"
    KEEP_SECRETS=1
else
    KEEP_SECRETS=0
    [ ! -f secrets/file.key ]    && openssl rand -base64 32 > secrets/file.key
    [ ! -f secrets/pii.key ]     && openssl rand -base64 32 > secrets/pii.key
    [ ! -f secrets/backup.pass ] && openssl rand -base64 32 | tr -d '\n' > secrets/backup.pass
    chmod 600 secrets/*.key secrets/backup.pass
    ok "Секреты сгенерированы"
fi

# ─── Шаг 5: .env.prod ────────────────────────────────
hdr "Конфигурация .env.prod"
[ -f .env.prod ] && cp .env.prod ".env.prod.bak.$(date +%s)"

DB_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
DB_MIG_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
DB_AUD_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
DB_SUPER_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)

cat > .env.prod << EOF
APP_DOMAIN=${APP_DOMAIN}
APP_BASE_URL=${APP_BASE_URL}
SECURE_COOKIES=true
HTTP_PORT=80
HTTPS_PORT=443
TLS_MODE=${TLS_MODE}

DB_NAME=afina_db
DB_SUPERUSER=postgres
DB_SUPERUSER_PASSWORD=${DB_SUPER_PASS}
DB_USER=afina_app
DB_PASSWORD=${DB_PASS}
DB_MIGRATOR_USER=afina_migrator
DB_MIGRATOR_PASSWORD=${DB_MIG_PASS}
DB_AUDITOR_USER=afina_auditor
DB_AUDITOR_PASSWORD=${DB_AUD_PASS}

ADMIN_IP_WHITELIST=${ADMIN_IP_WHITELIST}

MAIL_MODE=${MAIL_MODE}
MAIL_HOST=${MAIL_HOST}
MAIL_PORT=${MAIL_PORT}
MAIL_USERNAME=${MAIL_USERNAME}
MAIL_PASSWORD=${MAIL_PASSWORD}
MAIL_FROM=${MAIL_FROM}
MAIL_SSL=${MAIL_SSL}

CRYPTO_PRO_ENABLED=true
EOF
chmod 600 .env.prod
ok ".env.prod создан"

CREDS_FILE="install-credentials-$(date +%Y%m%d_%H%M%S).txt"
cat > "$CREDS_FILE" << EOF
════════════════════════════════════════════════════════
  ПАО "НБД-Банк" · Учетные данные (СОХРАНИТЕ И УДАЛИТЕ!)
  Создано: $(date '+%Y-%m-%d %H:%M:%S')
════════════════════════════════════════════════════════

БД (суперпользователь): ${DB_SUPER_PASS}
БД (приложение):        ${DB_PASS}
БД (миграции):          ${DB_MIG_PASS}
БД (аудит):             ${DB_AUD_PASS}

Ключи: secrets/file.key, secrets/pii.key, secrets/backup.pass
⚠️  УДАЛИТЕ ФАЙЛ: shred -u $CREDS_FILE
════════════════════════════════════════════════════════
EOF
chmod 600 "$CREDS_FILE"
warn "Учетные данные: $CREDS_FILE (сохраните и удалите!)"

# ─── Шаг 6: Docker + Compose ─────────────────────────
hdr "Docker"

if ! command -v docker &>/dev/null; then
    warn "Docker не установлен. Устанавливаю..."
    case "$OS_FAMILY" in
        debian|astra)
            pkg_install docker.io 2>/dev/null || pkg_install docker-ce 2>/dev/null || die "Не удалось установить Docker"
            ;;
        rhel)
            pkg_install docker 2>/dev/null || die "Не удалось установить Docker"
            ;;
        *)
            die "Установите Docker вручную"
            ;;
    esac
fi
command -v docker &>/dev/null || die "Docker не установлен"
ok "Docker: $(docker --version | awk '{print $3}' | tr -d ',')"

# Запуск daemon
if ! docker info &>/dev/null; then
    systemctl enable --now docker 2>/dev/null || systemctl start docker 2>/dev/null || true
    sleep 3
    docker info &>/dev/null || die "Docker daemon не запущен"
fi
ok "Docker daemon работает"

# Compose — определяем и при необходимости ставим
COMPOSE_CMD=""
if docker compose version &>/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose &>/dev/null; then
    COMPOSE_CMD="docker-compose"
else
    warn "Docker Compose не найден. Устанавливаю..."
    case "$OS_FAMILY" in
        debian)
            pkg_install docker-compose-v2 2>/dev/null \
                || pkg_install docker-compose-plugin 2>/dev/null \
                || pkg_install docker-compose 2>/dev/null \
                || true
            ;;
        astra)
            pkg_install docker-compose-plugin 2>/dev/null \
                || pkg_install docker-compose 2>/dev/null \
                || true
            ;;
        rhel)
            pkg_install docker-compose-plugin 2>/dev/null || true
            ;;
    esac
    if docker compose version &>/dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
    elif command -v docker-compose &>/dev/null; then
        COMPOSE_CMD="docker-compose"
    else
        die "Не удалось установить Docker Compose"
    fi
fi
ok "Compose: $COMPOSE_CMD ($($COMPOSE_CMD version --short 2>/dev/null | head -1))"

# ─── Шаг 7: Порты ────────────────────────────────────
hdr "Освобождение портов 80/443"
free_port 80  || die "Не удалось освободить порт 80"
ok "Порт 80 свободен"
free_port 443 || die "Не удалось освободить порт 443"
ok "Порт 443 свободен"

# ─── Шаг 8: Права на папки ───────────────────────────
hdr "Права доступа"
UID_TO_USE=999
chown -R ${UID_TO_USE}:${UID_TO_USE} secrets storage logs backups crls certs angie-certs 2>/dev/null \
    || warn "UID 999 недоступен в системе, использую текущие права"
chmod 700 secrets
chmod 600 secrets/*.key secrets/backup.pass 2>/dev/null || true
chmod 755 storage logs backups crls certs angie-certs 2>/dev/null || true
chmod 644 angie-certs/*.pem 2>/dev/null || true
ok "Права установлены"

# ─── Шаг 9: Сборка/загрузка образов ──────────────────
hdr "Подготовка образов"
if [ "$BUILD_MODE" = "2" ]; then
    [ -d images ] || die "Папка images/ не найдена"
    for img in images/*.tar; do
        [ -f "$img" ] || die "Нет образов в images/"
        echo -e "  ${B}▶ Загрузка $img...${N}"
        docker load < "$img" | tail -1
    done
    ok "Образы загружены"
else
    echo -e "  ${B}▶ Сборка app (5-10 мин)...${N}"
    $COMPOSE_CMD -f docker-compose-prod.yml --env-file .env.prod build --no-cache app 2>&1 | tee -a "$LOG" | tail -3 \
        || die "Сборка app не удалась (лог: $LOG)"

    echo -e "  ${B}▶ Сборка postgres...${N}"
    $COMPOSE_CMD -f docker-compose-prod.yml --env-file .env.prod build postgres 2>&1 | tee -a "$LOG" | tail -3 \
        || die "Сборка postgres не удалась"

    echo -e "  ${B}▶ Загрузка Angie...${N}"
    $COMPOSE_CMD -f docker-compose-prod.yml --env-file .env.prod pull angie 2>&1 | tail -2 \
        || die "Не удалось скачать Angie"
    ok "Образы готовы"
fi

# ─── Шаг 10: Запуск ──────────────────────────────────
hdr "Запуск стека"
$COMPOSE_CMD -f docker-compose-prod.yml --env-file .env.prod up -d 2>&1 | tee -a "$LOG" | tail -5
ok "Стек запущен"

echo -e "  ${DIM}Ожидание готовности (макс 180 с)...${N}"
WAIT=0; HEALTH_OK=0
while [ $WAIT -lt 180 ]; do
    if docker exec afina-app curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        HEALTH_OK=1; break
    fi
    sleep 3; WAIT=$((WAIT+3))
done
[ $HEALTH_OK -eq 1 ] || { docker logs afina-app --tail 50; die "App не поднялось за 180 с"; }
ok "Приложение запущено за ~${WAIT}с"

# ─── Шаг 11: Тесты ───────────────────────────────────
hdr "Проверки"
run_test() {
    local name="$1"; shift
    if eval "$@" &>/dev/null; then
        ok "$name"
    else
        err "$name"
    fi
}

run_test "Health локальный" 'docker exec afina-app curl -sf http://localhost:8080/actuator/health | grep -q UP'

if [ "$TLS_MODE" = "letsencrypt" ]; then
    sleep 10
    run_test "Health внешний ($APP_DOMAIN)" "curl -skf --max-time 15 https://${APP_DOMAIN}/actuator/health | grep -q UP"
else
    sleep 5
    run_test "Health внешний (custom cert)" "curl -skf --max-time 15 https://${APP_DOMAIN}/actuator/health | grep -q UP"
fi

for c in afina-postgres afina-app afina-angie; do
    run_test "Контейнер $c" "[ \"\$(docker inspect -f '{{.State.Status}}' $c 2>/dev/null)\" = 'running' ]"
done

MIG_BAD=$(docker exec afina-postgres psql -U postgres -d afina_db -tAc \
    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=false" 2>/dev/null || echo "FAIL")
if [ "$MIG_BAD" = "0" ]; then
    MIG_TOTAL=$(docker exec afina-postgres psql -U postgres -d afina_db -tAc \
        "SELECT COUNT(*) FROM flyway_schema_history" 2>/dev/null)
    ok "Миграции: $MIG_TOTAL успешны"
else
    err "Миграции: $MIG_BAD провалов"
fi

ROLES=$(docker exec afina-postgres psql -U postgres -d afina_db -tAc \
    "SELECT COUNT(*) FROM pg_roles WHERE rolname IN ('afina_app','afina_migrator','afina_auditor')" 2>/dev/null || echo "0")
[ "$ROLES" = "3" ] && ok "Роли БД: 3/3" || err "Роли БД: $ROLES/3"

docker logs afina-app 2>&1 | grep -q "FileEncryptor: ключ загружен" \
    && ok "FileEncryptor: ключ загружен" || err "FileEncryptor: ключ не загружен"
docker logs afina-app 2>&1 | grep -q "PiiEncryptor: ключ загружен" \
    && ok "PiiEncryptor: ключ загружен" || err "PiiEncryptor: ключ не загружен"

docker logs afina-app 2>&1 | grep -q "JCSP provider registered" \
    && ok "CryptoPro JCSP инициализирован" || warn "JCSP не найден в логах"

# ─── Шаг 12: CLI и Cron ──────────────────────────────
hdr "CLI и Cron"
if [ -f install-afina-cli.sh ]; then
    bash install-afina-cli.sh && ok "CLI: /usr/local/bin/afina"
fi

mkdir -p /etc/cron.d /var/log/afina
cat > /etc/cron.d/afina-backup <<'CRON'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 3 * * * root cd /opt/afinaz && ./backup-prod.sh >> /var/log/afina/backup.log 2>&1
CRON
cat > /etc/cron.d/afina-monitor <<'CRON'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
*/5 * * * * root cd /opt/afinaz && ./monitor.sh >> /var/log/afina/monitor.log 2>&1
CRON
cat > /etc/cron.d/afina-cleanup <<'CRON'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 4,16 * * * root cd /opt/afinaz && ./cleanup-all.sh >> /var/log/afina/cleanup.log 2>&1
CRON
chmod 644 /etc/cron.d/afina-*
ok "Cron: backup, monitor, cleanup"

# ─── Шаг 13: UFW ─────────────────────────────────────
hdr "Firewall"
if command -v ufw &>/dev/null; then
    ufw allow 22/tcp  &>/dev/null || true
    ufw allow 80/tcp  &>/dev/null || true
    ufw allow 443/tcp &>/dev/null || true
    ok "UFW: 22, 80, 443 разрешены"
else
    warn "UFW не установлен"
fi

# ─── Финал ───────────────────────────────────────────
echo ""
echo -e "${BOLD}══════════════════════════════════════════════════${N}"
if [ $FAILED -eq 0 ]; then
    echo -e "${G}${BOLD}   ✅ УСТАНОВКА ЗАВЕРШЕНА УСПЕШНО                 ${N}"
else
    echo -e "${Y}${BOLD}   ⚠ УСТАНОВКА С $FAILED ЗАМЕЧАНИЯМИ             ${N}"
fi
echo -e "${BOLD}══════════════════════════════════════════════════${N}"
echo ""
echo -e "  ${BOLD}Веб:${N}"
echo -e "    Клиент:  ${C}${APP_BASE_URL}/login${N}"
echo -e "    Админ:   ${C}${APP_BASE_URL}/admin/login${N}  (IP: $ADMIN_IP_WHITELIST)"
echo ""
echo -e "  ${BOLD}Учётные данные:${N} ${R}$CREDS_FILE${N}"
echo -e "    ${R}Сохраните в 1Password и удалите: shred -u $CREDS_FILE${N}"
echo ""
echo -e "  ${BOLD}Управление:${N} ${C}afina${N}"
echo -e "  ${BOLD}Лог:${N} $LOG"
echo -e "${BOLD}══════════════════════════════════════════════════${N}"

[ $FAILED -gt 0 ] && exit 1
exit 0