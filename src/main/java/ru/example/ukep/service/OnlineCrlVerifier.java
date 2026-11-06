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
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Онлайн-проверка отзыва через CRL из CDP-расширения сертификата.
 * Скачивает свежий CRL при каждой проверке.
 *
 * Fail-closed: если CDP не найден или CRL не удалось скачать — считаем
 * сертификат "не проверенным на отзыв", что блокирует подтверждение подписи.
 */
@Service
public class OnlineCrlVerifier {

    private static final Logger log = LoggerFactory.getLogger(OnlineCrlVerifier.class);
    private static final String CDP_OID = "2.5.29.31";

    @Value("${app.crl-online.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${app.crl-online.read-timeout-ms:15000}")
    private int readTimeoutMs;

    public record CrlCheckResult(
            boolean checked,
            boolean revoked,
            String revocationDate,
            String reason,
            String crlUrl,
            String crlIssuer,
            String crlThisUpdate,
            String crlNextUpdate,
            String error
    ) {
        public static CrlCheckResult ok(String url, String issuer, Date thisUp, Date nextUp) {
            return new CrlCheckResult(true, false, null, null,
                    url, issuer,
                    thisUp != null ? thisUp.toInstant().toString() : null,
                    nextUp != null ? nextUp.toInstant().toString() : null,
                    null);
        }
        public static CrlCheckResult revoked(String url, X509CRLEntry entry) {
            return new CrlCheckResult(true, true,
                    entry.getRevocationDate() != null ? entry.getRevocationDate().toInstant().toString() : null,
                    entry.getRevocationReason() != null ? entry.getRevocationReason().toString() : null,
                    url, null, null, null, null);
        }
        public static CrlCheckResult fail(String error) {
            return new CrlCheckResult(false, false, null, null, null, null, null, null, error);
        }
    }

    /**
     * Проверяет отзыв сертификата через свежий CRL с CDP.
     */
    public CrlCheckResult checkRevocation(X509Certificate cert) {
        List<String> urls = extractCdpUrls(cert);
        if (urls.isEmpty()) {
            log.warn("No CDP URLs in certificate: {}", cert.getSubjectX500Principal());
            return CrlCheckResult.fail("В сертификате нет CDP-расширения (CRL Distribution Points)");
        }

        List<String> errors = new ArrayList<>();
        for (String url : urls) {
            try {
                X509CRL crl = downloadCrl(url);
                X509CRLEntry entry = crl.getRevokedCertificate(cert.getSerialNumber());
                if (entry != null) {
                    log.warn("Certificate REVOKED: {} (serial={}), reason={}",
                            cert.getSubjectX500Principal(),
                            cert.getSerialNumber().toString(16),
                            entry.getRevocationReason());
                    return CrlCheckResult.revoked(url, entry);
                }
                log.info("CRL check OK: serial={} not revoked (url={})",
                        cert.getSerialNumber().toString(16), url);
                return CrlCheckResult.ok(url,
                        crl.getIssuerX500Principal().getName(),
                        crl.getThisUpdate(),
                        crl.getNextUpdate());
            } catch (Exception e) {
                errors.add(url + ": " + e.getMessage());
                log.warn("CRL download failed from {}: {}", url, e.getMessage());
            }
        }
        return CrlCheckResult.fail("Не удалось получить CRL ни с одного CDP: "
                + String.join("; ", errors));
    }

    /** Пакетная проверка всех сертификатов в цепочке (кроме self-signed root). */
    public Map<String, CrlCheckResult> checkChain(List<X509Certificate> chain) {
        Map<String, CrlCheckResult> results = new LinkedHashMap<>();
        for (int i = 0; i < chain.size(); i++) {
            X509Certificate cert = chain.get(i);
            boolean isRoot = cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
            if (isRoot) {
                results.put(cert.getSubjectX500Principal().getName(),
                        CrlCheckResult.ok(null, null, null, null));
                continue;
            }
            String key = cert.getSubjectX500Principal().getName();
            results.put(key, checkRevocation(cert));
        }
        return results;
    }

    private X509CRL downloadCrl(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Afina-Verifier/1.0");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream in = conn.getInputStream()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509", "BC");
            return (X509CRL) cf.generateCRL(in);
        } finally {
            conn.disconnect();
        }
    }

    private List<String> extractCdpUrls(X509Certificate cert) {
        List<String> out = new ArrayList<>();
        byte[] ext;
        try { ext = cert.getExtensionValue(CDP_OID); }
        catch (Exception e) { return out; }
        if (ext == null) return out;

        try (ASN1InputStream aIn = new ASN1InputStream(ext)) {
            ASN1OctetString octet = (ASN1OctetString) aIn.readObject();
            try (ASN1InputStream inner = new ASN1InputStream(octet.getOctets())) {
                ASN1Primitive p = inner.readObject();
                CRLDistPoint cdp = CRLDistPoint.getInstance(p);
                for (DistributionPoint dp : cdp.getDistributionPoints()) {
                    DistributionPointName name = dp.getDistributionPoint();
                    if (name == null || name.getType() != DistributionPointName.FULL_NAME) continue;
                    GeneralNames gns = GeneralNames.getInstance(name.getName());
                    for (GeneralName gn : gns.getNames()) {
                        if (gn.getTagNo() != GeneralName.uniformResourceIdentifier) continue;
                        String url = org.bouncycastle.asn1.ASN1IA5String
                                .getInstance(gn.getName()).getString();
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            out.add(url);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("CDP parse failed: {}", e.getMessage());
        }
        return out;
    }
}
