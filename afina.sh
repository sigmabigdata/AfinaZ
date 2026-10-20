#!/usr/bin/env bash
# ============================================================
# Афина · CLI для сервера
# Устанавливается как /usr/local/bin/afina (symlink на этот файл)
# ============================================================

set -o pipefail

# ---- Цвета ----
G='\033[0;32m'; Y='\033[1;33m'; R='\033[0;31m'; B='\033[0;34m'
C='\033[0;36m'; N='\033[0m'; BOLD='\033[1m'; DIM='\033[2m'

# ---- Константы ----
APP_DIR="/opt/afinaz"
COMPOSE_FILE="docker-compose-prod.yml"
ENV_FILE=".env.prod"

# ---- Проверка, что мы там где надо ----
if [ ! -d "$APP_DIR" ]; then
    echo -e "${R}✗ Не найден $APP_DIR${N}"
    exit 1
fi

cd "$APP_DIR" || exit 1

# ---- Помощники ----
dc() {
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

psql_() {
    docker exec afina-postgres psql -U postgres -d afina_db "$@"
}

ok()   { echo -e "${G}✓${N} $*"; }
warn() { echo -e "${Y}⚠${N} $*"; }
err()  { echo -e "${R}✗${N} $*"; }
hdr()  { echo -e "\n${C}${BOLD}▶ $*${N}"; }

# ============================================================
cmd_help() {
    echo -e "${BOLD}Афина · CLI${N}"
    echo ""
    echo -e "${BOLD}Основное:${N}"
    echo "  status              Сводка: контейнеры, health, ключи, SSL"
    echo "  up                  Поднять стек"
    echo "  down                Остановить стек"
    echo "  restart             Перезапустить app"
    echo "  deploy              git pull + rebuild + restart"
    echo ""
    echo -e "${BOLD}Логи и отладка:${N}"
    echo "  logs [app|angie|pg] [-f]     Логи (по умолчанию app, без -f)"
    echo "  shell [app|pg]               Открыть sh/bash в контейнере"
    echo "  db \"SQL\"                     SQL-запрос на проде"
    echo "  doctor                       Полная диагностика"
    echo "  health                       Health один раз"
    echo ""
    echo -e "${BOLD}Данные:${N}"
    echo "  backup                       Ручной бэкап"
    echo "  key                          Информация о ключах шифрования"
    echo "  audit [N]                    Последние N событий аудита (по умолч. 20)"
    echo "  users [search]               Список клиентов"
    echo ""
    echo -e "${BOLD}Инфраструктура:${N}"
    echo "  ssl                          Проверка SSL-сертификата"
    echo "  crl                          Список CRL"
    echo "  cron                         Показать cron-задачи"
    echo ""
    echo -e "${BOLD}Прочее:${N}"
    echo "  help                         Эта справка"
    echo "  version                      Версия app"
}

# ============================================================
cmd_status() {
    echo -e "${BOLD}Афина · Статус${N}"
    echo "────────────────────────────────────────────"

    # Контейнеры
    hdr "Контейнеры"
    dc ps --format "table {{.Name}}\t{{.Status}}" 2>/dev/null | tail -n +2 | while read -r line; do
        if echo "$line" | grep -q "healthy\|Up"; then
            echo -e "  ${G}●${N} $line"
        else
            echo -e "  ${R}●${N} $line"
        fi
    done

    # Health
    hdr "Health"
    local h
    h=$(curl -s -o /dev/null -w "%{http_code}" https://afinasystems.ru/actuator/health 2>/dev/null || echo "000")
    if [ "$h" = "200" ]; then
        ok "HTTPS: 200 OK"
    else
        err "HTTPS: $h"
    fi

    # Ключи
    hdr "Ключи шифрования"
    for k in file.key pii.key; do
        local p="$APP_DIR/secrets/$k"
        if [ -f "$p" ]; then
            local perm
            perm=$(stat -c '%a' "$p" 2>/dev/null || echo "?")
            local size
            size=$(stat -c '%s' "$p" 2>/dev/null || echo "?")
            if [ "$perm" = "600" ] && [ "$size" = "45" ]; then
                ok "$k: 45 B, $perm"
            else
                warn "$k: $size B, $perm"
            fi
        else
            err "$k: отсутствует"
        fi
    done

    # SSL
    hdr "SSL"
    if [ -x "$APP_DIR/check-ssl.sh" ]; then
        "$APP_DIR/check-ssl.sh" 2>&1 | grep -E "Days left|✓|✗|⚠" | head -3
    fi

    echo ""
}

# ============================================================
cmd_up() {
    dc up -d
    ok "Стек поднят"
}

cmd_down() {
    dc down
    ok "Стек остановлен"
}

cmd_restart() {
    dc restart app
    sleep 10
    dc ps --format "table {{.Name}}\t{{.Status}}" | grep app
}

cmd_deploy() {
    echo -e "${B}▶ git pull${N}"
    git pull || { err "git pull failed"; exit 1; }

    echo -e "${B}▶ rebuild${N}"
    dc build --no-cache app || { err "build failed"; exit 1; }

    echo -e "${B}▶ up${N}"
    dc up -d app

    echo -e "${B}▶ wait 45s${N}"
    sleep 45
    dc ps
    dc logs app 2>&1 | grep -iE 'Started|ERROR' | tail -3
}

# ============================================================
cmd_logs() {
    local service="app"
    local follow=""

    for arg in "$@"; do
        case "$arg" in
            app|angie|postgres|pg)
                [ "$arg" = "pg" ] && service="postgres" || service="$arg"
                ;;
            -f|--follow) follow="-f" ;;
        esac
    done

    dc logs $follow --tail 200 "$service"
}

# ============================================================
cmd_shell() {
    local target="${1:-app}"
    case "$target" in
        app)   docker exec -it afina-app sh ;;
        pg|postgres) docker exec -it afina-postgres bash ;;
        *) err "use: afina shell app|pg"; exit 1 ;;
    esac
}

# ============================================================
cmd_db() {
    if [ -z "$1" ]; then
        err "afina db \"SQL\" — нужен запрос"
        exit 1
    fi
    psql_ -c "$1"
}

# ============================================================
cmd_backup() {
    "$APP_DIR/backup-prod.sh"
    echo ""
    ls -lh "$APP_DIR/backups/" | tail -3
}

# ============================================================
cmd_health() {
    echo -e "${B}▶ Local:${N}"
    docker exec afina-app curl -fsS http://localhost:8080/actuator/health 2>/dev/null && echo
    echo -e "${B}▶ External:${N}"
    curl -fsS https://afinasystems.ru/actuator/health 2>/dev/null && echo
}

# ============================================================
cmd_key() {
    hdr "Ключи шифрования"
    for k in file.key pii.key; do
        local p="$APP_DIR/secrets/$k"
        if [ -f "$p" ]; then
            stat -c "  %n  %s bytes  mode %a  owner %U:%G" "$p"
        else
            err "  $k отсутствует"
        fi
    done
}

# ============================================================
cmd_audit() {
    local n="${1:-20}"
    psql_ -c "SELECT event_time, event_type, result, actor_email, target_info
              FROM audit_events
              ORDER BY id DESC LIMIT $n;"
}

# ============================================================
cmd_users() {
    local q="$1"
    if [ -z "$q" ]; then
        psql_ -c "SELECT id, full_name, email_hash, role, enabled, created_at
                  FROM users ORDER BY id;"
    else
        psql_ -c "SELECT id, full_name, role, enabled FROM users WHERE lower(full_name) LIKE lower('%$q%') OR email_hash = '$(echo -n "$q" | sha256sum | cut -d' ' -f1)';"
    fi
}

# ============================================================
cmd_ssl() {
    "$APP_DIR/check-ssl.sh"
}

# ============================================================
cmd_crl() {
    hdr "CRL в $APP_DIR/crls/"
    ls -lh "$APP_DIR/crls/" 2>/dev/null | grep -E '\.crl' || warn "CRL нет"
}

# ============================================================
cmd_cron() {
    hdr "Cron-задачи Афина"
    for f in /etc/cron.d/afina-*; do
        [ -f "$f" ] || continue
        echo -e "${B}▶ $(basename $f)${N}"
        grep -v '^#' "$f" | grep -v '^$' | grep -v '^SHELL\|^PATH'
    done
}

# ============================================================
cmd_version() {
    docker exec afina-app sh -c 'cat /app/build.txt 2>/dev/null' || \
    git log -1 --format="%h %s (%ci)"
}

# ============================================================
cmd_doctor() {
    echo -e "${BOLD}═══════════════════════════════════════════════${N}"
    echo -e "${BOLD}  Афина · Диагностика${N}"
    echo -e "${BOLD}═══════════════════════════════════════════════${N}"

    # 1. Docker
    if docker info >/dev/null 2>&1; then
        ok "Docker запущен"
    else
        err "Docker недоступен"; return 1
    fi

    # 2. Контейнеры
    local running
    running=$(dc ps --status running --services 2>/dev/null | sort | tr '\n' ' ')
    for svc in app angie postgres; do
        if echo "$running" | grep -qw "$svc"; then
            ok "Контейнер $svc: running"
        else
            err "Контейнер $svc: НЕ running"
        fi
    done

    # 3. Health
    if curl -fsS https://afinasystems.ru/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        ok "HTTPS /actuator/health: UP"
    else
        err "HTTPS /actuator/health: DOWN"
    fi

    # 4. Ключи
    for k in file.key pii.key; do
        local p="$APP_DIR/secrets/$k"
        if [ -f "$p" ] && [ "$(stat -c %a "$p")" = "600" ] && [ "$(stat -c %s "$p")" = "45" ]; then
            ok "Ключ $k: OK"
        else
            warn "Ключ $k: проблемы"
        fi
    done

    # 5. Миграции
    local mig_ok
    mig_ok=$(psql_ -t -A -c "SELECT count(*) FROM flyway_schema_history WHERE success=false" 2>/dev/null)
    if [ "$mig_ok" = "0" ]; then
        ok "Миграции: все успешны"
    else
        err "Миграции: $mig_ok провалено"
    fi

    # 6. Данные
    local users
    users=$(psql_ -t -A -c "SELECT count(*) FROM users" 2>/dev/null)
    local docs
    docs=$(psql_ -t -A -c "SELECT count(*) FROM documents" 2>/dev/null)
    ok "Данные: $users пользователей, $docs документов"

    # 7. PII-шифрование
    local pii
    pii=$(psql_ -t -A -c "SELECT count(*) FROM users WHERE email_enc IS NULL" 2>/dev/null)
    if [ "$pii" = "0" ]; then
        ok "PII: все email зашифрованы"
    else
        warn "PII: $pii записей без email_enc"
    fi

    # 8. Бэкапы
    local last_bk
    last_bk=$(ls -t "$APP_DIR/backups/afina_"*.sql.gz 2>/dev/null | head -1)
    if [ -n "$last_bk" ]; then
        local age_h
        age_h=$(( ($(date +%s) - $(stat -c %Y "$last_bk")) / 3600 ))
        if [ "$age_h" -lt 30 ]; then
            ok "Бэкап: $age_h ч назад"
        else
            warn "Бэкап: $age_h ч назад (старый)"
        fi
    else
        err "Бэкапов нет"
    fi

    # 9. Диск
    local disk
    disk=$(df -h / | awk 'NR==2 {print $5}' | tr -d '%')
    if [ "$disk" -lt 80 ]; then
        ok "Диск: $disk%"
    elif [ "$disk" -lt 90 ]; then
        warn "Диск: $disk%"
    else
        err "Диск: $disk%"
    fi

    # 10. Память
    local mem
    mem=$(free | awk '/Mem:/ {printf "%d", $3/$2*100}')
    if [ "$mem" -lt 80 ]; then
        ok "Память: $mem%"
    elif [ "$mem" -lt 90 ]; then
        warn "Память: $mem%"
    else
        err "Память: $mem%"
    fi

    # 11. System restart required
    if [ -f /var/run/reboot-required ]; then
        warn "Требуется перезагрузка (обновления ядра)"
    fi

    echo -e "${BOLD}═══════════════════════════════════════════════${N}"
}

# ============================================================
# Main
case "${1:-help}" in
    status|st)     cmd_status ;;
    up)            cmd_up ;;
    down)          cmd_down ;;
    restart|r)     cmd_restart ;;
    deploy|dep)    cmd_deploy ;;
    logs|l)        shift; cmd_logs "$@" ;;
    shell|sh)      shift; cmd_shell "$@" ;;
    db)            shift; cmd_db "$@" ;;
    backup|b)      cmd_backup ;;
    health|h)      cmd_health ;;
    key)           cmd_key ;;
    audit)         shift; cmd_audit "$@" ;;
    users|u)       shift; cmd_users "$@" ;;
    ssl)           cmd_ssl ;;
    crl)           cmd_crl ;;
    cron)          cmd_cron ;;
    doctor|doc)    cmd_doctor ;;
    version|v)     cmd_version ;;
    help|-h|--help) cmd_help ;;
    *)             err "Неизвестная команда: $1"; cmd_help; exit 1 ;;
esac
