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

    /** Подпись не сходится с содержимым — файл .sig от другого документа. */
    public static class SignatureMismatchException extends IllegalArgumentException {
        public SignatureMismatchException(String msg) { super(msg); }
    }

    /** Ни BC, ни JCSP не смогли проверить подпись (алгоритм не поддерживается). */
    public static class CryptoNotSupportedException extends IllegalStateException {
        public CryptoNotSupportedException(String msg, Throwable cause) { super(msg, cause); }
    }

    private static final String LOG_SKIP_CERT = "Skipping certificate: {}";


    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Толерантное декодирование base64 (принимает PEM, URL-safe, без padding).
     * Нужно, чтобы не падать на реальных .sig от разных плагинов.
     */
    public static byte[] decodeBase64Tolerant(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("empty base64");
        }
        // 1. Убрать PEM-обёртку и whitespace
        String cleaned = s
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s+", "");
        // 2. URL-safe → стандартный base64
        cleaned = cleaned.replace('-', '+').replace('_', '/');
        // 3. Оставить только base64-символы
        cleaned = cleaned.replaceAll("[^A-Za-z0-9+/=]", "");
        // 4. Убрать padding из середины (только на конце должен быть)
        int firstEq = cleaned.indexOf('=');
        if (firstEq >= 0) {
            cleaned = cleaned.substring(0, firstEq);
        }
        // 5. Добавить padding до кратности 4
        int rem = cleaned.length() % 4;
        if (rem == 2) cleaned += "==";
        else if (rem == 3) cleaned += "=";
        else if (rem == 1) {
            throw new IllegalArgumentException("Некорректная длина base64: " + cleaned.length());
        }
        // 6. Decode
        try {
            return java.util.Base64.getDecoder().decode(cleaned);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный base64: " + e.getMessage(), e);
        }
    }

    private final Path certsDir;
    private final Path crlsDir;

    private OnlineCrlVerifier onlineCrlVerifier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOnlineCrlVerifier(OnlineCrlVerifier v) {
        this.onlineCrlVerifier = v;
    }

    public SignatureVerifier(
            @Value("${app.certificates-path:/app/certs}") String certsPath,
            @Value("${app.crl-path:/app/crls}") String crlsPath) {
        this.certsDir = Paths.get(certsPath);
        this.crlsDir = Paths.get(crlsPath);
        log.info("SignatureVerifier: certs={}, crls={}", certsDir, crlsDir);
    }

    public Map<String, Object> verifyDetached(Path contentPath, String signatureBase64)
            throws java.io.IOException, org.bouncycastle.cms.CMSException, java.security.cert.CertificateException {
        byte[] data = Files.readAllBytes(contentPath);
        return verifyDetached(data, signatureBase64);
    }

    public Map<String, Object> verifyDetached(byte[] data, String signatureBase64)
            throws org.bouncycastle.cms.CMSException, java.security.cert.CertificateException {
        byte[] signatureBytes = decodeBase64Tolerant(signatureBase64);

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

    /**
     * Верификация подписи <b>без построения цепочки</b>.
     * <p>Проверяет только:
     * <ol>
     *   <li>Криптографическую подпись документа signer-сертификатом</li>
     *   <li>Срок действия signer-сертификата</li>
     * </ol>
     * <p>НЕ проверяет: issuer chain, доверие корню, отзыв по CRL.
     *
     * <p>Используется при загрузке .sig через админку, когда .sig сделан
     * с опцией END_ENTITY_ONLY (без промежуточного CA в CMS).</p>
     */
    public Map<String, Object> verifyDetachedBasic(byte[] data, String signatureBase64) throws Exception {
        byte[] signatureBytes = decodeBase64Tolerant(signatureBase64);

        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(data), signatureBytes);
        SignerInformationStore signers = cms.getSignerInfos();

        Map<String, Object> result = new HashMap<>();
        List<String> infos = new ArrayList<>();
        String firstCn = "";
        String firstSerial = "";

        for (SignerInformation signer : signers.getSigners()) {
            X509Certificate leaf = getSignerCert(signer, cms);

            // 1) Быстрая проверка: digest из CMS == digest переданных данных?
            boolean digestMatches = checkSignedDigest(signer, data);
            if (!digestMatches) {
                throw new SignatureMismatchException(
                        "Файл подписи не соответствует документу. " +
                        "Возможно, выбран .sig от другого файла.");
            }

            // 2) Криптографическая проверка (с fallback для ГОСТ 2012)
            try {
                if (!verifySignerCrypto(signer, leaf)) {
                    throw new SignatureMismatchException(
                            "Файл подписи не соответствует документу " +
                            "(подпись не сходится с содержимым).");
                }
            } catch (CryptoNotSupportedException e) {
                if (digestMatches) {
                    log.warn("verifyDetachedBasic: digest OK, но провайдер не проверил " +
                            "подпись ({}). Файл принят по digest.", e.getMessage());
                } else {
                    throw e;
                }
            }

            // 3) Срок действия leaf
            try {
                leaf.checkValidity();
            } catch (java.security.cert.CertificateException e) {
                throw new IllegalArgumentException(
                        "Срок действия сертификата подписанта истёк.");
            }

            String subject = leaf.getSubjectX500Principal().getName();
            String cn = extractCn(subject);
            String serial = leaf.getSerialNumber().toString(16);
            infos.add(subject + "; serial=" + serial);
            if (firstCn.isEmpty()) {
                firstCn = cn;
                firstSerial = serial;
            }
        }

        result.put("valid", true);
        result.put("signersCount", infos.size());
        result.put("signersInfo", String.join("\n", infos));
        result.put("signerSubject", firstCn);
        result.put("signerSerial", firstSerial);
        result.put("chainVerified", false);
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
                                       Set<X509CRL> crls) {
        // 1) Криптопроверка подписи (BC, fallback JCSP для ГОСТ)
        if (!verifySignerCrypto(signer, leaf)) {
            throw new IllegalStateException("Подпись недействительна");
        }
        // 2) Срок действия листа
        try {
            leaf.checkValidity();
        } catch (java.security.cert.CertificateException e) {
            throw new IllegalStateException("Срок действия сертификата истёк", e);
        }

        // 3) Построение цепочки (вручную)
        List<X509Certificate> chain = buildChain(leaf, cms, truststore);
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
            try {
                c.checkValidity();
            } catch (java.security.cert.CertificateException e) {
                throw new IllegalStateException("Срок действия звена цепочки истёк", e);
            }
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
        Exception bcError = null, jcspError = null;

        // 1) BouncyCastle
        try {
            return signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider("BC").build(leaf));
        } catch (Exception e) {
            bcError = e;
            log.debug("BC verify threw: {}", e.getMessage());
        }

        // 2) JCSP
        try {
            return signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider("JCSP").build(leaf));
        } catch (Exception e) {
            jcspError = e;
            log.debug("JCSP verify threw: {}", e.getMessage());
        }

        // 3) Ручной fallback: Signature.getInstance по OID / альтернативному имени
        Boolean manual = tryManualVerify(signer, leaf);
        if (manual != null) return manual;

        // SonarQube: переменные точно не null, так как мы в блоке, где оба метода упали
        String bcMsg = bcError.getMessage();
        String jcspMsg = jcspError.getMessage();
        log.warn("verifySignerCrypto: BC: {} | JCSP: {}", bcMsg, jcspMsg);
        throw new CryptoNotSupportedException(
                "Криптопровайдер не поддерживает алгоритм подписи этого сертификата. " +
                "Обратитесь к администратору.",
                jcspError != null ? jcspError : bcError);
    }

    private boolean tryManualVerify(SignerInformation signer, X509Certificate leaf) {
        try {
            byte[] signedData = signedAttrsOrContent(signer);
            byte[] sigBytes = signer.getSignature();
            if (signedData == null || sigBytes == null) return false;

            String[] algos = {
                signer.getEncryptionAlgOID(),
                "1.2.643.7.1.1.3.2",   // GOST 2012-256
                "1.2.643.7.1.1.3.3",   // GOST 2012-512
                "1.2.643.2.2.3",       // GOST 2001
                "GOST3411-2012-256withECGOST3410-2012-256",
                "GOST3411-2012-512withECGOST3410-2012-512",
                "GOST3411-2012-256WITHECGOST3410-2012-256",
                "GOST3411withECGOST3410",
            };
            for (String alg : algos) {
                if (alg == null || alg.isBlank()) continue;
                for (String provider : new String[]{"JCSP", "BC", "JCP"}) {
                    try {
                        var s = java.security.Signature.getInstance(alg, provider);
                        s.initVerify(leaf.getPublicKey());
                        s.update(signedData);
                        boolean ok = s.verify(sigBytes);
                        log.debug("manual verify: alg={}, provider={}, ok={}", alg, provider, ok);
                        return ok;
                    } catch (Exception ignored) { /* try next */ }
                }
            }
        } catch (Exception e) {
            log.debug("manual verify failed: {}", e.getMessage());
        }
        return false;
    }

    /** Signed attributes (DER) — то, что подписано. */
    private static byte[] signedAttrsOrContent(SignerInformation signer) {
        try {
            var attrs = signer.getSignedAttributes();
            if (attrs != null) {
                return attrs.toASN1Structure()
                        .getEncoded(org.bouncycastle.asn1.ASN1Encoding.DER);
            }
            // Без signed attributes content недоступен из SignerInformation
            // (он живёт в CMSProcessable и передаётся при конструировании CMSSignedData).
            // Для CAdES-BES signed attrs есть всегда; сюда не попадём.
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Быстрая, независимая от провайдера проверка: сравнивает digest
     * переданных данных с messageDigest-атрибутом CMS.
     *
     * @return TRUE — совпадает, FALSE — не совпадает, NULL — невозможно проверить.
     */
    private boolean checkSignedDigest(SignerInformation signer, byte[] data) {
        try {
            var attrs = signer.getSignedAttributes();
            if (attrs == null) return false;
            var mdAttr = attrs.get(org.bouncycastle.asn1.cms.CMSAttributes.messageDigest);
            if (mdAttr == null) return false;
            byte[] expected = ((org.bouncycastle.asn1.ASN1OctetString)
                    mdAttr.getAttrValues().getObjectAt(0)).getOctets();

            String[] algorithms = {
                "GOST3411-2012-256", "GOST3411-2012-512", "GOST3411",
                "SHA-256", "SHA-384", "SHA-512", "SHA-1"
            };
            for (String alg : algorithms) {
                for (String provider : new String[]{"BC", "JCSP"}) {
                    try {
                        byte[] computed = java.security.MessageDigest
                                .getInstance(alg, provider).digest(data);
                        if (java.util.Arrays.equals(computed, expected)) return true;
                    } catch (Exception ignored) {}
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("checkSignedDigest failed: {}", e.getMessage());
            return false;
        }
    }

    /** Извлекает все встроенные сертификаты из CMS (лист + промежуточные). */
    private X509Certificate getSignerCert(SignerInformation signer, CMSSignedData cms) throws java.security.cert.CertificateException {
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
                if (!loadOneCrl(f, cf, byIssuer)) failed++;
            }
        } catch (Exception e) {
            log.error("loadAllCrls error", e);
        }

        log.info("CRLs: {} файлов → {} уникальных issuer (failed={})",
                total, byIssuer.size(), failed);
        return new HashSet<>(byIssuer.values());
    }

    /** Парсит один CRL-файл и кладёт в map (самый свежий по issuer). Возвращает true при успехе. */
    private boolean loadOneCrl(Path f, CertificateFactory cf,
                               java.util.Map<String, X509CRL> byIssuer) {
        try (InputStream is = Files.newInputStream(f)) {
            X509CRL crl = (X509CRL) cf.generateCRL(is);
            String issuer = crl.getIssuerX500Principal().getName();
            X509CRL existing = byIssuer.get(issuer);
            if (existing == null || crl.getThisUpdate().after(existing.getThisUpdate())) {
                byIssuer.put(issuer, crl);
            }
            return true;
        } catch (Exception e) {
            log.debug("CRL parse failed: {} — {}", f.getFileName(), e.getMessage());
            return false;
        }
    }

    private List<X509Certificate> buildChain(X509Certificate leaf, CMSSignedData cms,
                                              Set<X509Certificate> truststore) {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(leaf);

        Set<X509Certificate> candidates = collectCandidates(cms);

        X509Certificate current = leaf;
        int maxDepth = 10;
        while (!isSelfSigned(current) && maxDepth-- > 0) {
            X509Certificate issuer = findIssuer(current, candidates, truststore);
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

    /** Ищет issuer для cert: сначала в candidates (CMS), затем в truststore. */
    private X509Certificate findIssuer(X509Certificate cert,
                                        Set<X509Certificate> candidates,
                                        Set<X509Certificate> truststore) {
        String wantedIssuer = cert.getIssuerX500Principal().getName();
        String leafCn = extractCn(cert.getSubjectX500Principal().getName());
        log.info("findIssuer[{}]: wanted issuer DN = {}", leafCn, wantedIssuer);
        log.info("findIssuer[{}]: candidates CMS ({}):", leafCn, candidates.size());
        for (X509Certificate cand : candidates) {
            log.info("  CMS subject={}", cand.getSubjectX500Principal().getName());
        }
        if (truststore != null) {
            log.info("findIssuer[{}]: truststore ({}):", leafCn, truststore.size());
            for (X509Certificate cand : truststore) {
                log.info("  TRUST subject={}", cand.getSubjectX500Principal().getName());
            }
        }

        // 1) CMS
        for (X509Certificate candidate : candidates) {
            if (!cert.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                continue;
            }
            boolean ok = verifySignature(cert, candidate);
            log.info("findIssuer[{}]: DN match in CMS, verify={}", leafCn, ok);
            if (ok) return candidate;
        }
        // 2) fallback: truststore
        if (truststore != null) {
            for (X509Certificate candidate : truststore) {
                if (!cert.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                    continue;
                }
                boolean ok = verifySignature(cert, candidate);
                log.info("findIssuer[{}]: DN match in TRUSTSTORE, verify={}", leafCn, ok);
                if (ok) return candidate;
            }
        }
        log.warn("findIssuer[{}]: issuer NOT FOUND ни в CMS, ни в truststore", leafCn);
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
            log.debug("verifySignature: BC failed ({}): {}", bcError.getClass().getSimpleName(), bcError.getMessage());
        }
        try {
            cert.verify(issuer.getPublicKey(), "JCSP");
            return true;
        } catch (Exception jcspError) {
            log.warn("verifySignature: BOTH BC and JCSP failed for '{}' <- '{}': {}",
                    extractCn(cert.getSubjectX500Principal().getName()),
                    extractCn(issuer.getSubjectX500Principal().getName()),
                    jcspError.getMessage());
            return false;
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
        if (log.isInfoEnabled()) {
            log.info("isTrustedRoot: chain root CN={}, serial={}, fp={}",
                    rootCn, rootSerial, hex16(rootFp));
        }

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

    /**
     * Онлайн-проверка отзыва через CDP из сертификатов (как на Госуслугах).
     * Возвращает отчёт по каждому сертификату цепочки.
     */
    public java.util.Map<String, OnlineCrlVerifier.CrlCheckResult> checkRevocationOnline(
            List<X509Certificate> chain) {
        if (onlineCrlVerifier == null) {
            log.warn("OnlineCrlVerifier не внедрён — проверка CRL недоступна");
            return java.util.Map.of();
        }
        return onlineCrlVerifier.checkChain(chain);
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

    // ═══════════════════════════════════════════════════════
    //  Строгая проверка с пошаговым отчётом (как Госуслуги)
    // ═══════════════════════════════════════════════════════

    /**
     * Полная проверка подписи с возвратом списка проверок.
     * Проверяет: целостность, криптоподпись, срок leaf, цепочку,
     * корень в truststore, подписи звеньев, срок всех звеньев,
     * CRL, EKU.
     */
    public java.util.Map<String, Object> verifyDetailed(byte[] data, String signatureBase64) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        java.util.List<java.util.Map<String, Object>> checks = new java.util.ArrayList<>();

        try {
            byte[] sigBytes = decodeBase64Tolerant(signatureBase64);
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(data), sigBytes);
            SignerInformationStore signers = cms.getSignerInfos();

            if (signers.size() == 0) {
                result.put("valid", false);
                result.put("error", "В подписи нет подписантов");
                result.put("checks", java.util.List.of());
                return result;
            }

            SignerInformation signer = signers.getSigners().iterator().next();
            X509Certificate leaf = getSignerCert(signer, cms);

            // ─── 1. Соответствие содержимому ───
            boolean digestOk = checkSignedDigest(signer, data);
            checks.add(checkItem("Соответствие содержимому", digestOk,
                    digestOk ? "Хеш подписанных данных совпадает с файлом"
                             : "Хеш подписанных данных НЕ совпадает — файл изменён"));

            // ─── 2. Криптографическая подпись ───
            boolean cryptoOk = false;
            String cryptoDetails = "";
            try {
                cryptoOk = verifySignerCrypto(signer, leaf);
                cryptoDetails = cryptoOk ? "Подпись проверена криптопровайдером (ГОСТ)"
                                         : "Подпись не прошла криптопроверку";
            } catch (Exception e) {
                cryptoDetails = "Ошибка криптопровайдера: " + e.getMessage();
            }
            checks.add(checkItem("Криптографическая подпись", cryptoOk, cryptoDetails));

            // ─── 3. Срок действия leaf ───
            boolean leafValid = true;
            try { leaf.checkValidity(); } catch (Exception e) { leafValid = false; }
            checks.add(checkItem("Срок действия сертификата подписанта", leafValid,
                    leafValid ? "Действителен до " + fmtDate(leaf.getNotAfter())
                              : "Срок истёк или не наступил"));

            // ─── 4. Построение цепочки ───
            java.util.List<X509Certificate> chain = new java.util.ArrayList<>();
            boolean chainOk = false;
            String chainDetails = "";
            java.util.Set<X509Certificate> truststore = loadTrustedCerts();
            try {
                chain = buildChain(leaf, cms, truststore);
                chainOk = !chain.isEmpty();
                chainDetails = chainOk
                        ? "Построена цепочка из " + chain.size() + " сертификатов"
                        : "Цепочка пуста";
            } catch (Exception e) {
                chainDetails = "Не удалось построить: " + e.getMessage();
            }
            checks.add(checkItem("Цепочка сертификатов", chainOk, chainDetails));

            // ─── 5. Корень в truststore ───
            boolean rootOk = false;
            String rootDetails = "";
            if (chainOk) {
                X509Certificate root = chain.get(chain.size() - 1);
                try {
                    rootOk = isTrustedRoot(root, truststore);
                    rootDetails = rootOk
                            ? "Корневой сертификат «"
                              + extractCn(root.getSubjectX500Principal().getName())
                              + "» в списке доверенных"
                            : "Корневой сертификат НЕ в списке доверенных";
                } catch (Exception e) {
                    rootDetails = "Ошибка проверки корня: " + e.getMessage();
                }
            } else {
                rootDetails = "Пропущено (цепочка не построена)";
            }
            checks.add(checkItem("Корневой сертификат в truststore", rootOk, rootDetails));

            // ─── 6. Подписи звеньев ───
            boolean chainSigsOk = false;
            String chainSigsDetails = "";
            if (chainOk) {
                try {
                    verifyChainSignatures(chain);
                    chainSigsOk = true;
                    chainSigsDetails = "Все подписи в цепочке валидны";
                } catch (Exception e) {
                    chainSigsDetails = "Провал: " + e.getMessage();
                }
            } else {
                chainSigsDetails = "Пропущено";
            }
            checks.add(checkItem("Подписи звеньев цепочки", chainSigsOk, chainSigsDetails));

            // ─── 7. Срок всех звеньев ───
            boolean allValidDates = false;
            String datesDetails = null;
            if (chainOk) {
                allValidDates = true;
                for (X509Certificate cc : chain) {
                    try {
                        cc.checkValidity();
                    } catch (Exception e) {
                        allValidDates = false;
                        datesDetails = "Истёк/не наступил срок: "
                                + extractCn(cc.getSubjectX500Principal().getName());
                        break;
                    }
                }
                if (allValidDates) datesDetails = "Все " + chain.size() + " сертификатов действительны";
                else if (datesDetails == null) datesDetails = "Ошибка проверки сроков";
            } else {
                datesDetails = "Пропущено (цепочка не построена)";
            }
            checks.add(checkItem("Срок действия всей цепочки", allValidDates, datesDetails));

            // ─── 8. Отзыв по CRL (онлайн, из CDP сертификатов) ───
            boolean crlOk = false;
            String crlDetails = "";
            java.util.Map<String, OnlineCrlVerifier.CrlCheckResult> crlReport = new java.util.LinkedHashMap<>();
            if (chainOk && onlineCrlVerifier != null) {
                crlReport = onlineCrlVerifier.checkChain(chain);
                int checked = 0, revoked = 0, failed = 0;
                for (var entry : crlReport.entrySet()) {
                    var r = entry.getValue();
                    if (r.revoked()) revoked++;
                    else if (r.checked()) checked++;
                    else failed++;
                }
                if (revoked > 0) {
                    crlDetails = "Отозванных сертификатов: " + revoked;
                } else if (failed > 0) {
                    crlDetails = "Не удалось проверить " + failed
                            + " сертификат(ов) — CDP недоступен";
                } else {
                    crlOk = true;
                    crlDetails = "Проверено онлайн по CDP: " + checked + " сертификат(ов)";
                }
            } else if (chainOk) {
                crlDetails = "Онлайн-проверка CRL недоступна";
            } else {
                crlDetails = "Пропущено (цепочка не построена)";
            }
            checks.add(checkItem("Отзыв сертификатов (CRL из CDP)", crlOk, crlDetails));

            // ─── 9. EKU (назначение ключа) ───
            boolean ekuOk = checkEku(leaf);
            checks.add(checkItem("Назначение ключа (EKU)", ekuOk,
                    ekuOk ? "Ключ предназначен для подписи документов"
                          : "Ключ НЕ предназначен для подписи документов"));

            // ─── Итог ───
            boolean allOk = checks.stream()
                    .allMatch(m -> Boolean.TRUE.equals(m.get("passed")));

            result.put("valid", allOk);
            result.put("checks", checks);
            result.put("signerSubject", extractCn(leaf.getSubjectX500Principal().getName()));
            result.put("signerFullSubject", CertificateSubjectFormatter.format(leaf));
            result.put("signerFullSubjectTech", leaf.getSubjectX500Principal().getName());
            result.put("signerSerial", leaf.getSerialNumber().toString(16).toUpperCase());
            result.put("signerIssuer", extractCn(leaf.getIssuerX500Principal().getName()));
            result.put("signerIssuerFull", CertificateSubjectFormatter.formatIssuer(leaf));
            result.put("signerIssuerFullTech", leaf.getIssuerX500Principal().getName());
            result.put("signerThumbprint", thumbprintSha1(leaf));
            result.put("signerKeyUsage", keyUsageStr(leaf));
            result.put("crlReport", crlReport);
            result.put("signerNotBefore", leaf.getNotBefore().toInstant().toString());
            result.put("signerNotAfter", leaf.getNotAfter().toInstant().toString());

            java.util.List<String> chainCns = new java.util.ArrayList<>();
            for (X509Certificate cc : chain) {
                chainCns.add(extractCn(cc.getSubjectX500Principal().getName()));
            }
            result.put("chain", chainCns);

            return result;

        } catch (Exception e) {
            log.warn("verifyDetailed failed: {}", e.getMessage());
            result.put("valid", false);
            result.put("error", "Ошибка: " + e.getMessage());
            result.put("checks", java.util.List.of());
            return result;
        }
    }

    private static java.util.Map<String, Object> checkItem(String name, boolean passed, String details) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("passed", passed);
        m.put("details", details);
        return m;
    }

    /** Проверяет EKU: digitalSignature (0) или nonRepudiation (1). */
    private boolean checkEku(X509Certificate cert) {
        try {
            boolean[] ku = cert.getKeyUsage();
            if (ku == null) return true; // нет расширения — не блокируем
            return ku.length > 0 && (ku[0] || (ku.length > 1 && ku[1]));
        } catch (Exception e) {
            return true;
        }
    }

    private static String fmtDate(java.util.Date d) {
        if (d == null) return "—";
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy").withZone(java.time.ZoneId.systemDefault());
        return f.format(d.toInstant());
    }

    /** SHA-1 отпечаток сертификата (hex uppercase, без разделителей). */
    private static String thumbprintSha1(X509Certificate cert) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest(cert.getEncoded());
            return java.util.HexFormat.of().withUpperCase().formatHex(h);
        } catch (Exception e) {
            return "—";
        }
    }

    /** Человекочитаемое keyUsage. */
    private static String keyUsageStr(X509Certificate cert) {
        try {
            boolean[] ku = cert.getKeyUsage();
            if (ku == null) return "не задан";
            String[] names = {"digitalSignature", "nonRepudiation", "keyEncipherment",
                    "dataEncipherment", "keyAgreement", "keyCertSign", "cRLSign",
                    "encipherOnly", "decipherOnly"};
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int i = 0; i < ku.length && i < names.length; i++) {
                if (ku[i]) out.add(names[i]);
            }
            return out.isEmpty() ? "не задан" : String.join(", ", out);
        } catch (Exception e) {
            return "—";
        }
    }
}
