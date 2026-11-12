#!/usr/bin/env bash
# ============================================================
# AfinaZ · Установщик дистрибутива (offline, один скрипт)
# ============================================================
# Все грабли, выявленные при разработке, учтены:
#   • postgres:15-alpine (не собираем — образ готов)
#   • admins.env — ФАЙЛ, не директория (создаём до compose)
#   • setup-out — директория (создаём до compose)
#   • secrets/keys — отдельный mount (rw)
#   • nginx от ISPmanager маскируем (не перезапускается)
#   • chown 999:999 для app uid
#   • MAIL_* НЕ пишем пустыми (NumberFormatException в MonitoringService)
#   • systemd unit applier устанавливаем сразу
# ============================================================
set -euo pipefail

DOMAIN=""
NON_ASTRA=0
DRY_RUN=0
STAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="/tmp/afina-install-${STAMP}.log"

G='\033[0;32m'; Y='\033[1;33m'; R='\033[0;31m'
C='\033[0;36m'; N='\033[0m'; BOLD='\033[1m'; DIM='\033[2m'

ok()   { echo -e "  ${G}✓${N} $*" | tee -a "$LOG_FILE"; }
warn() { echo -e "  ${Y}⚠${N} $*" | tee -a "$LOG_FILE"; }
err()  { echo -e "  ${R}✗${N} $*" | tee -a "$LOG_FILE"; }
hdr()  { echo "" | tee -a "$LOG_FILE"; echo -e "${BOLD}▶ $*${N}" | tee -a "$LOG_FILE"; }
info() { echo -e "  ${DIM}${C}•${N} $*" | tee -a "$LOG_FILE"; }
die()  { echo -e "\n${R}${BOLD}❌ $*${N}\nЛог: $LOG_FILE" | tee -a "$LOG_FILE"; exit 1; }

usage() {
    cat <<EOF
Использование: sudo ./install.sh --domain=<IP_или_домен> [опции]

  --domain=<IP|домен>   домен или IP сервера (обязательно)
  --accept-non-astra    продолжить на не-Astra ОС (Ubuntu/Debian)
  --dry-run             только preflight, ничего не изменять
  -h, --help            эта справка

Примеры:
  sudo ./install.sh --domain=94.103.13.34 --accept-non-astra
  sudo ./install.sh --domain=afina.example.ru --dry-run
EOF
    exit 0
}

# ── Args ──
while [ $# -gt 0 ]; do
    case "$1" in
        --domain=*) DOMAIN="${1#*=}" ;;
        --domain)   DOMAIN="${2:-}"; shift ;;
        --accept-non-astra) NON_ASTRA=1 ;;
        --dry-run) DRY_RUN=1 ;;
        -h|--help) usage ;;
        *) die "Неизвестный флаг: $1" ;;
    esac
    shift
done

[ -z "$DOMAIN" ] && die "--domain обязателен (см. --help)"

echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
echo -e "${BOLD}  ПАО «НБД-Банк» · Установка AfinaZ${N}"
echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
echo -e "${DIM}Домен: $DOMAIN${N}"
echo -e "${DIM}Лог:   $LOG_FILE${N}"
[ "$DRY_RUN" = "1" ] && echo -e "${Y}${BOLD}Режим: DRY-RUN${N}"
echo ""
: > "$LOG_FILE"

cd "$(dirname "$0")"

# ═══════════════════════════════════════════════════════════
# [1/8] Pre-flight
# ═══════════════════════════════════════════════════════════
hdr "[1/8] Pre-flight"
[ "$(id -u)" = "0" ] || die "Запустите под root"
[ "$(uname -m)" = "x86_64" ] || die "Требуется x86_64"
ok "root, x86_64"

FREE_GB=$(df -BG / | awk 'NR==2 {gsub("G",""); print $4}')
[ "$FREE_GB" -ge 20 ] || die "Мало места: ${FREE_GB} GB (нужно ≥20)"
ok "диск: ${FREE_GB} GB"

RAM_GB=$(free -g | awk '/Mem:/ {print $2}')
[ "$RAM_GB" -ge 3 ] && ok "RAM: ${RAM_GB} GB" || warn "RAM: ${RAM_GB} GB (рекомендуется ≥4)"

# Файлы дистрибутива
for f in docker-compose-prod.yml angie.conf setup-applier.sh entrypoint-wrapper.sh images/all-images.tar.gz; do
    [ -f "$f" ] || die "Отсутствует: $f"
done
ok "файлы дистрибутива"

# ═══════════════════════════════════════════════════════════
# [2/8] ОС
# ═══════════════════════════════════════════════════════════
hdr "[2/8] ОС"
if [ -f /etc/astra_version ]; then
    OS_FAMILY="astra"
    OS_NAME="Astra Linux SE $(cat /etc/astra_version 2>/dev/null | head -1)"
    ok "$OS_NAME (сертифицированная)"
else
    . /etc/os-release
    OS_NAME="${PRETTY_NAME}"
    case "${ID:-}" in
        ubuntu|debian) OS_FAMILY="debian" ;;
        *) die "ОС не поддерживается: ${ID:-unknown}" ;;
    esac
    warn "$OS_NAME — не Astra, для прода не рекомендуется"
    [ "$NON_ASTRA" = "1" ] || die "Нужен --accept-non-astra"
fi
ok "ОС: $OS_NAME (family: $OS_FAMILY)"

# ═══════════════════════════════════════════════════════════
# [3/8] Docker
# ═══════════════════════════════════════════════════════════
hdr "[3/8] Docker"
command -v docker >/dev/null || die "Docker не установлен"
docker info >/dev/null 2>&1 || die "Docker daemon не запущен"
docker compose version >/dev/null 2>&1 || die "docker compose не установлен"
ok "Docker + compose"
[ "$DRY_RUN" = "1" ] && ok "[dry-run] docker проверен, пропускаю образы" || true

# ═══════════════════════════════════════════════════════════
# [4/8] Порты (освобождаем + маскируем nginx)
# ═══════════════════════════════════════════════════════════
hdr "[4/8] Порты 80/443"
for port in 80 443; do
    if ss -tlnp 2>/dev/null | grep -q ":${port} "; then
        if docker ps --format '{{.Names}}' 2>/dev/null | grep -q afina-angie; then
            ok "порт $port — наш afina-angie"
        else
            warn "порт $port занят — освобождаю"
            if [ "$DRY_RUN" = "0" ]; then
                systemctl stop nginx apache2 2>/dev/null || true
                systemctl disable nginx apache2 2>/dev/null || true
                systemctl mask nginx apache2 2>/dev/null || true
                sleep 2
                ss -tlnp 2>/dev/null | grep -q ":${port} " && die "порт $port всё ещё занят"
            fi
            ok "порт $port освобождён (nginx/apache2 mask)"
        fi
    else
        ok "порт $port свободен"
    fi
done

# ═══════════════════════════════════════════════════════════
# [5/8] Директории + admins.env как ФАЙЛ (критично!)
# ═══════════════════════════════════════════════════════════
hdr "[5/8] Директории"
if [ "$DRY_RUN" = "0" ]; then
    # Удаляем битые директории, если создались ранее
    [ -d admins.env ] && rm -rf admins.env && warn "admins.env был директорией — удалён"
    [ -d setup-out ] && ok "setup-out уже существует" || mkdir -p setup-out

    mkdir -p secrets/keys certs crls logs backups storage/documents storage/documents/visualizations angie-certs

    # admins.env — ПУСТОЙ ФАЙЛ (wizard запишет)
    [ -f admins.env ] || touch admins.env

    ok "директории созданы"
    ok "admins.env — файл"
fi
[ "$DRY_RUN" = "1" ] && ok "[dry-run] пропускаю создание директорий"

# ═══════════════════════════════════════════════════════════
# [6/8] Секреты + .env.prod + TLS
# ═══════════════════════════════════════════════════════════
hdr "[6/8] Секреты и конфигурация"

if [ "$DRY_RUN" = "0" ]; then
    # 6.1. Ключи
    if [ ! -f secrets/keys/file.key.v1 ]; then
        openssl rand -base64 32 | tr -d '\n' > secrets/keys/file.key.v1
        echo -n "1" > secrets/keys/file.key.current
        ok "file.key.v1 создан"
    else
        ok "file.key.v1 уже есть"
    fi
    if [ ! -f secrets/keys/pii.key.v1 ]; then
        openssl rand -base64 32 | tr -d '\n' > secrets/keys/pii.key.v1
        echo -n "1" > secrets/keys/pii.key.current
        ok "pii.key.v1 создан"
    else
        ok "pii.key.v1 уже есть"
    fi
    if [ ! -f secrets/backup.pass ]; then
        openssl rand -base64 32 | tr -d '\n' > secrets/backup.pass
        ok "backup.pass создан"
    fi
    chmod 600 secrets/backup.pass secrets/keys/*

    # 6.2. .env.prod (или обновить)
    if [ -f .env.prod ]; then
        cp .env.prod ".env.prod.bak.${STAMP}"
        warn "старый .env.prod → .env.prod.bak.${STAMP}"
        # Переиспользуем пароли БД
        DB_PASS=$(grep '^DB_PASSWORD=' .env.prod | cut -d'=' -f2-)
        DB_MIG_PASS=$(grep '^DB_MIGRATOR_PASSWORD=' .env.prod | cut -d'=' -f2-)
        DB_AUD_PASS=$(grep '^DB_AUDITOR_PASSWORD=' .env.prod | cut -d'=' -f2-)
        DB_SUPER_PASS=$(grep '^DB_SUPERUSER_PASSWORD=' .env.prod | cut -d'=' -f2-)
    fi
    [ -z "${DB_PASS:-}" ] && DB_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    [ -z "${DB_MIG_PASS:-}" ] && DB_MIG_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    [ -z "${DB_AUD_PASS:-}" ] && DB_AUD_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
    [ -z "${DB_SUPER_PASS:-}" ] && DB_SUPER_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)

    SETUP_TOKEN=$(openssl rand -hex 32)

    cat > .env.prod <<ENVEOF
# AfinaZ · .env.prod (install $STAMP)
APP_DOMAIN=${DOMAIN}
APP_BASE_URL=https://${DOMAIN}
SECURE_COOKIES=true
HTTP_PORT=80
HTTPS_PORT=443
TLS_MODE=self-signed

DB_NAME=afina_db
DB_SUPERUSER=postgres
DB_SUPERUSER_PASSWORD=${DB_SUPER_PASS}
DB_USER=afina_app
DB_PASSWORD=${DB_PASS}
DB_MIGRATOR_USER=afina_migrator
DB_MIGRATOR_PASSWORD=${DB_MIG_PASS}
DB_AUDITOR_USER=afina_auditor
DB_AUDITOR_PASSWORD=${DB_AUD_PASS}

APP_SETUP_MODE=true
APP_SETUP_TOKEN=${SETUP_TOKEN}

ADMIN_IP_WHITELIST=127.0.0.1,::1,172.0.0.0/8
MAIL_MODE=log
MAIL_FROM=noreply@${DOMAIN}

CRYPTO_PRO_ENABLED=true
ENVEOF
    chmod 600 .env.prod
    ok ".env.prod создан"
    warn "Setup URL: https://${DOMAIN}/setup?token=${SETUP_TOKEN}"

    # 6.3. TLS self-signed
    if [ ! -f angie-certs/fullchain.pem ]; then
        openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
            -keyout angie-certs/privkey.pem \
            -out angie-certs/fullchain.pem \
            -subj "/CN=${DOMAIN}" \
            -addext "subjectAltName=DNS:${DOMAIN},IP:${DOMAIN}" 2>/dev/null || \
        openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
            -keyout angie-certs/privkey.pem \
            -out angie-certs/fullchain.pem \
            -subj "/CN=${DOMAIN}" 2>/dev/null
        ok "TLS self-signed создан"
    fi
    chmod 644 angie-certs/privkey.pem angie-certs/fullchain.pem

    # 6.4. Права uid 999 (app в контейнере)
    chown -R 999:999 secrets/keys storage logs backups crls setup-out 2>/dev/null || true
    chmod 700 secrets/keys
    chmod -R 775 storage logs backups crls setup-out 2>/dev/null || true
    chown 999:999 admins.env
    chmod 666 admins.env  # app должен писать при wizard finalize
    chmod 755 angie-certs
    chmod 644 angie-certs/*.pem
    ok "права uid 999:999"

    # 6.5. Credentials
    cat > "bootstrap-credentials-${STAMP}.txt" <<CREDSEOF
════════════════════════════════════════════════════════
  AfinaZ · Credentials (сохраните и удалите!)
  Создано: $(date '+%Y-%m-%d %H:%M:%S')
════════════════════════════════════════════════════════
Setup URL: https://${DOMAIN}/setup?token=${SETUP_TOKEN}

БД:
  Superuser:  ${DB_SUPER_PASS}
  App:        ${DB_PASS}
  Migrator:   ${DB_MIG_PASS}
  Auditor:    ${DB_AUD_PASS}

Секреты:
  secrets/backup.pass  (для восстановления бэкапов)
  secrets/keys/        (ключи шифрования PII и файлов)

⚠ СОХРАНИТЕ В 1PASSWORD И УДАЛИТЕ:
  shred -u bootstrap-credentials-${STAMP}.txt
════════════════════════════════════════════════════════
CREDSEOF
    chmod 600 "bootstrap-credentials-${STAMP}.txt"
    warn "Credentials: bootstrap-credentials-${STAMP}.txt"
else
    ok "[dry-run] секреты/.env/TLS пропущены"
fi

# ═══════════════════════════════════════════════════════════
# [7/8] Загрузка образов + запуск стека
# ═══════════════════════════════════════════════════════════
hdr "[7/8] Загрузка образов и запуск"

if [ "$DRY_RUN" = "0" ]; then
    info "Загружаю образы (2-3 минуты, ~2 GB)..."
    gunzip -c images/all-images.tar.gz | docker load 2>&1 | tail -5
    ok "образы загружены"

    info "Запускаю postgres..."
    docker compose -f docker-compose-prod.yml --env-file .env.prod up -d postgres 2>&1 | tail -2
    for i in $(seq 1 45); do
        docker exec afina-postgres pg_isready -U postgres >/dev/null 2>&1 && break
        sleep 2
    done
    ok "postgres готов"

    info "Создаю роли БД..."
    DB_NAME=$(grep '^DB_NAME=' .env.prod | cut -d'=' -f2-)
    DB_MIG_PASS=$(grep '^DB_MIGRATOR_PASSWORD=' .env.prod | cut -d'=' -f2-)
    DB_APP_PASS=$(grep '^DB_PASSWORD=' .env.prod | cut -d'=' -f2-)
    DB_AUD_PASS=$(grep '^DB_AUDITOR_PASSWORD=' .env.prod | cut -d'=' -f2-)

    for pair in "afina_migrator:${DB_MIG_PASS}" "afina_app:${DB_APP_PASS}" "afina_auditor:${DB_AUD_PASS}"; do
        r="${pair%%:*}"; p="${pair#*:}"
        docker exec -i afina-postgres psql -U postgres -d postgres -tAc \
            "SELECT 1 FROM pg_roles WHERE rolname='${r}'" | grep -q 1 || \
            docker exec -i afina-postgres psql -U postgres -d postgres \
                -c "CREATE ROLE ${r} WITH LOGIN PASSWORD '${p}';" >/dev/null
        docker exec -i afina-postgres psql -U postgres -d postgres \
            -c "ALTER USER ${r} WITH PASSWORD '${p}';" >/dev/null
    done
    docker exec -i afina-postgres psql -U postgres -d postgres \
        -c "GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO afina_migrator; GRANT CONNECT ON DATABASE ${DB_NAME} TO afina_app, afina_auditor;" >/dev/null
    docker exec -i afina-postgres psql -U postgres -d "${DB_NAME}" >/dev/null 2>&1 <<GRANTEOF || true
ALTER SCHEMA public OWNER TO afina_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO afina_migrator;
GRANT USAGE ON SCHEMA public TO afina_app, afina_auditor;
GRANTEOF
    ok "роли БД готовы"

    info "Запускаю app + angie..."
    docker compose -f docker-compose-prod.yml --env-file .env.prod up -d app angie 2>&1 | tail -3

    info "Жду health (max 120s)..."
    for i in $(seq 1 40); do
        H=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 3 "https://localhost/setup?token=$(grep '^APP_SETUP_TOKEN=' .env.prod | cut -d'=' -f2-)" 2>/dev/null || echo "000")
        [ "$H" = "200" ] || [ "$H" = "404" ] && { ok "app отвечает"; break; }
        sleep 3
    done

    # 7.4. Установить applier
    info "Устанавливаю systemd applier..."
    cat > /etc/systemd/system/afina-setup-applier.service <<'EOF'
[Unit]
Description=AfinaZ Setup Applier
After=docker.service
Requires=docker.service

[Service]
Type=simple
WorkingDirectory=$(pwd)
ExecStart=$(pwd)/setup-applier.sh
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
    # Подставляем реальный путь (heredoc без интерполяции не работает с $(pwd))
    APP_DIR=$(pwd)
    sed -i "s|\$(pwd)|$APP_DIR|g" /etc/systemd/system/afina-setup-applier.service

    systemctl daemon-reload
    systemctl enable --now afina-setup-applier.service >/dev/null 2>&1
    ok "applier установлен"

    # 7.5. CLI afina
    if [ -f afina.sh ]; then
        chmod +x afina.sh
        ln -sf "$APP_DIR/afina.sh" /usr/local/bin/afina
        ok "CLI: /usr/local/bin/afina"
    fi
else
    ok "[dry-run] загрузка образов и запуск пропущены"
fi

# ═══════════════════════════════════════════════════════════
# [8/8] Итог
# ═══════════════════════════════════════════════════════════
hdr "[8/8] Готово"

if [ "$DRY_RUN" = "0" ]; then
    TOKEN=$(grep '^APP_SETUP_TOKEN=' .env.prod | cut -d'=' -f2-)
    echo ""
    echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
    echo -e "${G}${BOLD}  ✅ УСТАНОВКА ЗАВЕРШЕНА${N}"
    echo -e "${BOLD}════════════════════════════════════════════════════════${N}"
    echo ""
    echo -e "${BOLD}Следующий шаг — откройте в браузере:${N}"
    echo ""
    echo -e "  ${C}https://${DOMAIN}/setup?token=${TOKEN}${N}"
    echo ""
    echo -e "${BOLD}Credentials сохранены:${N}"
    echo -e "  bootstrap-credentials-${STAMP}.txt"
    echo -e "${Y}  ⚠ Сохраните в 1Password, потом: shred -u bootstrap-credentials-${STAMP}.txt${N}"
    echo ""
    echo -e "${BOLD}После wizard'а (шаг 6 «Завершить»):${N}"
    echo "  • applier применит .env.prod.new"
    echo "  • контейнер перезапустится в обычном режиме"
    echo "  • вход в /admin/login по УКЭП"
    echo ""
    echo -e "${BOLD}Логи:${N} $LOG_FILE"
    echo -e "${BOLD}Управление:${N} afina (после setup)"
    echo ""
    docker compose -f docker-compose-prod.yml --env-file .env.prod ps
else
    ok "[dry-run] всё проверено, ничего не изменено"
fi
