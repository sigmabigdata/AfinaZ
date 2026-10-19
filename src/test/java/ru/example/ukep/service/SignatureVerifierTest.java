package ru.example.ukep.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты {@link SignatureVerifier} — детерминированные части.
 *
 * <p>Генерируем self-signed RSA-сертификат через BouncyCastle, подписываем
 * данные в формате CMS SignedData (аналог CAdES-BES), проверяем:
 * <ul>
 *   <li>успешную верификацию, если cert в truststore;</li>
 *   <li>отказ, если cert не в truststore;</li>
 *   <li>обработку битого base64 / пустой подписи.</li>
 * </ul>
 *
 * <p>Не покрывает: ГОСТ-подпись, real chain (нужен УЦ), CRL-отзыв (soft-fail).</p>
 */
@SuppressWarnings("java:S5838")  // assertThat(map.get()).isEqualTo(...) — выразительнее
class SignatureVerifierTest {

    @TempDir Path tempDir;

    static KeyPair keyPair;
    static X509Certificate cert;
    static byte[] data = "Hello, AfinaZ!".getBytes();

    private SignatureVerifier verifier;

    @BeforeAll
    static void generateCert() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=Test User, O=AfinaZ, C=RU");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - 3_600_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 86_400_000L);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        cert = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    @BeforeEach
    void setUp() throws Exception {
        Path certsDir = tempDir.resolve("certs");
        Path crlsDir = tempDir.resolve("crls");
        Files.createDirectories(certsDir);
        Files.createDirectories(crlsDir);

        // Кладём cert в truststore
        Files.write(certsDir.resolve("test-root.cer"), cert.getEncoded());

        verifier = new SignatureVerifier(certsDir.toString(), crlsDir.toString());
    }

    /** Генерирует detached CAdES-BES-подобную подпись через BouncyCastle. */
    private static String signDetached(byte[] content, boolean includeCert) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(keyPair.getPrivate());
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                .build(cs, cert));
        if (includeCert) {
            gen.addCertificates(new JcaCertStore(List.of(cert)));
        }
        CMSSignedData cms = gen.generate(new CMSProcessableByteArray(content), false);
        return Base64.getEncoder().encodeToString(cms.getEncoded());
    }

    @Test
    @DisplayName("verifyDetached — валидная подпись и cert в truststore → valid=true")
    void verifyDetachedSuccess() throws Exception {
        String sig = signDetached(data, true);
        Map<String, Object> result = verifier.verifyDetached(data, sig);

        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("signersCount")).isEqualTo(1);
        assertThat((String) result.get("signerSubject")).contains("Test User");
        assertThat((String) result.get("signerSerial")).isNotBlank();
    }

    @Test
    @DisplayName("verifyDetached — неверный base64 → RuntimeException от BC Base64")
    void verifyDetachedInvalidBase64() {
        assertThatThrownBy(() -> verifier.verifyDetached(data, "%%%not-base64%%%"))
                .isInstanceOf(org.bouncycastle.util.encoders.DecoderException.class);
    }

    @Test
    @DisplayName("verifyDetached — пустая подпись → CMSException")
    void verifyDetachedEmptySig() {
        assertThatThrownBy(() -> verifier.verifyDetached(data, ""))
                .isInstanceOf(org.bouncycastle.cms.CMSException.class);
    }

    @Test
    @DisplayName("verifyDetached — PEM-обёртка корректно снимается")
    void verifyDetachedPemWrapped() throws Exception {
        String sig = signDetached(data, true);
        // Оборачиваем как PEM — SignatureVerifier должен снять обёртку
        String pem = "-----BEGIN SIGNATURE-----\n" + sig + "\n-----END SIGNATURE-----";
        Map<String, Object> result = verifier.verifyDetached(data, pem);
        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("verifyDetached(Path, sig) — несуществующий файл → IOException")
    void verifyDetachedPathMissingFile() {
        Path missing = tempDir.resolve("no-such-file.bin");
        assertThatThrownBy(() -> verifier.verifyDetached(missing, "any"))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("verifyDetached(Path, sig) — валидная подпись → valid=true")
    void verifyDetachedPathSuccess() throws Exception {
        Path file = tempDir.resolve("content.bin");
        Files.write(file, data);
        String sig = signDetached(data, true);

        Map<String, Object> result = verifier.verifyDetached(file, sig);
        assertThat(result.get("valid")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("verifyDetached — cert не в truststore → IllegalStateException")
    void verifyDetachedUntrustedCert() throws Exception {
        // Создаём вторую папку certs, где нашего cert нет
        Path emptyCerts = tempDir.resolve("empty-certs");
        Path crls = tempDir.resolve("crls");
        Files.createDirectories(emptyCerts);
        Files.createDirectories(crls);

        SignatureVerifier strict = new SignatureVerifier(emptyCerts.toString(), crls.toString());
        String sig = signDetached(data, true);

        assertThatThrownBy(() -> strict.verifyDetached(data, sig))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("truststore");
    }
}
