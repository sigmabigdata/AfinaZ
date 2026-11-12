#!/usr/bin/env bash
# ============================================================
# AfinaZ · Bootstrap для Setup Mode
# ============================================================
# Первая фаза установки: подготавливает систему, запускает
# приложение в Setup Mode, печатает URL с токеном для wizard'а.
#
# После этого пользователь открывает в браузере:
#   https://<domain>/setup?token=<uuid>
# и завершает настройку через 6-шаговый wizard.
#
# Wizard в конце:
#   • пишет setup-out/.env.prod.new (финальные настройки)
#   • пишет setup-out/setup.complete (маркер)
#   • System.exit(0)
# → entrypoint-wrapper.sh подхватывает, применяет .env.prod.new
# → docker restart → обычный режим
#
# Использование:
#   sudo ./bootstrap.sh                              (интерактивно)
#   sudo ./bootstrap.sh --domain=afina.example.ru    (быстро, TLS self-signed)
#   sudo ./bootstrap.sh --domain=afina.example.ru \
#        --non-interactive --accept-non-astra
#   sudo ./bootstrap.sh --reset                       (сбросить wizard)
#
# Совместимость ОС:
#   Astra Linux SE 1.8  ✅ рекомендуется
#   Ubuntu 22.04/24.04  ✅ dev/staging
#   Debian 11/12        ✅ dev/staging
#   RHEL 8/9, Alma 8/9, Rocky 8/9  ✅ dev/staging
#   Debian 10           ⚠ EOL, с --accept-non-astra
# ============================================================
set -euo pipefail

# ─── Цвета ───
if [ -t 1 ] && [ "${NO_COLOR:-0}" = "0" ]; then
    G='\033[0;32m'; Y='\033[0;33m'; R='\033[0;31m'; B='\033[0;34m'
    C='\033[0;36m'; N='\033[0m'; BOLD='\033[1m'; DIM='\033[2m'
else
    G=''; Y=''; R=''; B=''; C=''; N=''; BOLD=''; DIM=''
fi

# ─── Константы ───
APP_DIR="/opt/afinaz"
COMPOSE_FILE="docker-compose-prod.yml"
ENV_FILE=".env.prod"
STAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="/tmp/afina-bootstrap-${STAMP}.log"

# ─── Args (default) ───
ARG_DOMAIN=""
ARG_TLS="self-signed"       # self-signed | custom
ARG_NON_INTERACTIVE=0
ARG_ACCEPT_NON_ASTRA=0
ARG_RESET=0
ARG_ADMIN_CN=""
ARG_ADMIN_SNILS=""
ARG_IMAGES_MODE="online"    # online | offline
ARG_DRY_RUN=0

# ─── Помощники ───
log()    { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG_FILE"; }
info()   { echo -e "  ${DIM}${C}•${N} $*" | tee -a "$LOG_FILE"; }
ok()     { echo -e "  ${G}✓${N} $*" | tee -a "$LOG_FILE"; }
warn()   { echo -e "  ${Y}⚠${N} $*" | tee -a "$LOG_FILE"; }
err()    { echo -e "  ${R}✗${N} $*" | tee -a "$LOG_FILE"; }
die()    { echo -e "\n${R}${BOLD}❌ $*${N}\nЛог: $LOG_FILE" | tee -a "$LOG_FILE"; exit 1; }
hdr()    { echo "" | tee -a "$LOG_FILE"; echo -e "${B}${BOLD}▶ $*${N}" | tee -a "$LOG_FILE"; }
ask()    {
    local prompt="$1" default="${2:-}" value
    if [ "$ARG_NON_INTERACTIVE" = "1" ]; then
        echo "$default"
        return
    fi
    if [ -n "$default" ]; then
        read -r -p "  $prompt [$default]: " value
        echo "${value:-$default}"
    else
        while [ -z "${value:-}" ]; do
            read -r -p "  $prompt: " value
        done
        echo "$value"
    fi
}

# ─── Parse args ───
parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --domain=*)       ARG_DOMAIN="${1#*=}" ;;
            --domain)         ARG_DOMAIN="${2:-}"; shift ;;
            --tls=*)          ARG_TLS="${1#*=}" ;;
            --tls)            ARG_TLS="${2:-}"; shift ;;
            --non-interactive) ARG_NON_INTERACTIVE=1 ;;
            --accept-non-astra) ARG_ACCEPT_NON_ASTRA=1 ;;
            --reset)          ARG_RESET=1 ;;
            --admin-cn=*)     ARG_ADMIN_CN="${1#*=}" ;;
            --admin-snils=*)  ARG_ADMIN_SNILS="${1#*=}" ;;
            --images=*)       ARG_IMAGES_MODE="${1#*=}" ;;
            --images)         ARG_IMAGES_MODE="${2:-}"; shift ;;
            --dry-run)        ARG_DRY_RUN=1 ;;
            -h|--help)
                cat <<HELP
Использование: sudo ./bootstrap.sh [опции]

Опции:
  --domain=DOMAIN          Домен или IP (обязателен для --non-interactive)
  --tls=self-signed|custom Режим TLS (default: self-signed)
                           self-signed — генерится автоматически
                           custom      — использует angie-certs/*.pem (если есть)
  --admin-cn="CN"          CN администратора (если не задан — wizard спросит)
  --admin-snils=N          SNILS администратора
  --images=online|offline  Режим образов (default: online)
                           online  — docker pull/build из реестров
                           offline — docker load из images/*.tar.gz
  --non-interactive        Не задавать вопросов (все из флагов)
  --accept-non-astra       Продолжить на не-Astra ОС
  --reset                  Сбросить wizard (не удаляет данные)
  --dry-run                Только preflight + план, ничего не пишет
                           Безопасно для тестов на проде
  -h, --help               Эта справка

Примеры:
  sudo ./bootstrap.sh
  sudo ./bootstrap.sh --domain=afina.example.ru
  sudo ./bootstrap.sh --domain=afina.example.ru --tls=self-signed \\
       --admin-cn="Иванов Иван Иванович" --admin-snils=12345678901 \\
       --non-interactive --accept-non-astra
HELP
                exit 0
                ;;
            *)
                die "Неизвестный флаг: $1 (см. --help)"
                ;;
        esac
        shift
    done

    # Валидация
    if [ "$ARG_NON_INTERACTIVE" = "1" ] && [ -z "$ARG_DOMAIN" ]; then
        die "--non-interactive требует --domain=DOMAIN"
    fi
    case "$ARG_TLS" in
        self-signed|custom) ;;
        *) die "--tls должен быть self-signed или custom (получено: $ARG_TLS)" ;;
    esac
    case "$ARG_IMAGES_MODE" in
        online|offline) ;;
        *) die "--images должен быть online или offline (получено: $ARG_IMAGES_MODE)" ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# [1/8] Pre-flight проверки
# ═══════════════════════════════════════════════════════════
preflight() {
    hdr "[1/8] Pre-flight"

    # root
    [ "$(id -u)" = "0" ] || die "Запустите под root: sudo ./bootstrap.sh"
    ok "root"

    # x86_64
    [ "$(uname -m)" = "x86_64" ] || die "Требуется x86_64 (КриптоПро не работает на ARM)"
    ok "x86_64"

    # Директория проекта
    cd "$APP_DIR" 2>/dev/null || die "$APP_DIR не найден. Клонируйте репозиторий."
    [ -f "$COMPOSE_FILE" ] || die "$COMPOSE_FILE не найден в $APP_DIR"
    [ -f Dockerfile ]      || die "Dockerfile не найден в $APP_DIR"
    [ -f entrypoint-wrapper.sh ] || die "entrypoint-wrapper.sh не найден (этап 0 не применён)"
    ok "файлы проекта"

    # Диск ≥ 20 GB
    local free_gb
    free_gb=$(df -BG / | awk 'NR==2 {gsub("G",""); print $4}')
    [ "$free_gb" -ge 20 ] || die "Мало места на диске: ${free_gb} GB (нужно ≥20 GB)"
    ok "диск: ${free_gb} GB свободно"

    # RAM ≥ 4 GB
    local mem_gb
    mem_gb=$(free -g | awk '/Mem:/ {print $2}')
    [ "$mem_gb" -ge 4 ] || warn "RAM: ${mem_gb} GB (рекомендуется ≥4 GB)"
    [ "$mem_gb" -ge 4 ] && ok "RAM: ${mem_gb} GB"

    # Порты 80/443 (если не наши контейнеры)
    for port in 80 443; do
        if ss -tlnp 2>/dev/null | grep -q ":${port} " ; then
            # Проверим, не наши ли это контейнеры
            if docker ps --format '{{.Names}}' 2>/dev/null | grep -q afina-angie; then
                ok "порт $port занят нашим afina-angie (OK)"
            else
                warn "порт $port занят:"
                ss -tlnp 2>/dev/null | grep ":${port} " | tee -a "$LOG_FILE"
                if [ "$ARG_NON_INTERACTIVE" = "0" ]; then
                    local ans
                    read -r -p "    Освободить порт $port? [y/N]: " ans
                    [ "$ans" = "y" ] || die "Порт $port занят"
                    # Стопим nginx/apache
                    for svc in nginx apache2 httpd lighttpd; do
                        systemctl stop "$svc" 2>/dev/null || true
                        systemctl disable "$svc" 2>/dev/null || true
                    done
                    sleep 1
                    ss -tlnp 2>/dev/null | grep -q ":${port} " && die "Не удалось освободить порт $port"
                    ok "порт $port освобождён"
                else
                    die "Порт $port занят — освободите вручную"
                fi
            fi
        else
            ok "порт $port свободен"
        fi
    done

    # Если reset — предупредить
    if [ "$ARG_RESET" = "1" ]; then
        warn "═══ RЕЖИМ --reset ═══"
        warn "Будет удалено: .env.prod, setup-out/*, secrets/initialized"
        warn "НЕ будет удалено: storage/, backups/, secrets/keys/, certs/, crls/, admins.env"
        if [ "$ARG_NON_INTERACTIVE" = "0" ]; then
            local ans
            read -r -p "  Продолжить reset? [y/N]: " ans
            [ "$ans" = "y" ] || die "Отменено"
        fi
    fi
}

# ═══════════════════════════════════════════════════════════
# [2/8] Определение ОС
# ═══════════════════════════════════════════════════════════
OS_FAMILY=""
OS_NAME=""
detect_os() {
    hdr "[2/8] Определение ОС"

    if [ -f /etc/astra_version ]; then
        OS_FAMILY="astra"
        OS_NAME="Astra Linux SE $(cat /etc/astra_version 2>/dev/null | head -1)"
        ok "$OS_NAME (сертифицированная ОС)"
        return
    fi

    if [ -f /etc/os-release ]; then
        # shellcheck disable=SC1091
        . /etc/os-release
        OS_NAME="${PRETTY_NAME:-$NAME}"

        case "${ID:-}" in
            ubuntu|debian)
                OS_FAMILY="debian"
                # Проверим EOL для Debian 10
                if [ "${ID}" = "debian" ] && [ "${VERSION_ID:-0}" -lt 11 ]; then
                    warn "$OS_NAME — EOL, security-обновлений нет"
                    if [ "$ARG_ACCEPT_NON_ASTRA" != "1" ]; then
                        if [ "$ARG_NON_INTERACTIVE" = "1" ]; then
                            die "Debian 10 EOL — нужен --accept-non-astra для продолжения"
                        fi
                        local ans
                        read -r -p "  Продолжить на EOL-ОС? [y/N]: " ans
                        [ "$ans" = "y" ] || die "Отменено"
                    fi
                fi
                ;;
            astra)
                OS_FAMILY="astra"
                ;;
            rhel|centos|fedora|almalinux|rocky)
                OS_FAMILY="rhel"
                ;;
            *)
                warn "Неизвестная ОС: ${ID:-unknown}"
                [ "$ARG_ACCEPT_NON_ASTRA" = "1" ] || die "Нужен --accept-non-astra"
                OS_FAMILY="unknown"
                ;;
        esac
    else
        die "Не удалось определить ОС"
    fi

    # Не Astra — предупреждение
    if [ "$OS_FAMILY" != "astra" ]; then
        warn "$OS_NAME — не Astra. Для банка (152-ФЗ, ГОСТ) нужна Astra Linux SE 1.8."
        if [ "$ARG_ACCEPT_NON_ASTRA" != "1" ]; then
            if [ "$ARG_NON_INTERACTIVE" = "0" ]; then
                local ans
                read -r -p "  Продолжить? [y/N]: " ans
                [ "$ans" = "y" ] || die "Отменено"
            else
                die "Нужен --accept-non-astra для продолжения на не-Astra"
            fi
        fi
    fi

    ok "ОС: $OS_NAME (family: $OS_FAMILY)"
}

# ═══════════════════════════════════════════════════════════
# [3/8] Docker
# ═══════════════════════════════════════════════════════════
pkg_install() {
    case "$OS_FAMILY" in
        astra|debian)
            DEBIAN_FRONTEND=noninteractive apt-get update -qq 2>&1 | tail -2
            DEBIAN_FRONTEND=noninteractive apt-get install -y "$@" 2>&1 | tail -3
            ;;
        rhel)
            dnf install -y "$@" 2>&1 | tail -3
            ;;
        *)
            die "Неизвестный пакетный менеджер (OS_FAMILY=$OS_FAMILY)"
            ;;
    esac
}

install_docker() {
    hdr "[3/8] Docker"

    if command -v docker >/dev/null 2>&1; then
        ok "Docker установлен: $(docker --version | awk '{print $3}' | tr -d ',')"
    else
        warn "Docker не установлен. Устанавливаю..."
        case "$OS_FAMILY" in
            astra|debian)
                pkg_install docker.io || die "Не удалось установить docker.io"
                ;;
            rhel)
                pkg_install docker || die "Не удалось установить docker"
                ;;
            *)
                die "Установите Docker вручную"
                ;;
        esac
        ok "Docker установлен: $(docker --version)"
    fi

    # Docker daemon
    if ! docker info >/dev/null 2>&1; then
        warn "Docker daemon не запущен, запускаю..."
        systemctl enable --now docker 2>/dev/null || systemctl start docker 2>/dev/null || true
        sleep 3
        docker info >/dev/null 2>&1 || die "Docker daemon не запущен"
    fi
    ok "Docker daemon работает"

    # Compose
    if docker compose version >/dev/null 2>&1; then
        ok "docker compose: $(docker compose version --short 2>/dev/null | head -1)"
    else
        warn "docker compose не найден. Устанавливаю..."
        case "$OS_FAMILY" in
            astra|debian)
                pkg_install docker-compose-plugin 2>/dev/null \
                    || pkg_install docker-compose-v2 2>/dev/null \
                    || pkg_install docker-compose 2>/dev/null \
                    || die "Не удалось установить docker compose"
                ;;
            rhel)
                pkg_install docker-compose-plugin || die "Не удалось установить docker compose plugin"
                ;;
            *)
                die "Установите docker compose вручную"
                ;;
        esac
        docker compose version >/dev/null 2>&1 || die "docker compose не установился"
        ok "docker compose: $(docker compose version --short | head -1)"
    fi
}

# ═══════════════════════════════════════════════════════════
# [4/8] Секреты
# ═══════════════════════════════════════════════════════════
generate_secrets() {
    hdr "[4/8] Секреты"

    if [ "$ARG_DRY_RUN" = "1" ]; then
        ok "[dry-run] пропускаю генерацию секретов"
        return 0
    fi

    mkdir -p secrets
    chmod 700 secrets
    mkdir -p secrets/keys

    # file.key
    if [ -f secrets/keys/file.key.current ]; then
        ok "file.key уже существует (версий: $(ls secrets/keys/file.key.v* 2>/dev/null | wc -l))"
    else
        openssl rand -base64 32 | tr -d '\n' > secrets/keys/file.key.v1
        chmod 600 secrets/keys/file.key.v1
        echo -n "1" > secrets/keys/file.key.current
        ok "file.key.v1 создан (32 байта)"
    fi

    # pii.key
    if [ -f secrets/keys/pii.key.current ]; then
        ok "pii.key уже существует (версий: $(ls secrets/keys/pii.key.v* 2>/dev/null | wc -l))"
    else
        openssl rand -base64 32 | tr -d '\n' > secrets/keys/pii.key.v1
        chmod 600 secrets/keys/pii.key.v1
        echo -n "1" > secrets/keys/pii.key.current
        ok "pii.key.v1 создан (32 байта)"
    fi

    # backup.pass
    if [ -f secrets/backup.pass ]; then
        ok "backup.pass уже существует"
    else
        openssl rand -base64 32 | tr -d '\n' > secrets/backup.pass
        chmod 600 secrets/backup.pass
        ok "backup.pass создан"
    fi
}

# ═══════════════════════════════════════════════════════════
# [5/8] .env.prod
# ═══════════════════════════════════════════════════════════
generate_env_prod() {
    hdr "[5/8] .env.prod"

    # DRY-RUN: ничего не пишем, только показываем план
    if [ "$ARG_DRY_RUN" = "1" ]; then
        ok "[dry-run] .env.prod будет создан:"
        echo -e "    APP_DOMAIN=${ARG_DOMAIN}"
        echo -e "    APP_SETUP_MODE=true"
        echo -e "    APP_SETUP_TOKEN=<32 hex chars>"
        echo -e "    DB пароли: <сгенерированы или переиспользованы>"
        echo -e "    Backup существующего: ${ENV_FILE}.bak.<ts> (если был)"
        return 0
    fi

    # Backup старого
    if [ -f "$ENV_FILE" ]; then
        cp "$ENV_FILE" "${ENV_FILE}.bak.${STAMP}"
        warn "Старый $ENV_FILE → ${ENV_FILE}.bak.${STAMP}"
    fi

    # Пароли БД — переиспользуем из старого, если есть
    local DB_PASS="" DB_MIG_PASS="" DB_AUD_PASS="" DB_SUPER_PASS=""
    if [ -f "${ENV_FILE}.bak.${STAMP}" ]; then
        DB_PASS=$(grep '^DB_PASSWORD=' "${ENV_FILE}.bak.${STAMP}" 2>/dev/null | cut -d'=' -f2- || true)
        DB_MIG_PASS=$(grep '^DB_MIGRATOR_PASSWORD=' "${ENV_FILE}.bak.${STAMP}" 2>/dev/null | cut -d'=' -f2- || true)
        DB_AUD_PASS=$(grep '^DB_AUDITOR_PASSWORD=' "${ENV_FILE}.bak.${STAMP}" 2>/dev/null | cut -d'=' -f2- || true)
        DB_SUPER_PASS=$(grep '^DB_SUPERUSER_PASSWORD=' "${ENV_FILE}.bak.${STAMP}" 2>/dev/null | cut -d'=' -f2- || true)
    fi

    [ -z "$DB_PASS" ] && DB_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    [ -z "$DB_MIG_PASS" ] && DB_MIG_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    [ -z "$DB_AUD_PASS" ] && DB_AUD_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    [ -z "$DB_SUPER_PASS" ] && DB_SUPER_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)

    # Токен setup
    SETUP_TOKEN=$(openssl rand -hex 32)

    cat > "$ENV_FILE" <<ENVEOF
# ============================================================
# AfinaZ · .env.prod (bootstrap $STAMP)
# ============================================================
# Этот файл создан bootstrap.sh в режиме Setup Mode.
# После завершения wizard он будет перезаписан из
# setup-out/.env.prod.new (entrypoint-wrapper.sh).

APP_DOMAIN=${ARG_DOMAIN}
APP_BASE_URL=https://${ARG_DOMAIN}
SECURE_COOKIES=true
HTTP_PORT=80
HTTPS_PORT=443
TLS_MODE=${ARG_TLS}

DB_NAME=afina_db
DB_SUPERUSER=postgres
DB_SUPERUSER_PASSWORD=${DB_SUPER_PASS}
DB_USER=afina_app
DB_PASSWORD=${DB_PASS}
DB_MIGRATOR_USER=afina_migrator
DB_MIGRATOR_PASSWORD=${DB_MIG_PASS}
DB_AUDITOR_USER=afina_auditor
DB_AUDITOR_PASSWORD=${DB_AUD_PASS}

# Setup Mode — выключится после завершения wizard
APP_SETUP_MODE=true
APP_SETUP_TOKEN=${SETUP_TOKEN}

# SMTP / admin / IP-whitelist задаются через wizard
ADMIN_IP_WHITELIST=127.0.0.1,::1,172.0.0.0/8
MAIL_MODE=log
# НЕ задаём пустые MAIL_HOST/PORT — это вызывает NumberFormatException
# в MonitoringService (@Value int не терпит пустую строку).
# Значения по умолчанию: MAIL_HOST=localhost, MAIL_PORT=25.
MAIL_FROM=noreply@${ARG_DOMAIN}

CRYPTO_PRO_ENABLED=true
ENVEOF
    chmod 600 "$ENV_FILE"
    ok ".env.prod создан (600)"
    warn "Setup Mode URL: https://${ARG_DOMAIN}/setup?token=${SETUP_TOKEN}"

    # Credentials в отдельный файл
    local creds_file="bootstrap-credentials-${STAMP}.txt"
    cat > "$creds_file" <<CREDSEOF
════════════════════════════════════════════════════════
  AfinaZ · Bootstrap credentials
  Создано: $(date '+%Y-%m-%d %H:%M:%S')
════════════════════════════════════════════════════════
⚠ СОХРАНИТЕ В 1PASSWORD И УДАЛИТЕ ЭТОТ ФАЙЛ
   shred -u ${creds_file}

Setup Mode:
  URL:    https://${ARG_DOMAIN}/setup?token=${SETUP_TOKEN}
  Token:  ${SETUP_TOKEN}

БД (суперпользователь): ${DB_SUPER_PASS}
БД (приложение):        ${DB_PASS}
БД (миграции):          ${DB_MIG_PASS}
БД (аудит):             ${DB_AUD_PASS}

Ключи: secrets/keys/*.key.v*, secrets/backup.pass
════════════════════════════════════════════════════════
CREDSEOF
    chmod 600 "$creds_file"
    warn "Credentials: $creds_file"
}

# ═══════════════════════════════════════════════════════════
# [6/8] TLS
# ═══════════════════════════════════════════════════════════
setup_tls() {
    hdr "[6/8] TLS"

    # DRY-RUN: ничего не пишем
    if [ "$ARG_DRY_RUN" = "1" ]; then
        ok "[dry-run] TLS режим: $ARG_TLS"
        case "$ARG_TLS" in
            self-signed) ok "[dry-run] будет сгенерирован self-signed cert (365 дней)" ;;
            custom)      ok "[dry-run] используются существующие angie-certs/*.pem" ;;
        esac
        return 0
    fi

    mkdir -p angie-certs

    case "$ARG_TLS" in
        self-signed)
            if [ -f angie-certs/fullchain.pem ] && [ -f angie-certs/privkey.pem ]; then
                ok "TLS: self-signed (уже есть)"
                return 0
            fi
            info "Генерирую self-signed для ${ARG_DOMAIN}"
            openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
                -keyout angie-certs/privkey.pem \
                -out angie-certs/fullchain.pem \
                -subj "/CN=${ARG_DOMAIN}" \
                -addext "subjectAltName=DNS:${ARG_DOMAIN}" 2>/dev/null \
                || openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
                    -keyout angie-certs/privkey.pem \
                    -out angie-certs/fullchain.pem \
                    -subj "/CN=${ARG_DOMAIN}" 2>/dev/null
            [ -f angie-certs/fullchain.pem ] || die "Не удалось создать self-signed cert"
            chmod 600 angie-certs/privkey.pem
            chmod 644 angie-certs/fullchain.pem
            ok "TLS: self-signed создан"
            ;;
        custom)
            if [ -f angie-certs/fullchain.pem ] && [ -f angie-certs/privkey.pem ]; then
                ok "TLS: custom (файлы на месте)"
            else
                die "TLS: custom — положите fullchain.pem + privkey.pem в angie-certs/ и повторите"
            fi
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# [7/8] Запуск стека
# ═══════════════════════════════════════════════════════════
dc() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }

wait_health() {
    local timeout="${1:-180}"
    local elapsed=0
    echo -n "  Ждём health (timeout ${timeout}s): "
    while [ $elapsed -lt $timeout ]; do
        local code
        code=$(curl -sk -o /dev/null -w "%{http_code}" https://localhost/actuator/health 2>/dev/null || echo "000")
        if [ "$code" = "200" ]; then
            echo ""
            ok "Health: 200 OK (за ${elapsed}s)"
            return 0
        fi
        echo -n "."
        sleep 3
        elapsed=$((elapsed + 3))
    done
    echo ""
    err "Health не поднялся за ${timeout}s"
    echo -e "  ${DIM}Последние логи app:${N}"
    docker logs afina-app --tail 20 2>&1 | tail -20
    return 1
}

start_stack() {
    hdr "[7/8] Запуск стека"

    if [ "$ARG_DRY_RUN" = "1" ]; then
        ok "[dry-run] план: postgres → роли БД → app → angie"
        return 0
    fi

    # 7.1. Postgres
    info "Postgres..."
    dc up -d postgres 2>&1 | tail -2
    local elapsed=0
    while [ $elapsed -lt 90 ]; do
        docker exec afina-postgres pg_isready -U postgres >/dev/null 2>&1 && break
        sleep 2
        elapsed=$((elapsed + 2))
    done
    [ $elapsed -lt 90 ] || die "Postgres не запустился за 90s"
    ok "Postgres готов (~${elapsed}s)"

    # 7.2. Роли БД
    info "Роли БД..."
    local DB_NAME DB_MIG_PASS DB_APP_PASS DB_AUD_PASS
    DB_NAME=$(grep '^DB_NAME=' .env.prod | cut -d'=' -f2-)
    DB_MIG_PASS=$(grep '^DB_MIGRATOR_PASSWORD=' .env.prod | cut -d'=' -f2-)
    DB_APP_PASS=$(grep '^DB_PASSWORD=' .env.prod | cut -d'=' -f2-)
    DB_AUD_PASS=$(grep '^DB_AUDITOR_PASSWORD=' .env.prod | cut -d'=' -f2-)

    local role_pair role pass
    for role_pair in "afina_migrator:${DB_MIG_PASS}" "afina_app:${DB_APP_PASS}" "afina_auditor:${DB_AUD_PASS}"; do
        role="${role_pair%%:*}"
        pass="${role_pair#*:}"
        if ! docker exec -i afina-postgres psql -U postgres -d postgres -tAc \
                "SELECT 1 FROM pg_roles WHERE rolname='${role}'" | grep -q 1; then
            docker exec -i afina-postgres psql -U postgres -d postgres \
                -c "CREATE ROLE ${role} WITH LOGIN PASSWORD '${pass}';" >/dev/null
        fi
        docker exec -i afina-postgres psql -U postgres -d postgres \
            -c "ALTER USER ${role} WITH PASSWORD '${pass}';" >/dev/null
    done

    docker exec -i afina-postgres psql -U postgres -d postgres \
        -c "GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO afina_migrator; GRANT CONNECT ON DATABASE ${DB_NAME} TO afina_app, afina_auditor;" >/dev/null
    docker exec -i afina-postgres psql -U postgres -d "${DB_NAME}" >/dev/null 2>&1 <<GRANTEOF || true
ALTER SCHEMA public OWNER TO afina_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO afina_migrator;
GRANT USAGE ON SCHEMA public TO afina_app, afina_auditor;
GRANTEOF
    ok "Роли БД готовы"

    # 7.3. App
    info "App..."
    dc up -d app 2>&1 | tail -2
    wait_health 180 || die "App не поднялось за 180s"

    # 7.4. Angie
    info "Angie..."
    dc up -d angie 2>&1 | tail -2
    sleep 5
    ok "Angie запущен"

    # 7.5. Health через 443
    info "Health через Angie (443)..."
    wait_health 60 || warn "Health через 443 не поднялся — проверьте afina-angie"
}

# ═══════════════════════════════════════════════════════════
# [8/8] Итог
# ═══════════════════════════════════════════════════════════
print_summary() {
    hdr "[8/8] Готово"

    local creds_file
    creds_file=$(ls -t bootstrap-credentials-*.txt 2>/dev/null | head -1 || true)

    echo ""
    echo -e "${G}${BOLD}════════════════════════════════════════════════════════${N}"
    if [ "$ARG_DRY_RUN" = "1" ]; then
        echo -e "${G}${BOLD}  ✅ DRY-RUN: ПРОВЕРКИ ПРОЙДЕНЫ${N}"
    else
        echo -e "${G}${BOLD}  ✅ BOOTSTRAP ЗАВЕРШЁН${N}"
    fi
    echo -e "${G}${BOLD}════════════════════════════════════════════════════════${N}"
    echo ""

    if [ "$ARG_DRY_RUN" = "1" ]; then
        echo -e "${BOLD}Режим: --dry-run (ничего не изменено)${N}"
        echo ""
        echo "Для реального запуска уберите --dry-run:"
        echo -e "  ${C}sudo ./bootstrap.sh --domain=${ARG_DOMAIN} --accept-non-astra${N}"
        echo ""
        return 0
    fi

    echo -e "${BOLD}Следующий шаг — откройте в браузере:${N}"
    echo ""
    echo -e "  ${C}https://${ARG_DOMAIN}/setup?token=${SETUP_TOKEN}${N}"
    echo ""
    echo -e "${BOLD}Wizard 6 шагов:${N}"
    echo "  1. Домен + TLS (self-signed / custom)"
    echo "  2. Email (log / local postfix / external SMTP)"
    echo "  3. Администратор (CN + SNILS)"
    echo "  4. IP-whitelist для /admin"
    echo "  5. Pre-flight проверки"
    echo "  6. Подтверждение → рестарт в обычный режим"
    echo ""

    if [ -n "$creds_file" ] && [ -f "$creds_file" ]; then
        echo -e "${Y}${BOLD}⚠ Сохраните credentials в 1Password:${N}"
        echo -e "  ${creds_file}"
        echo -e "  ${DIM}После сохранения: shred -u ${creds_file}${N}"
        echo ""
    fi

    echo -e "${BOLD}Логи:${N}"
    echo -e "  bootstrap: ${LOG_FILE}"
    echo -e "  app:       docker logs afina-app -f"
    echo -e "  angie:     docker logs afina-angie -f"
    echo ""
    echo -e "${BOLD}Статус:${N}"
    dc ps
    echo ""
    echo -e "${DIM}Токен действителен, пока wizard не завершён.${N}"
    echo -e "${DIM}Для сброса: sudo ./bootstrap.sh --reset --domain=${ARG_DOMAIN}${N}"
    echo ""
}

# ═══════════════════════════════════════════════════════════
# Reset (сброс wizard без удаления данных)
# ═══════════════════════════════════════════════════════════
do_reset() {
    hdr "Reset wizard"

    warn "Режим --reset:"
    warn "  • Будет backup .env.prod → .env.prod.bak.${STAMP}"
    warn "  • Будет удалён setup-out/* (маркеры wizard)"
    warn "  • Будет удалён secrets/initialized (если есть)"
    warn "НЕ будет затронуто:"
    warn "  • storage/  (файлы документов)"
    warn "  • backups/  (бэкапы БД)"
    warn "  • secrets/keys/  (ключи шифрования)"
    warn "  • certs/, crls/  (сертификаты, CRL)"
    warn "  • admins.env"
    echo ""

    if [ "$ARG_NON_INTERACTIVE" = "0" ]; then
        local ans
        read -r -p "Продолжить reset? [y/N]: " ans
        [ "$ans" = "y" ] || die "Отменено"
    fi

    [ -f .env.prod ] && cp .env.prod ".env.prod.bak.${STAMP}" && ok "Backup: .env.prod.bak.${STAMP}"

    rm -f setup-out/setup.complete setup-out/.env.prod.new setup-out/.env.prod.applied
    ok "setup-out/* очищен"

    rm -f secrets/initialized
    ok "secrets/initialized удалён (если был)"

    ok "Reset завершён"
    echo ""
    echo "Для повторного запуска wizard:"
    echo -e "  ${C}sudo ./bootstrap.sh --domain=${ARG_DOMAIN} --accept-non-astra${N}"
}

# ═══════════════════════════════════════════════════════════
# [install] Права доступа (chown 999:999 для app uid)
# ═══════════════════════════════════════════════════════════
fix_permissions() {
    hdr "Права доступа (uid 999:999)"

    if [ "$ARG_DRY_RUN" = "1" ]; then
        ok "[dry-run] chown 999:999 для secrets storage logs backups crls setup-out angie-certs admins.env"
        return 0
    fi

    # secrets — строго 700/600, owner 999
    chown -R 999:999 secrets
    chmod 700 secrets secrets/keys
    chmod 600 secrets/keys/* secrets/backup.pass 2>/dev/null || true

    # рабочие каталоги app
    chown -R 999:999 storage logs backups crls setup-out angie-certs 2>/dev/null || true
    chmod -R 775 storage logs backups crls setup-out angie-certs 2>/dev/null || true

    # admins.env — wizard пишет (uid 999)
    if [ -f admins.env ]; then
        chown 999:999 admins.env
        chmod 600 admins.env
    fi

    # certs — только чтение (root)
    chown -R root:root certs 2>/dev/null || true
    chmod 755 certs
    chmod 644 certs/* 2>/dev/null || true

    ok "Права выданы (secrets 700/600, storage 775)"
}

# ═══════════════════════════════════════════════════════════
# [install] setup-applier (systemd unit)
# ═══════════════════════════════════════════════════════════
install_setup_applier() {
    hdr "setup-applier (systemd)"

    if [ "$ARG_DRY_RUN" = "1" ]; then
        ok "[dry-run] systemd unit afina-setup-applier будет установлен"
        return 0
    fi

    if [ ! -f setup-applier.sh ]; then
        warn "setup-applier.sh не найден в $APP_DIR — пропускаю"
        return 0
    fi

    chmod +x setup-applier.sh
    mkdir -p /var/log/afina
    touch /var/log/afina/setup-applier.log

    local unit_file="/etc/systemd/system/afina-setup-applier.service"

    cat > "$unit_file" <<'SERVICE_EOF'
[Unit]
Description=AfinaZ Setup Applier (watches setup-out/setup.complete)
After=docker.service
Requires=docker.service

[Service]
Type=simple
WorkingDirectory=/opt/afinaz
ExecStart=/opt/afinaz/setup-applier.sh
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
SERVICE_EOF

    chmod 644 "$unit_file"
    systemctl daemon-reload
    systemctl enable afina-setup-applier.service >/dev/null 2>&1 || true
    systemctl restart afina-setup-applier.service

    sleep 2
    if systemctl is-active --quiet afina-setup-applier.service; then
        ok "afina-setup-applier: active (systemctl status для проверки)"
    else
        warn "afina-setup-applier: НЕ активен — проверьте: journalctl -u afina-setup-applier"
    fi
}

# ═══════════════════════════════════════════════════════════
# Точка входа
# ═══════════════════════════════════════════════════════════
main() {
    clear
    echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
    echo -e "${BOLD}  ПАО «НБД-Банк» · Bootstrap для Setup Mode${N}"
    echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
    echo -e "${DIM}Лог: $LOG_FILE${N}"
    echo -e "${DIM}Дата: $(date '+%Y-%m-%d %H:%M:%S %Z')${N}"
    [ "$ARG_DRY_RUN" = "1" ] && echo -e "${Y}${BOLD}Режим: DRY-RUN (ничего не изменяется)${N}"
    echo ""
    : > "$LOG_FILE"

    parse_args "$@"

    if [ "$ARG_RESET" = "1" ]; then
        cd "$APP_DIR" 2>/dev/null || die "$APP_DIR не найден"
        do_reset
        exit 0
    fi

    preflight
    detect_os
    install_docker

    # Интерактивный ввод домена, если не задан
    if [ -z "$ARG_DOMAIN" ]; then
        ARG_DOMAIN=$(ask "Домен или IP")
        [ -n "$ARG_DOMAIN" ] || die "Домен обязателен"
    fi

    generate_secrets
    generate_env_prod
    setup_tls
    start_stack
    fix_permissions
    install_setup_applier
    print_summary
}

main "$@"
