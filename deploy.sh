#!/usr/bin/env bash
# ============================================================
# Афина · Развёртывание на подготовленном VPS
# ============================================================
# Использует существующий .env.prod (без wizard).
# Для чистого сервера с интерактивным wizard → install.sh
#
# Требования:
#   - Ubuntu 22.04/24.04 LTS (x86_64), root/sudo
#   - .env.prod, admins.env, certs/, cryptopro-dist/
# ============================================================

set -euo pipefail
cd "$(dirname "$0")"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'; BOLD='\033[1m'
log()  { echo -e "${BLUE}▶ $*${NC}"; }
ok()   { echo -e "${GREEN}✅ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠️  $*${NC}"; }
err()  { echo -e "${RED}❌ $*${NC}"; exit 1; }
hdr()  { echo -e "\n${BOLD}▶ $*${NC}"; }

# ============================================================
# 0. Preflight
# ============================================================
hdr "0/8 — Проверка окружения"
[ "$EUID" -eq 0 ] || err "Запусти через sudo"
[ "$(uname -m)" = "x86_64" ] || err "Требуется x86_64 (CryptoPro не соберётся на ARM)"

[ -f .env.prod ] || err ".env.prod не найден (скопируй из .env.prod.example или запусти install.sh)"
[ -f admins.env ] || err "admins.env не найден"
[ -f cryptopro-dist/linux-amd64_deb.tgz ] || err "cryptopro-dist/linux-amd64_deb.tgz не найден"

# admins.env непустой
admin_lines=$(grep -v '^#' admins.env | grep -v '^$' | wc -l)
[ "$admin_lines" -gt 0 ] || err "admins.env пуст — добавь хотя бы одного админа (CN|SNILS)"
ok "admins.env: $admin_lines администратор(ов)"

# certs: хотя бы один валидный
[ -d certs ] || err "certs/ не найден"
valid_certs=0
for f in certs/*.cer certs/*.crt certs/*.pem; do
    [ -f "$f" ] || continue
    openssl x509 -in "$f" -noout 2>/dev/null && valid_certs=$((valid_certs + 1)) || warn "не X.509: $f"
done
[ "$valid_certs" -gt 0 ] || err "Ни один сертификат в certs/ не является валидным X.509"
ok "certs/: $valid_certs валидных сертификат(ов)"

# secrets/ не в git
if [ -f .gitignore ]; then
    grep -qE '^/?secrets' .gitignore || warn "secrets/ не в .gitignore — добавь!"
fi

# Свободное место
free_gb=$(( $(df --output=avail / | tail -1) / 1024 / 1024 ))
[ "$free_gb" -ge 10 ] || err "Свободно только ${free_gb} GB, нужно ≥10 GB"
ok "Свободно: ${free_gb} GB"

# Загружаем .env.prod
set -a; . ./.env.prod; set +a

# Порты 80/443
for port in "${HTTP_PORT:-80}" "${HTTPS_PORT:-443}"; do
    if ss -tlnp 2>/dev/null | grep -q ":$port "; then
        warn "Порт $port занят:"
        ss -tlnp 2>/dev/null | grep ":$port "
        echo -n "  Продолжить? [y/N]: "; read -r ans
        [ "$ans" = "y" ] || err "Прервано"
    fi
done
ok "Порты ${HTTP_PORT:-80}/${HTTPS_PORT:-443} свободны"

# TLS custom
if [ "${TLS_MODE:-letsencrypt}" = "custom" ]; then
    [ -f angie-certs/fullchain.pem ] || err "TLS_MODE=custom, но angie-certs/fullchain.pem не найден"
    [ -f angie-certs/privkey.pem ]   || err "TLS_MODE=custom, но angie-certs/privkey.pem не найден"
    chmod 644 angie-certs/fullchain.pem
    chmod 600 angie-certs/privkey.pem
    export TLS_DIRECTIVE="tls /certs/fullchain.pem /certs/privkey.pem"
    ok "TLS: custom"
else
    export TLS_DIRECTIVE=""
    ok "TLS: Let's Encrypt"
fi

# ============================================================
# 1. Обновление системы
# ============================================================
hdr "1/8 — apt update && upgrade"
export DEBIAN_FRONTEND=noninteractive
apt-get update -o Acquire::Retries=5 -qq
apt-get upgrade -y -qq
ok "Система обновлена"

# ============================================================
# 2. Docker
# ============================================================
hdr "2/8 — Docker"
if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh -s -- --quiet
    systemctl enable --now docker
    ok "Docker установлен"
else
    ok "Docker уже есть"
fi
docker compose version >/dev/null 2>&1 || err "docker compose v2 не установлен"
ok "docker compose: $(docker compose version --short)"

# ============================================================
# 3. Утилиты
# ============================================================
hdr "3/8 — Утилиты"
apt-get install -y -qq --no-install-recommends \
    ca-certificates curl gnupg lsb-release \
    openssl jq dnsutils iptables-persistent fail2ban tzdata
ok "Утилиты установлены"

# ============================================================
# 4. Firewall
# ============================================================
hdr "4/8 — Firewall (iptables)"
iptables -I INPUT -p tcp --dport 22  -j ACCEPT 2>/dev/null || true
iptables -I INPUT -p tcp --dport "${HTTP_PORT:-80}"  -j ACCEPT 2>/dev/null || true
iptables -I INPUT -p tcp --dport "${HTTPS_PORT:-443}" -j ACCEPT 2>/dev/null || true
iptables -I INPUT -s 172.18.0.0/16   -j ACCEPT 2>/dev/null || true
netfilter-persistent save 2>/dev/null || iptables-save > /etc/iptables/rules.v4
systemctl enable --now fail2ban >/dev/null 2>&1 || true
ok "Firewall + fail2ban"

# ============================================================
# 5. Параметры ядра
# ============================================================
hdr "5/8 — Параметры ядра"
if ! grep -q '^vm.overcommit_memory' /etc/sysctl.conf; then
    cat >> /etc/sysctl.conf <<'SYSCTL'
vm.overcommit_memory = 1
vm.swappiness = 1
SYSCTL
    sysctl -p >/dev/null
fi
ok "Ядро настроено"

# ============================================================
# 6. Каталоги + секреты
# ============================================================
hdr "6/8 — Каталоги и секреты"

mkdir -p storage/documents logs backups crls certs secrets
chmod 700 secrets

# Владелец — uid контейнера (afina, 999:999)
chown -R 999:999 storage logs backups secrets crls 2>/dev/null \
    || chown -R 1000:1000 storage logs backups secrets crls 2>/dev/null || true
chmod -R 775 storage logs backups crls

# file.key
if [ ! -f secrets/file.key ]; then
    openssl rand -base64 32 > secrets/file.key
    ok "secrets/file.key создан"
else
    warn "secrets/file.key уже существует — НЕ перезаписываю"
fi

# pii.key
if [ ! -f secrets/pii.key ]; then
    openssl rand -base64 32 > secrets/pii.key
    ok "secrets/pii.key создан"
else
    warn "secrets/pii.key уже существует — НЕ перезаписываю"
fi

# backup.pass — AES-256 ключ для бэкапов
if [ ! -f secrets/backup.pass ]; then
    openssl rand -base64 32 | tr -d '\n' > secrets/backup.pass
    ok "secrets/backup.pass создан"
else
    warn "secrets/backup.pass уже существует — НЕ перезаписываю"
fi

chmod 600 secrets/*.key secrets/backup.pass
chown 999:999 secrets/*.key secrets/backup.pass 2>/dev/null || true

ok "Каталоги: storage, logs, backups, crls, secrets"

# ============================================================
# 7. Сборка и запуск
# ============================================================
hdr "7/8 — Сборка и запуск (5–15 мин)"

log "Сборка образа"
docker compose -f docker-compose-prod.yml --env-file .env.prod build --no-cache app

log "Запуск стека"
docker compose -f docker-compose-prod.yml --env-file .env.prod up -d

log "Ожидание готовности (до 120 сек)"
ready=0
for i in $(seq 1 24); do
    sleep 5
    if curl -fsS --max-time 3 "http://localhost:8080/actuator/health" 2>/dev/null \
            | grep -q '"status":"UP"'; then
        ready=1
        ok "Приложение отвечает (${i}x5 сек)"
        break
    fi
    echo -n "."
done
echo ""

if [ "$ready" != "1" ]; then
    err "Приложение не поднялось за 120 сек. Логи:
    docker compose -f docker-compose-prod.yml --env-file .env.prod logs app --tail 100"
fi

docker compose -f docker-compose-prod.yml --env-file .env.prod ps

# ============================================================
# 8. Cron, CLI, post-tests
# ============================================================
hdr "8/8 — Cron, CLI, тесты"
WORK_DIR="$(pwd)"

# Cron: backup (3:00)
cat > /etc/cron.d/afina-backup <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 3 * * * root cd $WORK_DIR && ./backup-prod.sh >> $WORK_DIR/logs/backup.log 2>&1
CRONEOF

# Cron: cleanup-all (4:30, 16:30)
cat > /etc/cron.d/afina-cleanup-all <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 4,16 * * * root cd $WORK_DIR && ./cleanup-all.sh >> $WORK_DIR/logs/cleanup.log 2>&1
CRONEOF

# Cron: crl-cleanup (вс 4:00)
cat > /etc/cron.d/afina-crl-cleanup <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 4 * * 0 root cd $WORK_DIR && ./cleanup-crls.sh >> $WORK_DIR/logs/crl-cleanup.log 2>&1
CRONEOF

# Cron: monitor (каждые 5 мин)
cat > /etc/cron.d/afina-monitor <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
*/5 * * * * root cd $WORK_DIR && ./monitor.sh >/dev/null 2>&1
CRONEOF

# Cron: ssl-check (пн 9:00)
cat > /etc/cron.d/afina-ssl-check <<CRONEOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
0 9 * * 1 root cd $WORK_DIR && ./check-ssl.sh >> $WORK_DIR/logs/ssl-check.log 2>&1
CRONEOF

chmod 644 /etc/cron.d/afina-*
systemctl restart cron
ok "Cron: 5 задач установлены"

# CLI afina
if [ -f afina.sh ]; then
    chmod +x afina.sh
    ln -sf "$WORK_DIR/afina.sh" /usr/local/bin/afina
    ok "CLI: /usr/local/bin/afina"
fi

# Post-tests
log "Проверка ключей"
for k in file.key pii.key; do
    [ -f "secrets/$k" ] && [ "$(stat -c '%s' secrets/$k)" = "45" ] \
        && ok "$k — 45 B, OK" || err "$k — проблема"
done
[ -f secrets/backup.pass ] && ok "backup.pass — OK" || err "backup.pass — нет"

log "Проверка БД"
docker exec afina-postgres psql -U postgres -d afina_db -t -A -c \
    "SELECT count(*) FROM flyway_schema_history WHERE success=false" 2>/dev/null \
    | grep -q '^0$' && ok "Миграции: OK" || warn "Есть неуспешные миграции"

log "Ошибки в логах (последние)"
errors=$(docker compose -f docker-compose-prod.yml --env-file .env.prod logs app 2>&1 | grep -c " ERROR " || true)
[ "$errors" -eq 0 ] && ok "Ошибок нет" || warn "Найдено $errors ошибок — afina logs app"

# ============================================================
# Финал
# ============================================================
echo ""
echo -e "${GREEN}════════════════════════════════════════════════${NC}"
echo -e "${GREEN}✅ Развёртывание завершено${NC}"
echo -e "${GREEN}════════════════════════════════════════════════${NC}"
echo ""
echo "Адрес:              $(grep APP_BASE_URL .env.prod | cut -d= -f2)"
echo "Логи app:           docker compose -f docker-compose-prod.yml --env-file .env.prod logs -f app"
echo "Бэкап:              ./backup-prod.sh"
echo "Восстановить:       ./restore-prod.sh backups/afina_*.sql.gz.enc"
echo ""
echo -e "${BOLD}Управление:${NC}  afina status | afina doctor | afina logs app -f | afina help"
echo ""
echo -e "${BOLD}SSH-туннель для админки:${NC}"
echo "  ssh -L 8080:localhost:8080 root@$(hostname -I | awk '{print $1}')"
echo ""
