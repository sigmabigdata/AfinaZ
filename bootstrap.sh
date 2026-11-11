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

# ─── Помощники ───
log()    { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG_FILE"; }
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
# Точка входа (остальные фазы — в части 2, будут добавлены
# следующими коммитами как отдельные функции: generate_secrets,
# generate_env_prod, setup_tls, start_stack, wait_health, print_summary)
# ═══════════════════════════════════════════════════════════
main() {
    clear
    echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
    echo -e "${BOLD}  ПАО «НБД-Банк» · Bootstrap для Setup Mode${N}"
    echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
    echo -e "${DIM}Лог: $LOG_FILE${N}"
    echo -e "${DIM}Дата: $(date '+%Y-%m-%d %H:%M:%S %Z')${N}"
    echo ""
    : > "$LOG_FILE"

    parse_args "$@"
    preflight
    detect_os
    install_docker

    echo ""
    echo -e "${G}${BOLD}✓ Пре-проверки пройдены (часть 1/2)${N}"
    echo -e "${DIM}Далее: secrets, .env.prod, TLS, stack, health.${N}"
    echo -e "${DIM}Это будет добавлено в bootstrap.sh (часть 2).${N}"
    echo ""
    echo -e "${BOLD}Лог:${N} $LOG_FILE"
}

main "$@"
