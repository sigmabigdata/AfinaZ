# Афина — Сервис подписания документов УКЭП

Веб-сервис для подписания документов усиленной квалифицированной
электронной подписью (УКЭП) через плагин КриптоПро.

---

## Содержание

1. [Возможности](#возможности)
2. [Стек](#стек)
3. [Требования](#требования)
4. [Развёртывание](#развёртывание)
5. [Настройка после запуска](#настройка-после-запуска)
6. [Админ-панель](#админ-панель)
7. [Безопасность](#безопасность)
8. [Аудит и мониторинг](#аудит-и-мониторинг)
9. [Обслуживание](#обслуживание)
10. [CLI afina](#cli-afina)
11. [Типичные проблемы](#типичные-проблемы)
12. [Инструкция для клиента](#инструкция-для-клиента)

---

## Возможности

### Для клиентов
- Вход по **одноразовой ссылке на email** (magic-link, TTL 10 часов)
- Загрузка, просмотр, скачивание документов (PDF, DOCX, изображения)
- **Подписание УКЭП** через плагин КриптоПро (CAdES-BES detached)
- Несколько подписей на один документ
- Скачивание подписи отдельно или архивом (ZIP: документ + подпись)

### Для администраторов
- Вход **по УКЭП-сертификату** (challenge-response, без пароля)
- Управление клиентами: CRUD, блокировка, отправка ссылок
- Просмотр документов и подписей клиентов, скачивание, подпись от имени админа

### Безопасность
- **Шифрование файлов** AES-256-GCM (`file.key`)
- **Шифрование PII** (email, телефон, ФИО в документах) AES-256-GCM (`pii.key`)
- **Шифрование бэкапов БД** AES-256-CBC (`backup.pass`)
- **Пароль на ZIP с ключами** (AES-256, zip4j)
- **Email-hash** (SHA-256) для поиска без расшифровки
- Проверка подписи: **BouncyCastle** chain building + CRL
- **Авто-загрузка CRL** по CDP-URL из сертификатов
- **Rate limiting** на `/login`, `/admin/challenge`, `/admin/cert-login`
- **Запрет** удаления подписанных документов и подписей
- **Запрет** повторного подписания одним пользователем

### Аудит и мониторинг
- **Журнал аудита** (`audit_events`): логины, CRUD, подписи, бэкапы, настройки
- **Фильтры** по типу / пользователю / результату / дате, **экспорт CSV**
- **Retention policy**: автоочистка старше 365 дней (настраивается)
- **Мониторинг**: встроенный (`@Scheduled` 5 мин) + внешний (`monitor.sh` cron)
- **Email-алерты** при недоступности (3 сбоя подряд)
- **Статистика рассылки** за 24 часа

### Управление
- **Настройки в UI** (`/admin/settings`): SMTP, revocation-mode, rate-limit
- **Ключи** (`/admin/keys`): статус, бэкап, правила хранения
- **CRL** (`/admin/crl`): список, загрузка вручную, удаление
- **Бэкапы** (`/admin/system`): создание, скачивание, восстановление
- **CLI** `afina` — единая точка управления сервером

---

## Стек

| Слой | Технология |
|---|---|
| Backend | Java 17, Spring Boot 3.2.5 |
| БД | PostgreSQL 16 + Flyway + pgAudit + pg_stat_statements |
| ORM | Spring Data JPA, Hibernate 6 |
| Криптография УКЭП | BouncyCastle (chain, CRL), КриптоПро JCSP (ГОСТ) |
| Шифрование данных | AES-256-GCM (JCE SunJCE) — `file.key`, `pii.key` |
| Шифрование бэкапов | AES-256-CBC + PBKDF2 (openssl), `backup.pass` |
| ZIP с паролем | zip4j 2.11.5 (AES-256) |
| Хэширование | SHA-256 (email-hash, phone-hash) |
| Frontend | Thymeleaf, ванильный JS, плагин CryptoPro CAdES |
| Мониторинг | Spring @Scheduled + shell-cron `monitor.sh` |
| Инфраструктура | Docker Compose, Angie (форк Nginx, ФСТЭК) |
| ОС | Ubuntu 22.04 / 24.04 LTS, x86_64 |
| CLI | bash (`afina.sh`) |

---

## Требования

### Сервер

| Параметр | Значение |
|---|---|
| ОС | Ubuntu 22.04 / 24.04 LTS |
| Архитектура | **x86_64** (ARM не подойдёт из-за CryptoPro) |
| CPU | 2 vCPU минимум |
| RAM | 4 GB минимум |
| Диск | 40 GB SSD минимум |
| Права | root или sudo |
| Порты | 22 (SSH), 80 (HTTP), 443 (HTTPS) |
| Домен | A-запись на IP сервера |
| Интернет | исходящий на github.com, letsencrypt.org, *.cryptopro.ru |

### Что подготовить заранее

Обязательные файлы (положить в `/opt/afina/` до развёртывания):

| Файл | Что | Где взять |
|---|---|---|
| `admins.env` | Список админов `ФИО\|СНИЛС` | Ручной |
| `certs/*.cer` | Корневые сертификаты УЦ | Сайт УЦ или из сертификатов клиентов |
| `cryptopro-dist/linux-amd64_deb.tgz` | Дистрибутив КриптоПро CSP | Покупка у КриптоПро |

Опционально:

| Файл | Когда нужен |
|---|---|
| `.env.prod` | При ручном развёртывании (install.sh создаёт сам) |
| `angie-certs/fullchain.pem` + `privkey.pem` | Если свой SSL вместо Let's Encrypt |

`install.sh` / `deploy.sh` автоматически создают в `secrets/`:
`file.key`, `pii.key`, `backup.pass` — сохранить все три в 1Password.

---

## Развёртывание

Два способа: **автоматический** (`install.sh`) и **ручной**.

### Способ A. Автоматический (рекомендуется)

**1. Зайти на VPS:**

    ssh root@<IP-сервера>

**2. Настроить DNS:** A-запись домена → IP сервера.
Обязательно ДО запуска, иначе Let's Encrypt не получит сертификат.

**3. Склонировать репозиторий:**

    git clone git@github.com:sigmabigdata/Afina_bank.git /opt/afina
    cd /opt/afina

**4. Скопировать 3 обязательных файла** (с локальной машины):

    scp admins.env         afina-vps:/opt/afina/
    scp -r certs/          afina-vps:/opt/afina/
    scp -r cryptopro-dist/ afina-vps:/opt/afina/

**5. Запустить:**

    sudo ./install.sh

Скрипт спросит домен, SSL-режим, порты, SMTP; сгенерирует `.env.prod`,
ключи шифрования (`file.key`, `pii.key`, `backup.pass`), пароли БД;
установит Docker, UFW, fail2ban; соберёт и запустит стек; настроит
cron; поставит CLI `afina`.

**6. Сохранить в менеджер паролей** пароли БД и ключи шифрования
(скрипт покажет их в конце).

### Способ B. Ручной

**1. Подготовка сервера:**

    ssh root@<IP-сервера>
    apt update && apt upgrade -y
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker

**2. Склонировать репозиторий:**

    mkdir -p /opt/afina
    cd /opt/afina
    git clone git@github.com:sigmabigdata/Afina_bank.git .

**3. Разложить секреты** (с MacBook):

    scp .env.prod          afina-vps:/opt/afina/
    scp admins.env         afina-vps:/opt/afina/
    scp -r certs/          afina-vps:/opt/afina/
    scp -r cryptopro-dist/ afina-vps:/opt/afina/

**4. Заполнить `.env.prod`:**

    cd /opt/afina
    nano .env.prod

Минимум:

    APP_DOMAIN=afina.example.ru
    APP_BASE_URL=https://afina.example.ru
    SECURE_COOKIES=true
    HTTP_PORT=80
    HTTPS_PORT=443
    TLS_MODE=letsencrypt

    DB_NAME=afina_db
    DB_SUPERUSER=postgres
    DB_SUPERUSER_PASSWORD=<random>
    DB_USER=afina_app
    DB_PASSWORD=<random>
    DB_MIGRATOR_USER=afina_migrator
    DB_MIGRATOR_PASSWORD=<random>
    DB_AUDITOR_USER=afina_auditor
    DB_AUDITOR_PASSWORD=<random>

    ADMIN_IP_WHITELIST=127.0.0.1,::1,172.0.0.0/8

    MAIL_MODE=smtp
    MAIL_HOST=mail.example.ru
    MAIL_PORT=465
    MAIL_USERNAME=noreply@example.ru
    MAIL_PASSWORD=<smtp-password>
    MAIL_FROM=noreply@example.ru

    CRYPTO_PRO_ENABLED=true

Сгенерировать пароли: `openssl rand -base64 24`.

**5. Ключи шифрования (КРИТИЧНО):**

    cd /opt/afina
    mkdir -p secrets
    chmod 700 secrets
    openssl rand -base64 32 > secrets/file.key
    openssl rand -base64 32 > secrets/pii.key
    openssl rand -base64 32 | tr -d '\n' > secrets/backup.pass
    chown -R 999:999 secrets
    chmod 600 secrets/*.key secrets/backup.pass

Сохранить **все три** файла вне сервера (1Password, сейф):
`file.key`, `pii.key`, `backup.pass`.
Без первых двух — данные не расшифровать.
Без третьего — бэкапы не восстановить.

**6. DNS:**

    A    afina.example.ru       → <IP-сервера>
    A    www.afina.example.ru   → <IP-сервера>

Проверить: `dig +short afina.example.ru`.

**7. Запуск:**

    cd /opt/afina
    sudo ./deploy.sh

Скрипт делает: проверки окружения → apt update → Docker → UFW и
fail2ban → параметры ядра → каталоги → сборка образа → запуск стека
→ healthcheck → cron бэкапа.

Продолжительность: 5–15 минут.

---

## Настройка после запуска

### 1. Проверить миграции Flyway

    docker exec afina-postgres psql -U postgres -d afina_db -c \
      "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

Актуальные миграции (V1–V14):

| V | Описание |
|---|---|
| 1 | init |
| 2 | grants |
| 3 | ownership |
| 4 | login token used at |
| 5 | document signatures |
| 6 | file encryption |
| 7 | app settings |
| 8 | monitor history |
| 9 | pii user |
| 10 | pii documents |
| 11 | audit events |
| 12 | audit retention |
| 13 | smtp settings |
| 14 | drop plaintext pii |

У всех `success = t`.

### 2. Healthcheck

    curl -s http://localhost:8080/actuator/health     # {"status":"UP"}
    curl -sI https://afina.example.ru/actuator/health | head -1
    # HTTP/2 200

### 3. Проверка ключей

    docker compose -f docker-compose-prod.yml --env-file .env.prod logs app | \
      grep -iE 'FileEncryptor|PiiEncryptor'
    # FileEncryptor: ключ загружен из /opt/afina/secrets/file.key
    # PiiEncryptor:  ключ загружен из /opt/afina/secrets/pii.key

### 4. Проверить SMTP

Открыть `/admin/settings` → блок «SMTP» → «Проверить SMTP» →
отправить тестовое письмо. Проверить inbox.

Пароль SMTP хранится в БД **зашифрованным** (`pii.key`), не в plaintext.

---

## Админ-панель

Все страницы доступны только с сервера (IP-whitelist). Извне —
через SSH-туннель:

    ssh -L 8080:localhost:8080 root@<IP-сервера>
    # Открыть: http://localhost:8080/admin/login

### Страницы

| URL | Назначение |
|---|---|
| `/admin` | Дашборд: статистика клиентов и документов |
| `/admin/users` | Список клиентов, поиск, фильтры |
| `/admin/users/{id}` | Карточка клиента: ФИО, документы, подписи |
| `/admin/system` | Метрики, бэкапы, диагностика, логи, restart |
| `/admin/monitor` | История проверок, статистика рассылки |
| `/admin/crl` | Управление CRL |
| `/admin/audit` | Журнал аудита с фильтрами и CSV-экспортом |
| `/admin/keys` | Статус ключей, скачивание бэкапа, правила |
| `/admin/settings` | SMTP, revocation-mode, rate-limit, retention |

### Добавление администратора

    cd /opt/afina
    nano admins.env
    # Формат: ФИО|СНИЛС, например:
    # Иванов Иван Иванович|12345678901

    docker compose -f docker-compose-prod.yml --env-file .env.prod restart app

Проверка:

    docker compose -f docker-compose-prod.yml --env-file .env.prod logs app | \
      grep -A5 "Администраторы"

Запись в БД появится **после первого входа** через `/admin/login`.

---

## Безопасность

### Ключи шифрования

Два ключа в `/opt/afina/secrets/`:

| Ключ | Что шифрует | Потеря = |
|---|---|---|
| `file.key` | Содержимое файлов документов | Все `.enc` файлы нечитаемы |
| `pii.key` | Email, телефон, ФИО-в-документах в БД | PII в БД нечитаем |

**Правила:**
- Файлы должны иметь права `600`, владелец `999:999` (uid контейнера)
- Никогда не перезаписывать (иначе старые данные не расшифруются)
- Хранить 3 копии вне сервера: менеджер паролей + бумага в сейфе + флешка
- Никогда не передавать по открытым каналам

Управление: `/admin/keys` → скачать ZIP **с паролем** (AES-256, zip4j).
Пароль задаётся в UI, нигде не сохраняется — если забудете, архив не открыть.

### Проверка подписи

Используется **BouncyCastle** (не JCSP):

1. Парсинг CAdES-BES через `CMSSignedData`
2. Криптопроверка подписи (BC, fallback JCSP для ГОСТ)
3. **Chain building** вручную: leaf → intermediate → root
4. Проверка корня в truststore (`/app/certs/*.cer`)
5. Проверка отзыва по CRL из `/app/crls/`

**Авто-загрузка CRL**: `CrlDownloader` извлекает CDP-URL из сертификата
подписанта и скачивает в `/app/crls/auto-*.crl` (TTL 12 ч).
Дедупликация по issuer — оставляется самый свежий.

### Rate limiting

`RateLimitFilter` (высший приоритет, работает до Spring Security):

| Endpoint | Лимит |
|---|---|
| `POST /login` | 5 / 15 мин (IP+email) |
| `GET /admin/challenge` | 10 / 15 мин (IP) |
| `POST /admin/cert-login` | 5 / 15 мин (IP) |
| `GET /login/confirm` | 20 / 15 мин (IP) |

При превышении — `429 Too Many Requests` с `Retry-After`.

### PII-шифрование

| Поле | Хранение |
|---|---|
| `users.email` | `email_enc` (AES-GCM) + `email_hash` (SHA-256, unique) |
| `users.phone` | `phone_enc` + `phone_hash` (nullable) |
| `users.full_name` | Plaintext (для fuzzy-поиска) |
| `documents.original_name` | `original_name_enc` (AES-GCM) |
| `documents.signer_subject` | `signer_subject_enc` |
| `document_signatures.signer_subject` | `signer_subject_enc` |

JPA `@Convert` (`PiiStringConverter`) шифрует прозрачно.

### Защита от удаления

- **Клиент** не может удалить подписанный документ → 400
- **Клиент** не может удалить подпись → 400
- **Клиент** не может подписать один документ дважды
- **Админ** может удалить подписанный документ (с записью в audit)
- **Админ** не может удалить подписи

---

## Аудит и мониторинг

### Журнал аудита (`/admin/audit`)

Все важные события пишутся в `audit_events`:

- `LOGIN_SUCCESS`, `LOGIN_FAIL` — клиенты
- `ADMIN_LOGIN_SUCCESS`, `ADMIN_LOGIN_FAIL` — админы
- `USER_CREATE`, `USER_UPDATE`, `USER_DELETE`
- `DOC_UPLOAD`, `DOC_DELETE`
- `SIGN_SUCCESS`, `SIGN_FAIL`
- `SIGNATURE_DELETE_ATTEMPT` — попытки обхода
- `EMAIL_SENT`, `EMAIL_FAIL`
- `BACKUP_CREATE`, `BACKUP_RESTORE`
- `APP_RESTART`, `SETTINGS_UPDATE`, `KEYS_DOWNLOAD`
- `RATE_LIMIT` — превышения лимитов

Фильтры: тип / пользователь / результат / диапазон дат.
Экспорт: CSV (с BOM для Excel).
Retention: `audit.retention_days` (по умолчанию 365), автоочистка в 04:00.

### Мониторинг (`/admin/monitor`)

- История проверок `monitor_events`
- Счётчик сбоев подряд
- Кнопка «Проверить сейчас»
- Кнопка «Сбросить счётчик»
- Статистика рассылки email за 24 часа
- Последние 10 ошибок SMTP

**Два механизма:**

1. **Внутренний** (`MonitoringService` в Spring, каждые 5 мин) —
   проверяет `/actuator/health` изнутри, пишет в `monitor_events`.
2. **Внешний** (`monitor.sh` в cron, каждые 5 мин) — проверяет
   контейнеры + HTTPS, шлёт email при 3 сбоях подряд.

Оба используют `monitor.mail_to` из `/admin/settings`.

---

## Обслуживание

### Бэкап

**Вручную:**

    afina backup
    # или
    cd /opt/afina && ./backup-prod.sh

Создаёт в `backups/` (оба файла — **AES-256-CBC**):
- `afina_YYYYMMDD_HHMMSS.sql.gz.enc` — дамп БД, зашифрован
- `storage_YYYYMMDD_HHMMSS.tar.gz.enc` — архив `storage/documents/`, зашифрован

Пароль берётся из `secrets/backup.pass` (0600, владелец root). Если файла нет —
скрипт откажется работать (никаких незашифрованных бэкапов «по инерции»).

Ротация: удаляются файлы старше 30 дней.

**Тот же файл `secrets/backup.pass` нужен для восстановления.** Сохраните его
в менеджере паролей и в сейфе — иначе бэкапы не расшифровать.

**Автоматически:** cron `/etc/cron.d/afina-backup` ежедневно в 3:00.

### Восстановление

**Через UI:** `/admin/system` → кнопка «Восстановить».
**Только для legacy-бэкапов** (`*.sql.gz`, без `.enc`).

Для зашифрованных `.enc` — **только CLI**: пароль от `backup.pass`
не должен ходить через веб-сессию админа.

**Через CLI:**

    cd /opt/afina
    ./restore-prod.sh backups/afina_YYYYMMDD_HHMMSS.sql.gz.enc

Скрипт сам расшифрует `.enc` (пароль — `secrets/backup.pass`), остановит app,
сольёт дамп, поправит владельцев таблиц и запустит app обратно.

Для бэкапов старого формата (`.sql.gz` без `.enc`) — тоже работает.

**Вручную (если CLI недоступен):**

    cd /opt/afina
    docker compose -f docker-compose-prod.yml --env-file .env.prod stop app

    # Расшифровать и восстановить БД
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
        -pass file:secrets/backup.pass \
        -in backups/afina_YYYYMMDD_HHMMSS.sql.gz.enc \
      | gunzip \
      | docker exec -i afina-postgres psql -U afina_migrator -d afina_db -q

    # Восстановить владельцев и права
    docker exec -i afina-postgres psql -U postgres -d afina_db <<'SQL'
    DO $$ DECLARE r RECORD;
    BEGIN
        FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='public' LOOP
            EXECUTE 'ALTER TABLE public.' || quote_ident(r.tablename)
                    || ' OWNER TO afina_migrator';
        END LOOP;
        FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname='public' LOOP
            EXECUTE 'ALTER SEQUENCE public.' || quote_ident(r.sequencename)
                    || ' OWNER TO afina_migrator';
        END LOOP;
    END $$;
    SQL

    # Восстановить файлы (расшифровать .tar.gz.enc)
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
        -pass file:secrets/backup.pass \
        -in backups/storage_YYYYMMDD_HHMMSS.tar.gz.enc \
      | tar xzf - -C /opt/afina

    # Запустить
    docker compose -f docker-compose-prod.yml --env-file .env.prod up -d app

### Обновление приложения

> ⚠️ Не используйте `afina restart` для обновления кода — он **не пересобирает jar**.
> Только `afina deploy` (git pull + rebuild + up).

    cd /opt/afina
    afina deploy

Или вручную:

    cd /opt/afina
    git pull
    docker compose -f docker-compose-prod.yml --env-file .env.prod build --no-cache app
    docker compose -f docker-compose-prod.yml --env-file .env.prod up -d app
    sleep 30
    docker compose -f docker-compose-prod.yml --env-file .env.prod ps

Flyway применит новые миграции автоматически.

### Перезапуск

    afina restart
    # или через UI: /admin/system → «Перезапустить приложение»

### Логи

    afina logs app -f              # в реальном времени
    afina logs app                 # последние 200 строк
    afina logs angie
    afina logs pg

### Cron-задачи

    afina cron

Установленные задачи в `/etc/cron.d/`:

| Файл | Расписание | Что делает |
|---|---|---|
| `afina-backup` | 3:00 ежедневно | Бэкап БД + файлов (AES-256) |
| `afina-cleanup-all` | 4:30 и 16:30 | Чистка Docker-кэша, логов, старых CRL |
| `afina-crl-cleanup` | вс 4:00 | Удаление auto-CRL старше 90 дней |
| `afina-monitor` | каждые 5 мин | Внешний health-check + email-алерт |
| `afina-ssl-check` | пн 9:00 | Проверка срока SSL |

---

## CLI afina

Устанавливается в `/usr/local/bin/afina` (симлинк на `afina.sh`).

    afina help

| Команда | Что делает |
|---|---|
| `afina status` | Сводка: контейнеры, health, ключи, SSL |
| `afina doctor` | Полная диагностика (11 проверок) |
| `afina health` | Health local + external |
| `afina up / down / restart` | Управление стеком |
| `afina deploy` | git pull + rebuild + up |
| `afina logs [app\|angie\|pg] [-f]` | Логи |
| `afina shell [app\|pg]` | Войти в контейнер |
| `afina db "SQL"` | SQL-запрос на проде |
| `afina backup` | Ручной бэкап |
| `afina key` | Информация о ключах |
| `afina audit [N]` | Последние N событий |
| `afina users [search]` | Список клиентов |
| `afina ssl` | Проверка SSL-сертификата |
| `afina crl` | Список CRL |
| `afina cron` | Cron-задачи |
| `afina version` | Версия приложения |

---

## Типичные проблемы

### «Ключ шифрования не найден»

    java.lang.IllegalStateException: Ключ шифрования не найден: /opt/afina/secrets/file.key

**Причина:** контейнер (uid=999) не может прочитать файл.

**Решение:**

    cd /opt/afina
    chown -R 999:999 secrets
    chmod 700 secrets
    chmod 600 secrets/*.key secrets/backup.pass
    docker compose -f docker-compose-prod.yml --env-file .env.prod restart app

### Angie отдаёт 502 Bad Gateway

**Причина:** приложение не запустилось или ещё стартует.

    afina logs app | tail -50

**Ожидание:** после `Started UkepSignApplication` (~15 секунд) 502 пропадёт.

### Angie не получает Let's Encrypt

**Причины:**
- DNS не указывает на IP сервера (`dig +short afina.example.ru`)
- Порт 80 закрыт (нужен для ACME-челленджа)
- Rate limit LE (много выпусков за короткое время)

    afina logs angie | tail -30

### Письма не приходят клиентам

**Причины:**
- `From` не на домене SMTP-сервера (Gmail блокирует)
- SPF/DKIM не настроены у хостера почты
- Письмо в папке «Спам»

**Проверка:**
- `/admin/settings` → блок SMTP → «Проверить SMTP»
- Логи: `afina logs app | grep -i EmailService`
- Счётчик в `/admin/monitor` → «Рассылка email»

### Подпись не проходит: «is untrusted»

**Причина:** отсутствует корневой сертификат УЦ в truststore.

**Решение:**
1. Получить корневой `.cer` от УЦ
2. Положить в `/opt/afina/certs/`
3. `afina restart`

Проверить, какой issuer нужен:

    afina logs app | grep -i "chain built\|untrusted"

### Файлы в storage/ не зашифрованы

**Причина:** старая версия кода.

    cd /opt/afina
    git log --oneline -5
    afina deploy

### Приложение не стартует: Schema-validation

**Причина:** Entity рассинхронизирован с БД.

    afina logs app | grep -i schema-validation

Проверить `@Column(name=...)` в entity против реальных колонок:

    afina db "\d users"

---

## Инструкция для клиента

### Требования

- Windows 10/11, macOS 12+, Linux (Chromium-Gost)
- Chrome / Yandex Browser / Chromium-Gost
- Токен Рутокен ЭЦП 2.0/3.0 или Etoken

### Установка на компьютер

**1. Драйвер токена**
- Рутокен: https://www.rutoken.ru/support/download/
- Etoken: https://www.safenet-inc.com/

**2. КриптоПро CSP 5.0** — https://www.cryptopro.ru/products/csp
- Установить от имени администратора
- Ввести лицензию

**3. Плагин КриптоПро для браузера** — https://www.cryptopro.ru/products/cades/plugin
- Установить
- Включить расширение в `chrome://extensions`
- Дать доступ «на всех сайтах»

**4. Личный сертификат** — в хранилище «Личные»:
- Двойной клик по `.cer` → «Установить сертификат»

### Вход

1. Открыть `https://afina.example.ru/login`
2. Ввести email → ссылка на почту
3. Перейти по ссылке → личный кабинет

### Подписание

1. Загрузить документ
2. Нажать ✍ рядом с документом
3. Выбрать сертификат
4. Ввести PIN токена
5. Дождаться подтверждения

### Проблема: «Истекло время загрузки плагина»

- Проверить `chrome://extensions` — плагин включён
- Попробовать другой браузер
- Проверить, что нет блокировки `cryptopro.ru` (корпоративный прокси)

### Проблема: «Insert carrier ... to open container»

**Причина:** sandbox браузера блокирует токен.

**Решение:** запустить браузер с `--no-sandbox`:

Windows:

    "C:\Program Files\Google\Chrome\Application\chrome.exe" --no-sandbox

macOS:

    /Applications/Yandex.app/Contents/MacOS/Yandex --no-sandbox

---

## Лицензия

Проприетарная. Все права защищены.

КриптоПро CSP — лицензия КриптоПро (приобретается отдельно).

---

## Контакты

- Разработка: sigmabigdata
- Репозиторий: https://github.com/sigmabigdata/Afina_bank
