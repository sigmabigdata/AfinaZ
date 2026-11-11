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
        list.add(analyzeTmp());
        list.add(analyzeAptCache());
        list.add(analyzeJournal());
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
                } catch (Exception ignored) {
                    // Игнорируем ошибки чтения файлов/каталогов
                }
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
                } catch (Exception ignored) {
                    // Игнорируем ошибки чтения файлов/каталогов
                }
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
                } catch (Exception ignored) {
                    // Игнорируем ошибки чтения файлов/каталогов
                }
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
                    try { acc[0] += Files.size(f); acc[1]++; } catch (Exception ignored) {
                    // Игнорируем ошибки чтения файлов/каталогов
                }
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
                    try { acc[0] += Files.size(f); acc[1]++; } catch (Exception ignored) {
                    // Игнорируем ошибки чтения файлов/каталогов
                }
                }
            });
        } catch (Exception e) { log.debug("analyzeOrphanVisualizations: {}", e.getMessage()); }
        return new Category("orphanvis", "Визуализации-сироты", acc[0], acc[1], "файлы без записи в БД");
    }

    private Category analyzeAptCache() {
        Path dir = Path.of("/var/cache/apt/archives");
        if (!Files.isDirectory(dir)) return new Category("apt", "APT cache", 0, 0, "нет каталога");
        long[] acc = {0, 0};
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                try {
                    if (f.getFileName().toString().endsWith(".deb")) {
                        acc[0] += Files.size(f); acc[1]++;
                    }
                } catch (Exception ignored) {
                    // Игнорируем ошибки чтения файлов/каталогов
                }
            });
        } catch (Exception e) { log.debug("analyzeAptCache: {}", e.getMessage()); }
        return new Category("apt", "APT cache (.deb)", acc[0], acc[1], "apt clean");
    }

    private Category analyzeJournal() {
        long bytes = 0;
        try {
            String out = runProcess("journalctl", "--disk-usage");
            // "Archived and active journals take up 44.4M in the file system."
            var m = java.util.regex.Pattern.compile("take up ([\\d.]+)([KMG]?)")
                    .matcher(out);
            if (m.find()) {
                double v = Double.parseDouble(m.group(1));
                bytes = switch (m.group(2)) {
                    case "G" -> (long) (v * 1024 * 1024 * 1024);
                    case "M" -> (long) (v * 1024 * 1024);
                    case "K" -> (long) (v * 1024);
                    default  -> (long) v;
                };
            }
        } catch (Exception e) { log.debug("analyzeJournal: {}", e.getMessage()); }
        // Освободится столько, сколько выше target (по умолчанию 200M)
        long target = settings.getIntOrDefault("cleanup.journal_max_mb", 200) * 1024L * 1024L;
        long freed = Math.max(0, bytes - target);
        return new Category("journal", "journald", freed, 0,
                "current=" + formatBytes(bytes) + ", target=" + formatBytes(target));
    }

    private Category analyzeTmp() {
        Path dir = Path.of("/tmp");
        if (!Files.isDirectory(dir)) return new Category("tmp", "Временные файлы", 0, 0, "нет /tmp");
        long[] acc = {0, 0};
        int maxDays = settings.getIntOrDefault("cleanup.tmp_max_age_days", 7);
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                try {
                    String n = f.getFileName().toString();
                    boolean ours = n.startsWith("afina-") || n.startsWith("rebuild-")
                            || n.startsWith("build-") || n.endsWith(".tmp");
                    if (ours && isOlderThan(f, maxDays)) {
                        acc[0] += Files.size(f); acc[1]++;
                    }
                } catch (Exception ignored) {
                    // Игнорируем ошибки чтения файлов/каталогов
                }
            });
        } catch (Exception e) { log.debug("analyzeTmp: {}", e.getMessage()); }
        return new Category("tmp", "Временные файлы", acc[0], acc[1],
                "afina-*/rebuild-*/build-* старше " + maxDays + " дн.");
    }

    /**
     * Считает reclaimable Docker-мусор (build cache + unused images + stopped containers).
     * Volumes НЕ учитываются — они не удаляются (там БД и angie_data).
     *
     * Использует Jackson для точного подсчёта с учётом SharedSize.
     * Без вычитания SharedSize сумма Size всех образов раздувается в 10-20 раз,
     * так как каждый слой учитывается в каждом образе.
     */
    private Category analyzeDocker() {
        try {
            String out = dockerApi("GET", "/v1.43/system/df");
            com.fasterxml.jackson.databind.JsonNode root = 
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
            long total = 0;

            // 1. Unused images: вычитаем SharedSize
            com.fasterxml.jackson.databind.JsonNode images = root.path("Images");
            if (images.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode img : images) {
                    if (img.path("Containers").asInt(1) == 0) {
                        long size = img.path("Size").asLong(0);
                        long shared = img.path("SharedSize").asLong(0);
                        total += Math.max(0, size - shared);
                    }
                }
            }

            // 2. Build Cache: только InUse == false
            com.fasterxml.jackson.databind.JsonNode cache = root.path("BuildCache");
            if (cache.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode c : cache) {
                    if (!c.path("InUse").asBoolean(true)) {
                        total += c.path("Size").asLong(0);
                    }
                }
            }

            // 3. Stopped containers
            com.fasterxml.jackson.databind.JsonNode containers = root.path("Containers");
            if (containers.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode c : containers) {
                    if ("exited".equals(c.path("State").asText())) {
                        total += c.path("SizeRw").asLong(0);
                    }
                }
            }

            return new Category("docker", "Docker (кэш, образы)", total, 0,
                    "reclaimable: cache + unused images (без shared-слоёв) + stopped containers");
        } catch (Exception e) {
            return new Category("docker", "Docker (кэш, образы)", 0, 0,
                    "curl+docker.sock недоступен: " + e.getMessage());
        }
    }

    /** HTTP-вызов к docker.sock через curl. */
    private String dockerApi(String method, String path) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-s", "-S", "--unix-socket", "/var/run/docker.sock",
                "-X", method, "http://localhost" + path);
        pb.environment().clear();
        pb.environment().put("PATH", safePath);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) throw new java.io.IOException("curl exit " + rc + ": " + out);
        return out;
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
    public CleanResult cleanTmp() {
        Path dir = Path.of("/tmp");
        if (!Files.isDirectory(dir)) return new CleanResult("tmp", 0, 0, "нет /tmp");
        int maxDays = settings.getIntOrDefault("cleanup.tmp_max_age_days", 7);
        return cleanFiles(dir, "tmp",
                f -> {
                    String n = f.getFileName().toString();
                    boolean ours = n.startsWith("afina-") || n.startsWith("rebuild-")
                            || n.startsWith("build-") || n.endsWith(".tmp");
                    return ours && isOlderThan(f, maxDays);
                });
    }

    public CleanResult cleanAptCache() {
        long before = analyzeAptCache().bytes();
        try {
            runProcess("apt-get", "clean");
            long after = analyzeAptCache().bytes();
            long freed = Math.max(0, before - after);
            return new CleanResult("apt", freed, 0,
                    "APT cache очищен: " + formatBytes(freed));
        } catch (Exception e) {
            return new CleanResult("apt", 0, 0, "Ошибка: " + e.getMessage());
        }
    }

    public CleanResult cleanJournal() {
        int maxMb = settings.getIntOrDefault("cleanup.journal_max_mb", 200);
        long before = analyzeJournal().bytes() + maxMb * 1024L * 1024L;
        try {
            runProcess("journalctl", "--vacuum-size=" + maxMb + "M");
            long after = analyzeJournal().bytes() + maxMb * 1024L * 1024L;
            long freed = Math.max(0, before - after);
            return new CleanResult("journal", freed, 0,
                    "journald сжат до " + maxMb + " MB (освобождено " + formatBytes(freed) + ")");
        } catch (Exception e) {
            return new CleanResult("journal", 0, 0, "Ошибка: " + e.getMessage());
        }
    }

    public CleanResult cleanDocker() {
        long freed = 0;
        StringBuilder msg = new StringBuilder();

        // 1. Build cache
        try {
            String r = dockerApi("POST", "/v1.43/build/prune?all=true");
            long f = parseLongField(r, "SpaceReclaimed", false);
            freed += f;
            msg.append("cache: ").append(formatBytes(f)).append("; ");
        } catch (Exception e) {
            log.warn("docker build prune: {}", e.getMessage());
            msg.append("cache fail; ");
        }

        // 2. Unused images
        try {
            String r = dockerApi("POST", "/v1.43/images/prune?all=true");
            long f = parseLongField(r, "SpaceReclaimed", false);
            freed += f;
            msg.append("images: ").append(formatBytes(f)).append("; ");
        } catch (Exception e) {
            log.warn("docker image prune: {}", e.getMessage());
            msg.append("images fail; ");
        }

        // 3. Stopped containers (без volumes)
        try {
            dockerApi("POST", "/v1.43/containers/prune");
            msg.append("containers: pruned; ");
        } catch (Exception e) {
            msg.append("containers fail; ");
        }

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
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor(DOCKER_TIMEOUT_SEC, TimeUnit.SECONDS);
        return sb.toString();
    }

    public static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1024.0 / 1024);
        return String.format("%.2f GB", b / 1024.0 / 1024 / 1024);
    }
    /** Парсит числовое поле JSON по имени (первое вхождение). */
    private static long parseLongField(String json, String field, boolean any) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        long sum = 0;
        while (m.find()) {
            try { sum += Long.parseLong(m.group(1)); } catch (Exception ignored) {}
            if (!any) break;
        }
        return sum;
    }

}
