package ru.example.ukep.service;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERIA5String;
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

    public CrlDownloader(
            @Value("${app.crl-path:/app/crls}") String crlPath,
            @Value("${app.crl-max-age-hours:24}") long maxAgeHours) throws Exception {
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
        Set<String> urls = new HashSet<>();
        for (X509Certificate cert : certs) {
            urls.addAll(extractCdpUrls(cert));
        }
        int count = 0;
        for (String url : urls) {
            try {
                if (downloadIfStale(url)) count++;
            } catch (Exception e) {
                log.warn("CRL download failed для {}: {}", url, e.getMessage());
            }
        }
        return count;
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
    private CRLDistPoint parseCdpExtension(byte[] ext) throws Exception {
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
                String url = DERIA5String.getInstance(gn.getName()).getString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    urls.add(url);
                }
            }
        }
        return urls;
    }

    /** Скачивает URL, если файла нет или он старше maxAgeHours. */
    private boolean downloadIfStale(String url) throws Exception {
        // Скачиваем во временный файл, потом разберём issuer
        Path tmp = Files.createTempFile(crlDir, "dl-", ".crl.tmp");
        try {
            downloadToFile(url, tmp);
            if (Files.size(tmp) < 100) {
                throw new IllegalStateException("файл слишком маленький");
            }

            X509CRL crl = parseCrl(tmp);
            String issuerKey = issuerKey(crl);
            Path target = crlDir.resolve(AUTO_PREFIX + issuerKey + CRL_SUFFIX);

            if (isExistingFresh(target)) {
                return false; // свежий, не трогаем
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
    private boolean isExistingFresh(Path target) throws Exception {
        if (!Files.isRegularFile(target)) return false;
        long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(target).toMillis();
        long ageHours = ageMs / 3_600_000L;
        return ageHours < maxAgeHours;
    }

    /**
     * Удаляет все файлы auto-*.crl, которые НЕ совпадают с текущим issuerKey.
     * Вызывается перед сохранением нового файла, чтобы не копились старые
     * имена (например, названные по hash(url) из предыдущих версий).
     */
    private void deleteOldFilesForIssuer(String currentIssuerKey) {
        try (var stream = Files.list(crlDir)) {
            var files = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().startsWith(AUTO_PREFIX))
                    .filter(f -> f.getFileName().toString().endsWith(CRL_SUFFIX))
                    .toList();
            for (var f : files) {
                String name = f.getFileName().toString();
                if (name.equals(AUTO_PREFIX + currentIssuerKey + CRL_SUFFIX)) continue;
                try {
                    // Проверяем, тот ли это issuer
                    X509CRL other = parseCrl(f);
                    String otherKey = issuerKey(other);
                    if (otherKey.equals(currentIssuerKey)) {
                        Files.deleteIfExists(f);
                        log.info("Удалён устаревший CRL: {}", name);
                    }
                } catch (Exception parseEx) {
                    log.debug("CRL parse failed for {}: {}",
                            name, parseEx.getMessage());
                    // Файл не парсится — удалим, если он старый
                    long ageMs = System.currentTimeMillis()
                            - Files.getLastModifiedTime(f).toMillis();
                    if (ageMs > 7 * 24 * 3_600_000L) {
                        try {
                            Files.deleteIfExists(f);
                            log.warn("Удалён невалидный старый CRL: {}", name);
                        } catch (Exception e) {
                            log.warn("Не удалось удалить {}: {}", name, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("deleteOldFilesForIssuer error: {}", e.getMessage());
        }
    }

    private X509CRL parseCrl(Path file) throws Exception {
        try (InputStream is = Files.newInputStream(file)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509CRL) cf.generateCRL(is);
        }
    }

    /** Хеш от issuer DN → короткий ключ (16 hex-символов). */
    private String issuerKey(X509CRL crl) throws Exception {
        String issuer = crl.getIssuerX500Principal().getName();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(issuer.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h).substring(0, 16);
    }

    private void downloadToFile(String url, Path target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Afina-CRL-Downloader/1.0");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }
}
