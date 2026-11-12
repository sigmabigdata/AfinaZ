#!/usr/bin/env bash
# ============================================================
# AfinaZ · Сборка дистрибутива на Mac
# ============================================================
# Готовит .tar.gz для переноса на целевой сервер (x86_64 Linux)
# с предсобранными Docker-образами (amd64) — сборка на сервере не требуется.
#
# Требования к Mac:
#   • Docker Desktop (с Buildx)
#   • JDK 17 + Maven
#   • Apple Silicon: работает через --platform linux/amd64 (медленнее, ~10-15 мин build)
#
# Использование:
#   ./build-dist.sh                    # полная сборка
#   ./build-dist.sh --skip-app-build   # только образы (jar не пересобирать)
# ============================================================
set -euo pipefail

DIST_NAME="afina-dist-$(date +%Y%m%d_%H%M%S)"
DIST_DIR="$DIST_NAME"

echo "════════════════════════════════════════════════════════"
echo "  AfinaZ · Сборка дистрибутива"
echo "  Версия: $DIST_NAME"
echo "════════════════════════════════════════════════════════"
echo ""

# 1. Проверки
echo "▶ [1/6] Проверка окружения"
command -v docker >/dev/null || { echo "❌ нет docker"; exit 1; }
docker buildx version >/dev/null 2>&1 || { echo "❌ нет buildx"; exit 1; }
command -v mvn >/dev/null || { echo "❌ нет maven"; exit 1; }
echo "  ✓ docker $(docker --version | awk '{print $3}' | tr -d ',')"
echo "  ✓ maven $(mvn -version 2>/dev/null | head -1 | awk '{print $3}')"

# 2. Сборка jar
echo ""
echo "▶ [2/6] Сборка JAR (Maven)"
if [ "${1:-}" != "--skip-app-build" ]; then
    set -o pipefail
    mvn -DskipTests clean package 2>&1 | tail -5
    rc=$?
    if [ "$rc" != "0" ] || [ ! -f target/ukep-sign-service.jar ]; then
        echo "❌ Maven build failed"
        exit 1
    fi
fi
echo "  ✓ target/ukep-sign-service.jar ($(du -h target/ukep-sign-service.jar | cut -f1))"

# 3. Сборка docker-образа app (amd64)
echo ""
echo "▶ [3/6] Docker build app (linux/amd64) — 10-15 минут на Apple Silicon"
docker buildx build \
    --platform linux/amd64 \
    -t afinaz-app:latest \
    --load \
    -f Dockerfile . 2>&1 | tail -10
echo "  ✓ afinaz-app:latest"

# 4. Pull postgres:15-alpine + angie (amd64)
echo ""
echo "▶ [4/6] Pull postgres:15-alpine + angie (linux/amd64)"
docker pull --platform linux/amd64 postgres:15-alpine 2>&1 | tail -2
docker pull --platform linux/amd64 docker.angie.software/angie:1.12.3-alpine 2>&1 | tail -2
echo "  ✓ образы скачаны"

# 5. Создание структуры дистрибутива
echo ""
echo "▶ [5/6] Создание дистрибутива: $DIST_DIR/"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"/{images,certs,secrets/keys,angie-certs,storage,logs,backups,crls,setup-out}

# 5.1. Скрипты
cp install.sh "$DIST_DIR/" 2>/dev/null || echo "  ⚠ install.sh не найден (создадим отдельно)"
cp setup-applier.sh "$DIST_DIR/"
cp entrypoint-wrapper.sh "$DIST_DIR/"
cp bootstrap.sh "$DIST_DIR/" 2>/dev/null || true
chmod +x "$DIST_DIR"/*.sh 2>/dev/null || true

# 5.2. Compose + конфиги
cp docker-compose-prod.yml "$DIST_DIR/"
cp .env.prod.example "$DIST_DIR/" 2>/dev/null || true

# angie.conf для self-signed (не LE)
cat > "$DIST_DIR/angie.conf" <<'ANGIE_EOF'
worker_processes  auto;
error_log  /var/log/angie/error.log warn;
pid        /var/run/angie.pid;

events {
    worker_connections  1024;
}

http {
    include       /etc/angie/mime.types;
    default_type  application/octet-stream;
    sendfile        on;
    keepalive_timeout  65;

    resolver 127.0.0.11 valid=10s;
    resolver_timeout 10s;

    upstream afina_backend {
        server app:8080;
    }

    server {
        listen 80 default_server;
        listen [::]:80 default_server;

        location / {
            return 301 https://$host$request_uri;
        }
    }

    server {
        listen 443 ssl default_server;
        listen [::]:443 ssl default_server;
        http2 on;

        ssl_certificate     /etc/angie/certs/fullchain.pem;
        ssl_certificate_key /etc/angie/certs/privkey.pem;
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_ciphers HIGH:!aNULL:!MD5;

        location / {
            proxy_pass http://afina_backend;
            proxy_http_version 1.1;

            proxy_set_header Host              $http_host;
            proxy_set_header X-Real-IP         $remote_addr;
            proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header X-Forwarded-Host  $http_host;

            proxy_connect_timeout 10s;
            proxy_send_timeout    60s;
            proxy_read_timeout    60s;

            client_max_body_size 50m;
        }
    }
}
ANGIE_EOF

# 5.3. Certs УЦ
if [ -d certs ]; then
    cp certs/*.cer certs/*.crt certs/*.pem "$DIST_DIR/certs/" 2>/dev/null || true
fi

# 5.4. Пример admins.env
cat > "$DIST_DIR/admins.env.example" <<'ADMINS_EOF'
# admins.env — администраторы (CN|SNILS)
# Будет заполнен wizard'ом автоматически, но можно задать заранее:
# Дырул Анатолий Юрьевич|18962570627
ADMINS_EOF

# 5.5. Docker-образы (save + gzip)
echo ""
echo "▶ [5/6] Сохранение Docker-образов (это ~15-20 минут)"
echo "  ⏳ 3 образа, ~2 ГБ, сжатие gzip..."
docker save afinaz-app:latest postgres:15-alpine docker.angie.software/angie:1.12.3-alpine \
    | gzip -1 > "$DIST_DIR/images/all-images.tar.gz"
echo "  ✓ images/all-images.tar.gz ($(du -h $DIST_DIR/images/all-images.tar.gz | cut -f1))"

# 5.6. README
cat > "$DIST_DIR/README.txt" <<'README_EOF'
════════════════════════════════════════════════════════
  ПАО «НБД-Банк» · Дистрибутив AfinaZ
════════════════════════════════════════════════════════

Требования к серверу:
  • x86_64 Linux (Astra, Ubuntu 22.04/24.04, Debian 11/12)
  • 4 GB RAM (рекомендуется 8 GB)
  • 20 GB свободного места на диске
  • Docker + docker compose
  • Порты 80 и 443 свободны (освободятся автоматически)
  • Открытый доступ к registry.astralinux.ru (если нет — preflight предупредит)

Установка:
  1. Распакуйте архив на сервере:
       tar xzf afina-dist-YYYYMMDD_HHMMSS.tar.gz
       cd afina-dist-YYYYMMDD_HHMMSS/

  2. Запустите установщик:
       sudo ./install.sh --domain=<IP_или_домен> --accept-non-astra

  3. Скрипт напечатает URL для wizard'а:
       https://<domain>/setup?token=XXXX

  4. Откройте URL в браузере, пройдите 6 шагов wizard'а.

  5. После "Завершить" — приложение перезапустится в обычном режиме.
     Вход в /admin/login — по УКЭП (сертификат + PIN).

Состав дистрибутива:
  install.sh              — установщик (единственный, что нужно запускать)
  docker-compose-prod.yml — манифест стека
  angie.conf              — reverse proxy (self-signed или LE)
  setup-applier.sh        — системный watcher для применения setup-out/*.new
  entrypoint-wrapper.sh   — entrypoint для app контейнера
  images/all-images.tar.gz — предсобранные Docker-образы (app + postgres + angie)
  certs/                  — корневые сертификаты УЦ (для проверки УКЭП)
  admins.env.example      — пример списка админов (заполняется wizard'ом)
  secrets/                — пустая, заполнится при установке

ВАЖНО:
  • Дистрибутив содержит ПРЕДСОБРАННЫЕ образы amd64 — работает на любом x86_64.
  • Astra Linux — основная ОС для банка. Ubuntu/Debian — только dev/test
    (флаг --accept-non-astra обязателен).
  • После установки: секреты хранятся в secrets/ (0600). Backup-пароль в
    secrets/backup.pass — сохраните в 1Password.

Управление после установки:
  • Сайт:       https://<domain>/
  • Админка:    https://<domain>/admin/login  (вход по УКЭП)
  • Setup:      https://<domain>/setup        (только в setup-режиме)
  • Логи:       docker logs afina-app
  • Backup:     ./backup-prod.sh
  • Восстановление: ./restore-prod.sh
  • Управление: /usr/local/bin/afina (после установки CLI)

Поддержка: sigmabigdata/AfinaZ @ GitHub
README_EOF

echo "  ✓ README.txt"

# 6. Финальная упаковка
echo ""
echo "▶ [6/6] Упаковка в .tar.gz"
tar czf "${DIST_NAME}.tar.gz" "$DIST_DIR/"
DIST_SIZE=$(du -h "${DIST_NAME}.tar.gz" | cut -f1)

echo ""
echo "════════════════════════════════════════════════════════"
echo "  ✅ ГОТОВО"
echo "════════════════════════════════════════════════════════"
echo ""
echo "  Дистрибутив: ${DIST_NAME}.tar.gz (${DIST_SIZE})"
echo "  Содержимое:  $DIST_DIR/"
echo ""
echo "  Как передать на сервер:"
echo "    scp ${DIST_NAME}.tar.gz root@<IP>:/root/"
echo ""
echo "  На сервере:"
echo "    tar xzf ${DIST_NAME}.tar.gz"
echo "    cd ${DIST_DIR}/"
echo "    sudo ./install.sh --domain=<IP> --accept-non-astra"
echo ""
echo "════════════════════════════════════════════════════════"
