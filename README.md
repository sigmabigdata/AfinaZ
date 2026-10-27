# ПАО "НБД-Банк" · Сервис подписания документов УКЭП

Веб-сервис для подписания документов усиленной квалифицированной электронной подписью (УКЭП) через плагин КриптоПро.

## Стек технологий
* **Backend:** Java 17, Spring Boot 3.2.5
* **БД:** PostgreSQL 15 (Astra Linux SE 1.8), Flyway
* **Криптография:** КриптоПро CSP 5.0 + JCSP, BouncyCastle (CAdES/CRL), AES-256-GCM
* **Frontend:** Thymeleaf, JS, CryptoPro CAdES Browser Plugin
* **Инфраструктура:** Docker Compose, Angie (ФСТЭК)

## Возможности
* **Для клиентов:** Вход по magic-link (email), загрузка документов, подписание УКЭП, скачивание подписей.
* **Для админов:** Вход по сертификату, управление клиентами и документами, мониторинг, аудит, управление CRL и бэкапами, визуализация подписей в PDF со штампами и доверенностями.
* **Безопасность:** Шифрование файлов и PII (персональных данных), защита от повторного подписания, rate-limiting, строгий аудит действий.

## Быстрый старт (Production)

1. Склонировать репозиторий:
   `git clone git@github.com:sigmabigdata/AfinaZ.git /opt/afinaz`
2. Подготовить секреты в `/opt/afinaz/secrets/` (`file.key`, `pii.key`, `backup.pass`).
3. Заполнить `.env.prod` и `admins.env`.
4. Запустить:
   `docker compose -f docker-compose-prod.yml --env-file .env.prod up -d --build`

## Документация
Подробное описание архитектуры, API, модели безопасности и инструкции по эксплуатации читайте в [TECHNICAL.md](./TECHNICAL.md).
