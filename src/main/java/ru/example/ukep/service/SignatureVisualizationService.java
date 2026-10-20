package ru.example.ukep.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
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
    private static final float FONT_SIZE = 4.5f;
    private static final float LINE_HEIGHT = 4.5f;
    private static final float MARGIN = 6f;          // текст от края страницы
    private static final float BOX_MARGIN_X = 3f;    // рамка от края страницы
    private static final float BOX_PADDING = 2f;     // зазор между рамкой и текстом

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
                            String ownerFio, Instant signingTime) {}

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
            if (document.isApplyPoa()) {
                poaService.findEffectiveByFio(info.ownerFio())
                        .ifPresent(poa -> poaMap.put(sig.getId(), poa));
            }
        }

        try (PDDocument pdf = PDDocument.load(original);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDFont font = loadFont(pdf);

            for (int pageNum = 0; pageNum < pdf.getNumberOfPages(); pageNum++) {
                PDPage page = pdf.getPage(pageNum);
                float pageWidth = page.getMediaBox().getWidth();
                float stampHeight = calculateStampHeight(sigs.size(), document.isApplyPoa(), poaMap.size());
                float boxTopY = MARGIN + stampHeight + BOX_PADDING;
                float boxBotY = MARGIN - BOX_PADDING;

                try (PDPageContentStream cs = new PDPageContentStream(
                        pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                    // Синяя рамка
                    cs.setStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
                    cs.setLineWidth(0.6f);
                    cs.addRect(BOX_MARGIN_X, boxBotY,
                            pageWidth - 2 * BOX_MARGIN_X, boxTopY - boxBotY);
                    cs.stroke();

                    // Синий текст
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
                                document.isApplyPoa(), MARGIN, y);
                        y -= LINE_HEIGHT / 2f;
                    }

                    cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
                }
            }

            pdf.save(out);
            return out.toByteArray();
        }
    }

    private float calculateStampHeight(int sigCount, boolean applyPoa, int poaCount) {
        // По умолчанию: 7 строк. Если apply_poa=true → 8 строк (при наличии POA) или 7 (строка "не требуется")
        float linesPerBlock = applyPoa ? 8f : 7f;
        float between = (sigCount > 1) ? (sigCount - 1) * (LINE_HEIGHT / 2f) : 0f;
        return sigCount * linesPerBlock * LINE_HEIGHT + between + LINE_HEIGHT;
    }

    private float drawSignatureBlock(PDPageContentStream cs, PDFont font,
                                      DocumentSignature sig, CertInfo certInfo,
                                      PowerOfAttorney poa, boolean applyPoa,
                                      float x, float y) throws IOException {
        drawLine(cs, font, "Документ подписан электронной подписью", x, y);
        y -= LINE_HEIGHT;

        // Дата подписания: из CMS signingTime, fallback — из БД
        Instant signTime = certInfo.signingTime() != null
                ? certInfo.signingTime()
                : sig.getSignedAt();
        String signedAt = signTime != null
                ? TS_FMT.format(signTime.atZone(ZoneId.systemDefault()))
                : "—";
        drawLine(cs, font, "Дата подписания: " + signedAt, x, y);
        y -= LINE_HEIGHT;

        drawLine(cs, font, "Сведения о сертификате электронной подписи", x, y);
        y -= LINE_HEIGHT;

        drawLine(cs, font, "Серийный номер: " + nvl(certInfo.serialHex()), x, y);
        y -= LINE_HEIGHT;

        String validity = "—";
        if (certInfo.notBefore() != null && certInfo.notAfter() != null) {
            validity = "с " + D_FMT.format(certInfo.notBefore().atZone(ZoneId.systemDefault()))
                     + " по " + D_FMT.format(certInfo.notAfter().atZone(ZoneId.systemDefault()));
        }
        drawLine(cs, font, "Срок действия: " + validity, x, y);
        y -= LINE_HEIGHT;

        drawLine(cs, font, "Владелец:", x, y);
        y -= LINE_HEIGHT;

        drawLine(cs, font, nvl(certInfo.ownerFio()), x + 6f, y);
        y -= LINE_HEIGHT;

        if (applyPoa) {
            if (poa != null) {
                drawLine(cs, font, "Доверенность №" + nvl(poa.getStampNumber()), x, y);
                y -= LINE_HEIGHT;
                String poaValidity = "—";
                if (poa.getValidFrom() != null && poa.getValidUntil() != null) {
                    poaValidity = "с " + D_FMT.format(poa.getValidFrom())
                                + " по " + D_FMT.format(poa.getValidUntil());
                } else if (poa.getValidUntil() != null) {
                    poaValidity = "по " + D_FMT.format(poa.getValidUntil());
                }
                drawLine(cs, font, "Срок действия " + poaValidity, x, y);
                y -= LINE_HEIGHT;
            } else {
                drawLine(cs, font, "Доверенность не требуется", x, y);
                y -= LINE_HEIGHT;
            }
        }

        return y;
    }

    private static void drawLine(PDPageContentStream cs, PDFont font,
                                 String text, float x, float y) throws IOException {
        if (text == null || text.isBlank()) return;
        String safe = truncate(text, 140);
        cs.beginText();
        cs.setFont(font, FONT_SIZE);
        cs.newLineAtOffset(x, y);
        cs.showText(safe);
        cs.endText();
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private static String nvl(String s) { return s != null && !s.isBlank() ? s : "—"; }

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

            return new CertInfo(
                    cert.getSerialNumber().toString(16).toUpperCase(),
                    cert.getNotBefore().toInstant(),
                    cert.getNotAfter().toInstant(),
                    extractCn(cert.getSubjectX500Principal().getName()),
                    signingTime);

        } catch (Exception e) {
            log.debug("extractCertInfo failed for sig {}: {}", sig.getId(), e.getMessage());
        }

        // Fallback на данные из БД
        return new CertInfo(
                sig.getSignerSerial() != null ? sig.getSignerSerial().toUpperCase() : "—",
                null, null,
                extractCn(sig.getSignerSubject()),
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
