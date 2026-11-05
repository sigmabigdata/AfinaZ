#!/usr/bin/env bash
# Установка CLI-меню /usr/local/bin/afina

cat > /usr/local/bin/afina << 'AFINA_CLI'
#!/usr/bin/env bash
G='\033[0;32m'; Y='\033[1;33m'; R='\033[0;31m'; B='\033[0;34m'; C='\033[0;36m'; N='\033[0m'; BOLD='\033[1m'
cd /opt/afinaz || { echo -e "${R}Ошибка: /opt/afinaz${N}"; exit 1; }
pause() { read -p "Нажмите Enter..."; }

show_menu() {
    clear
    echo -e "${BOLD}══════════════════════════════════════════════════${N}"
    echo -e "${BOLD}      ПАО \"НБД-Банк\" · Панель управления       ${N}"
    echo -e "${BOLD}══════════════════════════════════════════════════${N}"
    echo -e "${C} 1.${N} 🚀 Деплой (git pull + сборка + запуск)"
    echo -e "${C} 2.${N} 🔄 Перезапустить приложение"
    echo -e "${C} 3.${N} 🩺 Health check"
    echo -e "${C} 4.${N} 📜 Логи (последние 50 строк)"
    echo -e "${C} 5.${N} 💾 Создать бэкап"
    echo -e "${C} 6.${N} 📦 Восстановить из бэкапа"
    echo -e "${C} 7.${N} 🧹 Очистка (Docker, CRL, логи)"
    echo -e "${C} 8.${N} 💻 Shell приложения"
    echo -e "${C} 9.${N} 🗄️  psql"
    echo -e "${C} 0.${N} ❌ Выход"
    echo -e "${BOLD}══════════════════════════════════════════════════${N}"
}

while true; do
    show_menu
    read -p "Выберите: " choice
    case $choice in
        1) git pull origin main
           docker compose -f docker-compose-prod.yml build --no-cache app
           docker compose -f docker-compose-prod.yml up -d --force-recreate app
           sleep 30
           docker logs afina-app --tail 15
           pause ;;
        2) docker compose -f docker-compose-prod.yml restart app
           sleep 5; docker logs afina-app --tail 10; pause ;;
        3) echo "Локальный:"; docker exec afina-app curl -s http://localhost:8080/actuator/health
           echo ""; echo "Внешний:"; curl -s https://afinasystems.ru/actuator/health; echo ""
           pause ;;
        4) docker logs afina-app --tail 50; pause ;;
        5) ./backup-prod.sh; ls -lh backups/ | tail -5; pause ;;
        6) echo "Доступные бэкапы:"; ls -1 backups/*.sql.gz.enc 2>/dev/null | sed 's|backups/||' | tail -10
           read -p "Имя файла: " bf
           bf=$(echo "$bf" | sed 's|^backups/||')
           [ -f "backups/$bf" ] && ./restore-prod.sh "backups/$bf" || echo "Файл не найден"
           pause ;;
        7) docker builder prune -a -f; docker image prune -a -f
           [ -f cleanup-crls.sh ] && ./cleanup-crls.sh
           df -h /; pause ;;
        8) docker exec -it afina-app /bin/sh; pause ;;
        9) docker exec -it afina-postgres psql -U postgres -d afina_db; pause ;;
        0) exit 0 ;;
        *) echo "Неверный выбор"; sleep 1 ;;
    esac
done
AFINA_CLI

chmod +x /usr/local/bin/afina
echo "✓ CLI установлен: /usr/local/bin/afina"
