package ru.example.ukep.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Парсер ЭМЧД XML (формат ФНС, urn://x-artefacts/EMCHD_1).
 * Извлекает: ФИО представителя, SNILS, INN, организацию, номер/дату/срок доверенности.
 * Без XXE (защита от XML-инъекций).
 */
public class PowerOfAttorneyXmlParser {

    public record Parsed(
            String fio, String snils, String inn,
            String orgName, String orgInn,
            String docNumber, String docGuid,
            LocalDate docDate, LocalDate validFrom, LocalDate validUntil,
            String notaryName
    ) {}

    public static class ParseException extends Exception {
        public ParseException(String msg) { super(msg); }
        public ParseException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static Parsed parse(byte[] xmlBytes) throws ParseException {
        Document doc;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);

            doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes));
        } catch (Exception e) {
            throw new ParseException("Не удалось разобрать XML: " + e.getMessage(), e);
        }

        Element root = doc.getDocumentElement();

        // --- СвДов (атрибуты номера/даты/срока) ---
        Element svDov = findFirst(root, "СвДов");
        if (svDov == null) throw new ParseException("Не найден элемент СвДов");
        String docNumber = attr(svDov, "ВнНомДовер");
        if (docNumber == null || docNumber.isBlank()) docNumber = attr(svDov, "НомДовер");
        String docGuid = attr(svDov, "НомДовер");
        LocalDate docDate = parseDate(attr(svDov, "ДатаВыдДовер"));
        LocalDate validUntil = parseDate(attr(svDov, "СрокДейст"));
        if (validUntil == null) throw new ParseException("Не найден срок действия доверенности (СрокДейст)");

        // --- СвУпПред (представитель) ---
        Element svUpPred = findFirst(root, "СвУпПред");
        if (svUpPred == null) throw new ParseException("Не найден элемент СвУпПред (представитель)");

        // ФИО
        String fio = null;
        Element fioEl = findFirst(svUpPred, "ФИО");
        if (fioEl != null) {
            fio = joinFio(attr(fioEl, "Фамилия"), attr(fioEl, "Имя"), attr(fioEl, "Отчество"));
        }
        if (fio == null) throw new ParseException("Не найдено ФИО представителя");

        // SNILS / INN — из СведФизЛ
        Element svFizL = findFirst(svUpPred, "СведФизЛ");
        String snils = svFizL != null ? attr(svFizL, "СНИЛС") : null;
        String inn = svFizL != null ? attr(svFizL, "ИННФЛ") : null;

        // --- СвДоверит (организация) ---
        String orgName = null, orgInn = null;
        Element svRosOrg = findFirst(root, "СвРосОрг");
        if (svRosOrg != null) {
            orgName = attr(svRosOrg, "НаимОрг");
            orgInn = attr(svRosOrg, "ИННЮЛ");
        }

        // --- Нотариус ---
        String notaryName = null;
        Element fioNotEl = findFirst(root, "ФИОНотДейств");
        if (fioNotEl != null) {
            notaryName = joinFio(attr(fioNotEl, "Фамилия"),
                                 attr(fioNotEl, "Имя"),
                                 attr(fioNotEl, "Отчество"));
        }

        return new Parsed(fio, snils, inn, orgName, orgInn,
                docNumber, docGuid, docDate, docDate, validUntil, notaryName);
    }

    private static String joinFio(String s, String n, String p) {
        StringBuilder sb = new StringBuilder();
        if (s != null && !s.isBlank()) sb.append(s.trim());
        if (n != null && !n.isBlank()) { if (sb.length() > 0) sb.append(' '); sb.append(n.trim()); }
        if (p != null && !p.isBlank()) { if (sb.length() > 0) sb.append(' '); sb.append(p.trim()); }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static Element findFirst(Element parent, String localName) {
        NodeList nl = parent.getElementsByTagNameNS("*", localName);
        return nl.getLength() > 0 ? (Element) nl.item(0) : null;
    }

    private static String attr(Element el, String name) {
        return el != null && el.hasAttribute(name) ? el.getAttribute(name) : null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    /** Хелпер для тестов/логов — нормализовать ФИО в один регистр. */
    public static String normalizeFio(String fio) {
        if (fio == null) return "";
        return fio.trim().toUpperCase().replaceAll("\\s+", " ");
    }
}
