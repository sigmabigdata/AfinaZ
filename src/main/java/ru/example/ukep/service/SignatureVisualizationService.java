package ru.example.ukep.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    private static final float FONT_SIZE = 8f;
    private static final float LINE_HEIGHT = 10f;
    private static final float MARGIN = 20f;
    private static final float STAMP_HEIGHT = 40f;  // на 4 строки

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

    private byte[] stampPdf(byte[] original, Document document) throws IOException {
        List<DocumentSignature> sigs = document.getSignatures();

        // Реквизиты доверенности (если включено)
        PowerOfAttorney poa = null;
        if (document.isApplyPoa() && !sigs.isEmpty()) {
            String firstSigner = sigs.get(0).getSignerSubject();
            poa = poaService.findEffectiveByFio(extractCn(firstSigner)).orElse(null);
        }

        try (PDDocument pdf = PDDocument.load(original);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Загрузить шрифт DejaVu (кириллица)
            PDFont font = loadFont(pdf);

            for (int pageNum = 0; pageNum < pdf.getNumberOfPages(); pageNum++) {
                PDPage page = pdf.getPage(pageNum);
                float pageWidth = page.getMediaBox().getWidth();

                try (PDPageContentStream cs = new PDPageContentStream(
                        pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                    // Линия-разделитель над штампом
                    cs.setLineWidth(0.5f);
                    cs.moveTo(MARGIN, MARGIN + STAMP_HEIGHT);
                    cs.lineTo(pageWidth - MARGIN, MARGIN + STAMP_HEIGHT);
                    cs.stroke();

                    float y = MARGIN + STAMP_HEIGHT - LINE_HEIGHT;

                    // Строка 1: подписанты
                    for (DocumentSignature sig : sigs) {
                        String line1 = "Подписал: " + (sig.getSignerSubject() != null
                                ? extractCn(sig.getSignerSubject()) : "—");
                        drawLine(cs, font, line1, MARGIN, y);
                        y -= LINE_HEIGHT;

                        // Строка 2: доверенность (если применимо)
                        if (poa != null && poa.getFio() != null
                                && poa.getFio().equalsIgnoreCase(extractCn(sig.getSignerSubject()))) {
                            String line2 = "Доверенность № " + nvl(poa.getStampNumber())
                                    + " от " + (poa.getDocDate() != null ? poa.getDocDate().format(D_FMT) : "—")
                                    + ", действ. до " + (poa.getValidUntil() != null ? poa.getValidUntil().format(D_FMT) : "—");
                            drawLine(cs, font, line2, MARGIN, y);
                            y -= LINE_HEIGHT;
                        }

                        // Строка 3: время подписания
                        String line3 = "Дата подписания: " + (sig.getSignedAt() != null
                                ? sig.getSignedAt().atZone(java.time.ZoneId.systemDefault()).format(TS_FMT) : "—");
                        drawLine(cs, font, line3, MARGIN, y);
                        y -= LINE_HEIGHT;

                        if (y < MARGIN) break;  // место кончилось — не выходим за поля
                    }
                }
            }

            pdf.save(out);
            return out.toByteArray();
        }
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
