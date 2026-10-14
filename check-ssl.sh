#!/usr/bin/env bash
# Проверка срока SSL-сертификата Афина.
# Возвращает:
#   0 — всё ок
#   1 — сертификат истекает менее чем за WARN_DAYS дней
#   2 — сертификат уже истёк / ошибка

set -e
cd "$(dirname "$0")"

WARN_DAYS=14
DOMAIN=$(grep -E '^APP_DOMAIN=' .env.prod 2>/dev/null | cut -d= -f2)
TLS_MODE=$(grep -E '^TLS_MODE=' .env.prod 2>/dev/null | cut -d= -f2 || echo letsencrypt)

if [ -z "$DOMAIN" ]; then
    echo "✗ APP_DOMAIN не задан в .env.prod"
    exit 2
fi

# --- Получить срок действия ---
# Проверяем сертификат через openssl s_client (то, что реально отдаётся).
# Внутри angie-alpine openssl нет, поэтому проверяем с хоста по сети.
END_DATE=$(echo | openssl s_client -connect "${DOMAIN}:443" -servername "${DOMAIN}" 2>/dev/null \
    | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)

if [ -z "$END_DATE" ]; then
    echo "✗ не удалось получить сертификат с ${DOMAIN}:443"
    echo "   проверь: DNS указывает на этот сервер? порт 443 открыт?"
    exit 2
fi

# --- Посчитать дни ---
END_EPOCH=$(date -d "$END_DATE" +%s 2>/dev/null || date -jf "%b %d %T %Y %Z" "$END_DATE" +%s)
NOW_EPOCH=$(date +%s)
DAYS_LEFT=$(( (END_EPOCH - NOW_EPOCH) / 86400 ))

echo "Domain:    $DOMAIN"
echo "TLS mode:  ${TLS_MODE:-letsencrypt}"
echo "Expires:   $END_DATE"
echo "Days left: $DAYS_LEFT"

if [ "$DAYS_LEFT" -lt 0 ]; then
    echo "✗ СЕРТИФИКАТ ИСТЁК"
    exit 2
elif [ "$DAYS_LEFT" -lt "$WARN_DAYS" ]; then
    echo "⚠ до истечения менее $WARN_DAYS дней"
    exit 1
else
    echo "✓ ок"
    exit 0
fi
