# ПАО «НБД-Банк» — Сервис подписания документов УКЭП

Веб-сервис для подписания документов УКЭП через КриптоПро.

Стек: Spring Boot 3.2.5 · PostgreSQL 15 · Angie · КриптоПро JCSP · Docker

---

## Требования к серверу

| Параметр | Минимум | Рекомендуется |
|---|---|---|
| ОС | Ubuntu 22.04 / Debian 11 / Astra SE 1.8 | Astra Linux SE 1.8 |
| RAM | 4 GB | 8 GB |
| Диск | 20 GB | 50 GB SSD |
| Порты | 80, 443 | 80, 443 |
| Docker | установлен | + compose |

Для банка (152-ФЗ, ГОСТ) — только Astra Linux SE 1.8. На Ubuntu/Debian — dev/test (флаг --accept-non-astra).

---

## Установка — 3 шага

### Шаг 1. Собрать дистрибутив (на Mac, один раз)

    git clone https://github.com/sigmabigdata/AfinaZ.git
    cd AfinaZ
    ./build-dist.sh

Результат (~15-20 мин): afina-dist-YYYYMMDD_HHMMSS.tar.gz (~2.5 GB).

### Шаг 2. Передать на сервер

    scp afina-dist-*.tar.gz root@IP:/root/

### Шаг 3. Установить (одна команда)

    cd /root
    tar xzf afina-dist-*.tar.gz
    cd afina-dist-*/

    # Проверка (dry-run)
    sudo ./install.sh --domain=IP --dry-run

    # Установка
    sudo ./install.sh --domain=IP --accept-non-astra

Результат (~3-5 минут): URL для wizard с токеном.

---

## Первичная настройка через Wizard (5 минут)

### 1. Открыть в браузере

    https://ДОМЕН/setup?token=ТОКЕН

Self-signed сертификат — браузер покажет «Не защищено». Обход:
- Chrome: на странице ошибки набери thisisunsafe
- Safari: Show Details → visit this website
- Firefox: Advanced → Accept the Risk

### 2. Пройти 6 шагов

1. Домен + TLS: self-signed
2. SMTP: Log (для теста, потом /admin/settings)
3. Администратор: CN (ФИО) + СНИЛС
4. IP-whitelist: 127.0.0.1,::1,172.0.0.0/8
5. Проверки: preflight (WARN не блокеры)
6. Подтверждение: «Завершить и перезапустить»

### 3. Дождаться (~30 сек)

Applier применит настройки, перезапустит app. Wizard выключится.

### 4. Войти в админку

    https://ДОМЕН/admin/login

«Войти с УКЭП» → сертификат → PIN.

---

## После установки

    docker compose -f docker-compose-prod.yml ps
    curl -sk https://ДОМЕН/actuator/health
    curl -sk -o /dev/null -w "%{http_code}\n" https://ДОМЕН/admin/login

### Управление — команда afina

    afina           # меню
    afina status    # статус
    afina logs app  # логи
    afina diag      # диагностика
    afina backup    # бэкап БД

### Обслуживание

- Бэкапы: afina backup (автоматически в 03:00)
- Ключи: /admin/keys → ZIP с паролем
- Обновление: git pull && docker compose build app && docker compose up -d app
- Мониторинг: /admin/monitor

---

## Частые проблемы

### Браузер: ERR_CERT_AUTHORITY_INVALID

Нормально — self-signed. Обход:
- Chrome: thisisunsafe
- Safari: Show Details → visit this website
- Firefox: Advanced → Accept Risk

### Порт 80/443 занят

install.sh автоматически маскирует nginx/apache2. ISPmanager на 1501 работает.
Вернуть nginx: systemctl unmask nginx && systemctl enable --now nginx

### App не стартует

    docker logs afina-app --tail 100 2>&1 | grep -B2 -A10 "Caused by"

Частые причины:
- AccessDeniedException secrets/keys/ → chown 999:999 secrets/
- PII ключ не найден → secrets/keys/pii.key.v1
- NumberFormatException → удалить пустые MAIL_* из .env.prod

### Восстановление из бэкапа

    ./restore-prod.sh backups/afina_*.sql.gz.enc
    ./restore-storage.sh backups/storage_*.tar.gz.enc

---

## Структура /opt/afinaz/

    docker-compose-prod.yml   — стек
    .env.prod                 — переменные (0600)
    admins.env                — CN|SNILS
    secrets/keys/*.key.vN     — AES-256 ключи
    secrets/backup.pass       — пароль бэкапов
    storage/documents/*.enc   — файлы (зашифрованы)
    backups/                  — .sql.gz.enc
    logs/                     — app.log
    certs/                    — корневые УЦ
    angie-certs/              — TLS

---

## Безопасность

Ключи шифрования: secrets/keys/ (0600, uid 999)
Пароль бэкапов: secrets/backup.pass
Пароли БД: .env.prod

Правила:
- 1Password / сейф / зашифрованная флешка
- НЕ передавать через email/мессенджеры
- НЕ хранить в git

При переезде: скопировать secrets/ + storage/ + backups/. Без ключей база нечитаема.

---

## Поддержка

GitHub: sigmabigdata/AfinaZ
Диагностика: afina diag

## Сертификация

Astra Linux SE 1.8 (ФСТЭК № 2557) · КриптоПро CSP 5.0 R2 · Angie 1.12.3 · Axiom JDK Pro 17.

См. CERTIFICATION.md.
