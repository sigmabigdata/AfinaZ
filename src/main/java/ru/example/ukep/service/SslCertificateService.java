package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Set;

@Service
public class SslCertificateService {

    private static final Logger log = LoggerFactory.getLogger(SslCertificateService.class);

    @Value("${app.angie-certs-path:/opt/afinaz/angie-certs}")
    private String angieCertsPath;

    public record ValidationResult(boolean valid, String error, String subject, String issuer, String notAfter) {
        public static ValidationResult ok(String subject, String issuer, String notAfter) {
            return new ValidationResult(true, null, subject, issuer, notAfter);
        }
        public static ValidationResult fail(String error) {
            return new ValidationResult(false, error, null, null, null);
        }
    }

    public ValidationResult validate(String certPem, String keyPem) {
        try {
            X509Certificate cert = parseCertificate(certPem);
            PrivateKey privateKey = parsePrivateKey(keyPem);
            if (!keysMatch(cert, privateKey)) {
                return ValidationResult.fail("Приватный ключ не соответствует сертификату");
            }
            cert.checkValidity();
            return ValidationResult.ok(
                    cert.getSubjectX500Principal().getName(),
                    cert.getIssuerX500Principal().getName(),
                    cert.getNotAfter().toInstant().toString());
        } catch (java.security.cert.CertificateExpiredException e) {
            return ValidationResult.fail("Срок действия сертификата истёк");
        } catch (java.security.cert.CertificateNotYetValidException e) {
            return ValidationResult.fail("Сертификат ещё не вступил в силу");
        } catch (Exception e) {
            return ValidationResult.fail("Ошибка разбора: " + e.getMessage());
        }
    }

    private X509Certificate parseCertificate(String pem) throws Exception {
        String base64 = pem
                .replaceAll("-----BEGIN CERTIFICATE-----", "")
                .replaceAll("-----END CERTIFICATE-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem
                .replaceAll("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll("-----END PRIVATE KEY-----", "")
                .replaceAll("-----BEGIN RSA PRIVATE KEY-----", "")
                .replaceAll("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("-----BEGIN EC PRIVATE KEY-----", "")
                .replaceAll("-----END EC PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String alg : new String[]{"RSA", "EC", "DSA"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (Exception ignored) { }
        }
        throw new IllegalArgumentException("Не удалось определить алгоритм приватного ключа (RSA/EC/DSA)");
    }

    private boolean keysMatch(X509Certificate cert, PrivateKey privateKey) {
        try {
            String alg = cert.getPublicKey().getAlgorithm();
            String sigAlg = switch (alg) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                case "DSA" -> "SHA256withDSA";
                default -> "SHA256with" + alg;
            };
            byte[] testData = "afina-key-match-test".getBytes(StandardCharsets.UTF_8);
            Signature sig = Signature.getInstance(sigAlg);
            sig.initSign(privateKey);
            sig.update(testData);
            byte[] signature = sig.sign();
            sig.initVerify(cert.getPublicKey());
            sig.update(testData);
            return sig.verify(signature);
        } catch (Exception e) {
            log.warn("keysMatch: {}", e.getMessage());
            return false;
        }
    }

    public void save(String certPem, String keyPem) throws IOException {
        Path dir = Paths.get(angieCertsPath);
        Files.createDirectories(dir);
        Path certPath = dir.resolve("fullchain.pem");
        Path keyPath = dir.resolve("privkey.pem");
        Files.writeString(certPath, certPem, StandardCharsets.UTF_8);
        Files.writeString(keyPath, keyPem, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(keyPath, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) { }
        log.info("SSL certificate saved to {}", certPath);
    }

    public void delete() throws IOException {
        Path dir = Paths.get(angieCertsPath);
        Files.deleteIfExists(dir.resolve("fullchain.pem"));
        Files.deleteIfExists(dir.resolve("privkey.pem"));
        log.info("SSL certificate deleted");
    }

    public void restartAngie() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("docker", "restart", "afina-angie");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("docker restart вернул код " + exit);
        }
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            ProcessBuilder inspect = new ProcessBuilder(
                    "docker", "inspect", "-f", "{{.State.Running}}", "afina-angie");
            inspect.redirectErrorStream(true);
            Process ip = inspect.start();
            String output = new String(ip.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            ip.waitFor();
            if ("true".equals(output)) {
                log.info("Angie restarted and is running");
                return;
            }
        }
        throw new IOException("Angie не запустился после перезапуска — проверь логи");
    }

    public boolean hasCertificate() {
        return Files.isRegularFile(Paths.get(angieCertsPath, "fullchain.pem"))
                && Files.isRegularFile(Paths.get(angieCertsPath, "privkey.pem"));
    }
}
