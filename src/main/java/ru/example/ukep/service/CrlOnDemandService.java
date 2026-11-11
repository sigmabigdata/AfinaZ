package ru.example.ukep.service;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.util.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.repository.DocumentSignatureRepository;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Скачивание CRL по требованию:
 *  - при загрузке .sig (только для сертификатов этой подписи)
 *  - при нажатии «Обновить все CRL» (для всех подписей в БД, force)
 *  - плюс автоочистка осиротевших auto-*.crl
 */
@Service
public class CrlOnDemandService {

    private static final Logger log = LoggerFactory.getLogger(CrlOnDemandService.class);

    private final DocumentSignatureRepository signatureRepo;
    private final CrlDownloader crlDownloader;
    private final CrlService crlService;
    private final AuditService audit;

    public CrlOnDemandService(DocumentSignatureRepository signatureRepo,
                              CrlDownloader crlDownloader,
                              CrlService crlService,
                              AuditService audit) {
        this.signatureRepo = signatureRepo;
        this.crlDownloader = crlDownloader;
        this.crlService = crlService;
        this.audit = audit;
    }

    public record RefreshResult(
            int totalSignatures,
            int parsedSignatures,
            int parseErrors,
            int uniqueCerts,
            int crlDownloaded,
            int crlDeleted,
            int crlFailed,
            java.util.List<String> failures
    ) {}

    /**
     * Скачивает CRL для сертификатов одной подписи (без force — по TTL).
     * Вызывается после успешной загрузки .sig.
     */
    public int ensureForSignature(String signatureBase64, Long documentId, String actor) {
        if (signatureBase64 == null || signatureBase64.isBlank()) return 0;
        try {
            Set<X509Certificate> certs = extractCerts(signatureBase64);
            if (certs.isEmpty()) {
                log.warn("ensureForSignature: no certs in signature for doc {}", documentId);
                return 0;
            }
            int n = crlDownloader.ensureCrlsFor(certs);
            log.info("ensureForSignature: downloaded {} CRL for doc {} ({} certs)",
                    n, documentId, certs.size());
            if (actor != null) {
                audit.event("CRL_FROM_SIG", "SUCCESS", actor,
                        "DOCUMENT", String.valueOf(documentId), null,
                        "downloaded " + n + " CRL for " + certs.size() + " certs");
            }
            return n;
        } catch (Exception e) {
            log.warn("ensureForSignature failed for doc {}: {}", documentId, e.getMessage());
            return 0;
        }
    }

    /**
     * Полный refresh: обходит все подписи, скачивает свежие CRL (force=true),
     * удаляет осиротевшие auto-*.crl.
     */
    public RefreshResult refreshAll(String actor) {
        List<DocumentSignature> all = signatureRepo.findAll();
        Set<X509Certificate> uniqueCerts = new HashSet<>();
        int parsed = 0, errors = 0;

        for (DocumentSignature sig : all) {
            try {
                uniqueCerts.addAll(extractCerts(sig.getSignatureBase64()));
                parsed++;
            } catch (Exception e) {
                errors++;
                log.debug("parse error for sig {}: {}", sig.getId(), e.getMessage());
            }
        }

        // force=true — игнорируем TTL, всегда перекачиваем
        CrlDownloader.DownloadReport report = crlDownloader.ensureCrlsForWithDetails(uniqueCerts, true);
        int downloaded = report.success();
        int failed = report.failed();
        java.util.List<String> failures = report.failures();

        // Удаляем CRL, чей issuer больше не встречается в подписях
        int deleted;
        try {
            deleted = cleanupOrphanCrls(uniqueCerts);
        } catch (Exception e) {
            log.warn("cleanupOrphanCrls failed: {}", e.getMessage());
            deleted = 0;
        }

        log.info("refreshAll: sigs={}, parsed={}, errors={}, certs={}, downloaded={}, failed={}, deleted={}",
                all.size(), parsed, errors, uniqueCerts.size(), downloaded, failed, deleted);
        for (String f : failures) {
            log.warn("  CRL failure: {}", f);
        }

        if (actor != null) {
            audit.event("CRL_REFRESH_ALL", "SUCCESS", actor,
                    "CRL", null, null,
                    "downloaded " + downloaded + ", deleted " + deleted
                            + " (certs " + uniqueCerts.size() + ")");
        }

        return new RefreshResult(all.size(), parsed, errors,
                uniqueCerts.size(), downloaded, deleted, failed, failures);
    }

    /**
     * Удаляет auto-*.crl, issuer которых не встречается в текущих сертификатах.
     */
    public int cleanupOrphanCrls(Set<X509Certificate> currentCerts) {
        Set<String> currentIssuers = new HashSet<>();
        if (currentCerts == null) return 0;
        for (X509Certificate cert : currentCerts) {
            boolean isRoot = cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
            if (!isRoot) {
                currentIssuers.add(cert.getIssuerX500Principal().getName());
            }
        }

        Path crlDir;
        try {
            crlDir = crlService.getCrlsDir();
        } catch (Exception e) {
            log.debug("getCrlsDir failed: {}", e.getMessage());
            return 0;
        }
        if (crlDir == null || !Files.isDirectory(crlDir)) return 0;

        int deleted = 0;
        try (var stream = Files.list(crlDir)) {
            var files = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().startsWith("auto-"))
                    .filter(f -> f.getFileName().toString().endsWith(".crl"))
                    .toList();

            for (Path f : files) {
                try {
                    X509CRL crl;
                    try (InputStream is = Files.newInputStream(f)) {
                        crl = (X509CRL) CertificateFactory
                                .getInstance("X.509", "BC").generateCRL(is);
                    }
                    String issuer = crl.getIssuerX500Principal().getName();
                    if (!currentIssuers.contains(issuer)) {
                        Files.deleteIfExists(f);
                        deleted++;
                        log.info("Orphan CRL deleted: {} (issuer not in any signature)",
                                f.getFileName());
                    }
                } catch (Exception e) {
                    log.debug("skip orphan-check for {}: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("cleanupOrphanCrls: {}", e.getMessage());
        }
        return deleted;
    }

    /** Извлекает все встроенные сертификаты из CAdES-BES подписи. */
    public Set<X509Certificate> extractCerts(String signatureBase64) throws Exception {
        Set<X509Certificate> result = new HashSet<>();
        if (signatureBase64 == null || signatureBase64.isBlank()) return result;

        byte[] bytes = SignatureVerifier.decodeBase64Tolerant(signatureBase64);
        CMSSignedData cms = new CMSSignedData(bytes);

        Store<X509CertificateHolder> store = cms.getCertificates();
        JcaX509CertificateConverter conv = new JcaX509CertificateConverter().setProvider("BC");
        for (X509CertificateHolder holder : store.getMatches(null)) {
            try {
                result.add(conv.getCertificate(holder));
            } catch (Exception ignored) {
                // пропускаем невалидные
            }
        }
        return result;
    }
}
