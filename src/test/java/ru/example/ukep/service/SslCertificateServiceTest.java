package ru.example.ukep.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class SslCertificateServiceTest {

    @TempDir
    Path tempDir;

    private SslCertificateService createService() {
        SslCertificateService s = new SslCertificateService();
        ReflectionTestUtils.setField(s, "angieCertsPath", tempDir.toString());
        return s;
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static X509Certificate genCert(KeyPair kp) throws Exception {
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=test.local"),
                BigInteger.valueOf(now),
                new Date(now - 1000),
                new Date(now + 86400000L * 365),
                new X500Name("CN=test.local"),
                kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static String toPem(byte[] der, String type) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append("\n");
        }
        sb.append("-----END ").append(type).append("-----\n");
        return sb.toString();
    }

    @Test
    void validate_validPair_ok() throws Exception {
        KeyPair kp = genKeyPair();
        X509Certificate cert = genCert(kp);
        String certPem = toPem(cert.getEncoded(), "CERTIFICATE");
        String keyPem = toPem(kp.getPrivate().getEncoded(), "PRIVATE KEY");

        var result = createService().validate(certPem, keyPem);
        assertTrue(result.valid(), "Ожидали valid=true, получили: " + result.error());
    }

    @Test
    void validate_wrongKey_fail() throws Exception {
        KeyPair kp1 = genKeyPair();
        KeyPair kp2 = genKeyPair();
        X509Certificate cert = genCert(kp1);
        String certPem = toPem(cert.getEncoded(), "CERTIFICATE");
        String keyPem = toPem(kp2.getPrivate().getEncoded(), "PRIVATE KEY");

        var result = createService().validate(certPem, keyPem);
        assertFalse(result.valid());
        assertTrue(result.error().contains("не соответствует"));
    }

    @Test
    void validate_garbageCert_fail() {
        var result = createService().validate("NOT A PEM", "NOT A PEM");
        assertFalse(result.valid());
    }

    @Test
    void saveAndDelete_filesCreatedAndRemoved() throws Exception {
        SslCertificateService s = createService();
        KeyPair kp = genKeyPair();
        X509Certificate cert = genCert(kp);
        String certPem = toPem(cert.getEncoded(), "CERTIFICATE");
        String keyPem = toPem(kp.getPrivate().getEncoded(), "PRIVATE KEY");

        s.save(certPem, keyPem);
        assertTrue(Files.isRegularFile(tempDir.resolve("fullchain.pem")));
        assertTrue(Files.isRegularFile(tempDir.resolve("privkey.pem")));
        assertTrue(s.hasCertificate());

        String keyContent = Files.readString(tempDir.resolve("privkey.pem"), StandardCharsets.UTF_8);
        assertTrue(keyContent.contains("PRIVATE KEY"));

        s.delete();
        assertFalse(Files.exists(tempDir.resolve("fullchain.pem")));
        assertFalse(Files.exists(tempDir.resolve("privkey.pem")));
        assertFalse(s.hasCertificate());
    }
}
