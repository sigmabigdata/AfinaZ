#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

G='\033[0;32m'; Y='\033[1;33m'; R='\033[0;31m'; B='\033[0;34m'
C='\033[0;36m'; N='\033[0m'; BOLD='\033[1m'
log()  { echo -e "${B}▶${N} $*"; }
ok()   { echo -e "${G}✓${N} $*"; }
warn() { echo -e "${Y}⚠${N} $*"; }
err()  { echo -e "${R}✗${N} $*"; exit 1; }
hdr()  { echo -e "\n${C}${BOLD}═══════════════════════════════════════════════${N}"; \
         echo -e "${C}${BOLD}  $*${N}"; \
         echo -e "${C}${BOLD}═══════════════════════════════════════════════${N}"; }

UNATTENDED=0
[ "$1" = "--unattended" ] && UNATTENDED=1

hdr "0/7 — Проверки окружения"

[ "$EUID" -eq 0 ] || err "Запусти через sudo"
[ "$(uname -m)" = "x86_64" ] || err "Требуется x86_64"

if [ -f /etc/os-release ]; then
    . /etc/os-release
    case "$VERSION_ID" in
        22.04|24.04) ok "Ubuntu $VERSION_ID" ;;
        *) warn "Ubuntu $VERSION_ID — не тестировалось" ;;
    esac
fi

command -v curl >/dev/null || err "curl не установлен"
command -v git  >/dev/null || err "git не установлен"

curl -fsS --max-time 5 https://github.com >/dev/null || err "Нет доступа к github.com"
ok "Интернет доступен"

[ -f admins.env ] || err "admins.env не найден"
[ -d certs ] || err "certs/ не найден"
[ -d cryptopro-dist ] || err "cryptopro-dist/ не найден"
[ -f cryptopro-dist/linux-amd64_deb.tgz ] || err "cryptopro-dist/linux-amd64_deb.tgz не найден"

# admins.env не пустой (есть хотя бы одна строка CN|SNILS)
admin_lines=$(grep -v '^#' admins.env | grep -v '^$' | wc -l)
[ "$admin_lines" -gt 0 ] || err "admins.env содержит только комментарии — добавь хотя бы одного админа (CN|SNILS)"
ok "admins.env: $admin_lines администратор(ов)"

# certs содержит хотя бы один PEM/CRT/CER
cert_files=$(find certs -maxdepth 1 -type f \( -name '*.cer' -o -name '*.crt' -o -name '*.pem' \) 2>/dev/null | wc -l)
[ "$cert_files" -gt 0 ] || err "certs/ не содержит .cer/.crt/.pem файлов"
ok "certs/: $cert_files сертификат(ов)"

# Проверим, что хотя бы один .cer/.crt парсится как X.509
valid_certs=0
for f in certs/*.cer certs/*.crt certs/*.pem; do
    [ -f "$f" ] || continue
    if openssl x509 -in "$f" -noout 2>/dev/null; then
        valid_certs=$((valid_certs + 1))
    else
        warn "Не удалось прочитать $f как X.509"
    fi
done
[ "$valid_certs" -gt 0 ] || err "Ни один сертификат в certs/ не является валидным X.509"
ok "Валидных сертификатов: $valid_certs"

# Авто-загрузка корневых сертификатов УЦ (где есть стабильные URL)
log "Попытка скачать корневые сертификаты известных УЦ"
mkdir -p certs

download_ca() {
    local name="$1" url="$2"
    local target="certs/$name"
    [ -f "$target" ] && { echo "  • $name уже есть"; return; }
    if curl -fsS --max-time 15 -o /tmp/ca-dl.cer "$url" 2>/dev/null; then
        if openssl x509 -in /tmp/ca-dl.cer -noout 2>/dev/null; then
            mv /tmp/ca-dl.cer "$target"
            echo "  ✓ $name"
        else
            rm -f /tmp/ca-dl.cer
            echo "  ✗ $name — не X.509"
        fi
    else
        echo "  ✗ $name — недоступен"
    fi
}

# НУЦ Минцифры
download_ca "russian_trusted_root_ca.pem"  "https://gu-st.ru/content/lending/russian_trusted_root_ca_pem.crt"
download_ca "russian_trusted_sub_ca.pem"   "https://gu-st.ru/content/lending/russian_trusted_sub_ca_pem.crt"

# Тензор
download_ca "tensor_ca_2021.cer"           "https://update.sbis.ru/report/cert/basic/tensorca-2021_gost2012.cer"

# Ростелеком
download_ca "rt_ca_root.crt"               "https://espd.wifi.rt.ru/docs/ca-root.crt"

# ФНС (корень ГУЦ)
download_ca "guc_root_2022.cer"            "http://root-cert.ru/docs/cer/mincifri.cer"

echo ""
echo "ℹ️  Если твой УЦ не в списке (Контур, Такском, СберКорус и т.п.):"
echo "   положи корневой сертификат руками в certs/ и перезапусти: afina restart"
echo ""

ok "admins.env, certs/, cryptopro-dist/ на месте"

# Свободное место (минимум 10 GB)
free_kb=$(df --output=avail / | tail -1 | tr -d ' ')
free_gb=$((free_kb / 1024 / 1024))
if [ "$free_gb" -lt 10 ]; then
    err "Свободно только ${free_gb} GB. Требуется минимум 10 GB"
fi
ok "Свободное место: ${free_gb} GB"

# Свободная память (минимум 2 GB)
mem_mb=$(free -m | awk '/Mem:/ {print $7}')
if [ "$mem_mb" -lt 1500 ]; then
    warn "Свободной памяти мало: ${mem_mb} MB (рекомендуется > 1.5 GB)"
fi
ok "Свободная память: ${mem_mb} MB"

if [ -f .gitignore ]; then
    grep -qE '^/?secrets' .gitignore || warn "secrets/ не в .gitignore — добавь!"
fi

[ "$UNATTENDED" = "1" ] && [ ! -f .env.install ] && err "Нужен .env.install"

ok "Окружение готово"
echo ""
echo "Готов к развёртыванию. Следующие шаги:"
echo "  1. Проверить домен и DNS"
echo "  2. Ответить на вопросы wizard"
echo "  3. Установка (5–15 мин)"

# ============================================================
# 1. Wizard — интерактивный опрос
# ============================================================
hdr "1/7 — Параметры развёртывания"

# ---- Домен ----
if [ "$UNATTENDED" = "1" ]; then
    . ./.env.install
else
    echo ""
    echo "Какой домен будет использовать система?"
    echo "Пример: afina.clientbank.ru"
    echo "⚠  Убедись, что A-запись домена указывает на этот сервер"
    echo -n "Домен: "
    read -r APP_DOMAIN
    [ -z "$APP_DOMAIN" ] && err "Домен обязателен"

    # Проверим DNS
    server_ip=$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo "?")
    domain_ip=$(dig +short "$APP_DOMAIN" 2>/dev/null | head -1)

    if [ "$server_ip" != "$domain_ip" ] && [ "$domain_ip" != "" ]; then
        warn "DNS: $APP_DOMAIN указывает на $domain_ip, а сервер — $server_ip"
        echo -n "Продолжить всё равно? [y/N]: "
        read -r ans
        [ "$ans" != "y" ] && err "Прервано"
    elif [ "$domain_ip" = "" ]; then
        warn "A-запись для $APP_DOMAIN не найдена"
    else
        ok "DNS: $APP_DOMAIN → $server_ip"
    fi
fi

# ---- SSL ----
if [ "$UNATTENDED" != "1" ]; then
    echo ""
    echo "SSL-сертификат:"
    echo "  1) Let's Encrypt (бесплатно, автоматически, нужен порт 80)"
    echo "  2) Свой сертификат (уже есть fullchain.pem + privkey.pem)"
    echo -n "Выбор [1/2]: "
    read -r ssl_choice

    if [ "$ssl_choice" = "2" ]; then
        TLS_MODE="custom"
        LE_EMAIL=""
        echo -n "Путь к fullchain.pem: "
        read -r SSL_FULLCHAIN
        echo -n "Путь к privkey.pem:   "
        read -r SSL_PRIVKEY

        [ -f "$SSL_FULLCHAIN" ] || err "Файл не найден: $SSL_FULLCHAIN"
        [ -f "$SSL_PRIVKEY" ]   || err "Файл не найден: $SSL_PRIVKEY"

        # Проверим, что ключ соответствует сертификату
        cert_mod=$(openssl x509 -noout -modulus -in "$SSL_FULLCHAIN" 2>/dev/null | md5sum | cut -d' ' -f1)
        key_mod=$(openssl rsa  -noout -modulus -in "$SSL_PRIVKEY"   2>/dev/null | md5sum | cut -d' ' -f1)
        if [ "$cert_mod" != "$key_mod" ]; then
            # Попробуем как EC-ключ
            key_mod=$(openssl ec -noout -modulus -in "$SSL_PRIVKEY" 2>/dev/null | md5sum | cut -d' ' -f1)
            if [ "$cert_mod" != "$key_mod" ]; then
                err "privkey.pem не соответствует fullchain.pem"
            fi
        fi
        ok "SSL: ключ и сертификат соответствуют друг другу"

        mkdir -p angie-certs
        cp "$SSL_FULLCHAIN" angie-certs/fullchain.pem
        cp "$SSL_PRIVKEY"   angie-certs/privkey.pem
        chmod 644 angie-certs/fullchain.pem
        chmod 600 angie-certs/privkey.pem

        # Проверим срок действия
        ssl_end=$(openssl x509 -in "$SSL_FULLCHAIN" -noout -enddate | cut -d= -f2)
        ssl_end_epoch=$(date -d "$ssl_end" +%s 2>/dev/null || echo 0)
        ssl_days=$(( (ssl_end_epoch - $(date +%s)) / 86400 ))
        if [ "$ssl_days" -lt 0 ]; then
            err "Сертификат уже истёк: $ssl_end"
        elif [ "$ssl_days" -lt 30 ]; then
            warn "Сертификат истекает через $ssl_days дней ($ssl_end)"
        else
            ok "Срок действия: $ssl_days дней"
        fi

        ok "SSL: свой сертификат скопирован в angie-certs/"
    else
        TLS_MODE="letsencrypt"
        echo -n "Email для Let's Encrypt (для уведомлений об истечении): "
        read -r LE_EMAIL
        [ -z "$LE_EMAIL" ] && err "Email обязателен для Let's Encrypt"
        ok "SSL: Let's Encrypt (автоматически)"
    fi
else
    # В unattended — из .env.install
    TLS_MODE="${TLS_MODE:-letsencrypt}"
    LE_EMAIL="${LE_EMAIL:-}"
fi

# ---- Порты ----
if [ "$UNATTENDED" != "1" ]; then
    echo ""
    echo "Порты (Enter = по умолчанию):"
    echo -n "HTTP-порт [80]: "
    read -r HTTP_PORT
    HTTP_PORT="${HTTP_PORT:-80}"

    echo -n "HTTPS-порт [443]: "
    read -r HTTPS_PORT
    HTTPS_PORT="${HTTPS_PORT:-443}"
else
    HTTP_PORT="${HTTP_PORT:-80}"
    HTTPS_PORT="${HTTPS_PORT:-443}"
fi

# Проверим занятость портов
for port in "$HTTP_PORT" "$HTTPS_PORT"; do
    if ss -tlnp 2>/dev/null | grep -q ":$port "; then
        warn "Порт $port занят"
        ss -tlnp 2>/dev/null | grep ":$port "
        echo -n "Продолжить? [y/N]: "
        read -r ans
        [ "$ans" != "y" ] && err "Прервано"
    fi
done
ok "Порты: HTTP=$HTTP_PORT, HTTPS=$HTTPS_PORT"

# ---- SMTP ----
if [ "$UNATTENDED" != "1" ]; then
    echo ""
    echo "SMTP — реквизиты для отправки писем (magic-link, алерты):"
    echo -n "SMTP host (например mail.clientbank.ru): "
    read -r MAIL_HOST
    [ -z "$MAIL_HOST" ] && err "SMTP host обязателен"

    echo -n "SMTP port [465]: "
    read -r MAIL_PORT
    MAIL_PORT="${MAIL_PORT:-465}"

    echo -n "SMTP username (обычно email): "
    read -r MAIL_USERNAME

    echo -n "SMTP password: "
    read -rs MAIL_PASSWORD
    echo ""
    [ -z "$MAIL_PASSWORD" ] && err "SMTP password обязателен"

    echo -n "Email отправителя (From) [$MAIL_USERNAME]: "
    read -r MAIL_FROM
    MAIL_FROM="${MAIL_FROM:-$MAIL_USERNAME}"

    echo -n "SSL (465) или STARTTLS (587)? [SSL]: "
    read -r smtp_enc
    if echo "$smtp_enc" | grep -qi starttls; then
        MAIL_SSL="false"
    else
        MAIL_SSL="true"
    fi
else
    MAIL_HOST="${MAIL_HOST}"
    MAIL_PORT="${MAIL_PORT:-465}"
    MAIL_USERNAME="${MAIL_USERNAME}"
    MAIL_PASSWORD="${MAIL_PASSWORD}"
    MAIL_FROM="${MAIL_FROM:-$MAIL_USERNAME}"
    MAIL_SSL="${MAIL_SSL:-true}"
fi
ok "SMTP: $MAIL_HOST:$MAIL_PORT ($MAIL_USERNAME)"

# ============================================================
# 2. Генерация секретов
# ============================================================
hdr "2/7 — Генерация секретов"

# ---- Пароли БД ----
if [ "$UNATTENDED" = "1" ]; then
    DB_SUPERUSER_PASSWORD="${DB_SUPERUSER_PASSWORD:-$(openssl rand -base64 24)}"
    DB_PASSWORD="${DB_PASSWORD:-$(openssl rand -base64 24)}"
    DB_MIGRATOR_PASSWORD="${DB_MIGRATOR_PASSWORD:-$(openssl rand -base64 24)}"
    DB_AUDITOR_PASSWORD="${DB_AUDITOR_PASSWORD:-$(openssl rand -base64 24)}"
else
    DB_SUPERUSER_PASSWORD=$(openssl rand -base64 24)
    DB_PASSWORD=$(openssl rand -base64 24)
    DB_MIGRATOR_PASSWORD=$(openssl rand -base64 24)
    DB_AUDITOR_PASSWORD=$(openssl rand -base64 24)
fi
ok "Пароли БД сгенерированы"

# ---- Ключи шифрования ----
mkdir -p secrets
chmod 700 secrets

if [ ! -f secrets/file.key ]; then
    openssl rand -base64 32 > secrets/file.key
    ok "secrets/file.key создан"
else
    warn "secrets/file.key уже существует — НЕ перезаписываю"
fi

if [ ! -f secrets/pii.key ]; then
    openssl rand -base64 32 > secrets/pii.key
    ok "secrets/pii.key создан"
else
    warn "secrets/pii.key уже существует — НЕ перезаписываю"
fi

# backup.pass — ключ AES-256 для шифрования бэкапов БД
if [ ! -f secrets/backup.pass ]; then
    openssl rand -base64 32 | tr -d '\n' > secrets/backup.pass
    ok "secrets/backup.pass создан"
else
    warn "secrets/backup.pass уже существует — НЕ перезаписываю"
fi

# Владелец — uid контейнера (999:999)
chown -R 999:999 secrets 2>/dev/null || true
chmod 600 secrets/*.key secrets/backup.pass

ok "Ключи шифрования готовы (file.key, pii.key, backup.pass)"

# ============================================================
# 3. Формирование .env.prod
# ============================================================
hdr "3/7 — Формирование .env.prod"

if [ -f .env.prod ]; then
    warn ".env.prod уже существует — сохраняю старый как .env.prod.bak.$(date +%Y%m%d_%H%M%S)"
    cp .env.prod ".env.prod.bak.$(date +%Y%m%d_%H%M%S)"
fi

cat > .env.prod <<ENVEOF
# Автоматически сгенерировано install.sh $(date -Iseconds)

APP_DOMAIN=$APP_DOMAIN
APP_BASE_URL=https://$APP_DOMAIN
SECURE_COOKIES=true

HTTP_PORT=$HTTP_PORT
HTTPS_PORT=$HTTPS_PORT

TLS_MODE=$TLS_MODE

DB_NAME=afina_db
DB_SUPERUSER=postgres
DB_SUPERUSER_PASSWORD=$DB_SUPERUSER_PASSWORD

DB_USER=afina_app
DB_PASSWORD=$DB_PASSWORD

DB_MIGRATOR_USER=afina_migrator
DB_MIGRATOR_PASSWORD=$DB_MIGRATOR_PASSWORD

DB_AUDITOR_USER=afina_auditor
DB_AUDITOR_PASSWORD=$DB_AUDITOR_PASSWORD

ADMIN_IP_WHITELIST=127.0.0.1,::1,172.0.0.0/8

MAIL_MODE=smtp
MAIL_HOST=$MAIL_HOST
MAIL_PORT=$MAIL_PORT
MAIL_USERNAME=$MAIL_USERNAME
MAIL_PASSWORD=$MAIL_PASSWORD
MAIL_FROM=$MAIL_FROM
MAIL_SSL=$MAIL_SSL

CRYPTO_PRO_ENABLED=true
ENVEOF

chmod 600 .env.prod
ok ".env.prod создан"

# ============================================================
# 4. Установка системы
# ============================================================
hdr "4/7 — Установка системы (Docker, UFW, fail2ban)"

export DEBIAN_FRONTEND=noninteractive

log "apt update"
apt-get update -o Acquire::Retries=5 -qq

log "Базовые утилиты"
apt-get install -y -qq \
    ca-certificates curl gnupg lsb-release \
    openssl jq dnsutils ufw fail2ban tzdata

# ---- Docker ----
if ! command -v docker >/dev/null 2>&1; then
    log "Установка Docker"
    curl -fsSL https://get.docker.com | sh -s -- --quiet
    systemctl enable --now docker
    ok "Docker установлен"
else
    ok "Docker уже установлен"
fi

docker compose version >/dev/null 2>&1 || err "docker compose v2 не установлен"
ok "docker compose: $(docker compose version --short)"

# ---- UFW ----
log "Настройка firewall"
ufw --force reset >/dev/null 2>&1 || true
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp comment 'SSH' >/dev/null
ufw allow "$HTTP_PORT/tcp"  comment 'HTTP'  >/dev/null
ufw allow "$HTTPS_PORT/tcp" comment 'HTTPS' >/dev/null
ufw --force enable >/dev/null
ok "UFW: порты 22, $HTTP_PORT, $HTTPS_PORT открыты"

# ---- fail2ban ----
systemctl enable --now fail2ban >/dev/null 2>&1 || true
ok "fail2ban запущен"

# ============================================================
# 5. Сборка и запуск
# ============================================================
hdr "5/7 — Сборка и запуск (5–10 мин)"

log "Сборка образа"
docker compose -f docker-compose-prod.yml --env-file .env.prod build --no-cache app

log "Запуск стека"
docker compose -f docker-compose-prod.yml --env-file .env.prod up -d

log "Ожидание готовности (до 120 сек)"
ready=0
for i in $(seq 1 24); do
    sleep 5
    if curl -fsS --max-time 3 "http://localhost:8080/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
        ready=1
        ok "Приложение отвечает (${i}x5 сек)"
        break
    fi
    echo -n "."
done
echo ""

if [ "$ready" != "1" ]; then
    err "Приложение не поднялось за 120 сек. Проверь логи:
    docker compose -f docker-compose-prod.yml --env-file .env.prod logs app --tail 100"
fi

docker compose -f docker-compose-prod.yml --env-file .env.prod ps

# ============================================================
# 6. Cron
# ============================================================
hdr "6/7 — Настройка cron"

WORK_DIR="$(pwd)"

cat > /etc/cron.d/afina-backup <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 3 * * * root cd $WORK_DIR && ./backup-prod.sh >> $WORK_DIR/logs/backup.log 2>&1
CRONEOF

cat > /etc/cron.d/afina-cleanup-all <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 4,16 * * * root cd $WORK_DIR && ./cleanup-all.sh >> $WORK_DIR/logs/cleanup.log 2>&1
CRONEOF

cat > /etc/cron.d/afina-crl-cleanup <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 4 * * 0 root cd $WORK_DIR && ./cleanup-crls.sh >> $WORK_DIR/logs/crl-cleanup.log 2>&1
CRONEOF

cat > /etc/cron.d/afina-monitor <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
*/5 * * * * root cd $WORK_DIR && ./monitor.sh >/dev/null 2>&1
CRONEOF

cat > /etc/cron.d/afina-ssl-check <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 9 * * 1 root cd $WORK_DIR && ./check-ssl.sh >> $WORK_DIR/logs/ssl-check.log 2>&1
CRONEOF

chmod 644 /etc/cron.d/afina-*
systemctl restart cron
ok "Cron: 5 задач установлены"

# ---- Установка CLI afina ----
if [ -f afina.sh ]; then
    ln -sf "$WORK_DIR/afina.sh" /usr/local/bin/afina
    chmod +x afina.sh
    ok "CLI: /usr/local/bin/afina"
fi

# ============================================================
# 7. Post-install тесты
# ============================================================
hdr "7/7 — Тесты"

# ---- 1. Health ----
log "Health check"
sleep 5
if curl -fsS --max-time 10 "https://$APP_DOMAIN/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    ok "HTTPS /actuator/health: UP"
    HTTPS_OK=1
else
    warn "HTTPS не отвечает — Angie может ещё получать сертификат"
    warn "Проверь: docker compose logs angie | tail -20"
    HTTPS_OK=0
fi

# ---- 2. Ключи ----
log "Проверка ключей"
for k in file.key pii.key; do
    if [ -f "secrets/$k" ] && [ "$(stat -c '%s' secrets/$k)" = "45" ]; then
        ok "$k — OK"
    else
        err "$k — проблема"
    fi
done

# ---- 3. БД ----
log "Проверка БД"
users_count=$(docker exec afina-postgres psql -U postgres -d afina_db -t -A -c "SELECT count(*) FROM users" 2>/dev/null || echo "0")
mig_ok=$(docker exec afina-postgres psql -U postgres -d afina_db -t -A -c "SELECT count(*) FROM flyway_schema_history WHERE success=false" 2>/dev/null || echo "?")
ok "Пользователи: $users_count, миграций с ошибками: $mig_ok"

# ---- 4. SMTP ----
log "Отправка тестового письма на $MAIL_FROM"
docker exec afina-app sh -c "curl -X POST -u admin:cert http://localhost:8080/admin/settings/test-email -d 'to=$MAIL_FROM'" >/dev/null 2>&1 || true
ok "Тестовое письмо: проверь $MAIL_FROM (включая папку Спам)"

# ---- 5. Логи ----
log "Проверка логов на ошибки"
errors=$(docker compose -f docker-compose-prod.yml --env-file .env.prod logs app 2>&1 | grep -c " ERROR " || true)
if [ "$errors" -eq 0 ]; then
    ok "Ошибок в логах нет"
else
    warn "В логах $errors ошибок — проверь: afina logs app"
fi

# ============================================================
# Финал
# ============================================================
hdr "✅ Развёртывание завершено"

echo ""
echo -e "${BOLD}Информация для сохранения в 1Password / KeePass:${N}"
echo ""
echo "  URL:              https://$APP_DOMAIN"
echo "  Админ-вход:       https://$APP_DOMAIN/admin/login"
echo "  Admin-IP:         admin доступен только с localhost, используй SSH-туннель"
echo ""
echo "  DB superuser:     postgres / $DB_SUPERUSER_PASSWORD"
echo "  DB app:           afina_app / $DB_PASSWORD"
echo "  DB migrator:      afina_migrator / $DB_MIGRATOR_PASSWORD"
echo "  DB auditor:       afina_auditor / $DB_AUDITOR_PASSWORD"
echo ""
echo "  file.key:         $(cat secrets/file.key)"
echo "  pii.key:          $(cat secrets/pii.key)"
echo "  backup.pass:      $(cat secrets/backup.pass)"
echo ""
echo -e "${R}${BOLD}⚠️  СЕЙЧАС ЖЕ:${N}"
echo "  1. Сохрани пароли БД выше в менеджер паролей"
echo "  2. Сохрани file.key, pii.key и backup.pass в менеджер паролей"
echo "     + на бумаге в сейфе"
echo "  3. Без этих ключей данные и бэкапы восстановить НЕВОЗМОЖНО"
echo ""
echo -e "${BOLD}Восстановление бэкапа:${N}"
echo "  ./restore-prod.sh backups/afina_*.sql.gz.enc"
echo ""
echo -e "${BOLD}Управление:${N}"
echo "  afina status         — состояние"
echo "  afina doctor         — полная диагностика"
echo "  afina logs app -f    — логи"
echo "  afina backup         — бэкап"
echo "  afina help           — все команды"
echo ""
echo -e "${BOLD}SSH-туннель для админки:${N}"
echo "  ssh -L 8080:localhost:8080 root@$(hostname -I | awk '{print $1}')"
echo "  Открой: http://localhost:8080/admin/login"
echo ""
if [ "$HTTPS_OK" = "1" ]; then
    ok "Система готова к работе"
else
    warn "Проверь Angie через 30 секунд: afina logs angie | tail -20"
fi
