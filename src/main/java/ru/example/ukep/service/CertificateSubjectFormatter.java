package ru.example.ukep.service;

import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1String;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Форматирует X.500 subject сертификата в читаемый вид.
 *
 * На вход: X509Certificate
 * На выход: строка вида
 *   "CN=Дырул Анатолий Юрьевич, SURNAME=Дырул, GIVENNAME=Анатолий Юрьевич,
 *    C=RU, E=dyroul@nbdbank.ru, СНИЛС=18962570627, ИНН=525636470737"
 *
 * OID'ы 2.5.4.4, 2.5.4.42, 2.5.4.12, 1.2.643.100.3, 1.2.643.3.131.1.1,
 * 1.2.840.113549.1.9.1 в RFC2253 приходят как "OID.1.2.3=#0c0a...".
 * Декодируем DER-hex через BouncyCastle.
 */
public final class CertificateSubjectFormatter {

    private static final Logger log = LoggerFactory.getLogger(CertificateSubjectFormatter.class);

    /** Карта: OID или короткое имя → человекочитаемое имя. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        // Стандартные
        LABELS.put("2.5.4.3", "CN");
        LABELS.put("CN", "CN");
        LABELS.put("2.5.4.4", "SURNAME");
        LABELS.put("SN", "SURNAME");
        LABELS.put("SURNAME", "SURNAME");
        LABELS.put("2.5.4.42", "GIVENNAME");
        LABELS.put("GN", "GIVENNAME");
        LABELS.put("GIVENNAME", "GIVENNAME");
        LABELS.put("2.5.4.12", "T");
        LABELS.put("TITLE", "T");
        LABELS.put("2.5.4.10", "O");
        LABELS.put("O", "O");
        LABELS.put("2.5.4.11", "OU");
        LABELS.put("OU", "OU");
        LABELS.put("2.5.4.6", "C");
        LABELS.put("C", "C");
        LABELS.put("2.5.4.8", "ST");
        LABELS.put("ST", "ST");
        LABELS.put("2.5.4.7", "L");
        LABELS.put("L", "L");
        LABELS.put("2.5.4.9", "STREET");
        LABELS.put("STREET", "STREET");
        LABELS.put("2.5.4.5", "SERIALNUMBER");
        LABELS.put("2.5.4.43", "INITIALS");
        LABELS.put("2.5.4.44", "GENERATION");
        LABELS.put("2.5.4.46", "DNQ");
        LABELS.put("2.5.4.65", "PSEUDONYM");
        // Российские
        LABELS.put("1.2.840.113549.1.9.1", "E");
        LABELS.put("EMAILADDRESS", "E");
        LABELS.put("E", "E");
        LABELS.put("1.2.643.100.3", "СНИЛС");
        LABELS.put("SNILS", "СНИЛС");
        LABELS.put("1.2.643.3.131.1.1", "ИНН");
        LABELS.put("INN", "ИНН");
        LABELS.put("1.2.643.100.1", "ОГРН");
        LABELS.put("OGRN", "ОГРН");
        LABELS.put("1.2.643.100.4", "ИННЮЛ");
        LABELS.put("1.2.643.100.5", "ОГРНИП");
        LABELS.put("1.2.643.100.104", "ОГРНИП");
        LABELS.put("1.2.643.100.114", "ОГРН");
        LABELS.put("1.2.643.100.201", "ОГРН");
        LABELS.put("1.2.643.100.202", "ИННЮЛ");
        LABELS.put("1.2.643.3.131.1.1", "ИНН");
    }

    private CertificateSubjectFormatter() { /* utility */ }

    /**
     * Возвращает читаемый subject сертификата.
     * Пример: "CN=Дырул Анатолий Юрьевич, SURNAME=Дырул, GIVENNAME=Анатолий Юрьевич, ..."
     */
    public static String format(X509Certificate cert) {
        if (cert == null) return "";
        try {
            String rfc2253 = cert.getSubjectX500Principal().getName(
                    javax.security.auth.x500.X500Principal.RFC2253);
            LdapName ldap = new LdapName(rfc2253);
            StringBuilder sb = new StringBuilder();
            // LdapName.getRdns() возвращает RDN в обратном порядке (от листа к корню).
            // Для вывода используем reverse — хотим CN первым.
            var rdns = new java.util.ArrayList<>(ldap.getRdns());
            java.util.Collections.reverse(rdns);
            for (Rdn rdn : rdns) {
                String type = rdn.getType();
                String value = decodeValue(rdn.getValue());
                if (value == null || value.isEmpty()) continue;
                String label = resolveLabel(type);
                if (sb.length() > 0) sb.append(", ");
                sb.append(label).append("=").append(value);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("format(subject) failed: {}", e.getMessage());
            return cert.getSubjectX500Principal().getName();
        }
    }

    /**
     * Возвращает читаемый issuer.
     */
    public static String formatIssuer(X509Certificate cert) {
        if (cert == null) return "";
        try {
            String rfc2253 = cert.getIssuerX500Principal().getName(
                    javax.security.auth.x500.X500Principal.RFC2253);
            LdapName ldap = new LdapName(rfc2253);
            StringBuilder sb = new StringBuilder();
            var rdns = new java.util.ArrayList<>(ldap.getRdns());
            java.util.Collections.reverse(rdns);
            for (Rdn rdn : rdns) {
                String type = rdn.getType();
                String value = decodeValue(rdn.getValue());
                if (value == null || value.isEmpty()) continue;
                String label = resolveLabel(type);
                if (sb.length() > 0) sb.append(", ");
                sb.append(label).append("=").append(value);
            }
            return sb.toString();
        } catch (Exception e) {
            return cert.getIssuerX500Principal().getName();
        }
    }

    private static String resolveLabel(String type) {
        if (type == null) return "?";
        String upper = type.toUpperCase();
        // Прямое совпадение
        String byExact = LABELS.get(type);
        if (byExact != null) return byExact;
        // Через OID.префикс
        String stripped = upper.startsWith("OID.") ? type.substring(4) : type;
        String byOid = LABELS.get(stripped);
        if (byOid != null) return byOid;
        // Через upper
        String byUpper = LABELS.get(upper);
        if (byUpper != null) return byUpper;
        // fallback — вернуть как есть
        return stripped;
    }

    /**
     * Декодирует значение RDN. Если byte[] (DER-encoded) — через ASN1,
     * иначе — как строку.
     */
    private static String decodeValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s.trim();
        if (value instanceof byte[] bytes) {
            try {
                ASN1Primitive asn1 = ASN1Primitive.fromByteArray(bytes);
                if (asn1 instanceof ASN1String as) {
                    return as.getString().trim();
                }
            } catch (Exception ignored) { /* fallback */ }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        return String.valueOf(value).trim();
    }
}
