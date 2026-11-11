# ПАО «НБД-Банк» — Сервис подписания документов УКЭП

## 1. Требования к серверу
- **ОС:** Astra Linux SE 1.8 или Ubuntu 22.04/24.04
- **Архитектура:** x86_64
- **RAM:** 4 ГБ
- **Диск:** 50 ГБ SSD
- **Порты:** 80 и 443
- **ПО:** Docker и Docker Compose

## 2. Что в дистрибутиве (всё ставится автоматически)
- `certs/` — сертификаты Минцифры / СКБ Контур / Russian Trusted
- `admins.env` — админ по умолчанию (Дырул Анатолий Юрьевич)
- `secrets/` — ключи file.key, pii.key, backup.pass
- `.env.prod` — конфигурация + пароли БД
- Роли БД: afina_migrator, afina_app, afina_auditor
- SSL: для IP — self-signed, для домена — Let's Encrypt

## 3. Онлайн-развертывание
```bash
# На Mac:
rsync -avz ./ root@IP:/opt/afinaz/

# На сервере:
cd /opt/afinaz && chmod +x install.sh && ./install.sh
```
Ответы: IP, TLS=2, Admin IP=*, Email=log, Images=**1**.

## 4. Офлайн-развертывание
```bash
# На Mac:
docker build -t afinaz-app:latest .
docker build -f Dockerfile-postgres -t afinaz-postgres:15-astra .
mkdir -p images
docker save afinaz-app:latest | gzip > images/afinaz-app.tar.gz
docker save afinaz-postgres:15-astra | gzip > images/afinaz-postgres.tar.gz
rsync -avz --exclude .git ./ root@IP:/opt/afinaz/
rsync -avz --progress images/ root@IP:/opt/afinaz/images/

# На сервере:
cd /opt/afinaz
docker load < images/afinaz-app.tar.gz
docker load < images/afinaz-postgres.tar.gz
chmod +x install.sh && ./install.sh
```
Ответы: IP, TLS=2, Admin IP=*, Email=log, Images=**2**.

## 5. Права доступа
Скрипт сам выдаёт:
```bash
chown -R 999:999 storage logs backups crls certs angie-certs secrets
chmod -R 775 storage logs backups crls certs angie-certs
chmod 700 secrets && chmod 600 secrets/*.key secrets/backup.pass
```

Если приложение упало с `AccessDeniedException`:
```bash
chown -R 999:999 storage certs secrets
docker restart afina-app
```

## 6. Управление — утилита `afina`
1 — Деплой, 2 — Перезапуск, 3 — Health, 4 — Логи, 5 — Бэкап, 6 — Восстановление, 7 — Очистка, 8 — Shell, 9 — psql, 10 — restore БД, 11 — restore storage, **12 — полная диагностика**.

Команды для диагностики:
- `afina doctor` — быстрая (10 проверок, ~5 сек)
- `afina diag` — полная end-to-end (20 разделов, ~70 проверок)
- `afina diag --no-color` — без ANSI-цветов (для логов)
- `afina diag --quiet` — только PASS/WARN/FAIL
- `afina diag --markdown-only` — только сгенерировать .md-отчёт

## 7. Решение проблем
- **502 Bad Gateway** — `docker logs afina-app --tail 50`
- **AccessDeniedException** — `chown -R 999:999 storage certs secrets && docker restart afina-app`
- **password authentication failed** — `docker compose down && docker volume rm afinaz_pgdata && ./install.sh`
- **Angie: cannot load certificate** — `openssl req -x509 -nodes -days 365 -newkey rsa:2048 -keyout angie-certs/privkey.pem -out angie-certs/fullchain.pem -subj "/CN=IP" && docker restart afina-angie`
- **Angie: host not found in upstream "app:8080"** — подожди 30 сек, потом `docker restart afina-angie`
- **Maven UnresolvableModelException** — используй офлайн-развертывание (раздел 4)
- **Нет CRL для CN=...** — это норма (soft-fail). При загрузке .sig CRL скачаются, или через /admin/crl → Обновить все
