#!/usr/bin/env bash
# ============================================================
# Афина · Интерактивное меню управления сервисом
# ============================================================
# Запуск: ./menu.sh
# ============================================================

set -e
cd "$(dirname "$0")"

# ---- Цвета ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
GRAY='\033[0;90m'
BOLD='\033[1m'
NC='\033[0m'  # No Color

# ---- Константы ----
# ---- Автоопределение окружения ----
# Если запущено из /opt/afina с .env.prod — это сервер (prod).
# Иначе — локальная разработка (dev).
if [ -f /opt/afina/.env.prod ] && [ "$(pwd)" = "/opt/afina" ]; then
    MODE="prod"
    CONTAINER="afina-postgres"
    COMPOSE_FILE="docker-compose-prod.yml"
    ENV_FILE=".env.prod"
    DB="afina_db"
    # Для чтения используем суперпользователя (он всегда есть)
    DB_USER="postgres"
    APP_URL="http://localhost:8080"
else
    MODE="dev"
    CONTAINER="afina-postgres-dev"
    COMPOSE_FILE="docker-compose-dev.yml"
    ENV_FILE=".env"
    DB="afina_db"
    DB_USER="app_user"
    APP_URL="http://localhost:8080"
fi

# Универсальный psql
psql_exec() {
    docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB" "$@"
}

# Универсальный compose
dc() {
    docker compose -f "$COMPOSE_FILE" "$@"
}

# ---- Хелперы ----
clear_screen() {
    clear
    echo -e "${PURPLE}${BOLD}"
    cat <<'BANNER'
   ___    __  _                 
  / _ |  / _|(_) _ __    __ _   
 | |_| | | |_ | || '_ \  / _` |  
 |  _  | |  _|| || | | || (_| |  
 |_| |_| |_|  |_||_| |_| \__,_|  
                                 
BANNER
    echo -e "${NC}${GRAY}  Сервис подписания документов УКЭП${NC}"
    echo -e "${GRAY}  ─────────────────────────────────────${NC}"
    if [ "$MODE" = "prod" ]; then
        echo -e "${GREEN}  Режим: PROD (контейнер ${CONTAINER})${NC}"
    else
        echo -e "${BLUE}  Режим: DEV (контейнер ${CONTAINER})${NC}"
    fi
    echo ""
}

pause() {
    echo ""
    echo -e "${GRAY}  Нажми Enter, чтобы вернуться в меню...${NC}"
    read -r
}

# Проверка, что мы в корне проекта
check_project() {
    if [ ! -f "pom.xml" ] || [ ! -f "run.sh" ]; then
        echo -e "${RED}❌ Ошибка: файл menu.sh должен лежать в корне проекта${NC}"
        exit 1
    fi
}

# Проверка, что Docker запущен
check_docker() {
    if docker info > /dev/null 2>&1; then
        return 0
    fi

    echo -e "${YELLOW}▶ Docker Desktop не запущен, пытаюсь открыть...${NC}"

    # macOS: открыть Docker Desktop
    if command -v open >/dev/null 2>&1; then
        open -a Docker 2>/dev/null || open -a "Docker Desktop" 2>/dev/null || true
    # Linux: systemd
    elif command -v systemctl >/dev/null 2>&1; then
        sudo systemctl start docker 2>/dev/null || true
    fi

    # Ждём до 60 секунд
    for i in $(seq 1 30); do
        sleep 2
        if docker info > /dev/null 2>&1; then
            echo -e "${GREEN}✅ Docker готов (${i}x2 сек)${NC}"
            return 0
        fi
    done

    echo -e "${RED}❌ Docker Desktop не запустился за 60 секунд${NC}"
    echo -e "${YELLOW}   Открой вручную: Программы → Docker Desktop${NC}"
    return 1
}

# Проверка, что контейнер БД работает
check_db() {
    # Уже работает?
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER}$"; then
        return 0
    fi

    # Поднять Docker если нужно
    check_docker || return 1

    echo -e "${YELLOW}▶ Контейнер ${CONTAINER} не запущен, поднимаю...${NC}"
    dc up -d >/dev/null 2>&1

    # Ждём до 30 секунд, пока Postgres ответит pg_isready
    for i in $(seq 1 15); do
        sleep 2
        if docker exec "$CONTAINER" pg_isready -U app_user -d "$DB" > /dev/null 2>&1; then
            echo -e "${GREEN}✅ PostgreSQL готов (${i}x2 сек)${NC}"
            return 0
        fi
    done

    echo -e "${RED}❌ Контейнер не поднялся за 30 секунд${NC}"
    echo -e "${YELLOW}   Последние строки лога:${NC}"
    dc logs --tail=15
    return 1
}

# Проверка, что приложение работает
check_app() {
    if ! curl -s "${APP_URL}/actuator/health" 2>/dev/null | grep -q "UP"; then
        echo -e "${RED}❌ Приложение не отвечает на ${APP_URL}${NC}"
        return 1
    fi
    return 0
}

# ============================================================
# МЕНЮ
# ============================================================
show_menu() {
    clear_screen
    echo -e "${CYAN}${BOLD}  ┌─────────────────────────────────────────────┐${NC}"
    echo -e "${CYAN}${BOLD}  │              ГЛАВНОЕ МЕНЮ                   │${NC}"
    echo -e "${CYAN}${BOLD}  └─────────────────────────────────────────────┘${NC}"
    echo ""
    echo -e "${BOLD}  ${GREEN}1.${NC} Приложение"
    echo -e "${BOLD}  ${GREEN}2.${NC} База данных"
    echo -e "${BOLD}  ${GREEN}3.${NC} Бэкапы"
    echo -e "${BOLD}  ${GREEN}4.${NC} Аудит и мониторинг"
    echo -e "${BOLD}  ${GREEN}5.${NC} Проверки и диагностика"
    echo -e "${BOLD}  ${GREEN}6.${NC} Пользователи и админы"
    echo -e "${BOLD}  ${GREEN}7.${NC} Production / Docker"
    echo -e "${BOLD}  ${GREEN}8.${NC} Полезные ссылки"
    echo -e "${BOLD}  ${GREEN}0.${NC} ${RED}Выход${NC}"
    echo ""
    echo -ne "${BOLD}  ${YELLOW}Выбор: ${NC}"
}

# ============================================================
# 1. ПРИЛОЖЕНИЕ
# ============================================================
menu_app() {
    while true; do
        clear_screen
        echo -e "${CYAN}${BOLD}  ┌─────────────────────────────────────────────┐${NC}"
        echo -e "${CYAN}${BOLD}  │            1. ПРИЛОЖЕНИЕ                    │${NC}"
        echo -e "${CYAN}${BOLD}  └─────────────────────────────────────────────┘${NC}"
        echo ""
        echo -e "  ${GREEN}1.${NC} Запустить приложение"
        echo -e "  ${GREEN}2.${NC} Остановить приложение"
        echo -e "  ${GREEN}3.${NC} Перезапустить приложение"
        echo -e "  ${GREEN}4.${NC} Показать логи (в реальном времени)"
        echo -e "  ${GREEN}5.${NC} Показать последние 50 строк лога"
        echo -e "  ${GREEN}6.${NC} Полная пересборка + запуск"
        echo -e "  ${GREEN}7.${NC} Открыть в браузере (страница входа)"
        echo -e "  ${GREEN}8.${NC} ${BOLD}⚡ Запустить всё одной кнопкой${NC} (Docker + БД + приложение)"
        echo ""
        echo -e "  ${YELLOW}0.${NC} ← Назад"
        echo ""
        echo -ne "${BOLD}  Выбор: ${NC}"
        read -r choice

        case $choice in
            1)
                echo -e "${BLUE}▶ Запускаю приложение...${NC}"
                ./run.sh
                ;;
            2)
                echo -e "${BLUE}▶ Останавливаю...${NC}"
                ./stop.sh
                pause
                ;;
            3)
                echo -e "${BLUE}▶ Перезапускаю...${NC}"
                ./stop.sh
                sleep 1
                ./run.sh
                ;;
            4)
                echo -e "${BLUE}▶ Логи в реальном времени (Ctrl+C — выход)${NC}"
                echo ""
                tail -f logs/app.log 2>/dev/null || echo -e "${RED}Логов нет${NC}"
                pause
                ;;
            5)
                echo -e "${BLUE}▶ Последние 50 строк лога:${NC}"
                echo ""
                tail -50 logs/app.log 2>/dev/null || echo -e "${RED}Логов нет${NC}"
                pause
                ;;
            6)
                echo -e "${BLUE}▶ Полная пересборка...${NC}"
                ./stop.sh
                mvn clean package -DskipTests
                ./run.sh
                ;;
            7)
                echo -e "${BLUE}▶ Открываю браузер...${NC}"
                if command -v open >/dev/null 2>&1; then
                    open "${APP_URL}/login"
                elif command -v xdg-open >/dev/null 2>&1; then
                    xdg-open "${APP_URL}/login"
                else
                    echo -e "${YELLOW}Открой вручную: ${APP_URL}/login${NC}"
                fi
                pause
                ;;
            8)
                echo -e "${BOLD}⚡ Полный запуск: Docker → БД → приложение${NC}"
                echo ""
                # 1. Docker
                if ! check_docker; then
                    pause; continue
                fi
                # 2. БД
                if ! check_db; then
                    pause; continue
                fi
                # 3. Приложение
                ./stop.sh 2>/dev/null || true
                ./run.sh
                ;;
            0) return ;;
            *) echo -e "${RED}Неверный выбор${NC}"; sleep 1 ;;
        esac
    done
}

# ============================================================
# 2. БАЗА ДАННЫХ
# ============================================================
menu_db() {
    while true; do
        clear_screen
        echo -e "${CYAN}${BOLD}  ┌─────────────────────────────────────────────┐${NC}"
        echo -e "${CYAN}${BOLD}  │            2. БАЗА ДАННЫХ                   │${NC}"
        echo -e "${CYAN}${BOLD}  └─────────────────────────────────────────────┘${NC}"
        echo ""
        echo -e "${BOLD}  ${BLUE}Управление контейнером:${NC}"
        echo -e "  ${GREEN}1.${NC} Запустить PostgreSQL"
        echo -e "  ${GREEN}2.${NC} Остановить PostgreSQL (данные сохранятся)"
        echo -e "  ${GREEN}3.${NC} Статус контейнера"
        echo -e "  ${GREEN}4.${NC} Логи контейнера"
        echo ""
        echo -e "${BOLD}  ${BLUE}Работа с БД:${NC}"
        echo -e "  ${GREEN}5.${NC} Подключиться к psql (интерактивно)"
        echo -e "  ${GREEN}6.${NC} Первичная настройка (setup-db.sh)"
        echo -e "  ${GREEN}7.${NC} Показать таблицы и размеры"
        echo -e "  ${GREEN}8.${NC} Показать миграции"
        echo -e "  ${GREEN}9.${NC} Показать всех пользователей"
        echo -e "  ${GREEN}10.${NC} Показать все документы"
        echo ""
        echo -e "${BOLD}  ${RED}Опасное:${NC}"
        echo -e "  ${RED}11.${NC} ${RED}⚠️  Полный сброс БД (удалить все данные!)${NC}"
        echo ""
        echo -e "  ${YELLOW}0.${NC} ← Назад"
        echo ""
        echo -ne "${BOLD}  Выбор: ${NC}"
        read -r choice

        case $choice in
            1)
                check_docker || { pause; continue; }
                echo -e "${BLUE}▶ Запускаю PostgreSQL ($COMPOSE_FILE)...${NC}"
                dc up -d
                sleep 5
                dc ps
                pause
                ;;
            2)
                echo -e "${BLUE}▶ Останавливаю PostgreSQL...${NC}"
                dc down
                pause
                ;;
            3)
                check_docker || { pause; continue; }
                dc ps
                pause
                ;;
            4)
                docker logs "${CONTAINER}" --tail 50 2>&1 || echo -e "${RED}Контейнер не найден${NC}"
                pause
                ;;
            5)
                check_db || { pause; continue; }
                echo -e "${BLUE}▶ Подключаюсь к psql. Для выхода — \\q${NC}"
                echo ""
                docker exec -it "${CONTAINER}" psql -U app_user -d "${DB}"
                pause
                ;;
            6)
                check_db || { pause; continue; }
                ./setup-db.sh
                pause
                ;;
            7)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT
                        relname AS table_name,
                        pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
                        n_live_tup AS rows
                    FROM pg_stat_user_tables
                    ORDER BY pg_total_relation_size(relid) DESC;"
                pause
                ;;
            8)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT version, description, success, installed_on
                    FROM flyway_schema_history
                    ORDER BY installed_rank;"
                pause
                ;;
            9)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT id, email, full_name, role, enabled, last_login_at
                    FROM users ORDER BY id;"
                pause
                ;;
            10)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT id, original_name, size, signed, signer_subject, owner_id
                    FROM documents ORDER BY id;"
                pause
                ;;
            11)
                echo ""
                echo -e "${RED}${BOLD}⚠️  ВНИМАНИЕ! Это удалит ВСЕ данные!${NC}"
                echo -e "${YELLOW}   - Все пользователи${NC}"
                echo -e "${YELLOW}   - Все документы${NC}"
                echo -e "${YELLOW}   - Всю историю миграций${NC}"
                echo ""
                echo -ne "${BOLD}   Введи ${RED}YES${NC}${BOLD} для подтверждения: ${NC}"
                read -r confirm
                if [ "$confirm" = "YES" ]; then
                    if [ "$MODE" = "dev" ]; then
                        ./stop.sh 2>/dev/null || true
                    fi
                    dc down -v
                    dc up -d
                    sleep 10
                    if [ "$MODE" = "dev" ]; then
                        ./setup-db.sh
                    else
                        echo -e "${YELLOW}▶ Prod: init-скрипты БД выполнятся автоматически при первом старте${NC}"
                    fi
                    echo -e "${GREEN}✅ БД сброшена${NC}"
                else
                    echo -e "${YELLOW}Отменено${NC}"
                fi
                pause
                ;;
            0) return ;;
            *) echo -e "${RED}Неверный выбор${NC}"; sleep 1 ;;
        esac
    done
}

# ============================================================
# 3. БЭКАПЫ
# ============================================================
menu_backup() {
    while true; do
        clear_screen
        echo -e "${CYAN}${BOLD}  ┌─────────────────────────────────────────────┐${NC}"
        echo -e "${CYAN}${BOLD}  │              3. БЭКАПЫ                      │${NC}"
        echo -e "${CYAN}${BOLD}  └─────────────────────────────────────────────┘${NC}"
        echo ""
        echo -e "  ${GREEN}1.${NC} Создать бэкап"
        echo -e "  ${GREEN}2.${NC} Список бэкапов"
        echo -e "  ${GREEN}3.${NC} Восстановить из бэкапа"
        echo -e "  ${GREEN}4.${NC} Проверить, что бэкапы работают (тест)"
        echo -e "  ${GREEN}5.${NC} Удалить бэкапы старше 7 дней"
        echo ""
        echo -e "  ${YELLOW}0.${NC} ← Назад"
        echo ""
        echo -ne "${BOLD}  Выбор: ${NC}"
        read -r choice

        case $choice in
            1)
                check_db || { pause; continue; }
                ./backup-dev.sh
                pause
                ;;
            2)
                echo -e "${BLUE}▶ Бэкапы в папке backups/:${NC}"
                echo ""
                if [ -d "backups" ] && [ -n "$(ls -A backups 2>/dev/null)" ]; then
                    ls -lh backups/ | grep -v "^total"
                    echo ""
                    echo -e "${GRAY}Всего: $(ls backups/*.sql.gz 2>/dev/null | wc -l | tr -d ' ') файлов,${NC}"
                    echo -e "${GRAY}Размер: $(du -sh backups/ 2>/dev/null | cut -f1)${NC}"
                else
                    echo -e "${YELLOW}Бэкапов нет${NC}"
                fi
                pause
                ;;
            3)
                check_db || { pause; continue; }
                if [ -z "$(ls -A backups/*.sql.gz 2>/dev/null)" ]; then
                    echo -e "${RED}❌ Бэкапов нет${NC}"
                    pause
                    continue
                fi
                echo -e "${BLUE}▶ Доступные бэкапы:${NC}"
                echo ""
                # Показываем пронумерованный список
                local i=1
                local files=()
                while IFS= read -r f; do
                    files+=("$f")
                    echo -e "  ${GREEN}${i}.${NC} $(basename "$f") ($(du -h "$f" | cut -f1))"
                    i=$((i + 1))
                done < <(ls -t backups/*.sql.gz)
                echo ""
                echo -ne "  ${YELLOW}Номер бэкапа (0 — отмена): ${NC}"
                read -r num
                if [ "$num" = "0" ] || [ -z "$num" ]; then
                    continue
                fi
                if [ "$num" -ge 1 ] && [ "$num" -lt "$i" ] 2>/dev/null; then
                    ./restore-dev.sh "${files[$((num - 1))]}"
                else
                    echo -e "${RED}Неверный номер${NC}"
                fi
                pause
                ;;
            4)
                check_db || { pause; continue; }
                ./test-backup.sh
                pause
                ;;
            5)
                echo -e "${BLUE}▶ Удаляю бэкапы старше 7 дней...${NC}"
                find backups -name "afina_*.sql.gz" -mtime +7 -delete 2>/dev/null
                echo -e "${GREEN}✅ Готово${NC}"
                pause
                ;;
            0) return ;;
            *) echo -e "${RED}Неверный выбор${NC}"; sleep 1 ;;
        esac
    done
}

# ============================================================
# 4. АУДИТ И МОНИТОРИНГ
# ============================================================
menu_audit() {
    while true; do
        clear_screen
        echo -e "${CYAN}${BOLD}  ┌─────────────────────────────────────────────┐${NC}"
        echo -e "${CYAN}${BOLD}  │          4. АУДИТ И МОНИТОРИНГ              │${NC}"
        echo -e "${CYAN}${BOLD}  └─────────────────────────────────────────────┘${NC}"
        echo ""
        echo -e "  ${GREEN}1.${NC} Аудит БД в реальном времени (pgAudit)"
        echo -e "  ${GREEN}2.${NC} Аудит за сегодня"
        echo -e "  ${GREEN}3.${NC} Все INSERT (создания)"
        echo -e "  ${GREEN}4.${NC} Все DELETE (удаления)"
        echo -e "  ${GREEN}5.${NC} Все изменения в users"
        echo -e "  ${GREEN}6.${NC} Письма (ссылки для входа)"
        echo -e "  ${GREEN}7.${NC} Healthcheck приложения"
        echo -e "  ${GREEN}8.${NC} Логи приложения (в реальном времени)"
        echo ""
        echo -e "  ${YELLOW}0.${NC} ← Назад"
        echo ""
        echo -ne "${BOLD}  Выбор: ${NC}"
        read -r choice

        local today_log="/var/lib/postgresql/data/pg_log/postgresql-$(date +%Y-%m-%d).log"

        case $choice in
            1)
                check_db || { pause; continue; }
                echo -e "${BLUE}▶ Аудит в реальном времени (Ctrl+C — выход)${NC}"
                echo ""
                ./auditlog.sh
                pause
                ;;
            2)
                check_db || { pause; continue; }
                echo -e "${BLUE}▶ Аудит за сегодня:${NC}"
                echo ""
                docker exec "${CONTAINER}" sh -c "cat ${today_log}" 2>/dev/null \
                    | grep AUDIT | tail -50 || echo -e "${YELLOW}Записей нет${NC}"
                pause
                ;;
            3)
                check_db || { pause; continue; }
                echo -e "${BLUE}▶ Все INSERT за сегодня:${NC}"
                echo ""
                docker exec "${CONTAINER}" sh -c "cat ${today_log}" 2>/dev/null \
                    | grep "AUDIT:.*INSERT" | tail -30 || echo -e "${YELLOW}Записей нет${NC}"
                pause
                ;;
            4)
                check_db || { pause; continue; }
                echo -e "${BLUE}▶ Все DELETE за сегодня:${NC}"
                echo ""
                docker exec "${CONTAINER}" sh -c "cat ${today_log}" 2>/dev/null \
                    | grep "AUDIT:.*DELETE" | tail -30 || echo -e "${YELLOW}Записей нет${NC}"
                pause
                ;;
            5)
                check_db || { pause; continue; }
                echo -e "${BLUE}▶ Все изменения в users:${NC}"
                echo ""
                docker exec "${CONTAINER}" sh -c "cat ${today_log}" 2>/dev/null \
                    | grep "public.users" | tail -30 || echo -e "${YELLOW}Записей нет${NC}"
                pause
                ;;
            6)
                echo -e "${BLUE}▶ Письма (Ctrl+C — выход)${NC}"
                echo ""
                ./maillog.sh
                pause
                ;;
            7)
                echo -e "${BLUE}▶ Healthcheck:${NC}"
                echo ""
                curl -s "${APP_URL}/actuator/health" && echo "" || echo -e "${RED}Приложение не отвечает${NC}"
                echo ""
                curl -s "${APP_URL}/ping" && echo "" || true
                pause
                ;;
            8)
                echo -e "${BLUE}▶ Логи приложения (Ctrl+C — выход)${NC}"
                echo ""
                tail -f logs/app.log 2>/dev/null || echo -e "${RED}Логов нет${NC}"
                pause
                ;;
            0) return ;;
            *) echo -e "${RED}Неверный выбор${NC}"; sleep 1 ;;
        esac
    done
}

# ============================================================
# 5. ПРОВЕРКИ
# ============================================================
menu_check() {
    while true; do
        clear_screen
        echo -e "${CYAN}${BOLD}  ┌─────────────────────────────────────────────┐${NC}"
        echo -e "${CYAN}${BOLD}  │         5. ПРОВЕРКИ И ДИАГНОСТИКА           │${NC}"
        echo -e "${CYAN}${BOLD}  └─────────────────────────────────────────────┘${NC}"
        echo ""
        echo -e "  ${GREEN}1.${NC} Полная проверка БД (dbcheck.sh)"
        echo -e "  ${GREEN}2.${NC} Проверить целостность БД"
        echo -e "  ${GREEN}3.${NC} Проверить права ролей"
        echo -e "  ${GREEN}4.${NC} Проверить расширения (pgAudit, pg_stat_statements)"
        echo -e "  ${GREEN}5.${NC} Проверить, что приложение ходит под afina_app"
        echo -e "  ${GREEN}6.${NC} Топ-10 самых долгих запросов"
        echo -e "  ${GREEN}7.${NC} Активные соединения к БД"
        echo -e "  ${GREEN}8.${NC} Проверить процесс Java"
        echo -e "  ${GREEN}9.${NC} Проверить порт 8080"
        echo ""
        echo -e "  ${YELLOW}0.${NC} ← Назад"
        echo ""
        echo -ne "${BOLD}  Выбор: ${NC}"
        read -r choice

        case $choice in
            1)
                if [ -f "./dbcheck.sh" ]; then
                    ./dbcheck.sh
                else
                    echo -e "${YELLOW}dbcheck.sh не найден, показываю базовые проверки${NC}"
                    docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "\dt"
                fi
                pause
                ;;
            2)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT
                        (SELECT COUNT(*) FROM users) AS users,
                        (SELECT COUNT(*) FROM documents) AS documents,
                        (SELECT COUNT(*) FROM documents d
                         WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = d.owner_id)) AS orphans;"
                pause
                ;;
            3)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT grantee, table_name, privilege_type
                    FROM information_schema.table_privileges
                    WHERE grantee IN ('afina_app', 'afina_auditor')
                      AND table_schema = 'public'
                    ORDER BY grantee, table_name;"
                pause
                ;;
            4)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "\dx"
                pause
                ;;
            5)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT DISTINCT usename, application_name
                    FROM pg_stat_activity
                    WHERE datname = '${DB}'
                    ORDER BY usename;"
                pause
                ;;
            6)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT
                        LEFT(query, 60) AS query,
                        calls,
                        ROUND(total_exec_time::numeric, 2) AS total_ms,
                        ROUND(mean_exec_time::numeric, 2) AS mean_ms
                    FROM pg_stat_statements
                    ORDER BY total_exec_time DESC
                    LIMIT 10;"
                pause
                ;;
            7)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT usename, state, count(*)
                    FROM pg_stat_activity
                    WHERE datname = '${DB}'
                    GROUP BY usename, state;"
                pause
                ;;
            8)
                echo -e "${BLUE}▶ Процессы Java:${NC}"
                echo ""
                ps aux | grep -i "java" | grep -v grep | awk '{print $2, $11, $12}' || echo -e "${YELLOW}Нет активных Java-процессов${NC}"
                pause
                ;;
            9)
                echo -e "${BLUE}▶ Порт 8080:${NC}"
                echo ""
                if lsof -i :8080 > /dev/null 2>&1; then
                    lsof -i :8080
                else
                    echo -e "${YELLOW}Порт 8080 свободен${NC}"
                fi
                pause
                ;;
            0) return ;;
            *) echo -e "${RED}Неверный выбор${NC}"; sleep 1 ;;
        esac
    done
}

# ============================================================
# 6. ПОЛЬЗОВАТЕЛИ И АДМИНЫ
# ============================================================
menu_users() {
    while true; do
        clear_screen
        echo -e "${CYAN}${BOLD}  ┌─────────────────────────────────────────────┐${NC}"
        echo -e "${CYAN}${BOLD}  │       6. ПОЛЬЗОВАТЕЛИ И АДМИНЫ              │${NC}"
        echo -e "${CYAN}${BOLD}  └─────────────────────────────────────────────┘${NC}"
        echo ""
        echo -e "${BOLD}  ${BLUE}Админы (admins.env):${NC}"
        echo -e "  ${GREEN}1.${NC} Показать список админов"
        echo -e "  ${GREEN}2.${NC} Открыть admins.env в редакторе"
        echo ""
        echo -e "${BOLD}  ${BLUE}Клиенты (в приложении):${NC}"
        echo -e "  ${GREEN}3.${NC} Открыть админку в браузере"
        echo -e "  ${GREEN}4.${NC} Показать клиентов из БД"
        echo -e "  ${GREEN}5.${NC} Создать тестового клиента (CLI)"
        echo -e "  ${GREEN}6.${NC} Разблокировать клиента (по email)"
        echo -e "  ${GREEN}7.${NC} Удалить клиента (по email)"
        echo ""
        echo -e "  ${YELLOW}0.${NC} ← Назад"
        echo ""
        echo -ne "${BOLD}  Выбор: ${NC}"
        read -r choice

        case $choice in
            1)
                echo -e "${BLUE}▶ Админы в admins.env:${NC}"
                echo ""
                if [ -f "admins.env" ]; then
                    grep -v "^#" admins.env | grep -v "^$" | while IFS='|' read -r cn snils; do
                        echo -e "  ${GREEN}●${NC} CN: ${BOLD}${cn}${NC}"
                        echo -e "    СНИЛС: ***${snils: -4}"
                    done
                else
                    echo -e "${RED}admins.env не найден${NC}"
                fi
                pause
                ;;
            2)
                if [ -f "admins.env" ]; then
                    ${EDITOR:-nano} admins.env
                    echo ""
                    echo -e "${YELLOW}▶ Не забудь перезапустить приложение для применения изменений${NC}"
                else
                    echo -e "${RED}admins.env не найден${NC}"
                fi
                pause
                ;;
            3)
                if command -v open >/dev/null 2>&1; then
                    open "${APP_URL}/admin/login"
                elif command -v xdg-open >/dev/null 2>&1; then
                    xdg-open "${APP_URL}/admin/login"
                else
                    echo -e "${YELLOW}Открой вручную: ${APP_URL}/admin/login${NC}"
                fi
                pause
                ;;
            4)
                check_db || { pause; continue; }
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    SELECT id, email, full_name, role, enabled
                    FROM users
                    ORDER BY role, id;"
                pause
                ;;
            5)
                check_db || { pause; continue; }
                echo -ne "  Email клиента: "
                read -r email
                echo -ne "  ФИО: "
                read -r fullname
                echo -ne "  Телефон: "
                read -r phone
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    INSERT INTO users (email, full_name, phone, role, enabled)
                    VALUES ('${email}', '${fullname}', '${phone}', 'ROLE_USER', true);"
                echo -e "${GREEN}✅ Клиент создан${NC}"
                pause
                ;;
            6)
                check_db || { pause; continue; }
                echo -ne "  Email клиента: "
                read -r email
                docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                    UPDATE users SET enabled = TRUE WHERE email = '${email}';"
                echo -e "${GREEN}✅ Готово${NC}"
                pause
                ;;
            7)
                check_db || { pause; continue; }
                echo -ne "  Email клиента: "
                read -r email
                echo -ne "  ${RED}Удалить пользователя и все его документы? (YES): ${NC}"
                read -r confirm
                if [ "$confirm" = "YES" ]; then
                    docker exec "${CONTAINER}" psql -U "${DB_USER}" -d "${DB}" -c "
                        DELETE FROM users WHERE email = '${email}';"
                    echo -e "${GREEN}✅ Удалено${NC}"
                else
                    echo -e "${YELLOW}Отменено${NC}"
                fi
                pause
                ;;
            0) return ;;
            *) echo -e "${RED}Неверный выбор${NC}"; sleep 1 ;;
        esac
    done
}

# ============================================================
# 7. PRODUCTION / DOCKER
# ============================================================
menu_prod() {
    while true; do
        clear_screen
        echo -e "${CYAN}${BOLD}  ┌─────────────────────────────────────────────┐${NC}"
        echo -e "${CYAN}${BOLD}  │        7. PRODUCTION / DOCKER               │${NC}"
        echo -e "${CYAN}${BOLD}  └─────────────────────────────────────────────┘${NC}"
        echo ""
        echo -e "${BOLD}  ${BLUE}Локальный тест прод-сборки:${NC}"
        echo -e "  ${GREEN}1.${NC} Собрать прод-образы"
        echo -e "  ${GREEN}2.${NC} Запустить прод-стек (локально, порт 8081)"
        echo -e "  ${GREEN}3.${NC} Остановить прод-стек"
        echo -e "  ${GREEN}4.${NC} Логи прод-стека"
        echo ""
        echo -e "${BOLD}  ${BLUE}Прод-развёртывание (VPS):${NC}"
        echo -e "  ${GREEN}5.${NC} Показать .env.prod.example"
        echo -e "  ${GREEN}6.${NC} Показать angie.conf"
        echo -e "  ${GREEN}7.${NC} Показать docker-compose-prod.yml"
        echo -e "  ${GREEN}8.${NC} Открыть README.md (раздел деплоя)"
        echo ""
        echo -e "  ${YELLOW}0.${NC} ← Назад"
        echo ""
        echo -ne "${BOLD}  Выбор: ${NC}"
        read -r choice

        case $choice in
            1)
                check_docker || { pause; continue; }
                echo -e "${BLUE}▶ Собираю прод-образы (2–3 минуты)...${NC}"
                docker compose -f docker-compose-prod-local.yml build
                pause
                ;;
            2)
                check_docker || { pause; continue; }
                echo -e "${BLUE}▶ Запускаю прод-стек...${NC}"
                docker compose -f docker-compose-prod-local.yml up -d
                sleep 20
                docker compose -f docker-compose-prod-local.yml ps
                echo ""
                echo -e "${GREEN}Приложение: ${APP_URL/8080/8081}${NC}"
                pause
                ;;
            3)
                echo -e "${BLUE}▶ Останавливаю прод-стек...${NC}"
                docker compose -f docker-compose-prod-local.yml down
                pause
                ;;
            4)
                docker compose -f docker-compose-prod-local.yml logs --tail 50 app 2>&1
                pause
                ;;
            5)
                echo -e "${BLUE}▶ .env.prod.example:${NC}"
                echo ""
                cat .env.prod.example 2>/dev/null || echo -e "${RED}Файл не найден${NC}"
                pause
                ;;
            6)
                echo -e "${BLUE}▶ angie.conf:${NC}"
                echo ""
                cat angie.conf 2>/dev/null || echo -e "${RED}Файл не найден${NC}"
                pause
                ;;
            7)
                echo -e "${BLUE}▶ docker-compose-prod.yml:${NC}"
                echo ""
                cat docker-compose-prod.yml 2>/dev/null || echo -e "${RED}Файл не найден${NC}"
                pause
                ;;
            8)
                echo -e "${BLUE}▶ Открываю README.md:${NC}"
                if command -v open >/dev/null 2>&1; then
                    open README.md
                elif command -v xdg-open >/dev/null 2>&1; then
                    xdg-open README.md
                else
                    less README.md
                fi
                pause
                ;;
            0) return ;;
            *) echo -e "${RED}Неверный выбор${NC}"; sleep 1 ;;
        esac
    done
}

# ============================================================
# 8. ПОЛЕЗНЫЕ ССЫЛКИ
# ============================================================
menu_links() {
    clear_screen
    echo -e "${CYAN}${BOLD}  ┌─────────────────────────────────────────────┐${NC}"
    echo -e "${CYAN}${BOLD}  │           8. ПОЛЕЗНЫЕ ССЫЛКИ                │${NC}"
    echo -e "${CYAN}${BOLD}  └─────────────────────────────────────────────┘${NC}"
    echo ""
    echo -e "${BOLD}  ${BLUE}Локальные адреса:${NC}"
    echo -e "  ${GREEN}●${NC} Вход клиента:      ${APP_URL}/login"
    echo -e "  ${GREEN}●${NC} Вход админа:       ${APP_URL}/admin/login"
    echo -e "  ${GREEN}●${NC} Healthcheck:       ${APP_URL}/actuator/health"
    echo -e "  ${GREEN}●${NC} Ping:              ${APP_URL}/ping"
    echo ""
    echo -e "${BOLD}  ${BLUE}Внешние ресурсы:${NC}"
    echo -e "  ${GREEN}●${NC} Chromium-Gost:     https://github.com/deemru/Chromium-Gost"
    echo -e "  ${GREEN}●${NC} Плагин КриптоПро:  https://www.cryptopro.ru/products/cades/plugin"
    echo -e "  ${GREEN}●${NC} Демо КриптоПро:    https://www.cryptopro.ru/sites/default/files/products/cades/demopage/cades_bes_sample.html"
    echo ""
    echo -e "${BOLD}  ${BLUE}Документация:${NC}"
    echo -e "  ${GREEN}●${NC} Flyway:            https://documentation.red-gate.com/flyway"
    echo -e "  ${GREEN}●${NC} pgAudit:           https://github.com/pgaudit/pgaudit"
    echo -e "  ${GREEN}●${NC} Angie:             https://angie.software/ru/docs/"
    echo ""
    echo -e "${BOLD}  ${BLUE}Проверить сейчас:${NC}"
    echo -ne "  ${YELLOW}Открыть какой URL? (1–8, 0 — отмена): ${NC}"
    read -r num

    case $num in
        1) open "${APP_URL}/login" 2>/dev/null || xdg-open "${APP_URL}/login" 2>/dev/null ;;
        2) open "${APP_URL}/admin/login" 2>/dev/null || xdg-open "${APP_URL}/admin/login" 2>/dev/null ;;
        3) open "${APP_URL}/actuator/health" 2>/dev/null || xdg-open "${APP_URL}/actuator/health" 2>/dev/null ;;
        4) open "${APP_URL}/ping" 2>/dev/null || xdg-open "${APP_URL}/ping" 2>/dev/null ;;
        5) open "https://github.com/deemru/Chromium-Gost" 2>/dev/null || xdg-open "https://github.com/deemru/Chromium-Gost" 2>/dev/null ;;
        6) open "https://www.cryptopro.ru/products/cades/plugin" 2>/dev/null || xdg-open "https://www.cryptopro.ru/products/cades/plugin" 2>/dev/null ;;
        7) open "https://www.cryptopro.ru/sites/default/files/products/cades/demopage/cades_bes_sample.html" 2>/dev/null ;;
        8) open "https://documentation.red-gate.com/flyway" 2>/dev/null ;;
        0) return ;;
    esac
    pause
}

# ============================================================
# ГЛАВНЫЙ ЦИКЛ
# ============================================================
main() {
    check_project

    while true; do
        show_menu
        read -r choice

        case $choice in
            1) menu_app ;;
            2) menu_db ;;
            3) menu_backup ;;
            4) menu_audit ;;
            5) menu_check ;;
            6) menu_users ;;
            7) menu_prod ;;
            8) menu_links ;;
            0)
                clear_screen
                echo -e "${PURPLE}${BOLD}  До встречи! 👋${NC}"
                echo ""
                exit 0
                ;;
            *)
                echo -e "${RED}  Неверный выбор${NC}"
                sleep 1
                ;;
        esac
    done
}

# Ctrl+C — корректный выход
trap 'echo ""; echo -e "${PURPLE}  До встречи! 👋${NC}"; exit 0' INT

main
