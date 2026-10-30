#!/usr/bin/env bash
# ============================================================
# ПАО "НБД-Банк" · Автоматический установщик (Astra Linux SE)
# ============================================================
# Использование:
#   1. Разложить файлы проекта в /opt/afinaz/
#   2. Положить: admins.env, certs/*.cer, cryptopro-dist/, axiom-dist/
#   3. Запустить: sudo ./install.sh
# ============================================================

set -euo pipefail

# ─── Цвета и утилиты ───────────────────────────────────────────
G='\033[0;32m'; Y='\033[1;33m'; R='\033[0;31m'
B='\033[0;34m'; C='\033[0;36m'; N='\033[0m'
BOLD='\033[1m'; DIM='\033[2m'

STEP=0
FAILED=0
LOG="/tmp/afina-install-$(date +%Y%m%d_%H%M%S).log"

log()     { echo "[$(date '+%H:%M:%S')] $*" >> "$LOG"; }
ok()      { echo -e "  ${G}✓${N} $*"; log "OK: $*"; }
warn()    { echo -e "  ${Y}⚠${N} $*"; log "WARN: $*"; }
err()     { echo -e "  ${R}✗${N} $*"; log "ERR: $*"; FAILED=$((FAILED+1)); }
hdr()     { STEP=$((STEP+1)); echo -e "\n${C}${BOLD}[$STEP] $*${N}"; log "STEP $STEP: $*"; }
die()     { echo -e "\n${R}${BOLD}❌ ОШИБКА: $*${N}"; echo "Полный лог: $LOG"; exit 1; }

ask() {
    local prompt="$1" default="$2" var
    if [ -n "$default" ]; then
        read -p "  $prompt [$default]: " var
        echo "${var:-$default}"
    else
        read -p "  $prompt: " var
        [ -z "$var" ] && die "Пустое значение недопустимо"
        echo "$var"
    fi
}

# ─── Старт ─────────────────────────────────────────────────────
clear
echo -e "${BOLD}══════════════════════════════════════════════════${N}"
echo -e "${BOLD}   ПАО «НБД-Банк» · Установка сервиса УКЭП         ${N}"
echo -e "${BOLD}══════════════════════════════════════════════════${N}"
echo -e "${DIM}Лог: $LOG${N}"

# ─── Проверка root ─────────────────────────────────────────────
hdr "Проверка прав и окружения"
if [ "$(id -u)" -ne 0 ]; then die "Запустите скрипт под root: sudo ./install.sh"; fi
ok "Запуск от root"

# Проверка ОС
if [ -f /etc/astra_version ]; then
    ASTRA_VER=$(cat /etc/astra_version 2>/dev/null || echo "?")
    ok "Astra Linux $ASTRA_VER"
elif [ -f /etc/debian_version ]; then
    DEB_VER=$(cat /etc/debian_version)
    warn "Это Debian $DEB_VER, не Astra Linux — для боевого контура не подходит, но установка продолжится"
else
    warn "Не удалось определить ОС (не Astra/Debian). Продолжаем."
fi

# Архитектура
ARCH=$(uname -m)
if [ "$ARCH" != "x86_64" ]; then
    die "Требуется x86_64 (КриптоПро не работает на ARM). Текущая: $ARCH"
fi
ok "Архитектура: x86_64"

# Проверка, что мы в папке проекта
if [ ! -f docker-compose-prod.yml ] || [ ! -f Dockerfile ]; then
    die "Скрипт запущен не в папке проекта. Нужны docker-compose-prod.yml и Dockerfile."
fi
ok "Файлы проекта найдены"

# ─── Проверка обязательных файлов ──────────────────────────────
hdr "Проверка обязательных файлов"
MISSING=0
for f in admins.env cryptopro-dist/linux-amd64_deb.tgz; do
    if [ ! -e "$f" ]; then
        err "Не найден: $f"
        MISSING=$((MISSING+1))
    fi
done

if [ ! -d certs ] || [ -z "$(ls -A certs 2>/dev/null)" ]; then
    warn "Папка certs/ пуста. Корневые сертификаты УЦ нужно будет добавить позже,"
    warn "иначе проверка подписи будет пропускать цепочку."
fi

if ls axiom-dist/*.tar.gz >/dev/null 2>&1; then
    ok "axiom-dist/*.tar.gz найден"
else
    warn "axiom-dist/*.tar.gz не найден — сборка будет использовать системный JDK (только для dev!)"
fi

if [ "$MISSING" -gt 0 ]; then
    die "Отсутствуют обязательные файлы (см. выше). Положите их в папку проекта."
fi
ok "Все обязательные файлы на месте"

# ─── Интерактивная конфигурация ────────────────────────────────
hdr "Параметры развертывания"
echo -e "  ${DIM}Оставьте пустым, чтобы использовать значение по умолчанию в [скобках]${N}"
echo ""

APP_DOMAIN=$(ask "Домен (например, afina.bank.ru)" "")
APP_BASE_URL="https://${APP_DOMAIN}"
echo "  → BASE_URL: $APP_BASE_URL"

echo ""
echo -e "  ${BOLD}Режим TLS:${N}"
echo "    1) Let's Encrypt (нужен открытый интернет и открытый 80 порт)"
echo "    2) Свой сертификат (файлы в angie-certs/: fullchain.pem + privkey.pem)"
TLS_MODE_CHOICE=$(ask "Выберите [1]" "1")
if [ "$TLS_MODE_CHOICE" = "2" ]; then
    TLS_MODE="custom"
    if [ ! -f angie-certs/fullchain.pem ] || [ ! -f angie-certs/privkey.pem ]; then
        die "В angie-certs/ нет fullchain.pem или privkey.pem"
    fi
    ok "TLS: custom (свой сертификат)"
else
    TLS_MODE="letsencrypt"
    ok "TLS: Let's Encrypt"
fi

echo ""
ADMIN_IP_WHITELIST=$(ask "IP-подсети админов (через запятую)" "127.0.0.1,::1,172.0.0.0/8")
echo "  → Whitelist: $ADMIN_IP_WHITELIST"

echo ""
MAIL_MODE=$(ask "Режим email (log/smtp) [log]" "log")
if [ "$MAIL_MODE" = "smtp" ]; then
    MAIL_HOST=$(ask "SMTP host" "smtp.bank.ru")
    MAIL_PORT=$(ask "SMTP port" "465")
    MAIL_USERNAME=$(ask "SMTP username (email)" "")
    MAIL_PASSWORD=$(ask "SMTP password" "")
    MAIL_FROM=$(ask "From (email)" "$MAIL_USERNAME")
    MAIL_SSL=$(ask "SSL (true для 465, false для 587+STARTTLS) [true]" "true")
else
    MAIL_HOST=""; MAIL_PORT=""; MAIL_USERNAME=""; MAIL_PASSWORD=""; MAIL_FROM="noreply@bank.ru"; MAIL_SSL="true"
    ok "Email: в режиме log (письма не отправляются, только пишутся в лог)"
fi

echo ""
echo -e "  ${BOLD}Режим установки Docker-образов:${N}"
echo "    1) Online — собрать из исходников (нужен интернет)"
echo "    2) Offline — загрузить из images/*.tar (для закрытого контура)"
BUILD_MODE=$(ask "Выберите [1]" "1")

# ─── Генерация секретов ────────────────────────────────────────
hdr "Генерация секретов"
mkdir -p secrets && chmod 700 secrets

if [ -f secrets/file.key ] && [ -f secrets/pii.key ] && [ -f secrets/backup.pass ]; then
    warn "Секреты уже существуют — НЕ перезаписываем (иначе потеряем данные!)"
    ok "Используем существующие ключи"
    KEEP_SECRETS=1
else
    KEEP_SECRETS=0
    [ ! -f secrets/file.key ]    && openssl rand -base64 32 > secrets/file.key
    [ ! -f secrets/pii.key ]     && openssl rand -base64 32 > secrets/pii.key
    [ ! -f secrets/backup.pass ] && openssl rand -base64 32 | tr -d '\n' > secrets/backup.pass
    chmod 600 secrets/*.key secrets/backup.pass 2>/dev/null || true
    ok "Ключи сгенерированы (file.key, pii.key, backup.pass)"
fi

# Выставляем права для контейнера (UID 999)
chown -R 999:999 secrets storage logs backups crls certs 2>/dev/null || warn "Не удалось chown secrets на 999:999 (возможно, UID не существует)"
chmod 700 secrets
chmod 600 secrets/*.key secrets/backup.pass 2>/dev/null || true

# ─── .env.prod ────────────────────────────────────────────────
hdr "Создание .env.prod"
if [ -f .env.prod ]; then
    warn ".env.prod уже существует. Перезаписываем..."
    cp .env.prod ".env.prod.bak.$(date +%s)"
    ok "Старый сохранён в .env.prod.bak.*"
fi

DB_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
DB_MIG_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
DB_AUD_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
DB_SUPER_PASS=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)

cat > .env.prod << EOF
# Автоматически сгенерировано $(date '+%Y-%m-%d %H:%M:%S')
# НЕ КОММИТИТЬ В GIT!

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
ok ".env.prod создан (chmod 600)"
chmod 644 angie-certs/* 2>/dev/null || true

# ─── Сохраняем пароли в отдельный файл для админа ──────────────
CREDS_FILE="install-credentials-$(date +%Y%m%d_%H%M%S).txt"
cat > "$CREDS_FILE" << EOF
════════════════════════════════════════════════════════
  ПАО "НБД-Банк" · Учетные данные (СОХРАНИТЕ И УДАЛИТЕ!)
  Создано: $(date '+%Y-%m-%d %H:%M:%S')
════════════════════════════════════════════════════════

БД (суперпользователь postgres):
  DB_SUPERUSER_PASSWORD = ${DB_SUPER_PASS}

БД (приложение):
  DB_USER     = afina_app
  DB_PASSWORD = ${DB_PASS}

БД (миграции Flyway):
  DB_MIGRATOR_USER     = afina_migrator
  DB_MIGRATOR_PASSWORD = ${DB_MIG_PASS}

БД (аудит):
  DB_AUDITOR_USER     = afina_auditor
  DB_AUDITOR_PASSWORD = ${DB_AUD_PASS}

Ключи шифрования (в secrets/):
  file.key    — AES-256 для файлов документов
  pii.key     — AES-256 для PII в БД
  backup.pass — AES-256-CBC для бэкапов

⚠️  ЭТОТ ФАЙЛ СОДЕРЖИТ ПАРОЛИ. Сохраните в 1Password/сейф
    и СРАЗУ УДАЛИТЕ с сервера: shred -u $CREDS_FILE

════════════════════════════════════════════════════════
EOF
chmod 600 "$CREDS_FILE"
warn "Учетные данные сохранены в: $CREDS_FILE (сохраните и удалите!)"

# ─── Docker ───────────────────────────────────────────────────
hdr "Проверка Docker"
if ! command -v docker &>/dev/null; then
    die "Docker не установлен. Установите: apt install docker.io docker-compose-plugin"
fi
ok "Docker: $(docker --version)"

if ! docker compose version &>/dev/null; then
    die "Docker Compose plugin (v2) не установлен. Установите: apt install docker-compose-plugin"
fi
ok "Docker Compose: $(docker compose version --short)"

if ! docker info &>/dev/null; then
    die "Docker daemon не запущен. Запустите: systemctl start docker"
fi
ok "Docker daemon работает"

# ─── Порты ────────────────────────────────────────────────────

# ─── Освобождение портов 80 и 443 ──────────────────────────────
hdr "Освобождение портов"
for port in 80 443; do
    if ss -tlnp 2>/dev/null | grep -q ":${port} "; then
        warn "Порт ${port} занят. Пытаюсь остановить службы-конкуренты..."
        for svc in nginx apache2 ihttpd; do
            systemctl stop $svc 2>/dev/null || true
            systemctl disable $svc 2>/dev/null || true
        done
        # Если все еще занят (например, docker-proxy от старых запусков)
        if ss -tlnp 2>/dev/null | grep -q ":${port} "; then
            die "Порт ${port} все еще занят сторонним процессом. Освободите его вручную."
        fi
    fi
done
ok "Порты 80 и 443 свободны"

# ─── Проверка портов (упрощенно) ──────────────────────────────
hdr "Проверка портов"
for port in 80 443; do
    if ss -tlnp 2>/dev/null | grep -q ":${port} " && \
       ! docker ps --format '{{.Ports}}' 2>/dev/null | grep -q ":${port}->"; then
        warn "Порт ${port} занят другим процессом:"
        ss -tlnp 2>/dev/null | grep ":${port} " || true
        die "Освободите порт ${port} перед установкой"
    fi
done
ok "Порты 80 и 443 свободны"

# ─── Скачивание/сборка образов ────────────────────────────────
hdr "Подготовка Docker-образов"
if [ "$BUILD_MODE" = "2" ]; then
    # Offline-режим
    for img in afinaz-app.tar afinaz-postgres.tar angie.tar; do
        if [ ! -f "images/$img" ]; then
            die "Не найден images/$img — загрузите docker save-архивы"
        fi
    done
    echo -e "  ${B}▶ Загрузка образов из images/*.tar...${N}"
    for img in images/*.tar; do
        docker load < "$img" | tail -1
    done
    ok "Образы загружены"
else
    # Online-режим
    echo -e "  ${B}▶ Сборка app (может занять 5-10 минут)...${N}"
    if ! docker compose -f docker-compose-prod.yml --env-file .env.prod build --no-cache app 2>&1 | tee -a "$LOG" | tail -3; then
        die "Сборка app не удалась. Проверьте лог: $LOG"
    fi
    ok "Образ app собран"

    echo -e "  ${B}▶ Сборка postgres...${N}"
    if ! docker compose -f docker-compose-prod.yml --env-file .env.prod build postgres 2>&1 | tee -a "$LOG" | tail -3; then
        die "Сборка postgres не удалась"
    fi
    ok "Образ postgres собран"

    echo -e "  ${B}▶ Загрузка Angie из registry...${N}"
    if ! docker compose -f docker-compose-prod.yml --env-file .env.prod pull angie 2>&1 | tail -2; then
        die "Не удалось скачать Angie"
    fi
    ok "Образ Angie загружен"
fi

# ─── Запуск стека ─────────────────────────────────────────────
hdr "Запуск стека"
echo -e "  ${B}▶ docker compose up -d...${N}"
if ! docker compose -f docker-compose-prod.yml --env-file .env.prod up -d 2>&1 | tee -a "$LOG" | tail -5; then
    die "docker compose up не удался. Лог: $LOG"
fi
ok "Контейнеры запущены"

# Ждём старта приложения
echo -e "  ${DIM}Ожидание готовности (макс 120 секунд)...${N}"
WAIT=0
HEALTH_OK=0
while [ $WAIT -lt 120 ]; do
    if docker exec afina-app curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        HEALTH_OK=1
        break
    fi
    sleep 3
    WAIT=$((WAIT+3))
done

if [ $HEALTH_OK -eq 0 ]; then
    echo -e "\n${R}Приложение не поднялось за 120 секунд. Последние логи:${N}"
    docker logs afina-app --tail 50
    die "Проверьте логи выше"
fi
ok "Приложение запущено за ~${WAIT}с"

# ─── ТЕСТЫ ────────────────────────────────────────────────────
hdr "Проверки после установки"

# 1. Healthcheck локальный
if docker exec afina-app curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
    ok "Healthcheck локальный: UP"
else
    err "Healthcheck локальный: FAIL"
fi

# 2. Healthcheck внешний
if [ "$TLS_MODE" = "letsencrypt" ]; then
    EXTERNAL_URL="https://${APP_DOMAIN}/actuator/health"
    sleep 10
    if curl -skf "$EXTERNAL_URL" --max-time 15 | grep -q '"status":"UP"'; then
        ok "Healthcheck внешний ($APP_DOMAIN): UP"
    else
        warn "Healthcheck внешний ($APP_DOMAIN): не отвечает (может быть, DNS ещё не обновился или TLS не готов)"
    fi
else
    ok "Healthcheck внешний: пропущен (custom TLS — проверьте через браузер)"
fi

# 3. Все контейнеры healthy/running
CONTAINERS_OK=1
for c in afina-postgres afina-app afina-angie; do
    STATUS=$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null || echo "not_found")
    if [ "$STATUS" = "running" ]; then
        ok "Контейнер $c: running"
    else
        err "Контейнер $c: $STATUS"
        CONTAINERS_OK=0
    fi
done

# 4. Миграции Flyway
MIGRATION_BAD=$(docker exec afina-postgres psql -U postgres -d afina_db -tAc \
    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false" 2>/dev/null || echo "query_failed")
if [ "$MIGRATION_BAD" = "0" ]; then
    MIGRATIONS_TOTAL=$(docker exec afina-postgres psql -U postgres -d afina_db -tAc \
        "SELECT COUNT(*) FROM flyway_schema_history" 2>/dev/null)
    ok "Миграции Flyway: $MIGRATIONS_TOTAL успешны, 0 ошибок"
elif [ "$MIGRATION_BAD" = "query_failed" ]; then
    err "Миграции Flyway: не удалось выполнить запрос"
else
    err "Миграции Flyway: $MIGRATION_BAD провалов!"
fi

# 5. Таблицы БД созданы
TABLES=$(docker exec afina-postgres psql -U postgres -d afina_db -tAc \
    "SELECT COUNT(*) FROM pg_tables WHERE schemaname='public'" 2>/dev/null || echo "0")
if [ "$TABLES" -ge 15 ]; then
    ok "Таблицы БД: $TABLES шт."
else
    err "Таблицы БД: только $TABLES (ожидалось минимум 15)"
fi

# 6. Роли БД
ROLES=$(docker exec afina-postgres psql -U postgres -d afina_db -tAc \
    "SELECT COUNT(*) FROM pg_roles WHERE rolname IN ('afina_app','afina_migrator','afina_auditor')" 2>/dev/null || echo "0")
if [ "$ROLES" = "3" ]; then
    ok "Роли БД: afina_app, afina_migrator, afina_auditor — все 3 созданы"
else
    err "Роли БД: только $ROLES из 3"
fi

# 7. Расширения
EXTS=$(docker exec afina-postgres psql -U postgres -d afina_db -tAc \
    "SELECT COUNT(*) FROM pg_extension WHERE extname IN ('pgcrypto','uuid-ossp','pg_stat_statements')" 2>/dev/null || echo "0")
if [ "$EXTS" -ge 2 ]; then
    ok "Расширения БД: pgcrypto, uuid-ossp установлены"
else
    warn "Расширения БД: только $EXTS из 3 (pg_stat_statements может отсутствовать в контейнере)"
fi

# 8. Ключи шифрования загружены
if docker logs afina-app 2>&1 | grep -q "FileEncryptor: ключ загружен"; then
    ok "FileEncryptor: ключ загружен"
else
    err "FileEncryptor: ключ НЕ загружен"
fi
if docker logs afina-app 2>&1 | grep -q "PiiEncryptor: ключ загружен"; then
    ok "PiiEncryptor: ключ загружен"
else
    err "PiiEncryptor: ключ НЕ загружен"
fi

# 9. КриптоПро (JCSP)
if docker logs afina-app 2>&1 | grep -qE "JCSP provider registered|CryptoPro JCSP"; then
    ok "CryptoPro JCSP: инициализирован"
else
    warn "CryptoPro JCSP: не найден в логах (проверьте вручную)"
fi

# 10. Angie отвечает на /login
if curl -skf "https://${APP_DOMAIN}/login" --max-time 10 -o /dev/null -w "%{http_code}" 2>/dev/null | grep -qE "200|302"; then
    ok "Angie: /login отвечает 200/302"
else
    warn "Angie: /login не отвечает (проверьте DNS + сертификат)"
fi

# 11. Админ-панель отвечает
if curl -skf "https://${APP_DOMAIN}/admin/login" --max-time 10 -o /dev/null -w "%{http_code}" 2>/dev/null | grep -qE "200|403"; then
    ok "Админ-панель: отвечает"
else
    warn "Админ-панель: не отвечает (проверьте IP-whitelist: $ADMIN_IP_WHITELIST)"
fi

# ─── CLI-меню ─────────────────────────────────────────────────
hdr "Установка CLI-меню"
if [ -f install-afina-cli.sh ]; then
    bash install-afina-cli.sh
    ok "CLI: /usr/local/bin/afina"
else
    warn "Файл install-afina-cli.sh не найден — пропускаем"
fi

# ─── Cron ─────────────────────────────────────────────────────
hdr "Настройка cron-задач"
mkdir -p /etc/cron.d

cat > /etc/cron.d/afina-backup <<'CRON'
# Бэкап БД + файлов (ежедневно в 3:00)
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 3 * * * root cd /opt/afinaz && ./backup-prod.sh >> /var/log/afina/backup.log 2>&1
CRON

cat > /etc/cron.d/afina-monitor <<'CRON'
# Внешний мониторинг (каждые 5 минут)
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
*/5 * * * * root cd /opt/afinaz && ./monitor.sh >> /var/log/afina/monitor.log 2>&1
CRON

cat > /etc/cron.d/afina-cleanup <<'CRON'
# Очистка Docker + CRL (ежедневно в 4:30 и 16:30)
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 4,16 * * * root cd /opt/afinaz && ./cleanup-all.sh >> /var/log/afina/cleanup.log 2>&1
CRON

cat > /etc/cron.d/afina-ssl-check <<'CRON'
# Проверка SSL-сертификата (по понедельникам в 9:00)
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 9 * * 1 root cd /opt/afinaz && ./check-ssl.sh >> /var/log/afina/ssl.log 2>&1
CRON

chmod 644 /etc/cron.d/afina-*
mkdir -p /var/log/afina
ok "Cron: backup, monitor, cleanup, ssl"

# ─── UFW (если установлен) ────────────────────────────────────
hdr "Настройка firewall"
if command -v ufw &>/dev/null; then
    ufw allow 22/tcp comment 'SSH' >/dev/null 2>&1 || true
    ufw allow 80/tcp comment 'HTTP' >/dev/null 2>&1 || true
    ufw allow 443/tcp comment 'HTTPS' >/dev/null 2>&1 || true
    ok "UFW: разрешены порты 22, 80, 443"
else
    warn "UFW не установлен — настройте firewall вручную"
fi

# ─── Итоговый отчёт ──────────────────────────────────────────
echo ""
echo -e "${BOLD}══════════════════════════════════════════════════${N}"
if [ $FAILED -eq 0 ]; then
    echo -e "${G}${BOLD}   ✅ УСТАНОВКА ЗАВЕРШЕНА УСПЕШНО                 ${N}"
else
    echo -e "${Y}${BOLD}   ⚠ УСТАНОВКА ЗАВЕРШЕНА С $FAILED ЗАМЕЧАНИЯМИ   ${N}"
fi
echo -e "${BOLD}══════════════════════════════════════════════════${N}"
echo ""
echo -e "  ${BOLD}Веб-интерфейс:${N}"
echo -e "    Клиент:  ${C}https://${APP_DOMAIN}/login${N}"
echo -e "    Админ:   ${C}https://${APP_DOMAIN}/admin/login${N}  (только с IP: $ADMIN_IP_WHITELIST)"
echo ""
echo -e "  ${BOLD}Учётные данные:${N}"
echo -e "    ${R}Сохранены в: $CREDS_FILE${N}"
echo -e "    ${R}Сохраните их в 1Password и удалите: shred -u $CREDS_FILE${N}"
echo ""
echo -e "  ${BOLD}Ключи шифрования (КРИТИЧНО!):${N}"
echo -e "    secrets/file.key, secrets/pii.key, secrets/backup.pass"
echo -e "    ${R}Потеря = невозможность расшифровать данные. Сохраните в сейф!${N}"
echo ""
echo -e "  ${BOLD}Управление:${N}"
echo -e "    ${C}afina${N}  — интерактивное меню (деплой/логи/бэкап/restore)"
echo ""
echo -e "  ${BOLD}Логи установки:${N} $LOG"
echo -e "${BOLD}══════════════════════════════════════════════════${N}"

if [ $FAILED -gt 0 ]; then
    echo ""
    echo -e "${Y}Проверьте замечания выше. Полный лог: $LOG${N}"
    exit 1
fi
exit 0