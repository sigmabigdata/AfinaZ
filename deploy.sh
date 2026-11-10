#!/usr/bin/env bash
# deploy.sh — единый скрипт развертывания AfinaZ на чистом сервере
#
# Использование:
#   ./deploy.sh <ip>              # спросит пароль
#   AFINA_PASS=xxx ./deploy.sh <ip>
#   ./deploy.sh <ip> <password>
#   AFINA_SSH_KEY=~/.ssh/id_rsa ./deploy.sh <ip>
#
# Что делает:
#   1. Проверяет наличие всех локальных файлов (images, certs, cryptopro-dist, admins.env)
#   2. Ждет, пока сервер поднимет SSH
#   3. Копирует исходники на сервер (rsync)
#   4. Копирует Docker-образы (1.6 ГБ) с retry-логикой
#   5. Запускает install.sh на сервере неинтерактивно
#   6. Показывает финальный статус

set -euo pipefail

G='\033[0;32m'; Y='\033[1;33m'; R='\033[0;31m'; B='\033[0;34m'; C='\033[0;36m'; N='\033[0m'; BOLD='\033[1m'
ok()   { echo -e "${G}✓${N} $*"; }
warn() { echo -e "${Y}⚠${N} $*"; }
err()  { echo -e "${R}✗${N} $*"; }
hdr()  { echo -e "\n${C}${BOLD}▶ $*${N}"; }
die()  { err "$*"; exit 1; }

# ─── Параметры ───
SERVER="${1:-}"
PASS="${2:-${AFINA_PASS:-}}"
SSH_KEY="${AFINA_SSH_KEY:-}"

if [ -z "$SERVER" ]; then
    echo "Использование: $0 <server-ip> [password]"
    echo "Или: AFINA_PASS=xxx $0 <ip>"
    echo "Или: AFINA_SSH_KEY=~/.ssh/id_rsa $0 <ip>"
    exit 1
fi

if [ -z "$SSH_KEY" ] && [ -z "$PASS" ]; then
    read -rsp "Пароль root@$SERVER: " PASS
    echo ""
fi

# ─── SSH/rsync helpers ───
SSH_BASE="ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ServerAliveInterval=30 -o ServerAliveCountMax=20"
RSYNC_SSH="$SSH_BASE"

if [ -n "$SSH_KEY" ]; then
    SSH_BASE="$SSH_BASE -i $SSH_KEY"
    RSYNC_SSH="$RSYNC_SSH -i $SSH_KEY"
elif [ -n "$PASS" ]; then
    if ! command -v sshpass &>/dev/null; then
        die "Установи sshpass: brew install hudochenkov/sshpass/sshpass\n   Или используй SSH-ключ: AFINA_SSH_KEY=~/.ssh/id_rsa $0 $SERVER"
    fi
    SSH_BASE="sshpass -p '$PASS' $SSH_BASE"
    RSYNC_SSH="sshpass -p '$PASS' $RSYNC_SSH"
fi

run_ssh() {
    eval "$SSH_BASE root@$SERVER \"\$@\""
}

# ─── 1. Проверка локальных файлов ───
hdr "Проверка локальных файлов"

[ -f install.sh ]              || die "install.sh не найден"
[ -f docker-compose-prod.yml ] || die "docker-compose-prod.yml не найден"
[ -d images ]                  || die "Папка images/ не найдена"
[ -d certs ]                   || die "Папка certs/ не найдена"
[ -d cryptopro-dist ]          || die "Папка cryptopro-dist/ не найдена"
[ -d axiom-dist ]              || die "Папка axiom-dist/ не найдена"

# Проверяем образы (find — безопасно для zsh)
# Нужно 2 обязательных образа: app + postgres. Angie тянется на сервере
APP_IMG="images/afinaz-app.tar.gz"
PG_IMG="images/afinaz-postgres.tar.gz"
for f in "$APP_IMG" "$PG_IMG"; do
    if [ ! -f "$f" ]; then
        die "Образ не найден: $f. Собери: docker save ... | gzip > $f"
    fi
done
ok "Образы (2 шт):"
echo "     $APP_IMG $(du -h "$APP_IMG" | cut -f1)"
echo "     $PG_IMG $(du -h "$PG_IMG" | cut -f1)"
echo "     angie — будет скачан на сервере с registry (правильная архитектура)"

# Предупреждаем если остался битый angie-архив
if [ -f "images/angie-1.12.3.tar.gz" ]; then
    warn "images/angie-1.12.3.tar.gz найден — игнорируется (тянем с registry)"
fi

# Проверяем сертификаты (find — работает и в bash, и в zsh)
CERT_COUNT=0
if [ -d certs ]; then
    CERT_COUNT=$(find certs -maxdepth 1 -type f \( -name '*.cer' -o -name '*.crt' -o -name '*.pem' \) | wc -l | tr -d ' ')
fi
if [ "${CERT_COUNT:-0}" -gt 0 ]; then
    ok "Сертификатов: $CERT_COUNT"
else
    warn "В certs/ нет сертификатов — проверка УКЭП не будет работать"
fi

# Проверяем cryptopro-dist (мягко)
if [ -f cryptopro-dist/linux-amd64_deb.tgz ]; then
    ok "cryptopro-dist/linux-amd64_deb.tgz найден"
else
    warn "cryptopro-dist/linux-amd64_deb.tgz не найден (не критично для offline)"
fi

# Проверяем admins.env — должна быть хотя бы одна РЕАЛЬНАЯ запись (не комментарий)
if [ ! -f admins.env ] || ! grep -qE "^[^#[:space:]].*\|.*" admins.env 2>/dev/null; then
    warn "admins.env содержит только комментарии — исправляю"
    cat > admins.env <<ADMEOF
# admins.env — администраторы (CN|SNILS)
Дырул Анатолий Юрьевич|18962570627
ADMEOF
    chmod 644 admins.env
fi
ADMIN_LINE=$(grep -E "^[^#[:space:]].*\|.*" admins.env | head -1)
ok "Администратор: $ADMIN_LINE"

# ─── 2. Ждем SSH ───
hdr "Ждем готовности сервера ($SERVER:22)"
for i in $(seq 1 60); do
    if nc -z -w 2 "$SERVER" 22 2>/dev/null; then
        ok "SSH доступен (попытка $i)"
        break
    fi
    [ $i -eq 60 ] && die "Сервер не отвечает за 10 минут"
    echo "  ждем 10 сек... (попытка $i/60)"
    sleep 10
done

# ─── 3. Копируем исходники ───
hdr "Копируем исходники"
rsync -avz --partial \
    --exclude '.git' --exclude 'target' --exclude '.idea' \
    --exclude 'secrets' --exclude '.env.prod' --exclude 'backups' \
    --exclude 'images' --exclude 'storage' --exclude 'logs' \
    -e "$RSYNC_SSH" \
    ./ root@$SERVER:/opt/afinaz/ \
    | tail -5
ok "Исходники скопированы"

# ─── 4. Копируем образы (с retry) ───
hdr "Копируем Docker-образы (1.6 ГБ)"
for attempt in 1 2 3 4 5; do
    echo -e "  ${B}▶ Попытка $attempt/5${N}"
    if rsync -avz --partial --progress \
        -e "$RSYNC_SSH" \
        images/ root@$SERVER:/opt/afinaz/images/ 2>&1 | tail -3; then
        # Проверяем, что все три файла на месте на сервере
        MISSING=$(run_ssh "cd /opt/afinaz/images 2>/dev/null && ls -1 *.tar.gz 2>/dev/null | wc -l" || echo "0")
        if [ "$MISSING" -ge 3 ]; then
            ok "Все три образа на сервере"
            break
        fi
    fi
    [ $attempt -eq 5 ] && die "Не удалось скопировать образы за 5 попыток"
    warn "Обрыв — повтор через 10 сек"
    sleep 10
done

# Финальная проверка образов на сервере
hdr "Проверка образов на сервере"
run_ssh "ls -lh /opt/afinaz/images/*.tar.gz" || die "Образы не найдены на сервере"

# ─── 5. Запуск install.sh на сервере ───
hdr "Запуск install.sh на сервере (неинтерактивно)"
echo "  Ответы: IP=$SERVER, TLS=2 (self-signed), Admin IP=*, Email=log, Images=2 (offline)"
echo ""

# Передаем ответы через stdin install.sh (НЕ через inline-команды!)
{
    printf "%s\n" "$SERVER"       # IP
    printf "2\n"                  # TLS = custom
    printf "*\n"                  # ADMIN_IP_WHITELIST
    printf "log\n"                # MAIL_MODE
    printf "2\n"                  # BUILD_MODE = offline
} | eval "$SSH_BASE root@$SERVER 'cd /opt/afinaz && chmod +x install.sh && TERM=xterm-256color ./install.sh'" \
    > /tmp/afina-deploy.log 2>&1 \
    && ok "install.sh выполнен" \
    || { err "install.sh упал. Последние 30 строк:"; tail -30 /tmp/afina-deploy.log; exit 1; }

# ─── 6. Финальный статус ───
hdr "Финальная проверка"
run_ssh "cd /opt/afinaz && docker compose -f docker-compose-prod.yml --env-file .env.prod ps"
echo ""
echo -e "${BOLD}▶ Health:${N}"
run_ssh "curl -sk https://localhost/actuator/health || echo 'HEALTH FAIL'"
echo ""
echo -e "${BOLD}▶ Администратор:${N}"
run_ssh "docker logs afina-app --tail 20 2>&1 | grep -i 'CN=' || echo 'не найден'"
echo ""
echo -e "${BOLD}▶ Angie (последние 5 строк):${N}"
run_ssh "docker logs afina-angie --tail 5"

echo ""
echo -e "${G}${BOLD}════════════════════════════════════════════════════════════${N}"
echo -e "${G}${BOLD}  ✅ РАЗВЕРТЫВАНИЕ ЗАВЕРШЕНО${N}"
echo -e "${G}${BOLD}════════════════════════════════════════════════════════════${N}"
echo ""
echo -e "  Сайт:  ${C}https://$SERVER/login${N}"
echo -e "  Админ: ${C}https://$SERVER/admin/login${N}"
echo ""
echo -e "  Лог установки на сервере: ${Y}/tmp/afina-install-*.log${N}"
echo -e "  Лог этого запуска:        ${Y}/tmp/afina-deploy.log${N}"
echo ""

