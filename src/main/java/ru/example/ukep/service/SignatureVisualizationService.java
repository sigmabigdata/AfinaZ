package ru.example.ukep.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Визуализация подписи — синий штамп в нижнем колонтитуле каждой страницы PDF.
 *
 * <p>Стиль как в Контур.Диадок: тёмно-синий текст + синяя рамка на всю ширину.</p>
 *
 * <p>Блок одной подписи:
 * <pre>
 *   Документ подписан электронной подписью
 *   Дата подписания: 20.10.2026 09:47:11
 *   Сведения о сертификате электронной подписи
 *   Серийный номер: 18EA034CC60693A14C4F3B1E019F1834
 *   Срок действия: с 30.06.2026 по 30.06.2027
 *   Владелец:
 *     Голендеева Светлана Дмитриевна
 *   Доверенность № GUID от ... действ. до ... | Доверенность не требуется
 * </pre>
 */
@Service
public class SignatureVisualizationService {

    private static final Logger log = LoggerFactory.getLogger(SignatureVisualizationService.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final DateTimeFormatter D_FMT  = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // Стиль штампа (как в Контур.Диадок)
    private static final float FONT_SIZE = 7f;
    private static final float LINE_HEIGHT = 9f;
    private static final float MARGIN = 20f;
    private static final float BOX_MARGIN_X = 5f;   // рамка от края страницы
    private static final float BOX_PADDING = 4f;    // зазор между рамкой и текстом

    // Цвета (RGB 0..1): #003399 — тёмно-синий, как в Диадоке
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

    // ============ ядро: наложение штампов ============

    private record CertInfo(String serialHex, Instant notBefore, Instant notAfter, String ownerFio) {}

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
                float stampHeight = calculateStampHeight(sigs.size(), document.isApplyPoa());
                float boxTopY = MARGIN + stampHeight + BOX_PADDING;
                float boxBotY = MARGIN - BOX_PADDING;

                try (PDPageContentStream cs = new PDPageContentStream(
                        pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                    // 1. Синяя рамка на всю ширину страницы (с небольшим отступом от края)
                    cs.setStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
                    cs.setLineWidth(0.8f);
                    cs.addRect(BOX_MARGIN_X, boxBotY,
                            pageWidth - 2 * BOX_MARGIN_X, boxTopY - boxBotY);
                    cs.stroke();

                    // 2. Текст штампа — синий
                    cs.setNonStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);

                    float y = boxTopY - LINE_HEIGHT;
                    boolean first = true;
                    for (var sig : sigs) {
                        if (!first) {
                            // Тонкий разделитель между подписями
                            cs.setStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
                            cs.setLineWidth(0.3f);
                            cs.moveTo(MARGIN + 4f, y + LINE_HEIGHT / 2f);
                            cs.lineTo(pageWidth - MARGIN - 4f, y + LINE_HEIGHT / 2f);
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

                    // 3. Восстановить чёрный (на всякий случай, для дальнейших надписей)
                    cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
                }
            }

            pdf.save(out);
            return out.toByteArray();
        }
    }

    private float calculateStampHeight(int sigCount, boolean applyPoa) {
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

        String signedAt = sig.getSignedAt() != null
                ? TS_FMT.format(sig.getSignedAt().atZone(ZoneId.systemDefault()))
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

        drawLine(cs, font, nvl(certInfo.ownerFio()), x + 10f, y);
        y -= LINE_HEIGHT;

        if (applyPoa) {
            String poaLine;
            if (poa != null) {
                poaLine = "Доверенность № " + nvl(poa.getStampNumber())
                        + " от " + (poa.getDocDate() != null ? D_FMT.format(poa.getDocDate()) : "—")
                        + ", действ. до " + (poa.getValidUntil() != null ? D_FMT.format(poa.getValidUntil()) : "—");
            } else {
                poaLine = "Доверенность не требуется";
            }
            drawLine(cs, font, poaLine, x, y);
            y -= LINE_HEIGHT;
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

            JcaX509CertificateConverter conv = new JcaX509CertificateConverter().setProvider("BC");
            for (var holder : cms.getCertificates().getMatches(null)) {
                X509Certificate cert;
                try {
                    cert = conv.getCertificate(holder);
                } catch (Exception ex) {
                    continue;
                }
                if (cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) continue;
                return new CertInfo(
                        cert.getSerialNumber().toString(16).toUpperCase(),
                        cert.getNotBefore().toInstant(),
                        cert.getNotAfter().toInstant(),
                        extractCn(cert.getSubjectX500Principal().getName()));
            }
        } catch (Exception e) {
            log.debug("extractCertInfo failed for sig {}: {}", sig.getId(), e.getMessage());
        }
        return new CertInfo(
                sig.getSignerSerial() != null ? sig.getSignerSerial().toUpperCase() : "—",
                null, null,
                extractCn(sig.getSignerSubject()));
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
