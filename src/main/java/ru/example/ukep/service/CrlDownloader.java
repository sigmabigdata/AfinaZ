package ru.example.ukep.service;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * Загружает CRL по URL из CDP-расширения (CRL Distribution Points)
 * сертификатов подписантов.
 *
 * Файлы кладутся в crlDir с именем auto-<hash(issuer)[:16]>.crl.
 * Дедупликация по issuer: разные зеркала одного CA → один файл.
 * При обновлении старый файл удаляется.
 *
 * TTL задаётся через app.crl-max-age-hours (по умолчанию 24).
 */
@Service
public class CrlDownloader {

    private static final Logger log = LoggerFactory.getLogger(CrlDownloader.class);

    private static final String CDP_OID = "2.5.29.31";
    private static final String AUTO_PREFIX = "auto-";
    private static final String CRL_SUFFIX = ".crl";

    private final Path crlDir;
    private final long maxAgeHours;

    /** Максимальный размер CRL (в байтах). Больше — не парсим, не сохраняем. */
    private static final long MAX_CRL_SIZE_BYTES = 20L * 1024 * 1024; // 20 MB

    public CrlDownloader(
            @Value("${app.crl-path:/app/crls}") String crlPath,
            @Value("${app.crl-max-age-hours:24}") long maxAgeHours) throws java.io.IOException {
        this.crlDir = Paths.get(crlPath).toAbsolutePath().normalize();
        Files.createDirectories(crlDir);
        this.maxAgeHours = maxAgeHours;
        log.info("CrlDownloader: директория {}, TTL {} ч", crlDir, maxAgeHours);
    }

    /**
     * Скачивает CRL для всех переданных сертификатов (идемпотентно).
     * Возвращает количество успешно загруженных/обновлённых файлов.
     */
    public int ensureCrlsFor(Set<X509Certificate> certs) {
        return ensureCrlsFor(certs, false);
    }

    /**
     * @param force true — игнорировать TTL и перекачать CRL принудительно
     */
    public int ensureCrlsFor(Set<X509Certificate> certs, boolean force) {
        return ensureCrlsForWithDetails(certs, force).success();
    }

    /**
     * @return success — успешных обновлений, failed — неудачных,
     *         failures — список "url: error" для UI/логов
     */
    public record DownloadReport(int success, int failed, java.util.List<String> failures) {}

    public DownloadReport ensureCrlsForWithDetails(Set<X509Certificate> certs, boolean force) {
        Set<String> urls = new HashSet<>();
        for (X509Certificate cert : certs) {
            urls.addAll(extractCdpUrls(cert));
        }
        int count = 0;
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (String url : urls) {
            try {
                if (downloadIfStale(url, force)) count++;
            } catch (Exception e) {
                String shortUrl = url.length() > 60 ? url.substring(0, 60) + "..." : url;
                String msg = shortUrl + " — " + e.getMessage();
                failures.add(msg);
                log.warn("CRL download failed для {}: {}", url, e.getMessage());
            }
        }
        return new DownloadReport(count, failures.size(), failures);
    }

    /** Извлекает все CDP URL (http/https) из расширения 2.5.29.31. */
    private Set<String> extractCdpUrls(X509Certificate cert) {
        byte[] ext;
        try {
            ext = cert.getExtensionValue(CDP_OID);
        } catch (Exception e) {
            return Set.of();
        }
        if (ext == null) return Set.of();

        try {
            CRLDistPoint cdp = parseCdpExtension(ext);
            return collectHttpUrls(cdp);
        } catch (Exception e) {
            log.debug("CDP parse failed для {}: {}",
                    cert.getSubjectX500Principal(), e.getMessage());
            return Set.of();
        }
    }

    /** Парсит ASN.1-обёртку расширения CDP → CRLDistPoint. */
    private CRLDistPoint parseCdpExtension(byte[] ext) throws java.io.IOException {
        try (ASN1InputStream aIn = new ASN1InputStream(ext)) {
            ASN1OctetString octet = (ASN1OctetString) aIn.readObject();
            try (ASN1InputStream innerIn = new ASN1InputStream(octet.getOctets())) {
                ASN1Primitive p = innerIn.readObject();
                return CRLDistPoint.getInstance(p);
            }
        }
    }

    /** Собирает http/https URI из всех distribution points. */
    private Set<String> collectHttpUrls(CRLDistPoint cdp) {
        Set<String> urls = new HashSet<>();
        for (DistributionPoint dp : cdp.getDistributionPoints()) {
            DistributionPointName name = dp.getDistributionPoint();
            if (name == null || name.getType() != DistributionPointName.FULL_NAME) continue;
            GeneralNames gns = GeneralNames.getInstance(name.getName());
            for (GeneralName gn : gns.getNames()) {
                if (gn.getTagNo() != GeneralName.uniformResourceIdentifier) continue;
                String url = org.bouncycastle.asn1.ASN1IA5String.getInstance(gn.getName()).getString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    urls.add(url);
                }
            }
        }
        return urls;
    }

    /** Скачивает URL, если файла нет или он старше maxAgeHours. */
    private boolean downloadIfStale(String url, boolean force)
            throws java.io.IOException,
                   java.security.cert.CertificateException,
                   java.security.cert.CRLException,
                   java.security.NoSuchAlgorithmException {
        // Скачиваем во временный файл, потом разберём issuer
        Path tmp = Files.createTempFile(crlDir, "dl-", ".crl.tmp");
        try {
            downloadToFile(url, tmp);
            long size = Files.size(tmp);
            if (size < 100) {
                throw new IllegalStateException("файл слишком маленький");
            }
            if (size > MAX_CRL_SIZE_BYTES) {
                log.warn("CRL слишком большой ({} байт > {}), пропускаем: {}",
                        size, MAX_CRL_SIZE_BYTES, url);
                Files.deleteIfExists(tmp);
                return false; // НЕ парсим, НЕ сохраняем — защита от OOM
            }

            X509CRL crl = parseCrl(tmp);
            String issuerKey = issuerKey(crl);
            Path target = crlDir.resolve(AUTO_PREFIX + issuerKey + CRL_SUFFIX);

            if (!force && isExistingFresh(target)) {
                return false; // свежий, не трогаем (если не force)
            }

            deleteOldFilesForIssuer(issuerKey);

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("CRL обновлён: {} ({} байт, issuer={})",
                    target.getFileName(), Files.size(target), crl.getIssuerX500Principal());
            return true;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** true, если target существует и его mtime свежее maxAgeHours. */
    private boolean isExistingFresh(Path target) throws java.io.IOException {
        if (!Files.isRegularFile(target)) return false;
        long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(target).toMillis();
        long ageHours = ageMs / 3_600_000L;
        return ageHours < maxAgeHours;
    }

    /**
     * Удаляет старые auto-*.crl с тем же issuer-prefix (кроме текущего файла).
     * НЕ парсит содержимое — работает по именам файлов. Быстро и без OOM.
     */
    private void deleteOldFilesForIssuer(String currentIssuerKey) {
        String keepName = AUTO_PREFIX + currentIssuerKey + CRL_SUFFIX;
        try (var stream = Files.list(crlDir)) {
            var files = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString();
                        return n.startsWith(AUTO_PREFIX) && n.endsWith(CRL_SUFFIX)
                                && !n.equals(keepName);
                    })
                    .toList();
            // Удаляем только если файл старше 1 часа (не трогаем свежие)
            long now = System.currentTimeMillis();
            for (Path f : files) {
                try {
                    long ageMs = now - Files.getLastModifiedTime(f).toMillis();
                    if (ageMs > 3600_000L) { // > 1 час
                        Files.deleteIfExists(f);
                        log.info("Удалён устаревший auto-*.crl: {}", f.getFileName());
                    }
                } catch (Exception e) {
                    log.debug("skip delete {}: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("deleteOldFilesForIssuer: {}", e.getMessage());
        }
    }

    private X509CRL parseCrl(Path file)
            throws java.io.IOException,
                   java.security.cert.CertificateException,
                   java.security.cert.CRLException {
        try (InputStream is = Files.newInputStream(file)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509CRL) cf.generateCRL(is);
        }
    }

    /** Хеш от issuer DN → короткий ключ (16 hex-символов). */
    private String issuerKey(X509CRL crl) throws java.security.NoSuchAlgorithmException {
        String issuer = crl.getIssuerX500Principal().getName();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(issuer.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h).substring(0, 16);
    }

    private void downloadToFile(String url, Path target) throws java.io.IOException {
        try {
            downloadOne(url, target);
        } catch (Exception first) {
            // Fallback: http → https (некоторые gov-сайты отдают CRL только по https)
            if (url.startsWith("http://")) {
                String httpsUrl = "https://" + url.substring("http://".length());
                log.info("CRL retry as https: {}", httpsUrl);
                try {
                    downloadOne(httpsUrl, target);
                    return;
                } catch (Exception second) {
                    log.debug("https retry failed: {}", second.getMessage());
                }
            }
            throw first;
        }
    }

    private void downloadOne(String url, Path target) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        // Браузерный UA — некоторые gov-сайты блокируют "ботов" без Referer/UA
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "ru,en;q=0.9");
        int code = conn.getResponseCode();
        if (code != 200) {
            // 304 Not Modified — это OK (файл не изменился, но обновить mtime надо)
            if (code == 304) {
                throw new IllegalStateException("HTTP 304 (не изменён)");
            }
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }
}
