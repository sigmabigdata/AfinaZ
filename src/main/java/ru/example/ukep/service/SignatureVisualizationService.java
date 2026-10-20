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
 * Наложение штампов подписантов в нижний колонтитул каждой страницы PDF.
 *
 * <p>Штамп — 3 строки мелким шрифтом (8pt):
 * <ul>
 *   <li>ФИО и роль подписанта</li>
 *   <li>Номер доверенности (GUID) + срок действия</li>
 *   <li>Дата и время подписания</li>
 * </ul>
 *
 * <p>Если у документа стоит {@code apply_poa=true} и есть действующая доверенность
 * по ФИО первого подписанта — добавляется 4-я строка с реквизитами ЭМЧД.
 */
@Service
public class SignatureVisualizationService {

    private static final Logger log = LoggerFactory.getLogger(SignatureVisualizationService.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final DateTimeFormatter D_FMT  = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final float FONT_SIZE = 7f;
    private static final float LINE_HEIGHT = 9f;
    private static final float MARGIN = 20f;

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

    /**
     * Сгенерировать (или пересоздать) визуализацию.
     * Возвращает обновлённый {@link DocumentVisualization}.
     */
    @Transactional
    public DocumentVisualization generate(Document document) throws IOException {
        // Перезагружаем документ в текущей транзакции с eager-загрузкой signatures,
        // иначе на detached-сущности из контроллера будет LazyInitializationException.
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

        // Проверить, что PDF валиден
        try (PDDocument check = PDDocument.load(original)) {
            if (check.getNumberOfPages() == 0) {
                throw new IllegalStateException("PDF не содержит страниц");
            }
        }

        byte[] withStamps = stampPdf(original, doc);

        // Сохранить
        String storedName = "vis_" + doc.getId() + "_" + System.currentTimeMillis() + ".pdf";
        Path target = storageRoot.resolve("visualizations").resolve(storedName);
        Files.write(target, withStamps,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        // Upsert записи
        DocumentVisualization vis = visRepo.findByDocument(doc).orElseGet(() -> {
            DocumentVisualization v = new DocumentVisualization();
            v.setDocument(doc);
            return v;
        });
        // Удалить старый файл, если был
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

    /** Загрузить готовый PDF визуализации. */
    public byte[] loadBytes(DocumentVisualization vis) throws IOException {
        Path p = storageRoot.resolve("visualizations").resolve(vis.getStoredName());
        if (!Files.isRegularFile(p)) {
            throw new IOException("Файл визуализации не найден: " + vis.getStoredName());
        }
        return Files.readAllBytes(p);
    }

    /** Есть ли готовая визуализация? */
    public Optional<DocumentVisualization> findFor(Document document) {
        return visRepo.findByDocument(document);
    }

    // ============ ядро: наложение штампов ============

    /** Метаданные сертификата подписанта, извлекаемые из CMS-подписи. */
    private record CertInfo(String serialHex, Instant notBefore, Instant notAfter, String ownerFio) {}

    private byte[] stampPdf(byte[] original, Document document) throws IOException {
        // Порядок: свежие подписи сверху
        List<DocumentSignature> sigs = new ArrayList<>(document.getSignatures());
        sigs.sort((a, b) -> {
            if (a.getSignedAt() == null && b.getSignedAt() == null) return 0;
            if (a.getSignedAt() == null) return 1;
            if (b.getSignedAt() == null) return -1;
            return b.getSignedAt().compareTo(a.getSignedAt());
        });

        // Предварительно извлечь cert info и (если включено) доверенности
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
                float yTop = MARGIN + stampHeight;

                try (PDPageContentStream cs = new PDPageContentStream(
                        pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                    // Верхняя линия штампа
                    cs.setLineWidth(0.5f);
                    cs.moveTo(MARGIN, yTop);
                    cs.lineTo(pageWidth - MARGIN, yTop);
                    cs.stroke();

                    float y = yTop - LINE_HEIGHT;
                    boolean first = true;
                    for (var sig : sigs) {
                        if (!first) {
                            // Разделитель между подписями
                            cs.setLineWidth(0.3f);
                            cs.moveTo(MARGIN, y + LINE_HEIGHT / 2f);
                            cs.lineTo(pageWidth - MARGIN, y + LINE_HEIGHT / 2f);
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
                }
            }

            pdf.save(out);
            return out.toByteArray();
        }
    }

    /** Высота штампа: 7-8 строк на подпись + разделители + отступы. */
    private float calculateStampHeight(int sigCount, boolean applyPoa) {
        float linesPerBlock = applyPoa ? 8f : 7f;
        float between = (sigCount > 1) ? (sigCount - 1) * (LINE_HEIGHT / 2f) : 0f;
        return sigCount * linesPerBlock * LINE_HEIGHT + between + LINE_HEIGHT;
    }

    /** Рисует блок одной подписи, возвращает y-координату после блока. */
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

    /**
     * Извлекает из CAdES-BES подписи сертификат подписанта (leaf, не self-signed)
     * и возвращает его serial / notBefore / notAfter / CN.
     * Fallback: signerSerial и signerSubject из БД.
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

            JcaX509CertificateConverter conv = new JcaX509CertificateConverter().setProvider("BC");
            for (var holder : cms.getCertificates().getMatches(null)) {
                X509Certificate cert;
                try {
                    cert = conv.getCertificate(holder);
                } catch (Exception ex) {
                    continue;
                }
                // Leaf = не self-signed
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
        // Fallback
        return new CertInfo(
                sig.getSignerSerial() != null ? sig.getSignerSerial().toUpperCase() : "—",
                null, null,
                extractCn(sig.getSignerSubject()));
    }

    private static void drawLine(PDPageContentStream cs, PDFont font,
                                 String text, float x, float y) throws IOException {
        if (text == null || text.isBlank()) return;
        // Обрезка длинных строк по ширине листа
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

    private static String nvl(String s) { return s != null ? s : "—"; }

    /** Извлечь CN из X.500 DN. */
    private static String extractCn(String subject) {
        if (subject == null) return "—";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(subject);
        return m.find() ? m.group(1).replace("\"", "").trim() : subject;
    }

    private PDFont loadFont(PDDocument pdf) throws IOException {
        // Попробовать DejaVu из системы, потом — fallback на Helvetica (без кириллицы)
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
        // PDFBox 2.0.x: готовый статический экземпляр PDType1Font.HELVETICA (без конструктора)
        return org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA;
    }
}
