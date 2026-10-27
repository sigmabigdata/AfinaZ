# Техническая документация ПАО «НБД-Банк»

## 1. Архитектура
Система развёрнута в Docker Compose и состоит из трех сервисов:
1. **app** (Spring Boot) — бизнес-логика, шифрование, генерация PDF.
2. **postgres** (PostgreSQL 15) — хранение метаданных и PII.
3. **angie** (Форк Nginx) — reverse-proxy, TLS-терминация (Let's Encrypt / Custom).

## 2. Модель безопасности
* **Файлы документов** шифруются на диске (AES-256-GCM).
* **PII (email, ФИО)** в БД шифруются (AES-256-GCM).
* **Подписи** проверяются через BouncyCastle с построением цепочки и проверкой CRL.
* **Бэкапы** шифруются (AES-256-CBC).
* **Защита от удаления**: клиент не может удалить подписанный документ или подпись.

## 3. Ключевые модули
* `SignatureVerifier` — проверка CAdES-BES, chain building.
* `SignatureVisualizationService` — генерация PDF. Добавляет логотип Банка и текст в футер каждой страницы, а синие штампы подписей (ФИО, сертификат, доверенность) накладывает только на последнюю страницу.
* `CrlDownloader` — автоматическая загрузка CRL по CDP-URL из сертификатов.
* `CleanupService` — очистка дискового пространства (Docker кэш, логи, APT, файлы-сироты).
* `PiiStringConverter` — JPA конвертер для прозрачного шифрования полей БД.

## 4. Основные эндпоинты (API)
* `GET /login` — вход клиента (magic-link).
* `POST /api/sign/accept` — подписание документа клиентом.
* `GET /admin/challenge` + `POST /admin/cert-login` — вход админа по УКЭП.
* `POST /admin/documents/{id}/apply-poa` — применение доверенности к подписи.
* `GET /admin/documents/{id}/visualization` — скачивание PDF со штампами и футером.
* `GET /admin/system/cleanup/analyze` — анализ мусора на сервере.
* `POST /admin/system/cleanup/{type}` — запуск очистки (docker, logs, apt, orphans).

## 5. Обслуживание
* **Бэкап:** ежедневно в 3:00 через cron (`backup-prod.sh`).
* **Очистка мусора:** `/admin/system` → вкладка «Очистка» (Docker, логи, APT, сироты).
* **Ротация логов:** настроена в `docker-compose-prod.yml` (`max-size: 10m`).
* **Миграции БД:** автоматически через Flyway при старте контейнера.
