package ru.example.ukep.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Генерирует PDF-отчёт о проверке подписи.
 * Содержит: заголовок, дату, результат, чек-лист, информацию о подписанте, цепочку.
 */
@Service
public class VerificationReportPdfService {

    private static final Logger log = LoggerFactory.getLogger(VerificationReportPdfService.class);
    private static final float MARGIN = 40f;
    private static final float LINE = 14f;
    private static final float[] COLOR_BLUE = {0.0f, 0.20f, 0.60f};
    private static final float[] COLOR_OK = {0.09f, 0.50f, 0.22f};
    private static final float[] COLOR_ERR = {0.66f, 0.14f, 0.14f};
    private static final float[] COLOR_GRAY = {0.4f, 0.4f, 0.4f};
    private static final float[] COLOR_BLACK = {0f, 0f, 0f};

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter D = DateTimeFormatter
            .ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault());

    public byte[] generate(String documentName, long documentSize,
                            String documentSha256,
                            Map<String, Object> result) throws IOException {
        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDRectangle size = PDRectangle.A4;
            PDPage page = new PDPage(size);
            pdf.addPage(page);

            PDFont font = loadFont(pdf);

            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                float left = MARGIN;
                float right = size.getWidth() - MARGIN;
                float y = size.getHeight() - MARGIN;

                // ─── Заголовок ───
                cs.setNonStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
                cs.addRect(left, y - 4, right - left, 2);
                cs.fill();
                y -= 22;

                writeText(cs, font, 16, "ОТЧЁТ О ПРОВЕРКЕ ЭЛЕКТРОННОЙ ПОДПИСИ", left, y);
                y -= 18;
                cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
                writeText(cs, font, 9, "ПАО «НБД-Банк» · Сервис проверки УКЭП", left, y);
                writeText(cs, font, 9,
                        "Сформирован: " + TS.format(java.time.Instant.now()),
                        left, y - 12);
                y -= 30;

                // ─── Результат ───
                boolean valid = Boolean.TRUE.equals(result.get("valid"));
                float[] color = valid ? COLOR_OK : COLOR_ERR;
                cs.setNonStrokingColor(color[0], color[1], color[2]);
                cs.addRect(left, y - 44, right - left, 44);
                cs.fill();

                cs.setNonStrokingColor(1, 1, 1);
                writeText(cs, font, 13,
                        valid ? "✓  ПОДПИСЬ ДЕЙСТВИТЕЛЬНА"
                              : "✗  ПОДПИСЬ НЕДЕЙСТВИТЕЛЬНА",
                        left + 14, y - 18);
                writeText(cs, font, 9,
                        valid ? "Все проверки пройдены успешно"
                              : "Обнаружены проблемы — см. список ниже",
                        left + 14, y - 34);
                y -= 60;
                cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);

                // ─── Документ ───
                y = section(cs, font, "Документ", left, right, y);
                y = kv(cs, font, "Название", safe(documentName), left, right, y);
                y = kv(cs, font, "Размер", formatSize(documentSize), left, right, y);
                if (documentSha256 != null && !documentSha256.isBlank()) {
                    y = kv(cs, font, "SHA-256", safe(documentSha256), left, right, y);
                }
                y -= 8;

                // ─── Проверки ───
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
                int total = checks == null ? 0 : checks.size();
                int passed = 0;
                if (checks != null) {
                    for (Map<String, Object> chk : checks) {
                        if (Boolean.TRUE.equals(chk.get("passed"))) passed++;
                    }
                }
                y = section(cs, font,
                        "Проверки (" + passed + " из " + total + " пройдено)",
                        left, right, y);

                if (checks != null) {
                    for (Map<String, Object> chk : checks) {
                        if (y < MARGIN + 100) break; // защита от переполнения
                        boolean ok = Boolean.TRUE.equals(chk.get("passed"));
                        float[] c2 = ok ? COLOR_OK : COLOR_ERR;
                        cs.setNonStrokingColor(c2[0], c2[1], c2[2]);
                        writeText(cs, font, 10, ok ? "✓" : "✗", left, y);
                        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
                        writeText(cs, font, 9, safe((String) chk.get("name")), left + 18, y);
                        String det = safe((String) chk.get("details"));
                        if (!det.isEmpty()) {
                            cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
                            writeText(cs, font, 8, truncate(det, 85), left + 18, y - 11);
                            cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
                            y -= 24;
                        } else {
                            y -= 14;
                        }
                    }
                }
                y -= 6;

                // ─── Подписант ───
                y = section(cs, font, "Подписант", left, right, y);
                y = kv(cs, font, "ФИО / CN", safe((String) result.get("signerSubject")), left, right, y);
                y = kv(cs, font, "Издатель (УЦ)", safe((String) result.get("signerIssuer")), left, right, y);
                y = kv(cs, font, "Серийный номер", safe((String) result.get("signerSerial")), left, right, y);
                String nb = formatIso((String) result.get("signerNotBefore"));
                String na = formatIso((String) result.get("signerNotAfter"));
                y = kv(cs, font, "Срок действия", nb + " — " + na, left, right, y);

                // ─── Футер ───
                cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
                writeText(cs, font, 7,
                        "Отчёт сформирован автоматически сервисом проверки УКЭП ПАО «НБД-Банк».",
                        left, MARGIN + 16);
                writeText(cs, font, 7,
                        "Документ не является юридическим заключением — проверка проведена по "
                        + "техническим критериям (цепочка, truststore, CRL, EKU).",
                        left, MARGIN + 6);
            }

            pdf.save(out);
            return out.toByteArray();
        }
    }

    private float section(PDPageContentStream cs, PDFont font, String title,
                           float left, float right, float y) throws IOException {
        cs.setNonStrokingColor(COLOR_BLUE[0], COLOR_BLUE[1], COLOR_BLUE[2]);
        writeText(cs, font, 11, title, left, y);
        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
        y -= 4;
        cs.setStrokingColor(0.85f, 0.88f, 0.92f);
        cs.setLineWidth(0.5f);
        cs.moveTo(left, y);
        cs.lineTo(right, y);
        cs.stroke();
        return y - LINE + 2;
    }

    private float kv(PDPageContentStream cs, PDFont font, String key, String value,
                      float left, float right, float y) throws IOException {
        cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
        writeText(cs, font, 9, key + ":", left, y);
        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
        writeText(cs, font, 9, truncate(value, 95), left + 100, y);
        return y - LINE;
    }

    private static void writeText(PDPageContentStream cs, PDFont font, float size,
                                    String text, float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text == null ? "" : text);
        cs.endText();
    }

    private PDFont loadFont(PDDocument pdf) throws IOException {
        // Используем тот же поиск, что в SigningCertificateService
        Path f = SigningCertificateService.findCyrillicFont();
        if (f != null) {
            try (InputStream is = Files.newInputStream(f)) {
                return PDType0Font.load(pdf, is);
            }
        }
        log.warn("Кириллический шрифт не найден — отчёт будет с латиницей");
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

    private static String formatIso(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            return D.format(java.time.Instant.parse(iso));
        } catch (Exception e) { return iso; }
    }
}
