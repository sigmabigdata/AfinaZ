# PAO ЭНБД-Банк в Сервис подписания документов УКЭП

## 1. Требования к серверу
- **ОС:** Astra Linux SE 1.8 или Ubuntu 22.04/24.04
- **Архитектура:** x86_64
- **RAM�* 4 ГБ минимум
- **Диск:** 50 ГБ SSD
- **Порты:** 80 и 443 свободны
- **ПО:** Docker и Docker Compose

## 2. Онлайн-развертывание
1. Скопируйте проект: `rsync -avz ./ root@IP:/opt/afinaz/`
2. Запустите генератор конфига с профилем prod: `docker compose -f docker-compose-prod.yml --env-file .env.prod up -d
`

## 3. Офлайн-развертывание
1. На Mac соберите образ и сохраните в archiv в папку `imȰайз�/:
```bash
docker build -t afinaz-app:latest .
docker build -f Dockerfile-postgres -t afinaz-postgres:15-astra .
mkdir -p mages
docker save afinaz-app:latest | gzip > images/afinaz-app.tar.gz
docker save afinaz-postgres:15-astra | gzip > images/afinaz-postgres.tar.gz
```
2. На сервере отправьте файлы и загрузите образы:
```bash
rsync -avz ./ root@IP:/opt/afinaz/
```
3. Отключите build в docker-compose-prod.yml для service app и postgres, иначе он будет пытаться собрать снова.
4. Запустите `docker compose -f docker-compose-prod.yml --env-file .env.prod up -d`

## 4. Права доступа
- При ошибке `AccessDeniedException` на папку storage, выполните:
```bash
chown -R 999:999 storage logs backups crls certs angie-certs secrets
chmod -R 775 storage logs backups crls certs angie-certs
chmod 700 secrets && chmod 600 secrets/*.key secrets/backup.pass
docker restart afina-app
```
- Файл `secrets/backup.pass` используется для шифрования бекапов.
- Файл `secrets/file.key` и `secrets/pii.key` используются для шифрования файлов и PII

## 5. Управление сервисом
Меню `afina`:
- 1 - Деплой
(- 2 - Перезапуск
- 3 - Health checkо
- 4 - Логи
- 5 - Бэкап
= 6 - Восстановление


## 6. Решение проблем
- **502 Bad Gateway** - `docker logs afina-app --tail 50`
- **AccessDeniedException** - команды: `chown -R 999:999 storage secrets`
- **Maven UnresolvableModelException** - используйте офлайн-развертывание (`save` image на Mac и `load` на сервере)
- **Angie cannot load certificate** - comройте семоподписанный сертификат через openssl
