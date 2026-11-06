package ru.example.ukep.service;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Форматирует X.500 subject/issuer сертификата в читаемый вид.
 * Использует BouncyCastle X500Name — работает с DER-кодированными полями
 * (SURNAME, GIVENNAME, СНИЛС, ИНН, ОГРН и т.д.) без ошибок JNDI.
 */
public final class CertificateSubjectFormatter {

    private static final Logger log = LoggerFactory.getLogger(CertificateSubjectFormatter.class);

    /** OID → короткое имя (как на Госуслугах). */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("2.5.4.3", "CN");
        LABELS.put("2.5.4.4", "SURNAME");
        LABELS.put("2.5.4.42", "GIVENNAME");
        LABELS.put("2.5.4.43", "INITIALS");
        LABELS.put("2.5.4.12", "T");
        LABELS.put("2.5.4.10", "O");
        LABELS.put("2.5.4.11", "OU");
        LABELS.put("2.5.4.6", "C");
        LABELS.put("2.5.4.8", "ST");
        LABELS.put("2.5.4.7", "L");
        LABELS.put("2.5.4.9", "STREET");
        LABELS.put("2.5.4.5", "SERIALNUMBER");
        LABELS.put("2.5.4.46", "DNQ");
        LABELS.put("2.5.4.65", "PSEUDONYM");
        LABELS.put("1.2.840.113549.1.9.1", "E");           // emailAddress
        LABELS.put("1.2.643.100.3", "СНИЛС");                // SNILS
        LABELS.put("1.2.643.3.131.1.1", "ИНН");              // ИНН физлица
        LABELS.put("1.2.643.100.1", "ОГРН");
        LABELS.put("1.2.643.100.4", "ИННЮЛ");
        LABELS.put("1.2.643.100.5", "ОГРНИП");
        LABELS.put("1.2.643.100.104", "ОГРНИП");
        LABELS.put("1.2.643.100.201", "ОГРН");
        LABELS.put("1.2.643.100.202", "ИННЮЛ");
    }

    private CertificateSubjectFormatter() { /* utility */ }

    /** Читаемый subject, например:
     *  "CN=Дырул Анатолий Юрьевич, SURNAME=Дырул, GIVENNAME=Анатолий Юрьевич,
     *   C=RU, E=dyroul@nbdbank.ru, СНИЛС=18962570627, ИНН=525636470737" */
    public static String format(X509Certificate cert) {
        if (cert == null) return "";
        try {
            X500Name x500 = X500Name.getInstance(cert.getSubjectX500Principal().getEncoded());
            return render(x500);
        } catch (Exception e) {
            log.warn("format(subject) failed: {}", e.getMessage());
            return cert.getSubjectX500Principal().getName();
        }
    }

    /** Читаемый issuer. */
    public static String formatIssuer(X509Certificate cert) {
        if (cert == null) return "";
        try {
            X500Name x500 = X500Name.getInstance(cert.getIssuerX500Principal().getEncoded());
            return render(x500);
        } catch (Exception e) {
            log.warn("format(issuer) failed: {}", e.getMessage());
            return cert.getIssuerX500Principal().getName();
        }
    }

    private static String render(X500Name x500) {
        StringBuilder sb = new StringBuilder();
        for (RDN rdn : x500.getRDNs()) {
            for (AttributeTypeAndValue atv : rdn.getTypesAndValues()) {
                ASN1ObjectIdentifier type = atv.getType();
                ASN1Encodable value = atv.getValue();
                String label = labelOf(type);
                String val = decodeValue(value);
                if (val == null || val.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(label).append("=").append(val);
            }
        }
        return sb.toString();
    }

    private static String labelOf(ASN1ObjectIdentifier oid) {
        String byOid = LABELS.get(oid.getId());
        return byOid != null ? byOid : oid.getId();
    }

    /** Декодирует значение: ASN1String → String, иначе — toString. */
    private static String decodeValue(ASN1Encodable value) {
        if (value == null) return null;
        if (value instanceof ASN1String as) {
            return as.getString().trim();
        }
        try {
            return value.toASN1Primitive().toString();
        } catch (Exception e) {
            return null;
        }
    }
}
