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
    private static final float LINE = 18f;
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
                writeText(cs, font, 14, "ОТЧЁТ О ПРОВЕРКЕ КВАЛИФИЦИРОВАННОЙ", left, y);
                y -= 18;
                writeText(cs, font, 14, "ЭЛЕКТРОННОЙ ПОДПИСИ", left, y);
                y -= 22;
                cs.setStrokingColor(0.85f, 0.88f, 0.92f);
                cs.setLineWidth(1f);
                cs.moveTo(left, y);
                cs.lineTo(right, y);
                cs.stroke();
                y -= 20;

                // ─── Статус ───
                boolean valid = Boolean.TRUE.equals(result.get("valid"));
                float[] color = valid ? COLOR_OK : COLOR_ERR;
                cs.setNonStrokingColor(color[0], color[1], color[2]);
                cs.addRect(left, y - 56, right - left, 56);
                cs.fill();

                cs.setNonStrokingColor(1, 1, 1);
                writeText(cs, font, 10, "ПОДПИСЬ", left + 14, y - 18);
                writeText(cs, font, 18,
                        valid ? "ДЕЙСТВИТЕЛЬНА" : "НЕДЕЙСТВИТЕЛЬНА",
                        left + 14, y - 40);
                y -= 72;
                cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);

                // ─── Подпись 1 ───
                writeText(cs, font, 11, "Подпись 1", left, y);
                y -= 4;
                cs.setStrokingColor(0.85f, 0.88f, 0.92f);
                cs.moveTo(left, y);
                cs.lineTo(right, y);
                cs.stroke();
                y -= 16;

                // Статус ЭП
                y = kv(cs, font, "Статус ЭП", valid ? "Электронная подпись верна" : "Неверна", left, right, y);

                // Статус сертификата
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
                boolean certValid = false;
                if (checks != null) {
                    for (Map<String, Object> chk : checks) {
                        String n = (String) chk.get("name");
                        if (n != null && n.contains("Срок действия сертификата")) {
                            certValid = Boolean.TRUE.equals(chk.get("passed"));
                        }
                    }
                }
                y = kv(cs, font, "Статус сертификата ЭП", certValid ? "ДЕЙСТВИТЕЛЕН" : "НЕДЕЙСТВИТЕЛЕН",
                        left, right, y);

                // Владелец
                y = kv(cs, font, "Владелец сертификата",
                        truncate(safe((String) result.getOrDefault("signerFullSubject", "—")), 90),
                        left, right, y);

                // Издатель
                y = kv(cs, font, "Издатель сертификата",
                        truncate(safe((String) result.getOrDefault("signerIssuerFull", "—")), 90),
                        left, right, y);

                // Серийный номер
                y = kv(cs, font, "Серийный номер",
                        safe((String) result.getOrDefault("signerSerial", "—")), left, right, y);

                // Отпечаток
                y = kv(cs, font, "Отпечаток",
                        safe((String) result.getOrDefault("signerThumbprint", "—")), left, right, y);

                // Действует
                String nb = formatIso((String) result.get("signerNotBefore"));
                String na = formatIso((String) result.get("signerNotAfter"));
                y = kv(cs, font, "Действует", "с " + nb + " по " + na, left, right, y);

                // Дата проверки
                y = kv(cs, font, "Дата проверки",
                        TS.format(java.time.Instant.now()), left, right, y);

                // SHA-256 документа
                if (documentSha256 != null && !documentSha256.isBlank()) {
                    y = kv(cs, font, "SHA-256 содержимого",
                            truncate(documentSha256, 80), left, right, y);
                }

                y -= 10;

                // ─── Все проверки ───
                int total = checks == null ? 0 : checks.size();
                int passed = 0;
                if (checks != null) {
                    for (Map<String, Object> chk : checks) {
                        if (Boolean.TRUE.equals(chk.get("passed"))) passed++;
                    }
                }
                y = section(cs, font, "Проверки (" + passed + " из " + total + ")",
                        left, right, y);

                if (checks != null) {
                    for (Map<String, Object> chk : checks) {
                        if (y < MARGIN + 60) break;
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

                // ─── Футер ───
                cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
                writeText(cs, font, 7,
                        "Отчёт сформирован автоматически сервисом проверки УКЭП ПАО «НБД-Банк».",
                        left, MARGIN + 16);
                writeText(cs, font, 7,
                        "Проверка выполнена по техническим критериям: целостность, "
                        + "криптоподпись, цепочка, truststore, CRL из CDP, EKU.",
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
        float fontSize = 9f;
        String keyText = key + ":";
        // Измеряем ширину ключа в пикселях
        float keyWidth = 0f;
        try {
            keyWidth = font.getStringWidth(keyText) / 1000f * fontSize;
        } catch (Exception ignored) {
            keyWidth = 120f;  // fallback
        }
        keyWidth += 10f;  // зазор между ключом и значением

        // Ключ
        cs.setNonStrokingColor(COLOR_GRAY[0], COLOR_GRAY[1], COLOR_GRAY[2]);
        writeText(cs, font, fontSize, keyText, left, y);

        // Значение — обрезаем по фактической ширине
        cs.setNonStrokingColor(COLOR_BLACK[0], COLOR_BLACK[1], COLOR_BLACK[2]);
        float availWidth = right - (left + keyWidth);
        String safeValue = fitText(font, value == null ? "—" : value, fontSize, availWidth);
        writeText(cs, font, fontSize, safeValue, left + keyWidth, y);

        return y - LINE;
    }

    /**
     * Обрезает текст по фактической ширине в пикселях (с многоточием).
     */
    private static String fitText(PDFont font, String text, float fontSize, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        try {
            float fullWidth = font.getStringWidth(text) / 1000f * fontSize;
            if (fullWidth <= maxWidth) return text;

            String ellipsis = "...";
            float ellipsisWidth = font.getStringWidth(ellipsis) / 1000f * fontSize;

            String trimmed = text;
            int safety = 0;
            while (trimmed.length() > 0 && safety < 1000) {
                safety++;
                trimmed = trimmed.substring(0, trimmed.length() - 1);
                float w = font.getStringWidth(trimmed) / 1000f * fontSize + ellipsisWidth;
                if (w <= maxWidth) return trimmed + ellipsis;
            }
            return ellipsis;
        } catch (Exception e) {
            return text.length() > 60 ? text.substring(0, 57) + "..." : text;
        }
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
