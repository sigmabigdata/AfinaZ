package ru.example.ukep.service;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.repository.DocumentSignatureRepository;

import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Фоновое обновление CRL.
 *
 * Каждые 6 часов проходит по всем document_signatures,
 * извлекает сертификаты подписантов из сохранённых CAdES-BES,
 * и обновляет CRL для них через CrlDownloader.
 *
 * При подписании документа CRL не скачиваются — только читаются
 * с диска. Отсутствие CRL = soft-fail.
 */
@Service
public class CrlRefreshService {

    private static final Logger log = LoggerFactory.getLogger(CrlRefreshService.class);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final DocumentSignatureRepository signatureRepo;
    private final CrlDownloader crlDownloader;

    public CrlRefreshService(DocumentSignatureRepository signatureRepo,
                             CrlDownloader crlDownloader) {
        this.signatureRepo = signatureRepo;
        this.crlDownloader = crlDownloader;
    }

    /** Первый запуск через 5 минут после старта, потом каждые 6 часов. */
    @Scheduled(fixedDelay = 6 * 3600_000L, initialDelay = 5 * 60_000L)
    public void refreshAll() {
        log.info("CrlRefreshService: начало обновления CRL");
        long t0 = System.currentTimeMillis();

        try {
            List<DocumentSignature> all = signatureRepo.findAll();
            CollectResult res = collectAllCerts(all);
            log.info("CrlRefreshService: подписей {} (распарсено {}, ошибок {}), "
                            + "уникальных сертификатов {}",
                    all.size(), res.parsed(), res.errors(), res.certs().size());

            int updated = crlDownloader.ensureCrlsFor(res.certs());
            long dt = System.currentTimeMillis() - t0;
            log.info("CrlRefreshService: завершено за {} мс, обновлено {} CRL",
                    dt, updated);
        } catch (Exception e) {
            log.error("CrlRefreshService: ошибка", e);
        }
    }

    /** Результат обхода всех подписей: уникальные сертификаты + счётчики. */
    private record CollectResult(Set<X509Certificate> certs, int parsed, int errors) {}

    /** Собирает уникальные сертификаты из всех подписей. */
    private CollectResult collectAllCerts(List<DocumentSignature> signatures) {
        Set<X509Certificate> uniqueCerts = new HashSet<>();
        int parsed = 0;
        int errors = 0;
        for (DocumentSignature sig : signatures) {
            try {
                uniqueCerts.addAll(extractCerts(sig.getSignatureBase64()));
                parsed++;
            } catch (Exception e) {
                errors++;
                log.debug("Не удалось разобрать подпись id={}: {}",
                        sig.getId(), e.getMessage());
            }
        }
        return new CollectResult(uniqueCerts, parsed, errors);
    }

    /** Извлекает все сертификаты из CAdES-BES подписи. */
    private Set<X509Certificate> extractCerts(String signatureBase64)
            throws java.security.cert.CertificateException, org.bouncycastle.cms.CMSException {
        Set<X509Certificate> result = new HashSet<>();
        if (signatureBase64 == null || signatureBase64.isBlank()) return result;

        String cleaned = signatureBase64
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s+", "")
                .replaceAll("[^A-Za-z0-9+/=]", "");
        byte[] bytes = Base64.getDecoder().decode(cleaned);

        // Content пустой — интересуют только встроенные сертификаты
        CMSSignedData cms = new CMSSignedData(bytes);
        Store<X509CertificateHolder> store = cms.getCertificates();
        for (X509CertificateHolder holder : store.getMatches(null)) {
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider("BC").getCertificate(holder);
            result.add(cert);
        }
        return result;
    }
}
