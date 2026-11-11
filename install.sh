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
ADMIN_CN="Дырул Анатолий Юрьевич"
ADMIN_SNILS="18962570627"

# Проверяем: файл отсутствует ИЛИ в нём только комментарии/пустые строки
if [ ! -f admins.env ] || ! grep -qE "^[^#[:space:]].*\|.*" admins.env 2>/dev/null; then
    warn "admins.env отсутствует или содержит только комментарии — пересоздаю"
    cat > admins.env <<ADMEOF
# admins.env — администраторы (CN|SNILS)
${ADMIN_CN}|${ADMIN_SNILS}
ADMEOF
    chmod 644 admins.env
    ok "Создан admins.env с администратором: ${ADMIN_CN}"
elif ! grep -q "|${ADMIN_SNILS}" admins.env; then
    echo "${ADMIN_CN}|${ADMIN_SNILS}" >> admins.env
    chmod 644 admins.env
    ok "Добавлен администратор ${ADMIN_CN} в существующий admins.env"
else
    ok "admins.env уже содержит администратора ${ADMIN_CN}"
fi
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
fi

# Let's Encrypt не выдаёт сертификаты на IP — принудительно переключаемся на custom
if [ "$TLS_MODE" = "letsencrypt" ] && [[ "$APP_DOMAIN" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    warn "Let's Encrypt не поддерживает IP-адреса. Генерирую self-signed."
    TLS_MODE="custom"
fi

if [ "$TLS_MODE" = "custom" ]; then
    if [ ! -f angie-certs/fullchain.pem ] || [ ! -f angie-certs/privkey.pem ]; then
        warn "Сертификаты не найдены. Генерирую self-signed для ${APP_DOMAIN}..."
        mkdir -p angie-certs
        openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
          -keyout angie-certs/privkey.pem \
          -out angie-certs/fullchain.pem \
          -subj "/CN=${APP_DOMAIN}" 2>/dev/null
        chmod 600 angie-certs/privkey.pem
        chmod 644 angie-certs/fullchain.pem
        ok "Самоподписанный сертификат создан"
    fi
    ok "TLS: custom (self-signed)"
else
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
if [ "$BUILD_MODE" = "1" ]; then
    [ -f cryptopro-dist/linux-amd64_deb.tgz ] || die "Для online-сборки нужен cryptopro-dist/linux-amd64_deb.tgz"
    ls axiom-dist/*.tar.gz >/dev/null 2>&1 || warn "axiom-dist/*.tar.gz не найден, будет использован системный JDK"
fi

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

if [ -f .env.prod ] && [ "$KEEP_ENV" != "0" ]; then
    # .env.prod уже есть — берем пароли из него, не перегенерируем
    warn ".env.prod уже существует — пароли БД берутся из него"
    DB_PASS=$(grep '^DB_PASSWORD=' .env.prod | cut -d'=' -f2)
    DB_MIG_PASS=$(grep '^DB_MIGRATOR_PASSWORD=' .env.prod | cut -d'=' -f2)
    DB_AUD_PASS=$(grep '^DB_AUDITOR_PASSWORD=' .env.prod | cut -d'=' -f2)
    DB_SUPER_PASS=$(grep '^DB_SUPERUSER_PASSWORD=' .env.prod | cut -d'=' -f2)
    SKIP_ENV_WRITE=1
else
    DB_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    DB_MIG_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    DB_AUD_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    DB_SUPER_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    SKIP_ENV_WRITE=0
fi

if [ "$SKIP_ENV_WRITE" = "1" ]; then
    ok ".env.prod сохранён без изменений"
else
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
fi

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

# Сначала останавливаем старые контейнеры проекта (если запущены)
if [ -f docker-compose-prod.yml ]; then
    echo -e "  ${DIM}▶ Останавливаем старые контейнеры проекта...${N}"
    $COMPOSE_CMD -f docker-compose-prod.yml --env-file .env.prod down 2>/dev/null || true
    # Также удаляем по имени, если остались "висящие"
    docker rm -f afina-app afina-postgres afina-angie 2>/dev/null || true
    sleep 2
fi

# Теперь освобождаем порты от системных сервисов (nginx, apache)
free_port 80  || die "Не удалось освободить порт 80"
ok "Порт 80 свободен"
free_port 443 || die "Не удалось освободить порт 443"
ok "Порт 443 свободен"

# ─── Шаг 8: Права на папки ───────────────────────────
hdr "Права доступа"

# Гарантированно создаем все нужные папки (они могут отсутствовать в git)
mkdir -p storage/documents storage/visualizations logs backups crls certs angie-certs secrets
echo -e "  ${DIM}▶ Папки созданы: storage logs backups crls certs angie-certs secrets${N}"

UID_TO_USE=999
if id -u 999 >/dev/null 2>&1; then
    chown -R 999:999 storage logs backups crls certs angie-certs secrets
    chmod -R 775 storage logs backups crls certs angie-certs
    chmod 700 secrets
    chmod 600 secrets/*.key secrets/backup.pass 2>/dev/null || true
    ok "Права выданы пользователю 999"
else
    warn "UID 999 не найден, использую root"
    chown -R root:root storage logs backups crls certs angie-certs secrets
    chmod -R 775 storage logs backups crls certs angie-certs
    chmod 700 secrets
    ok "Права выданы root (не рекомендуется)"
fi

# ─── Шаг 9: Сборка/загрузка образов ──────────────────
hdr "Подготовка образов"
if [ "$BUILD_MODE" = "2" ]; then
    [ -d images ] || die "Папка images/ не найдена"

    # Ищем и .tar, и .tar.gz
    ARCHIVES=$(ls images/*.tar images/*.tar.gz 2>/dev/null || true)
    if [ -z "$ARCHIVES" ]; then
        die "Нет образов в images/ (ни .tar, ни .tar.gz)"
    fi

    for img in $ARCHIVES; do
        [ -f "$img" ] || continue
        echo -e "  ${B}▶ Загрузка $img...${N}"
        if [[ "$img" == *.tar.gz ]]; then
            gunzip -c "$img" | docker load | tail -1
        else
            docker load < "$img" | tail -1
        fi
    done

    # Angie ВСЕГДА тянем с registry (Docker save на Mac часто создает битые архивы)
    # Это быстро (~10 сек) и гарантирует правильную архитектуру amd64
    ANGIE_ARCH=$(docker image inspect docker.angie.software/angie:1.12.3-alpine --format '{{.Architecture}}' 2>/dev/null || echo "none")
    if [ "$ANGIE_ARCH" != "amd64" ]; then
        warn "angie архитектура '$ANGIE_ARCH' ≠ amd64 — перекачиваем с registry"
        docker rmi -f docker.angie.software/angie:1.12.3-alpine 2>/dev/null || true
        docker pull --platform linux/amd64 docker.angie.software/angie:1.12.3-alpine 2>&1 | tail -3
    fi
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

# ─── Шаг 9.5: Postfix mynetworks (для локальной почты) ───
hdr "Postfix (локальная доставка почты)"

if command -v postconf &>/dev/null; then
    # Бэкап
    cp /etc/postfix/main.cf /etc/postfix/main.cf.bak.$(date +%s) 2>/dev/null || true

    CURRENT_NET=$(postconf -h mynetworks 2>/dev/null || echo "127.0.0.0/8")
    log "Текущий mynetworks: $CURRENT_NET"

    NEED_UPDATE=0
    NEW_NET="$CURRENT_NET"
    for net in "172.17.0.0/16" "172.18.0.0/16"; do
        if ! echo "$CURRENT_NET" | grep -qw "$net"; then
            NEW_NET="$NEW_NET $net"
            NEED_UPDATE=1
        fi
    done

    if [ "$NEED_UPDATE" = "1" ]; then
        postconf -e "mynetworks = $NEW_NET" 2>/dev/null
        systemctl reload postfix 2>/dev/null || postfix reload 2>/dev/null || true
        sleep 1
        ok "Postfix mynetworks: $NEW_NET"
    else
        ok "Postfix mynetworks уже включает Docker-сети"
    fi

    # Проверка: пускает ли Postfix relay из Docker-сети
    # (проверяем ПОСЛЕ запуска app, см. ниже — на этом шаге app ещё не поднят)
else
    warn "Postfix не установлен на хосте"
    warn "  Настройте SMTP в .env.prod вручную:"
    warn "  локальный Postfix: MAIL_HOST=<домен>, MAIL_PORT=25, без пароля"
    warn "  внешний SMTP: MAIL_PORT=587, MAIL_USERNAME, MAIL_PASSWORD, STARTTLS=true"
fi

# ─── Шаг 10: Запуск ──────────────────────────────────
hdr "Запуск стека (поэтапно)"

# 10.1. Postgres
echo -e "  ${DIM}▶ postgres...${N}"
$COMPOSE_CMD -f docker-compose-prod.yml --env-file .env.prod up -d postgres 2>&1 | tail -2
WAIT=0
while [ $WAIT -lt 90 ]; do
    if docker exec afina-postgres pg_isready -U postgres >/dev/null 2>&1; then break; fi
    sleep 2; WAIT=$((WAIT+2))
done
[ $WAIT -lt 90 ] || die "Postgres не запустился за 90 с"
ok "Postgres готов (~${WAIT}с)"

# 10.2. Создаём роли БД (idempotent)
echo -e "  ${DIM}▶ роли БД...${N}"
DB_MIG_PASS=$(grep "^DB_MIGRATOR_PASSWORD=" .env.prod | cut -d'=' -f2)
DB_APP_PASS=$(grep "^DB_PASSWORD=" .env.prod | cut -d'=' -f2)
DB_AUD_PASS=$(grep "^DB_AUDITOR_PASSWORD=" .env.prod | cut -d'=' -f2)

# afina_migrator
if ! docker exec -i afina-postgres psql -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='afina_migrator'" | grep -q 1; then
    docker exec -i afina-postgres psql -U postgres -d postgres -c "CREATE ROLE afina_migrator WITH LOGIN PASSWORD '$DB_MIG_PASS';" >/dev/null
fi
docker exec -i afina-postgres psql -U postgres -d postgres -c "ALTER USER afina_migrator WITH PASSWORD '$DB_MIG_PASS';" >/dev/null

# afina_app
if ! docker exec -i afina-postgres psql -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='afina_app'" | grep -q 1; then
    docker exec -i afina-postgres psql -U postgres -d postgres -c "CREATE ROLE afina_app WITH LOGIN PASSWORD '$DB_APP_PASS';" >/dev/null
fi
docker exec -i afina-postgres psql -U postgres -d postgres -c "ALTER USER afina_app WITH PASSWORD '$DB_APP_PASS';" >/dev/null

# afina_auditor
if ! docker exec -i afina-postgres psql -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='afina_auditor'" | grep -q 1; then
    docker exec -i afina-postgres psql -U postgres -d postgres -c "CREATE ROLE afina_auditor WITH LOGIN PASSWORD '$DB_AUD_PASS';" >/dev/null
fi
docker exec -i afina-postgres psql -U postgres -d postgres -c "ALTER USER afina_auditor WITH PASSWORD '$DB_AUD_PASS';" >/dev/null

# Гранты
docker exec -i afina-postgres psql -U postgres -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE afina_db TO afina_migrator; GRANT CONNECT ON DATABASE afina_db TO afina_app, afina_auditor;" >/dev/null

docker exec -i afina-postgres psql -U postgres -d afina_db >/dev/null 2>&1 <<EOF
ALTER SCHEMA public OWNER TO afina_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO afina_migrator;
GRANT USAGE ON SCHEMA public TO afina_app, afina_auditor;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
  GRANT SELECT ON TABLES TO afina_auditor;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
  GRANT SELECT ON SEQUENCES TO afina_auditor;
EOF
ok "Роли БД созданы/проверены"

# Сброс старых smtp.* если они указывают на нерабочие значения
# (не трогаем, если host непустой и не localhost — это может быть реальный внешний SMTP)
docker exec -i afina-postgres psql -U postgres -d afina_db -tAc \
  "SELECT value FROM app_settings WHERE key='smtp.host'" 2>/dev/null | grep -qE '^(localhost|127\.0\.0\.1|)$' && \
  docker exec -i afina-postgres psql -U postgres -d afina_db -c \
  "UPDATE app_settings SET value='' WHERE key IN ('smtp.host','smtp.port','smtp.username','smtp.password','smtp.from','smtp.ssl')" >/dev/null 2>&1 || true

# 10.3. App
echo -e "  ${DIM}▶ app...${N}"
$COMPOSE_CMD -f docker-compose-prod.yml --env-file .env.prod up -d app 2>&1 | tail -2
WAIT=0; HEALTH_OK=0
while [ $WAIT -lt 180 ]; do
    if docker exec afina-app curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        HEALTH_OK=1; break
    fi
    sleep 3; WAIT=$((WAIT+3))
done
if [ $HEALTH_OK -ne 1 ]; then
    docker logs afina-app --tail 50
    die "App не поднялось за 180 с"
fi
ok "App готов (~${WAIT}с)"

# 10.4. Angie (после app, чтобы DNS резолвился)
echo -e "  ${DIM}▶ angie...${N}"
$COMPOSE_CMD -f docker-compose-prod.yml --env-file .env.prod up -d angie 2>&1 | tail -2
sleep 5
ok "Angie запущен"

# 10.5. Проверка SMTP-relay из контейнера app
echo -e "  ${DIM}▶ SMTP relay...${N}"
if docker exec afina-app sh -c 'command -v python3 >/dev/null 2>&1' 2>/dev/null; then
    RELAY_RESULT=$(docker exec afina-app python3 -c '
import smtplib, os
try:
    s = smtplib.SMTP(os.environ.get("MAIL_HOST","localhost"),
                     int(os.environ.get("MAIL_PORT","25")), timeout=5)
    s.ehlo(); s.quit()
    print("OK")
except Exception as e:
    print("FAIL:" + str(e))
' 2>/dev/null || echo "FAIL:no-python")

    case "$RELAY_RESULT" in
        OK) ok "SMTP relay: подключение к Postfix работает" ;;
        FAIL:no-python) warn "SMTP: python3 нет в контейнере — пропускаю проверку" ;;
        *) warn "SMTP relay: $RELAY_RESULT" ;;
    esac
else
    warn "SMTP: пропускаю проверку (нет python3 в контейнере)"
fi


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
if [ -f afina.sh ]; then
    chmod +x afina.sh
    ln -sf /opt/afinaz/afina.sh /usr/local/bin/afina
    ok "CLI: /usr/local/bin/afina → /opt/afinaz/afina.sh"
fi

mkdir -p /etc/cron.d /var/log/afina
cat > /etc/cron.d/afina-backup <<'CRON'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 3 * * * root cd /opt/afinaz && ./backup-prod.sh >> /var/log/afina/backup.log 2>&1
CRON
# monitor.sh должен быть исполняемым для cron (./monitor.sh)
chmod +x /opt/afinaz/monitor.sh 2>/dev/null || true
mkdir -p /var/log/afina
touch /var/log/afina/monitor-cron.log
chmod 644 /var/log/afina/monitor-cron.log

cat > /etc/cron.d/afina-monitor <<'CRON'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
*/5 * * * * root cd /opt/afinaz && ./monitor.sh >> /var/log/afina/monitor-cron.log 2>&1
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