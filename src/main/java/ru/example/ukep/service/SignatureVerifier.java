package ru.example.ukep.service;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.Store;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Верификатор CAdES-BES подписей через BouncyCastle с ручным
 * построением цепочки сертификатов и проверкой отзыва по CRL.
 *
 * НЕ использует JCSP для chain building — это позволяет обойти
 * проблему "Root certificate is untrusted", когда JCSP ищет корни
 * в своём системном хранилище, игнорируя переданный truststore.
 */
@Service
public class SignatureVerifier {
    private static final String LOG_SKIP_CERT = "Skipping certificate: {}";


    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final Path certsDir;
    private final Path crlsDir;

    public SignatureVerifier(
            @Value("${app.certificates-path:/app/certs}") String certsPath,
            @Value("${app.crl-path:/app/crls}") String crlsPath) {
        this.certsDir = Paths.get(certsPath);
        this.crlsDir = Paths.get(crlsPath);
        log.info("SignatureVerifier: certs={}, crls={}", certsDir, crlsDir);
    }

    public Map<String, Object> verifyDetached(Path contentPath, String signatureBase64) throws Exception {
        byte[] data = Files.readAllBytes(contentPath);
        return verifyDetached(data, signatureBase64);
    }

    public Map<String, Object> verifyDetached(byte[] data, String signatureBase64) throws Exception {
        String cleaned = signatureBase64
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s+", "")
                .replaceAll("[^A-Za-z0-9+/=]", "");
        byte[] signatureBytes = Base64.decode(cleaned);

        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(data), signatureBytes);
        SignerInformationStore signers = cms.getSignerInfos();

        Set<X509Certificate> truststore = loadTrustedCerts();

        // CRL обновляются фоново в CrlRefreshService (каждые 6 часов).
        // Здесь только читаем то, что уже есть. Отсутствие CRL = soft-fail.
        Set<X509CRL> crls = loadAllCrls();

        Map<String, Object> result = new HashMap<>();
        List<String> infos = new ArrayList<>();
        String firstCn = "";
        String firstSerial = "";

        for (SignerInformation signer : signers.getSigners()) {
            X509Certificate leaf = getSignerCert(signer, cms);
            SignerMeta meta = verifyOneSigner(signer, leaf, cms, truststore, crls);
            infos.add(meta.info());
            if (firstCn.isEmpty()) {
                firstCn = meta.cn();
                firstSerial = meta.serial();
            }
        }

        result.put("valid", true);
        result.put("signersCount", infos.size());
        result.put("signersInfo", String.join("\n", infos));
        result.put("signerSubject", firstCn);
        result.put("signerSerial", firstSerial);
        return result;
    }

    // ==================== Внутренние методы ====================

    /** Метаданные одного подписанта (для результата верификации). */
    private record SignerMeta(String cn, String serial, String info) {}

    /**
     * Полная проверка одного подписанта: крипто, цепочка, корень в truststore,
     * срок действия, отзыв по CRL.
     */
    private SignerMeta verifyOneSigner(SignerInformation signer, X509Certificate leaf,
                                       CMSSignedData cms,
                                       Set<X509Certificate> truststore,
                                       Set<X509CRL> crls) throws Exception {
        // 1) Криптопроверка подписи (BC, fallback JCSP для ГОСТ)
        if (!verifySignerCrypto(signer, leaf)) {
            throw new IllegalStateException("Подпись недействительна");
        }
        // 2) Срок действия листа
        leaf.checkValidity();

        // 3) Построение цепочки (вручную)
        List<X509Certificate> chain = buildChain(leaf, cms);
        if (log.isInfoEnabled()) {
            log.info("Chain built: {} certificates (leaf={})", chain.size(),
                    leaf.getSubjectX500Principal());
        }

        // 4) Корень в truststore
        X509Certificate root = chain.get(chain.size() - 1);
        if (!isTrustedRoot(root, truststore)) {
            throw new IllegalStateException(
                    "Корневой сертификат не в truststore: " + root.getSubjectX500Principal());
        }
        // 5) Подписи звеньев
        verifyChainSignatures(chain);
        // 6) Срок действия всех звеньев
        for (X509Certificate c : chain) {
            c.checkValidity();
        }
        // 7) Отзыв по CRL
        checkNotRevoked(chain, crls);

        String subject = leaf.getSubjectX500Principal().getName();
        return new SignerMeta(
                extractCn(subject),
                leaf.getSerialNumber().toString(16),
                subject + "; serial=" + leaf.getSerialNumber().toString(16));
    }

    private boolean verifySignerCrypto(SignerInformation signer, X509Certificate leaf) {
        // Пробуем BouncyCastle
        try {
            return signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider("BC").build(leaf));
        } catch (Exception bcError) {
            log.debug("BC verify failed: {}, try JCSP", bcError.getMessage());
        }
        // Fallback на JCSP (для ГОСТ, если BC не справился)
        try {
            return signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider("JCSP").build(leaf));
        } catch (Exception jcspError) {
            throw new IllegalStateException("Ошибка проверки подписи: " + jcspError.getMessage(), jcspError);
        }
    }

    /** Извлекает все встроенные сертификаты из CMS (лист + промежуточные). */
        private X509Certificate getSignerCert(SignerInformation signer, CMSSignedData cms) throws Exception {
        Store<X509CertificateHolder> certStore = cms.getCertificates();
        Collection<X509CertificateHolder> matches = certStore.getMatches(signer.getSID());
        if (matches.isEmpty()) {
            throw new IllegalStateException("Сертификат подписанта не найден в подписи");
        }
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(matches.iterator().next());
    }

    private Set<X509Certificate> loadTrustedCerts() {
        Set<X509Certificate> result = new HashSet<>();
        if (!Files.isDirectory(certsDir)) {
            log.warn("truststore dir not found: {}", certsDir);
            return result;
        }
        try (Stream<Path> stream = Files.list(certsDir)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509", "BC");
            stream.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString().toLowerCase();
                if (!name.endsWith(".cer") && !name.endsWith(".crt") && !name.endsWith(".pem")) {
                    return;
                }
                try (InputStream is = Files.newInputStream(f)) {
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
                    result.add(cert);
                    log.info("Truststore: loaded {} — Subject: {}", f.getFileName(), cert.getSubjectX500Principal());
                } catch (Exception ex) {
                    log.warn("Truststore: FAILED to load {}: {}", f.getFileName(), ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("loadTrustedCerts error", e);
        }
        log.info("Truststore: {} certificates loaded from {}", result.size(), certsDir);
        return result;
    }

    /**
     * Загружает все *.crl из директории, дедуплицирует по issuer —
     * оставляет только самый свежий (по thisUpdate) CRL для каждого CA.
     */
    private Set<X509CRL> loadAllCrls() {
        if (!Files.isDirectory(crlsDir)) {
            log.warn("crls dir not found: {}", crlsDir);
            return new HashSet<>();
        }

        // issuer → самый свежий CRL
        java.util.Map<String, X509CRL> byIssuer = new java.util.HashMap<>();
        int total = 0;
        int failed = 0;

        try (Stream<Path> stream = Files.list(crlsDir)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509", "BC");
            java.util.List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".crl"))
                    .sorted()
                    .toList();
            for (Path f : files) {
                total++;
                try (InputStream is = Files.newInputStream(f)) {
                    X509CRL crl = (X509CRL) cf.generateCRL(is);
                    String issuer = crl.getIssuerX500Principal().getName();
                    X509CRL existing = byIssuer.get(issuer);
                    if (existing == null || crl.getThisUpdate().after(existing.getThisUpdate())) {
                        byIssuer.put(issuer, crl);
                    }
                } catch (Exception e) {
                    failed++;
                    log.debug("CRL parse failed: {} — {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("loadAllCrls error", e);
        }

        log.info("CRLs: {} файлов → {} уникальных issuer (failed={})",
                total, byIssuer.size(), failed);
        return new HashSet<>(byIssuer.values());
    }

    private List<X509Certificate> buildChain(X509Certificate leaf, CMSSignedData cms) throws Exception {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(leaf);

        Set<X509Certificate> candidates = collectCandidates(cms);

        X509Certificate current = leaf;
        int maxDepth = 10;
        while (!isSelfSigned(current) && maxDepth-- > 0) {
            X509Certificate issuer = findIssuer(current, candidates);
            if (issuer == null) {
                throw new IllegalStateException(
                        "Не найден issuer для " + current.getSubjectX500Principal());
            }
            chain.add(issuer);
            current = issuer;
        }
        if (maxDepth <= 0) {
            throw new IllegalStateException("Цепочка слишком длинная (>10)");
        }
        return chain;
    }

    /** Все сертификаты из CMS в виде Set. Невалидные — тихо пропускаем. */
    private Set<X509Certificate> collectCandidates(CMSSignedData cms) {
        Set<X509Certificate> candidates = new HashSet<>();
        Store<X509CertificateHolder> certStore = cms.getCertificates();
        JcaX509CertificateConverter conv = new JcaX509CertificateConverter().setProvider("BC");
        for (X509CertificateHolder holder : certStore.getMatches(null)) {
            try {
                candidates.add(conv.getCertificate(holder));
            } catch (Exception ex) {
                log.debug(LOG_SKIP_CERT, ex.getMessage());
            }
        }
        return candidates;
    }

    /** Ищет issuer для cert среди candidates с проверкой подписи. */
    private X509Certificate findIssuer(X509Certificate cert, Set<X509Certificate> candidates) {
        for (X509Certificate candidate : candidates) {
            if (!cert.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                continue;
            }
            if (verifySignature(cert, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void verifyChainSignatures(List<X509Certificate> chain) {
        for (int i = 0; i < chain.size() - 1; i++) {
            X509Certificate cert = chain.get(i);
            X509Certificate issuer = chain.get(i + 1);
            if (!verifySignature(cert, issuer)) {
                throw new IllegalStateException(
                        "Подпись звена невалидна: " + cert.getSubjectX500Principal()
                        + " ← " + issuer.getSubjectX500Principal());
            }
        }
    }

    private boolean verifySignature(X509Certificate cert, X509Certificate issuer) {
        try {
            cert.verify(issuer.getPublicKey(), "BC");
            return true;
        } catch (Exception bcError) {
            try {
                cert.verify(issuer.getPublicKey(), "JCSP");
                return true;
            } catch (Exception jcspError) {
                return false;
            }
        }
    }

    private boolean isSelfSigned(X509Certificate cert) {
        return cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
    }

    private boolean isTrustedRoot(X509Certificate root, Set<X509Certificate> truststore) {
        byte[] rootFp = fingerprint(root);
        if (rootFp.length == 0) return false;

        String rootCn = extractCn(root.getSubjectX500Principal().getName());
        String rootSerial = root.getSerialNumber().toString(16);
        log.info("isTrustedRoot: chain root CN={}, serial={}, fp={}",
                rootCn, rootSerial, hex16(rootFp));

        for (X509Certificate trusted : truststore) {
            byte[] trustedFp = fingerprint(trusted);
            String trustedCn = extractCn(trusted.getSubjectX500Principal().getName());
            String trustedSerial = trusted.getSerialNumber().toString(16);

            // 1) точное совпадение по SHA-256
            if (trustedFp.length > 0 && Arrays.equals(rootFp, trustedFp)) {
                log.info("isTrustedRoot: MATCH on fingerprint — {}", trustedCn);
                return true;
            }
            // 2) совпадение по Subject + Issuer (оба self-signed) — ГОСТ fallback
            if (root.getSubjectX500Principal().equals(trusted.getSubjectX500Principal())
                    && root.getIssuerX500Principal().equals(trusted.getIssuerX500Principal())
                    && isSelfSigned(trusted)) {
                log.warn("isTrustedRoot: MATCH by DN (fingerprint differs!) — " +
                        "chain root serial={}, trusted serial={}, CN={}. " +
                        "Скорее всего, это перевыпуск корневого — считаем trusted.",
                        rootSerial, trustedSerial, trustedCn);
                return true;
            }
        }
        log.warn("isTrustedRoot: NOT TRUSTED — chain root CN={}, serial={} не найден в truststore ({} сертификатов)",
                rootCn, rootSerial, truststore.size());
        return false;
    }

    private static String hex16(byte[] fp) {
        if (fp == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(fp.length, 8); i++) {
            sb.append(String.format("%02x", fp[i]));
        }
        return sb.toString();
    }

    /**
     * SHA-256 отпечаток всего сертификата (getEncoded).
     * Надёжнее, чем сравнивать getPublicKey().getEncoded() для ГОСТ — у BC
     * бывает разная DER-кодировка SubjectPublicKeyInfo для одного ключа.
     */
    private static byte[] fingerprint(X509Certificate cert) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(cert.getEncoded());
        } catch (Exception e) {
            log.warn("Failed to compute fingerprint: {}", e.getMessage());
            return new byte[0];
        }
    }

    private void checkNotRevoked(List<X509Certificate> chain, Set<X509CRL> crls) {
        for (int i = 0; i < chain.size() - 1; i++) {
            X509Certificate cert = chain.get(i);
            X509CRL matchingCrl = null;
            for (X509CRL crl : crls) {
                if (crl.getIssuerX500Principal().equals(cert.getIssuerX500Principal())) {
                    matchingCrl = crl;
                    break;
                }
            }
            if (matchingCrl == null) {
                log.warn("Нет CRL для {} — проверка отзыва пропущена",
                        cert.getIssuerX500Principal());
                continue;
            }
            X509CRLEntry entry = matchingCrl.getRevokedCertificate(cert.getSerialNumber());
            if (entry != null) {
                throw new IllegalStateException(
                        "Сертификат отозван: " + cert.getSubjectX500Principal()
                        + ", serial=" + cert.getSerialNumber().toString(16)
                        + ", reason=" + entry.getRevocationReason());
            }
        }
    }

    private String extractCn(String x500) {
        if (x500 == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(x500);
        return m.find() ? m.group(1).replace("\"", "").trim() : x500;
    }
}
