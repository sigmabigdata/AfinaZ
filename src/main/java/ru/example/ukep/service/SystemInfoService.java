package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Собирает метрики сервера и БД для админ-панели.
 * Читает /proc (Linux) и данные через JdbcTemplate.
 */
@Service
public class SystemInfoService {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoService.class);
    private static final String K_INSTALLED_ON = "installed_on";
    private static final String K_TOTAL = "total";
    private static final String K_PERCENT = "percent";

    private final JdbcTemplate jdbc;

    public SystemInfoService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Uptime приложения. */
    public Duration getJvmUptime() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        return Duration.ofMillis(rt.getUptime());
    }

    /** Средняя нагрузка (1/5/15 мин). */
    public double[] getLoadAverage() {
        try {
            java.lang.management.OperatingSystemMXBean os =
                    ManagementFactory.getOperatingSystemMXBean();
            double load = os.getSystemLoadAverage();
            return new double[]{load, load, load};
        } catch (Exception e) {
            return new double[]{0, 0, 0};
        }
    }

    /** RAM: всего/использовано/свободно (МБ). */
    public Map<String, Long> getMemory() {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            // Читаем /proc/meminfo (Linux)
            List<String> lines = Files.readAllLines(Path.of("/proc/meminfo"));
            long total = 0;
            long available = 0;
            long free = 0;
            for (String line : lines) {
                if (line.startsWith("MemTotal:"))        total     = parseKb(line);
                else if (line.startsWith("MemAvailable:")) available = parseKb(line);
                else if (line.startsWith("MemFree:"))      free      = parseKb(line);
            }
            long used = total - available;
            result.put(K_TOTAL,     total / 1024);      // MB
            result.put("used",      used  / 1024);
            result.put("available", available / 1024);
            result.put("free",      free / 1024);
            result.put(K_PERCENT,   total > 0 ? (used * 100 / total) : 0);
        } catch (Exception e) {
            // fallback — данные JVM
            Runtime rt = Runtime.getRuntime();
            long total = rt.totalMemory();
            long free  = rt.freeMemory();
            result.put(K_TOTAL,     total / 1024 / 1024);
            result.put("used",      (total - free) / 1024 / 1024);
            result.put("available", free / 1024 / 1024);
            result.put("free",      free / 1024 / 1024);
            result.put(K_PERCENT,   total > 0 ? ((total - free) * 100 / total) : 0);
        }
        return result;
    }

    /** CPU: количество ядер + примерная загрузка (%). */
    public Map<String, Object> getCpu() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cores", Runtime.getRuntime().availableProcessors());
        result.put("loadAvg", getLoadAverage());
        result.put(K_PERCENT, cpuPercent());
        return result;
    }

    private long parseKb(String line) {
        try {
            String v = line.replaceAll("\\D", " ").trim().split("\\s+")[0];
            return Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Примерная загрузка CPU через /proc/stat. */
    private long cpuPercent() {
        try {
            long[] s1 = readCpuStat();
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return 0;
            }
            long[] s2 = readCpuStat();
            long idle  = s2[3] - s1[3];
            long total = 0;
            for (int i = 0; i < s2.length; i++) total += s2[i] - s1[i];
            if (total <= 0) return 0;
            return (total - idle) * 100 / total;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private long[] readCpuStat() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("/proc/stat"));
        for (String line : lines) {
            if (line.startsWith("cpu ")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                long[] arr = new long[parts.length];
                for (int i = 0; i < parts.length; i++) arr[i] = Long.parseLong(parts[i]);
                return arr;
            }
        }
        return new long[10];
    }

    /** Диск: общий/использованный/свободный (ГБ). */
    public Map<String, Long> getDisk() {
        Map<String, Long> result = new LinkedHashMap<>();
        File root = new File("/");
        long total = root.getTotalSpace();
        long free  = root.getFreeSpace();
        long used  = total - free;
        result.put(K_TOTAL,   total / 1024 / 1024 / 1024);
        result.put("used",    used  / 1024 / 1024 / 1024);
        result.put("free",    free  / 1024 / 1024 / 1024);
        result.put(K_PERCENT, total > 0 ? (used * 100 / total) : 0);
        return result;
    }

    /** БД: размер, таблицы, миграции, соединения. */
    public Map<String, Object> getDatabaseStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Long size = jdbc.queryForObject(
                "SELECT pg_database_size(current_database())", Long.class);
            result.put("sizeBytes", size == null ? 0 : size);
            result.put("sizePretty", size == null ? "0 B" : prettySize(size));
        } catch (Exception e) {
            result.put("sizePretty", "n/a");
        }

        try {
            List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT relname AS name, " +
                "       n_live_tup AS rows, " +
                "       pg_total_relation_size(relid) AS size " +
                "FROM pg_stat_user_tables " +
                "ORDER BY pg_total_relation_size(relid) DESC");
            List<Map<String, Object>> pretty = new ArrayList<>();
            for (Map<String, Object> t : tables) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t.get("name"));
                m.put("rows", t.get("rows"));
                Long sz = ((Number) t.get("size")).longValue();
                m.put("size", prettySize(sz));
                pretty.add(m);
            }
            result.put("tables", pretty);
        } catch (Exception e) {
            result.put("tables", List.of());
        }

        try {
            List<Map<String, Object>> raw = jdbc.queryForList(
                "SELECT version, description, success, installed_on " +
                "FROM flyway_schema_history ORDER BY installed_rank");
            List<Map<String, Object>> migr = new ArrayList<>();
            for (Map<String, Object> r : raw) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", r.get("version"));
                m.put("description", r.get("description"));
                m.put("success", r.get("success"));
                Object ts = r.get(K_INSTALLED_ON);
                // Timestamp → Instant, чтобы Thymeleaf #temporals умел форматировать
                if (ts instanceof java.sql.Timestamp timestamp) {
                    m.put(K_INSTALLED_ON, timestamp.toInstant());
                } else {
                    m.put(K_INSTALLED_ON, ts);
                }
                migr.add(m);
            }
            result.put("migrations", migr);
        } catch (Exception e) {
            result.put("migrations", List.of());
        }

        try {
            Long conns = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()",
                Long.class);
            result.put("connections", conns);
        } catch (Exception e) {
            result.put("connections", 0);
        }

        try {
            Long users = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
            Long docs  = jdbc.queryForObject("SELECT count(*) FROM documents", Long.class);
            Long sigs  = jdbc.queryForObject("SELECT count(*) FROM document_signatures", Long.class);
            result.put("usersCount", users);
            result.put("docsCount", docs);
            result.put("sigsCount", sigs);
        } catch (Exception ex) {
            log.debug("stat query failed: {}", ex.getMessage());
        }

        return result;
    }

    /** Информация о JVM / OS / сборке. */
    public Map<String, String> getRuntimeInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVendor", System.getProperty("java.vendor"));
        info.put("osName", System.getProperty("os.name"));
        info.put("osArch", System.getProperty("os.arch"));
        info.put("osVersion", System.getProperty("os.version"));
        info.put("userDir", System.getProperty("user.dir"));
        info.put("timezone", System.getProperty("user.timezone"));
        try {
            File f = new File(System.getProperty("user.dir"));
            info.put("workingDir", f.getAbsolutePath());
        } catch (Exception ex) {
            log.debug("stat query failed: {}", ex.getMessage());
        }
        return info;
    }

    /** Размер файлов в storage/documents. */
    public java.util.Map<String, Object> getStorageSize() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            java.nio.file.Path root = java.nio.file.Paths.get(
                    System.getProperty("app.storage-path", "/app/storage/documents"));
            if (!java.nio.file.Files.isDirectory(root)) {
                root = java.nio.file.Paths.get("/app/storage/documents");
            }
            long total = 0;
            long count = 0;
            try (var stream = java.nio.file.Files.walk(root)) {
                var list = stream.filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".enc"))
                        .toList();
                for (var f : list) {
                    total += java.nio.file.Files.size(f);
                    count++;
                }
            }
            result.put("bytes", total);
            result.put("pretty", prettySize(total));
            result.put("files", count);
        } catch (Exception e) {
            result.put("bytes", 0L);
            result.put("pretty", "n/a");
            result.put("files", 0L);
        }
        return result;
    }

    public String prettySize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1024 / 1024) + " MB";
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    /** Диагностические проверки БД. Возвращает список проверок. */
    public java.util.List<java.util.Map<String, Object>> runDiagnostics() {
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();

        // 1. Доступность БД
        list.add(check("Доступность БД", () -> {
            Integer x = jdbc.queryForObject("SELECT 1", Integer.class);
            return x != null && x == 1 ? "OK" : "FAIL";
        }));

        // 2. Роли и права
        list.add(check("Роли БД (3 ожидаются)", () -> {
            Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_roles " +
                "WHERE rolname IN ('afina_app','afina_migrator','afina_auditor')",
                Integer.class);
            return n + " / 3";
        }));

        // 3. Целостность: сироты в documents
        list.add(check("Документы без владельца", () -> {
            Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM documents d " +
                "WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = d.owner_id)",
                Integer.class);
            return n == 0 ? "OK (0)" : "ПРОБЛЕМА: " + n;
        }));

        // 4. Целостность: подписи без документа
        list.add(check("Подписи без документа", () -> {
            Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM document_signatures s " +
                "WHERE NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = s.document_id)",
                Integer.class);
            return n == 0 ? "OK (0)" : "ПРОБЛЕМА: " + n;
        }));

        // 5. Размер БД
        list.add(check("Размер БД", () -> jdbc.queryForObject(
                "SELECT pg_size_pretty(pg_database_size(current_database()))",
                String.class)));

        // 6. Миграции
        list.add(check("Все миграции успешны", () -> {
            Integer bad = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false",
                Integer.class);
            return bad == 0 ? "OK" : "FAIL: " + bad;
        }));

        // 7. Активные соединения
        list.add(check("Активные соединения", () -> {
            Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()",
                Integer.class);
            return String.valueOf(n);
        }));

        return list;
    }

    private java.util.Map<String, Object> check(String name, java.util.concurrent.Callable<String> c) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        try {
            m.put("value", c.call());
            m.put("ok", true);
        } catch (Exception e) {
            m.put("value", "Ошибка: " + e.getMessage());
            m.put("ok", false);
        }
        return m;
    }
}
