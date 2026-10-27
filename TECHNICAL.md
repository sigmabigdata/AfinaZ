# Техническая документация ПАО "НБД-Банк"

## 1. Архитектура
Система развёрнута в Docker Compose и состоит из трех сервисов:
1. **app** (Spring Boot) — бизнес-логика, шифрование, генерация PDF.
2. **postgres** (PostgreSQL 15) — хранение метаданных и PII.
3. **angie** (Форк Nginx) — reverse-proxy, TLS-терминация.

## 2. Модель безопасности
* **Файлы документов** шифруются на диске (AES-256-GCM).
* **PII (email, ФИО)** в БД шифруются (AES-256-GCM).
* **Подписи** проверяются через BouncyCastle с построением цепочки и проверкой CRL.
* **Бэкапы** шифруются (AES-256-CBC).

## 3. Ключевые модули
* `SignatureVerifier` — проверка CAdES-BES, chain building.
* `SignatureVisualizationService` — генерация PDF. Добавляет логотип Банка и текст в футер каждой страницы, а синие штампы подписей (ФИО, сертификат, доверенность) накладывает только на последнюю страницу.
* `CrlDownloader` — автоматическая загрузка CRL по CDP.
* `CleanupService` — очистка дискового пространства (Docker кэш, логи, APT, файлы-сироты).
* `PiiStringConverter` — JPA конвертер для прозрачного шифрования полей БД.

## 4. Основные эндпоинты (API)
* `GET /login` — вход клиента (magic-link).
* `POST /api/sign/accept` — подписание документа клиентом.
* `GET /admin/challenge` + `POST /admin/cert-login` — вход админа по УКЭП.
* `POST /admin/documents/{id}/apply-poa` — применение доверенности к подписи.
* `GET /admin/documents/{id}/visualization` — скачивание PDF со штампами и футером.
* `GET /admin/system/cleanup/analyze` — анализ мусора на сервере.

## 5. Обслуживание
* **Бэкап:** ежедневно в 3:00 через cron (`backup-prod.sh`).
* **Очистка мусора:** `/admin/system` → вкладка «Очистка» (Docker, логи, APT, сироты).
* **Ротация логов:** настроена в `docker-compose-prod.yml` (`max-size: 10m`).
* **Миграции БД:** автоматически через Flyway при старте контейнера.

## 6. Полный список миграций БД (Flyway)

| V | Описание | Что делает |
|---|---|---|
| 1 | init | Базовые таблицы `users`, `documents` |
| 2 | grants | Роли БД и права (`afina_app`, `afina_migrator`, `afina_auditor`) |
| 3 | ownership | Владелец схемы `afina_migrator` |
| 4 | login_token_used_at | Защита от prefetch magic-link в Gmail |
| 5 | document_signatures | Таблица для множественных подписей |
| 6 | file_encryption | Таблица ключей `file_keys`, колонки AES-GCM |
| 7 | app_settings | Таблица настроек (key-value) |
| 8 | monitor_history | История проверок доступности сервиса |
| 9 | pii_user | Шифрование email и телефона в `users` |
| 10 | pii_documents | Шифрование имен файлов и ФИО в `documents` |
| 11 | audit_events | Журнал аудита |
| 12 | audit_retention | Настройка срока хранения аудита (365 дней) |
| 13 | smtp_settings | Настройки почты |
| 14 | drop_plaintext_pii | Удаление старых открытых колонок email/phone |
| 15 | document_folders | Разделы (папки) для документов клиентов |
| 16 | powers_of_attorney | Доверенности (ЭМЧД), таблица `powers_of_attorney` |
| 17 | poa_stamp_number | Номер доверенности (GUID) для штампов |
| 18 | document_visualizations | Таблица хранения сгенерированных PDF |
| 19 | signature_apply_poa | Привязка доверенности к конкретной подписи |
| 20 | cleanup_settings | Настройки очистки (CRL, бэкапы, логи) |
| 21 | cleanup_extra_settings | Настройки очистки (tmp, journald) |
