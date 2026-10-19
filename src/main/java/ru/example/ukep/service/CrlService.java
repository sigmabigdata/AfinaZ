package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Service
public class CrlService {

    private static final Logger log = LoggerFactory.getLogger(CrlService.class);

    private final Path crlsDir;

    public CrlService(@Value("${app.crl-path:/app/crls}") String crlPath) {
        this.crlsDir = Paths.get(crlPath).toAbsolutePath().normalize();
    }

    public record CrlInfo(
            String fileName,
            long sizeBytes,
            String sizePretty,
            String issuer,
            Instant lastUpdate,
            Instant nextUpdate,
            long daysToExpiry,
            boolean manual
    ) {}

    public List<CrlInfo> listAll() {
        if (!Files.isDirectory(crlsDir)) return List.of();

        List<CrlInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(crlsDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(f -> f.getFileName().toString().endsWith(".crl"))
                  .map(this::readCrlInfo)
                  .filter(Objects::nonNull)
                  .forEach(result::add);
        } catch (Exception e) {
            log.error("listAll error", e);
        }

        result.sort(Comparator.comparing(CrlInfo::issuer).thenComparing(CrlInfo::fileName));
        return result;
    }

    /** Парсит один CRL-файл. Возвращает null, если файл повреждён. */
    private CrlInfo readCrlInfo(Path f) {
        try (InputStream is = Files.newInputStream(f)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(is);
            long size = Files.size(f);

            Instant lastUpdate = crl.getThisUpdate() != null
                    ? crl.getThisUpdate().toInstant() : null;
            Instant nextUpdate = crl.getNextUpdate() != null
                    ? crl.getNextUpdate().toInstant() : null;
            long daysToExpiry = nextUpdate != null
                    ? (nextUpdate.toEpochMilli() - System.currentTimeMillis()) / 86_400_000L
                    : -1;

            String name = f.getFileName().toString();
            boolean manual = !name.startsWith("auto-");

            return new CrlInfo(
                    name, size, prettySize(size),
                    crl.getIssuerX500Principal().getName(),
                    lastUpdate, nextUpdate, daysToExpiry, manual);
        } catch (Exception e) {
            log.warn("Не удалось разобрать {}: {}", f.getFileName(), e.getMessage());
            return null;
        }
    }

    /** Загрузить CRL вручную из байтов. */
    public void saveManual(String originalName, byte[] data)
            throws java.io.IOException,
                   java.security.cert.CertificateException,
                   java.security.cert.CRLException {
        // Валидация: должен быть корректный CRL
        try (InputStream is = new java.io.ByteArrayInputStream(data)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cf.generateCRL(is);
        }

        // Очистка имени
        String safe = originalName == null ? "manual.crl"
                : originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!safe.endsWith(".crl")) safe += ".crl";

        Path target = crlsDir.resolve(safe);
        Files.createDirectories(crlsDir);
        Files.write(target, data,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
        log.info("CRL сохранён вручную: {} ({} байт)", safe, data.length);
    }

    public void delete(String fileName) throws java.io.IOException {
        Path target = crlsDir.resolve(fileName).normalize();
        if (!target.startsWith(crlsDir)) {
            throw new SecurityException("Недопустимый путь");
        }
        Files.deleteIfExists(target);
        log.info("CRL удалён: {}", fileName);
    }

    /** Общий размер всех .crl в директории. */
    public long getTotalSize() {
        if (!Files.isDirectory(crlsDir)) return 0;
        try (var stream = Files.list(crlsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".crl"))
                    .mapToLong(f -> {
                        try { return Files.size(f); } catch (Exception e) { return 0; }
                    })
                    .sum();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Количество .crl файлов. */
    public long getCount() {
        if (!Files.isDirectory(crlsDir)) return 0;
        try (var stream = Files.list(crlsDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".crl"))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Удалить несколько файлов по именам. */
    public int deleteMany(List<String> names) {
        int n = 0;
        for (String name : names) {
            try {
                delete(name);
                n++;
            } catch (Exception e) {
                log.warn("Не удалось удалить {}: {}", name, e.getMessage());
            }
        }
        return n;
    }

    /** Удалить все auto-*.crl. Возвращает количество удалённых. */
    public int deleteAllAuto() {
        if (!Files.isDirectory(crlsDir)) return 0;
        int count = 0;
        try (var stream = Files.list(crlsDir)) {
            var files = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().startsWith("auto-"))
                    .filter(f -> f.getFileName().toString().endsWith(".crl"))
                    .toList();
            for (var f : files) {
                if (deleteQuietly(f)) count++;
            }
            log.info("Удалено auto-*.crl: {}", count);
        } catch (Exception e) {
            log.error("deleteAllAuto error", e);
        }
        return count;
    }

    /** Удалить файл, не падая на ошибке. Возвращает true при успехе. */
    private boolean deleteQuietly(Path f) {
        try {
            Files.deleteIfExists(f);
            return true;
        } catch (Exception e) {
            log.warn("Не удалось удалить {}: {}", f.getFileName(), e.getMessage());
            return false;
        }
    }

    public Path getCrlsDir() { return crlsDir; }

    public static String prettySize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.2f MB", bytes / 1024.0 / 1024);
    }

    public static String formatInstant(Instant i) {
        if (i == null) return "—";
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(ZoneId.systemDefault()).format(i);
    }
}
