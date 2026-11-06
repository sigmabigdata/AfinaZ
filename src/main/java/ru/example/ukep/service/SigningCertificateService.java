package ru.example.ukep.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.repository.DocumentRepository;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Генерирует PDF «Справка о подписании» — печатный документ
 * для юристов/контрагентов/налоговой. Содержит:
 *  - реквизиты документа (название, размер, SHA-256)
 *  - таблицу подписей (ФИО, серийник, дата, доверенность)
 *  - QR-код на публичную верификацию
 *  - подпись-заверение сервиса
 */
@Service
public class SigningCertificateService {

    private static final Logger log = LoggerFactory.getLogger(SigningCertificateService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final DateTimeFormatter D  = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final float MARGIN = 50f;
    private static final float LINE = 14f;
    private static final float[] COLOR_BLUE = {0.0f, 0.20f, 0.60f};
    private static final float[] COLOR_GRAY = {0.4f, 0.4f, 0.4f};
    private static final float[] COLOR_BLACK = {0f, 0f, 0f};

    private final DocumentRepository documentRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    public SigningCertificateService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public byte[] generate(Long documentId) throws IOException {
        Document doc = documentRepository.findByIdWithSignatures(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));

        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDRectangle size = PDRectangle.A4;
            PDPage page = new PDPage(size);
            pdf.addPage(page);

            PDFont font = loadFont(pdf);
            PDFont bold = font;  // DejaVuSans — единственный, упрощаем

            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                float y = size.getHeight() - MARGIN;
                float left = MARGIN;
                float right = size.getWidth() - MARGIN;

                // ═══ Заголовок ═══
                y = drawHeader(cs, font, bold, left, right, y);

                // ═══ Реквизиты документа ═══
                y -= LINE;
                y = drawSection(cs, font, bold, "Документ", left, right, y);

                y = drawKV(cs, font, "Название:", safe(doc.getOriginalName()), left, right, y);
                y = drawKV(cs, font, "Размер:", formatSize(doc.getSize()), left, right, y);
                y = drawKV(cs, font, "Загружен:",
                        doc.getUploadedAt() != null
                                ? TS.format(doc.getUploadedAt().atZone(ZoneId.systemDefault()))
                                : "—",
                        left, right, y);
                y = drawKV(cs, font, "SHA-256:", safe(doc.getFileSha256()), left, right, y);

                // ═══ Подписи ═══
                y -= LINE;
                y = drawSection(cs, font, bold,
                        "Подписи (" + doc.getSignatures().size() + ")", left, right, y);

                if (doc.getSignatures().isEmpty()) {
                    y = drawKV(cs, font, "", "(документ не подписан)", left, right, y);
                } else {
                    int n = 0;
                    for (DocumentSignature s : doc.getSignatures()) {
                        n++;
                        y = drawSignatureBlock(cs, font, n, s, left, right, y);
                        if (y < MARGIN + 180) break;  // защита от переполнения
                    }
                }

                // ═══ QR-код публичной верификации ═══
                if (doc.getPublicUuid() != null && baseUrl != null && !baseUrl.isBlank()) {
                    y = Math.max(y - 30, MARGIN + 150);
                    drawQrBlock(cs, pdf, font, doc, left, right, y);
                }

                // ═══ Футер ═══
                drawFooter(cs, font, left, right);
            }

            pdf.save(out);
            return out.toByteArray();
        }
    }

    // ==================== Внутренние методы ====================

    private float drawHeader(PDPageContentStream cs, PDFont font, PDFont bold,
                              float left, float right, float y) throws IOException {
        // Синяя полоса
        cs.setNonStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
        cs.addRect(left, y - 4, right - left, 2);
        cs.fill();
        y -= 18;

        // Заголовок
        cs.setNonStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
        writeText(cs, bold, 18, "СПРАВКА О ПОДПИСАНИИ ДОКУМЕНТА", left, y);
        y -= 22;

        cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
        writeText(cs, font, 10, "ПАО «НБД-Банк» · Сервис электронной подписи", left, y);
        y -= 4;

        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
        return y;
    }

    private float drawSection(PDPageContentStream cs, PDFont font, PDFont bold,
                               String title, float left, float right, float y) throws IOException {
        cs.setNonStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
        writeText(cs, bold, 11, title, left, y);
        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
        y -= 4;
        // тонкая линия
        cs.setStrokingColor(0.85f, 0.88f, 0.92f);
        cs.setLineWidth(0.5f);
        cs.moveTo(left, y);
        cs.lineTo(right, y);
        cs.stroke();
        return y - LINE + 2;
    }

    private float drawKV(PDPageContentStream cs, PDFont font, String key, String value,
                          float left, float right, float y) throws IOException {
        // key
        cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
        writeText(cs, font, 9, key, left, y);
        // value
        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
        String v = truncate(value, 95);
        writeText(cs, font, 10, v, left + 80, y);
        return y - LINE;
    }

    private float drawSignatureBlock(PDPageContentStream cs, PDFont font, int n,
                                      DocumentSignature s, float left, float right,
                                      float y) throws IOException {
        // Фон
        cs.setNonStrokingColor(0.97f, 0.98f, 1.0f);
        cs.addRect(left, y - 52, right - left, 56);
        cs.fill();

        // Номер в круге
        cs.setNonStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
        cs.addRect(left + 4, y - 18, 20, 20);
        cs.fill();

        cs.setNonStrokingColor(1, 1, 1);
        writeText(cs, font, 11, String.valueOf(n), left + 11, y - 13);
        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);

        float x = left + 32;
        // Строка 1: Subject
        writeText(cs, font, 10, "Подписант: " + safe(s.getSignerSubject()), x, y - 4);
        // Строка 2: Serial + дата
        String serial = s.getSignerSerial() != null ? s.getSignerSerial() : "—";
        String when = s.getSignedAt() != null
                ? TS.format(s.getSignedAt().atZone(ZoneId.systemDefault()))
                : "—";
        writeText(cs, font, 9, "Сертификат: " + serial, x, y - 18);
        writeText(cs, font, 9, "Подписан: " + when, x, y - 30);
        // Строка 3: Доверенность
        if (s.isApplyPoa()) {
            writeText(cs, font, 9, "Доверенность: применена", x, y - 42);
        } else {
            writeText(cs, font, 9, "Доверенность: не требуется", x, y - 42);
        }

        return y - 64;
    }

    private void drawQrBlock(PDPageContentStream cs, PDDocument pdf, PDFont font,
                              Document doc, float left, float right, float y) throws IOException {
        String url = baseUrl.replaceAll("/+$", "") + "/info/" + doc.getPublicUuid();
        BufferedImage qr = generateQr(url, 200);
        if (qr == null) return;

        PDImageXObject img = LosslessFactory.createFromImage(pdf, qr);
        float qrSize = 80f;
        float qrX = right - qrSize;

        cs.drawImage(img, qrX, y - qrSize, qrSize, qrSize);

        // Подпись рядом
        cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
        writeText(cs, font, 9, "Проверить подпись онлайн:", left, y - 20);
        writeText(cs, font, 8, url, left, y - 34);
        writeText(cs, font, 8, "Наведите камеру телефона на QR-код",
                left, y - 48);

        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
    }

    private void drawFooter(PDPageContentStream cs, PDFont font,
                             float left, float right) throws IOException {
        cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
        writeText(cs, font, 8, "Справка сгенерирована автоматически "
                + TS.format(java.time.LocalDateTime.now()), left, MARGIN + 20);
        writeText(cs, font, 8, "Юридическая сила подтверждается "
                + "электронной подписью и публичной верификацией по QR-коду.",
                left, MARGIN + 8);
        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
    }

    private static void writeText(PDPageContentStream cs, PDFont font, float size,
                                    String text, float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text == null ? "" : text);
        cs.endText();
    }

    private BufferedImage generateQr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < size; x++)
                for (int y = 0; y < size; y++)
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
            return img;
        } catch (Exception e) {
            log.warn("QR generation failed: {}", e.getMessage());
            return null;
        }
    }

    /** Все известные пути к TTF-шрифтам с поддержкой кириллицы. */
    private static final String[] FONT_PATHS = {
        // Linux / Debian / Astra
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
        // macOS
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
        "/Library/Fonts/Arial Unicode.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
        // Windows
        "C:\\Windows\\Fonts\\arial.ttf",
        "C:\\Windows\\Fonts\\Arial.ttf",
    };

    /** Возвращает первый существующий шрифт или null. */
    public static Path findCyrillicFont() {
        for (String p : FONT_PATHS) {
            Path f = Paths.get(p);
            if (Files.isRegularFile(f)) return f;
        }
        return null;
    }

    private PDFont loadFont(PDDocument pdf) throws IOException {
        Path f = findCyrillicFont();
        if (f != null) {
            try (InputStream is = Files.newInputStream(f)) {
                return PDType0Font.load(pdf, is);
            }
        }
        log.warn("TTF с кириллицей не найден — используется Helvetica (кириллица не отобразится)");
        return org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA;
    }

    private static String safe(String s) { return s == null ? "—" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.2f MB", bytes / 1024.0 / 1024);
    }
}
