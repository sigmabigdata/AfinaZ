package ru.example.ukep.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.entity.DocumentVisualization;
import ru.example.ukep.entity.PowerOfAttorney;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.DocumentVisualizationRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Визуализация подписи — синий штамп в нижнем колонтитуле каждой страницы PDF.
 * Cert подписанта и signingTime извлекаются из CMS-подписи (по SID), не из БД.
 */
@Service
public class SignatureVisualizationService {

    private static final Logger log = LoggerFactory.getLogger(SignatureVisualizationService.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final DateTimeFormatter D_FMT  = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // Компактный стиль (в 2 раза ниже): 4.5pt вместо 7pt, line-height 4.5 вместо 9
    private static final float FONT_SIZE = 6.0f;
    private static final float LINE_HEIGHT = 8f;
    private static final float MARGIN = 20f;         // текст от низа страницы
    private static final float BOX_MARGIN_X = 10f;   // рамка от боков страницы
    private static final float BOX_PADDING = 3f;     // зазор между рамкой и текстом

    // Цвет #003399 (тёмно-синий, как в Диадоке)
    private static final float[] COLOR_BLUE = {0.0f, 0.20f, 0.60f};
    private static final float[] COLOR_BLACK = {0f, 0f, 0f};

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final DocumentRepository documentRepository;
    private final DocumentVisualizationRepository visRepo;
    private final DocumentService documentService;
    private final PowerOfAttorneyService poaService;
    private final Path storageRoot;

    public SignatureVisualizationService(DocumentRepository documentRepository,
                                         DocumentVisualizationRepository visRepo,
                                         DocumentService documentService,
                                         PowerOfAttorneyService poaService,
                                         @Value("${app.storage-path}") String storagePath) throws IOException {
        this.documentRepository = documentRepository;
        this.visRepo = visRepo;
        this.documentService = documentService;
        this.poaService = poaService;
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        Files.createDirectories(storageRoot.resolve("visualizations"));
    }

    @Transactional
    public DocumentVisualization generate(Document document) throws IOException {
        Document doc = documentRepository.findByIdWithSignatures(document.getId())
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));

        if (doc.getSignatureBase64() == null && doc.getSignatures().isEmpty()) {
            throw new IllegalStateException("Документ ещё не подписан — визуализация недоступна");
        }

        String contentType = doc.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase("application/pdf")) {
            throw new IllegalStateException(
                    "Визуализация поддерживается только для PDF. Тип: " + contentType);
        }

        byte[] original = documentService.getBytes(doc);

        try (PDDocument check = PDDocument.load(original)) {
            if (check.getNumberOfPages() == 0) {
                throw new IllegalStateException("PDF не содержит страниц");
            }
        }

        byte[] withStamps = stampPdf(original, doc);

        String storedName = "vis_" + doc.getId() + "_" + System.currentTimeMillis() + ".pdf";
        Path target = storageRoot.resolve("visualizations").resolve(storedName);
        Files.write(target, withStamps,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        DocumentVisualization vis = visRepo.findByDocument(doc).orElseGet(() -> {
            DocumentVisualization v = new DocumentVisualization();
            v.setDocument(doc);
            return v;
        });
        if (vis.getStoredName() != null && !vis.getStoredName().equals(storedName)) {
            try {
                Files.deleteIfExists(storageRoot.resolve("visualizations").resolve(vis.getStoredName()));
            } catch (Exception e) {
                log.debug("Не удалось удалить старую визуализацию: {}", e.getMessage());
            }
        }
        vis.setStoredName(storedName);
        vis.setSize(withStamps.length);
        vis.setUpdatedAt(Instant.now());
        vis.setVersion(vis.getVersion() + 1);
        DocumentVisualization saved = visRepo.save(vis);

        log.info("Визуализация документа id={}: {} байт, v{}, подписей: {}",
                doc.getId(), withStamps.length, saved.getVersion(),
                doc.getSignatures().size());
        return saved;
    }

    public byte[] loadBytes(DocumentVisualization vis) throws IOException {
        Path p = storageRoot.resolve("visualizations").resolve(vis.getStoredName());
        if (!Files.isRegularFile(p)) {
            throw new IOException("Файл визуализации не найден: " + vis.getStoredName());
        }
        return Files.readAllBytes(p);
    }

    public Optional<DocumentVisualization> findFor(Document document) {
        return visRepo.findByDocument(document);
    }

    // ============ ядро ============

    private record CertInfo(String serialHex, Instant notBefore, Instant notAfter,
                            String ownerFio, String ownerDisplay, Instant signingTime) {}

    private byte[] stampPdf(byte[] original, Document document) throws IOException {
        List<DocumentSignature> sigs = new ArrayList<>(document.getSignatures());
        sigs.sort((a, b) -> {
            if (a.getSignedAt() == null && b.getSignedAt() == null) return 0;
            if (a.getSignedAt() == null) return 1;
            if (b.getSignedAt() == null) return -1;
            return b.getSignedAt().compareTo(a.getSignedAt());
        });

        Map<Long, CertInfo> certInfoMap = new HashMap<>();
        Map<Long, PowerOfAttorney> poaMap = new HashMap<>();
        for (var sig : sigs) {
            CertInfo info = extractCertInfo(sig, original);
            certInfoMap.put(sig.getId(), info);
            if (sig.isApplyPoa()) {
                Optional<PowerOfAttorney> poa = poaService.findEffectiveByFio(info.ownerFio());
                poa.ifPresent(p -> poaMap.put(sig.getId(), p));
            }
        }

        try (PDDocument pdf = PDDocument.load(original);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDFont font = loadFont(pdf);
            PDImageXObject logo = loadLogo(pdf);
            int totalPages = pdf.getNumberOfPages();

            for (int pageNum = 0; pageNum < totalPages; pageNum++) {
                PDPage page = pdf.getPage(pageNum);
                float pageWidth = page.getMediaBox().getWidth();

                try (PDPageContentStream cs = new PDPageContentStream(
                        pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                    // 1. ФУТЕР НА КАЖДОЙ СТРАНИЦЕ (лого + текст)
                    drawFooter(cs, font, logo, pageWidth);

                    // 2. ШТАМПЫ ПОДПИСЕЙ — ТОЛЬКО НА ПОСЛЕДНЕЙ СТРАНИЦЕ
                    if (pageNum == totalPages - 1) {
                        float stampHeight = calculateStampHeight(sigs);
                        // Приподнимаем штамп над футером (футер занимает ~20f)
                        float boxTopY = MARGIN + 20f + stampHeight + BOX_PADDING;
                        float boxBotY = MARGIN + 20f;

                        // Синяя рамка штампа
                        cs.setStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
                        cs.setLineWidth(0.6f);
                        cs.addRect(BOX_MARGIN_X, boxBotY,
                                pageWidth - 2 * BOX_MARGIN_X, boxTopY - boxBotY);
                        cs.stroke();

                        cs.setNonStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);

                        float y = boxTopY - LINE_HEIGHT;
                        boolean first = true;
                        for (var sig : sigs) {
                            if (!first) {
                                cs.setStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
                                cs.setLineWidth(0.25f);
                                cs.moveTo(MARGIN + 2f, y + LINE_HEIGHT / 2f);
                                cs.lineTo(pageWidth - MARGIN - 2f, y + LINE_HEIGHT / 2f);
                                cs.stroke();
                                y -= LINE_HEIGHT / 2f;
                            }
                            first = false;
                            y = drawSignatureBlock(cs, font, sig,
                                    certInfoMap.get(sig.getId()),
                                    poaMap.get(sig.getId()),
                                    sig.isApplyPoa(), pageWidth, y);
                            y -= LINE_HEIGHT / 2f;
                        }
                        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
                    }
                }
            }

            pdf.save(out);
            return out.toByteArray();
        }
    }

    private void drawFooter(PDPageContentStream cs, PDFont font, PDImageXObject logo, float pageWidth) throws IOException {
        float footerY = 15f; // Отступ от низа
        float logoWidth = 70f;
        float logoHeight = logo != null ? logoWidth * ((float) logo.getHeight() / logo.getWidth()) : 0f;

        // Логотип слева
        if (logo != null) {
            cs.drawImage(logo, MARGIN, footerY, logoWidth, logoHeight);
        }

        // Текст справа (серый, элегантный)
        String text = "Документ подписан усиленной квалифицированной электронной подписью";
        float fontSize = 8f;
        cs.setFont(font, fontSize);
        cs.setNonStrokingColor(0.4f, 0.4f, 0.4f); // Серый

        float textWidth = font.getStringWidth(text) / 1000f * fontSize;
        float textX = pageWidth - MARGIN - textWidth;
        float textY = footerY + (logoHeight / 2f) - (fontSize / 3f);

        cs.beginText();
        cs.newLineAtOffset(textX, textY);
        cs.showText(text);
        cs.endText();

        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
    }

    private PDImageXObject loadLogo(PDDocument pdf) {
        try (java.io.InputStream is = getClass().getResourceAsStream("/static/img/logo.png")) {
            if (is == null) {
                log.warn("Логотип не найден в /static/img/logo.png, футер будет без лого");
                return null;
            }
            byte[] bytes = is.readAllBytes();
            return PDImageXObject.createFromByteArray(pdf, bytes, "logo");
        } catch (java.io.IOException e) {
            log.warn("Не удалось загрузить логотип: {}", e.getMessage());
            return null;
        }
    }

    private float calculateStampHeight(List<DocumentSignature> sigs) {
        float total = 0f;
        for (var sig : sigs) {
            // 3 строки базовых + 1 строка POA (если applyPoa)
            float linesPerBlock = sig.isApplyPoa() ? 4f : 3f;
            total += linesPerBlock * LINE_HEIGHT;
        }
        float between = (sigs.size() > 1) ? (sigs.size() - 1) * (LINE_HEIGHT / 2f) : 0f;
        return total + between + LINE_HEIGHT;
    }

    private float drawSignatureBlock(PDPageContentStream cs, PDFont font,
                                      DocumentSignature sig, CertInfo certInfo,
                                      PowerOfAttorney poa, boolean applyPoa,
                                      float pageWidth, float y) throws IOException {
        float left = MARGIN;
        float right = pageWidth - MARGIN;

        // Строка 1: заголовок слева | дата справа
        Instant signTime = certInfo.signingTime() != null
                ? certInfo.signingTime()
                : sig.getSignedAt();
        String signedAt = signTime != null
                ? TS_FMT.format(signTime.atZone(ZoneId.systemDefault()))
                : "—";
        drawLineLeft(cs, font, "Дата: " + signedAt, left, y);
        drawLineRight(cs, font, "Документ подписан электронной подписью", right, y);
        y -= LINE_HEIGHT;

        // Строка 2: владелец (T, CN, O) — на всю ширину
        drawLineLeft(cs, font, "Владелец: " + nvl(certInfo.ownerDisplay()), left, y);
        y -= LINE_HEIGHT;

        // Строка 3: серийный номер слева | срок действия справа
        String validity = "—";
        if (certInfo.notBefore() != null && certInfo.notAfter() != null) {
            validity = "с " + D_FMT.format(certInfo.notBefore().atZone(ZoneId.systemDefault()))
                     + " по " + D_FMT.format(certInfo.notAfter().atZone(ZoneId.systemDefault()));
        }
        drawLineRight(cs, font, "Действителен: " + validity, right, y);
        drawLineLeft(cs, font, "Сертификат: " + nvl(certInfo.serialHex()), left, y);
        y -= LINE_HEIGHT;

        // Строка 4 (опц.): доверенность слева | срок справа
        if (applyPoa) {
            if (poa != null) {
                drawLineLeft(cs, font, "Доверенность №" + nvl(poa.getStampNumber()), left, y);
                String poaValidity = "—";
                if (poa.getValidFrom() != null && poa.getValidUntil() != null) {
                    poaValidity = "с " + D_FMT.format(poa.getValidFrom())
                                + " по " + D_FMT.format(poa.getValidUntil());
                } else if (poa.getValidUntil() != null) {
                    poaValidity = "по " + D_FMT.format(poa.getValidUntil());
                }
                drawLineRight(cs, font, "Действительна: " + poaValidity, right, y);
                y -= LINE_HEIGHT;
            } else {
                drawLineLeft(cs, font, "Доверенность не требуется", left, y);
                y -= LINE_HEIGHT;
            }
        }

        return y;
    }

    private static void drawLineLeft(PDPageContentStream cs, PDFont font,
                                      String text, float x, float y) throws IOException {
        if (text == null || text.isBlank()) return;
        String safe = truncate(text, 200);
        cs.beginText();
        cs.setFont(font, FONT_SIZE);
        cs.newLineAtOffset(x, y);
        cs.showText(safe);
        cs.endText();
    }

    /** Рисует текст с выравниванием по правому краю (rightX — правая граница). */
    private static void drawLineRight(PDPageContentStream cs, PDFont font,
                                       String text, float rightX, float y) throws IOException {
        if (text == null || text.isBlank()) return;
        String safe = truncate(text, 200);
        float width = font.getStringWidth(safe) / 1000f * FONT_SIZE;
        cs.beginText();
        cs.setFont(font, FONT_SIZE);
        cs.newLineAtOffset(rightX - width, y);
        cs.showText(safe);
        cs.endText();
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private static String nvl(String s) { return s != null && !s.isBlank() ? s : "—"; }

    /**
     * Собирает отображаемое имя владельца сертификата:
     *   [Должность] + [ФИО] + [Организация]
     *
     * <p>Источники:
     * <ul>
     *   <li>Должность: 2.5.4.12 / T / TITLE, либо unstructuredName</li>
     *   <li>ФИО: 2.5.4.4 (SURNAME) + 2.5.4.42 (GIVENNAME);
     *       fallback — CN, если не похож на название юрлица</li>
     *   <li>Организация: O</li>
     * </ul>
     *
     * <p>OID-поля часто приходят как DER-encoded byte[] (в RFC2253 — «#hex»),
     * поэтому их значения декодируются через BouncyCastle.
     */
    private static String buildOwnerDisplay(java.security.cert.X509Certificate cert) {
        try {
            var p = cert.getSubjectX500Principal();
            var ldap = new javax.naming.ldap.LdapName(
                    p.getName(javax.security.auth.x500.X500Principal.RFC2253));

            String cn = null, o = null, t = null;
            String surname = null, givenName = null, unstructured = null;

            for (var rdn : ldap.getRdns()) {
                String typeUpper = rdn.getType().toUpperCase();
                String value = decodeRdnValue(rdn.getValue());
                if (value == null || value.isEmpty()) continue;
                switch (typeUpper) {
                    case "CN" -> cn = value;
                    case "O"  -> o  = value;
                    case "T", "TITLE", "2.5.4.12" -> t = value;
                    case "SURNAME", "SN", "2.5.4.4" -> surname = value;
                    case "GIVENNAME", "GN", "2.5.4.42" -> givenName = value;
                    case "1.2.840.113549.1.9.2" -> unstructured = value;
                    default -> { /* OID.1.2.643.* — ИНН/СНИЛС/ОГРН, не для отображения */ }
                }
            }

            // 1. Должность
            String position = t;
            if (position == null && unstructured != null && looksLikePosition(unstructured)) {
                position = unstructured;
            }

            // 2. ФИО
            String fio = null;
            if (surname != null && givenName != null) {
                fio = surname + " " + givenName;
            } else if (surname != null) {
                fio = surname;
            } else if (givenName != null) {
                fio = givenName;
            } else if (cn != null && !looksLikeOrganization(cn)) {
                fio = cn;
            } else if (unstructured != null && !looksLikeOrganization(unstructured)) {
                fio = unstructured;
            }

            // 3. Организация (только O, без дубля с ФИО)
            String org = o;
            if (org != null && fio != null && org.equalsIgnoreCase(fio)) org = null;

            // Должность и ФИО из сертификата часто в КАПСЕ — приводим к виду
            String posDisplay = capitalizeFirst(position);
            String fioDisplay = titleCaseFio(fio);

            // Формат: "[Должность] [Организация], [ФИО]"
            //   Директор ООО "Ромашка", Иванов Иван Иванович
            // Если нет должности: "[Организация], [ФИО]"
            // Если нет ФИО:       "[Должность] [Организация]"
            // Физлицо без O/T:    "[ФИО]"
            String posOrg = null;
            if (posDisplay != null && !posDisplay.isBlank()
                    && org != null && !org.isBlank()) {
                posOrg = posDisplay + " " + org;
            } else if (posDisplay != null && !posDisplay.isBlank()) {
                posOrg = posDisplay;
            } else if (org != null && !org.isBlank()) {
                posOrg = org;
            }

            if (posOrg != null && fioDisplay != null && !fioDisplay.isBlank()) {
                return posOrg + ", " + fioDisplay;
            }
            if (posOrg != null) return posOrg;
            if (fioDisplay != null && !fioDisplay.isBlank()) return fioDisplay;
            if (cn != null && !cn.isBlank()) return cn;
            return p.getName();
        } catch (Exception e) {
            return cert.getSubjectX500Principal().getName();
        }
    }

    /**
     * Декодирует значение RDN: если byte[] (DER) — через ASN1,
     * иначе — как строку. Нужно потому, что OID-поля (2.5.4.4, 2.5.4.42, …)
     * в RFC2253 приходят как «#hex», и Java отдаёт их байтами.
     */
    private static String decodeRdnValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s.trim();
        if (value instanceof byte[] bytes) {
            try {
                var asn1 = org.bouncycastle.asn1.ASN1Primitive.fromByteArray(bytes);
                if (asn1 instanceof org.bouncycastle.asn1.ASN1String as) {
                    return as.getString().trim();
                }
            } catch (Exception ignored) { /* fallback ниже */ }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        return String.valueOf(value).trim();
    }

    /** Название юрлица/ИП (ООО, АО, ПАО, ЗАО, ОАО, ИП, LLC, LTD, INC). */
    private static boolean looksLikeOrganization(String s) {
        if (s == null || s.isBlank()) return false;
        String u = s.toUpperCase();
        return u.startsWith("ООО") || u.startsWith("АО ") || u.startsWith("ПАО")
            || u.startsWith("ЗАО") || u.startsWith("ОАО") || u.startsWith("ИП ")
            || u.contains(" ООО ") || u.contains(" АО ") || u.contains(" ПАО ")
            || u.contains(" ЗАО ") || u.contains(" ОАО ")
            || u.contains("LLC") || u.contains("LTD") || u.contains("INC");
    }

    /** Похоже на должность (директор, менеджер, …). */
    private static boolean looksLikePosition(String s) {
        if (s == null) return false;
        String u = s.toUpperCase();
        return u.contains("ДИРЕКТОР") || u.contains("МЕНЕДЖЕР")
            || u.contains("ГЛАВ")       || u.contains("ЗАМЕСТИТЕЛЬ")
            || u.contains("РУКОВОДИТЕЛЬ") || u.contains("СПЕЦИАЛИСТ")
            || u.contains("БУХГАЛТЕР")  || u.contains("ИНЖЕНЕР")
            || u.contains("НАЧАЛЬНИК")  || u.contains("ПРЕДСЕДАТЕЛЬ")
            || u.contains("DIRECTOR")    || u.contains("MANAGER")
            || u.contains("CHIEF")       || u.contains("HEAD");
    }

    /**
     * Первая буква заглавная, остальные строчные.
     * "ДИРЕКТОР"           → "Директор"
     * "ГЕНЕРАЛЬНЫЙ ДИРЕКТОР" → "Генеральный директор"
     * Смешанный регистр (уже "Директор") — возвращается как есть.
     */
    private static String capitalizeFirst(String s) {
        if (s == null || s.isBlank()) return s;
        if (!s.equals(s.toUpperCase())) return s;
        String lower = s.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Каждое слово с заглавной буквы, остальные строчные.
     * "МАЖЕЙКО АЛЕКСЕЙ АНАТОЛЬЕВИЧ" → "Мажейко Алексей Анатольевич"
     * Смешанный регистр (уже "Иванов Иван") — возвращается как есть.
     * Разделители (пробел, дефис, точка) начинают новое слово.
     */
    private static String titleCaseFio(String s) {
        if (s == null || s.isBlank()) return s;
        if (!s.equals(s.toUpperCase())) return s;
        var sb = new StringBuilder();
        boolean newWord = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetter(ch)) {
                sb.append(newWord ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
                newWord = false;
            } else {
                sb.append(ch);
                newWord = true;
            }
        }
        return sb.toString();
    }

    private static String extractCn(String subject) {
        if (subject == null) return "—";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(subject);
        return m.find() ? m.group(1).replace("\"", "").trim() : subject;
    }

    /**
     * Извлекает leaf-сертификат подписанта по SID из CMS (а не первый non-self-signed),
     * его serial/validity/CN и signingTime из CMS signed attributes.
     */
    private CertInfo extractCertInfo(DocumentSignature sig, byte[] content) {
        try {
            String cleaned = sig.getSignatureBase64()
                    .replaceAll("-----BEGIN[^-]*-----", "")
                    .replaceAll("-----END[^-]*-----", "")
                    .replaceAll("\\s+", "")
                    .replaceAll("[^A-Za-z0-9+/=]", "");
            byte[] raw = Base64.getDecoder().decode(cleaned);

            CMSSignedData cms;
            try {
                cms = new CMSSignedData(new CMSProcessableByteArray(content), raw);
            } catch (Exception e) {
                cms = new CMSSignedData(raw);
            }

            // Первый signer (в подавляющем большинстве случаев — единственный)
            Collection<SignerInformation> signerInfos = cms.getSignerInfos().getSigners();
            if (signerInfos.isEmpty()) {
                throw new IllegalStateException("CMS не содержит signerInfos");
            }
            SignerInformation signer = signerInfos.iterator().next();

            // Cert подписанта по SID — это leaf, не промежуточный CA
            Collection<X509CertificateHolder> matches = cms.getCertificates().getMatches(signer.getSID());
            if (matches.isEmpty()) {
                throw new IllegalStateException("Cert подписанта не найден по SID");
            }
            X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC")
                    .getCertificate(matches.iterator().next());

            // signingTime из CMS signed attributes
            Instant signingTime = extractSigningTime(signer);

            String fullSubject = cert.getSubjectX500Principal().getName();
            log.info("extractCertInfo: full subject = {}", fullSubject);
            String ownerFio = extractCn(fullSubject);
            String ownerDisplay = buildOwnerDisplay(cert);
            log.info("extractCertInfo: sig={}, serial={}, ownerFio='{}', ownerDisplay='{}', signingTime={}",
                    sig.getId(), cert.getSerialNumber().toString(16), ownerFio, ownerDisplay, signingTime);
            return new CertInfo(
                    cert.getSerialNumber().toString(16).toUpperCase(),
                    cert.getNotBefore().toInstant(),
                    cert.getNotAfter().toInstant(),
                    ownerFio,
                    ownerDisplay,
                    signingTime);

        } catch (Exception e) {
            log.debug("extractCertInfo failed for sig {}: {}", sig.getId(), e.getMessage());
        }

        // Fallback на данные из БД
        String fallbackDisplay = extractCn(sig.getSignerSubject());
        return new CertInfo(
                sig.getSignerSerial() != null ? sig.getSignerSerial().toUpperCase() : "—",
                null, null,
                fallbackDisplay,
                fallbackDisplay,
                null);
    }

    /** Извлечь signingTime из CMS signed attributes (RFC 5652). */
    private Instant extractSigningTime(SignerInformation signer) {
        try {
            AttributeTable signedAttrs = signer.getSignedAttributes();
            if (signedAttrs == null) return null;
            Attribute attr = signedAttrs.get(CMSAttributes.signingTime);
            if (attr == null) return null;
            ASN1Primitive value = attr.getAttrValues().getObjectAt(0).toASN1Primitive();
            return Time.getInstance(value).getDate().toInstant();
        } catch (Exception e) {
            log.debug("extractSigningTime failed: {}", e.getMessage());
            return null;
        }
    }

    private PDFont loadFont(PDDocument pdf) throws IOException {
        String[] paths = {
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
        };
        for (String p : paths) {
            Path f = Paths.get(p);
            if (Files.isRegularFile(f)) {
                try (InputStream is = Files.newInputStream(f)) {
                    return PDType0Font.load(pdf, is);
                }
            }
        }
        log.warn("DejaVuSans.ttf не найден — используется Helvetica (кириллица не отобразится)");
        return org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA;
    }
}
