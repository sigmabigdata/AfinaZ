package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.DocumentVisualizationRepository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Очистка мусора на сервере.
 *
 * <p><b>Безопасность:</b> docker-очистка НИКОГДА не передаёт {@code --volumes},
 * поэтому pgdata, angie_data и прочие volumes остаются целы. Удаляются только
 * build cache, dangling images и остановленные контейнеры.</p>
 *
 * <p>Retention настраивается через app_settings:
 * {@code cleanup.crls_max_age_days}, {@code cleanup.backups_max_age_days},
 * {@code cleanup.logs_max_age_days}.</p>
 */
@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);
    private static final long DOCKER_TIMEOUT_SEC = 120;
    private static final Pattern RECLAIM = Pattern.compile(
            "Total reclaimed space:\\s*([\\d.]+\\s*[A-Za-z]+)");

    /** Отчёт по мусору в одной категории. */
    public record Category(String key, String title, long bytes, long count, String note) {}

    /** Полный отчёт. */
    public record Report(List<Category> categories, long totalBytes) {}

    /** Результат очистки. */
    public record CleanResult(String category, long freedBytes, long deletedCount, String message) {}

    private final DocumentRepository documentRepository;
    private final DocumentVisualizationRepository visRepository;
    private final SettingsService settings;

    @Value("${app.storage-path}")
    private String storagePath;

    @Value("${app.logs-path:/app/logs}")
    private String logsPath;

    @Value("${app.backups-path:/app/backups}")
    private String backupsPath;

    @Value("${app.crl-path:/app/crls}")
    private String crlsPath;

    @Value("${app.safe-path:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}")
    private String safePath;

    public CleanupService(DocumentRepository documentRepository,
                          DocumentVisualizationRepository visRepository,
                          SettingsService settings) {
        this.documentRepository = documentRepository;
        this.visRepository = visRepository;
        this.settings = settings;
    }

    // ─────────────── Анализ ───────────────

    public Report analyze() {
        List<Category> list = new ArrayList<>();
        list.add(analyzeLogs());
        list.add(analyzeCrls());
        list.add(analyzeBackups());
        list.add(analyzeOrphanFiles());
        list.add(analyzeOrphanVisualizations());
        list.add(analyzeDocker());
        long total = list.stream().mapToLong(Category::bytes).sum();
        return new Report(list, total);
    }

    private Category analyzeLogs() {
        Path dir = Path.of(logsPath);
        if (!Files.isDirectory(dir)) return new Category("logs", "Логи приложения", 0, 0, "нет каталога");
        long[] acc = {0, 0};
        int maxDays = settings.getIntOrDefault("cleanup.logs_max_age_days", 30);
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                try {
                    String n = f.getFileName().toString();
                    if (n.endsWith(".log.gz") && isOlderThan(f, maxDays)) {
                        acc[0] += Files.size(f); acc[1]++;
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception e) { log.debug("analyzeLogs: {}", e.getMessage()); }
        return new Category("logs", "Логи приложения", acc[0], acc[1], "старше " + maxDays + " дн.");
    }

    private Category analyzeCrls() {
        Path dir = Path.of(crlsPath);
        if (!Files.isDirectory(dir)) return new Category("crls", "CRL (auto-*)", 0, 0, "нет каталога");
        long[] acc = {0, 0};
        int maxDays = settings.getIntOrDefault("cleanup.crls_max_age_days", 90);
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                try {
                    String n = f.getFileName().toString();
                    if (n.startsWith("auto-") && n.endsWith(".crl") && isOlderThan(f, maxDays)) {
                        acc[0] += Files.size(f); acc[1]++;
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception e) { log.debug("analyzeCrls: {}", e.getMessage()); }
        return new Category("crls", "CRL (auto-*)", acc[0], acc[1], "старше " + maxDays + " дн.");
    }

    private Category analyzeBackups() {
        Path dir = Path.of(backupsPath);
        if (!Files.isDirectory(dir)) return new Category("backups", "Бэкапы БД/storage", 0, 0, "нет каталога");
        long[] acc = {0, 0};
        int maxDays = settings.getIntOrDefault("cleanup.backups_max_age_days", 30);
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                try {
                    String n = f.getFileName().toString();
                    boolean isBackup = n.startsWith("afina_") || n.startsWith("storage_")
                            || n.startsWith("test_");
                    if (isBackup && isOlderThan(f, maxDays)) {
                        acc[0] += Files.size(f); acc[1]++;
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception e) { log.debug("analyzeBackups: {}", e.getMessage()); }
        return new Category("backups", "Бэкапы БД/storage", acc[0], acc[1], "старше " + maxDays + " дн.");
    }

    private Category analyzeOrphanFiles() {
        Path dir = Path.of(storagePath);
        if (!Files.isDirectory(dir)) return new Category("orphans", "Файлы-сироты (storage)", 0, 0, "нет каталога");
        Set<String> known = new HashSet<>();
        documentRepository.findAll().forEach(d -> {
            if (d.getStoredName() != null) known.add(d.getStoredName());
        });
        long[] acc = {0, 0};
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString();
                if (!name.endsWith(".enc")) return;
                if (!known.contains(name)) {
                    try { acc[0] += Files.size(f); acc[1]++; } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) { log.debug("analyzeOrphanFiles: {}", e.getMessage()); }
        return new Category("orphans", "Файлы-сироты (storage)", acc[0], acc[1], "файлы без записи в БД");
    }

    private Category analyzeOrphanVisualizations() {
        Path dir = Path.of(storagePath).resolve("visualizations");
        if (!Files.isDirectory(dir)) return new Category("orphanvis", "Визуализации-сироты", 0, 0, "нет каталога");
        Set<String> known = new HashSet<>();
        visRepository.findAll().forEach(v -> {
            if (v.getStoredName() != null) known.add(v.getStoredName());
        });
        long[] acc = {0, 0};
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString();
                if (!name.endsWith(".pdf")) return;
                if (!known.contains(name)) {
                    try { acc[0] += Files.size(f); acc[1]++; } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) { log.debug("analyzeOrphanVisualizations: {}", e.getMessage()); }
        return new Category("orphanvis", "Визуализации-сироты", acc[0], acc[1], "файлы без записи в БД");
    }

    private Category analyzeDocker() {
        try {
            String out = runProcess("docker", "system", "df", "--format",
                    "{{.Type}}\t{{.Size}}\t{{.Reclaimable}}");
            long total = 0;
            StringBuilder note = new StringBuilder();
            for (String line : out.split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t");
                if (parts.length < 3) continue;
                note.append(parts[0]).append("=").append(parts[1]).append(" ");
                String reclaim = parts[2].replaceAll("\\s*\\(.*", "").trim();
                total += parseHumanBytes(reclaim);
            }
            return new Category("docker", "Docker (кэш, образы)", total, 0, note.toString().trim());
        } catch (Exception e) {
            return new Category("docker", "Docker (кэш, образы)", 0, 0, "недоступно: " + e.getMessage());
        }
    }

    // ─────────────── Очистка ───────────────

    public CleanResult cleanLogs() {
        return cleanFiles(Path.of(logsPath), "logs",
                f -> {
                    String n = f.getFileName().toString();
                    return n.endsWith(".log.gz")
                            && isOlderThan(f, settings.getIntOrDefault("cleanup.logs_max_age_days", 30));
                });
    }

    public CleanResult cleanCrls() {
        return cleanFiles(Path.of(crlsPath), "crls",
                f -> {
                    String n = f.getFileName().toString();
                    return n.startsWith("auto-") && n.endsWith(".crl")
                            && isOlderThan(f, settings.getIntOrDefault("cleanup.crls_max_age_days", 90));
                });
    }

    public CleanResult cleanBackups() {
        return cleanFiles(Path.of(backupsPath), "backups",
                f -> {
                    String n = f.getFileName().toString();
                    boolean isBackup = n.startsWith("afina_") || n.startsWith("storage_")
                            || n.startsWith("test_");
                    return isBackup
                            && isOlderThan(f, settings.getIntOrDefault("cleanup.backups_max_age_days", 30));
                });
    }

    public CleanResult cleanOrphanFiles() {
        Path dir = Path.of(storagePath);
        if (!Files.isDirectory(dir)) return new CleanResult("orphans", 0, 0, "нет каталога");
        Set<String> known = new HashSet<>();
        documentRepository.findAll().forEach(d -> {
            if (d.getStoredName() != null) known.add(d.getStoredName());
        });
        long[] acc = {0, 0};
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString();
                if (!name.endsWith(".enc")) return;
                if (known.contains(name)) return;
                try {
                    long sz = Files.size(f);
                    if (Files.deleteIfExists(f)) { acc[0] += sz; acc[1]++; }
                } catch (Exception e) {
                    log.warn("cleanOrphanFiles: не удалось удалить {}: {}", name, e.getMessage());
                }
            });
        } catch (Exception e) { log.debug("cleanOrphanFiles: {}", e.getMessage()); }
        return new CleanResult("orphans", acc[0], acc[1], "Удалено файлов-сирот: " + acc[1]);
    }

    public CleanResult cleanOrphanVisualizations() {
        Path dir = Path.of(storagePath).resolve("visualizations");
        if (!Files.isDirectory(dir)) return new CleanResult("orphanvis", 0, 0, "нет каталога");
        Set<String> known = new HashSet<>();
        visRepository.findAll().forEach(v -> {
            if (v.getStoredName() != null) known.add(v.getStoredName());
        });
        long[] acc = {0, 0};
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString();
                if (!name.endsWith(".pdf")) return;
                if (known.contains(name)) return;
                try {
                    long sz = Files.size(f);
                    if (Files.deleteIfExists(f)) { acc[0] += sz; acc[1]++; }
                } catch (Exception e) {
                    log.warn("cleanOrphanVisualizations: не удалось удалить {}: {}", name, e.getMessage());
                }
            });
        } catch (Exception e) { log.debug("cleanOrphanVisualizations: {}", e.getMessage()); }
        return new CleanResult("orphanvis", acc[0], acc[1], "Удалено визуализаций-сирот: " + acc[1]);
    }

    /**
     * Docker-очистка. БЕЗ --volumes.
     * Порядок: build cache → dangling+unused images → stopped containers.
     */
    public CleanResult cleanDocker() {
        long freed = 0;
        StringBuilder msg = new StringBuilder();
        try {
            String r1 = runProcess("docker", "builder", "prune", "-a", "-f");
            freed += parseReclaimed(r1); msg.append("cache: ").append(parseReclaimStr(r1)).append("; ");
        } catch (Exception e) { msg.append("cache fail; "); }
        try {
            String r2 = runProcess("docker", "image", "prune", "-a", "-f");
            freed += parseReclaimed(r2); msg.append("images: ").append(parseReclaimStr(r2)).append("; ");
        } catch (Exception e) { msg.append("images fail; "); }
        try {
            String r3 = runProcess("docker", "container", "prune", "-f");
            msg.append("containers: ").append(parseReclaimStr(r3)).append("; ");
        } catch (Exception e) { msg.append("containers fail; "); }

        return new CleanResult("docker", freed, 0,
                "Docker: " + formatBytes(freed) + " (" + msg + ")");
    }

    // ─────────────── Хелперы ───────────────

    private CleanResult cleanFiles(Path dir, String category, Predicate<Path> filter) {
        if (!Files.isDirectory(dir)) return new CleanResult(category, 0, 0, "нет каталога");
        long[] acc = {0, 0};
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).filter(filter).forEach(f -> {
                try {
                    long sz = Files.size(f);
                    if (Files.deleteIfExists(f)) { acc[0] += sz; acc[1]++; }
                } catch (Exception e) {
                    log.warn("cleanFiles[{}]: не удалось удалить {}: {}",
                            category, f.getFileName(), e.getMessage());
                }
            });
        } catch (Exception e) { log.debug("cleanFiles[{}]: {}", category, e.getMessage()); }
        return new CleanResult(category, acc[0], acc[1],
                "Удалено файлов: " + acc[1] + " (" + formatBytes(acc[0]) + ")");
    }

    private static boolean isOlderThan(Path f, int days) {
        if (days <= 0) return false;
        try {
            Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
            return Files.getLastModifiedTime(f).toInstant().isBefore(cutoff);
        } catch (Exception e) { return false; }
    }

    private String runProcess(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().put("PATH", safePath);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor(DOCKER_TIMEOUT_SEC, TimeUnit.SECONDS);
        return sb.toString();
    }

    private long parseReclaimed(String s) {
        Matcher m = RECLAIM.matcher(s);
        if (m.find()) return parseHumanBytes(m.group(1));
        return 0;
    }

    private String parseReclaimStr(String s) {
        Matcher m = RECLAIM.matcher(s);
        return m.find() ? m.group(1) : "0B";
    }

    public static long parseHumanBytes(String s) {
        if (s == null || s.isBlank()) return 0;
        String t = s.trim().toUpperCase().replaceAll("[^0-9.A-Z]", "");
        try {
            if (t.endsWith("B") && !t.endsWith("KB") && !t.endsWith("MB") && !t.endsWith("GB")
                    && !t.endsWith("TB")) {
                return (long) Double.parseDouble(t.substring(0, t.length() - 1));
            }
            if (t.endsWith("KB")) return (long) (Double.parseDouble(t.substring(0, t.length() - 2)) * 1024);
            if (t.endsWith("MB")) return (long) (Double.parseDouble(t.substring(0, t.length() - 2)) * 1024 * 1024);
            if (t.endsWith("GB")) return (long) (Double.parseDouble(t.substring(0, t.length() - 2)) * 1024 * 1024 * 1024);
            if (t.endsWith("TB")) return (long) (Double.parseDouble(t.substring(0, t.length() - 2)) * 1024L * 1024 * 1024 * 1024);
            return (long) Double.parseDouble(t);
        } catch (Exception e) { return 0; }
    }

    public static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1024.0 / 1024);
        return String.format("%.2f GB", b / 1024.0 / 1024 / 1024);
    }
}
