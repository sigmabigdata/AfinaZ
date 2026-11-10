package ru.example.ukep.util;

import org.springframework.http.ContentDisposition;

import java.nio.charset.StandardCharsets;

/**
 * Хелпер для Content-Disposition с корректной кодировкой Unicode.
 *
 * В отличие от URLEncoder.encode (который заменяет пробелы на +, а + в RFC 5987
 * не декодируется в пробел), Spring ContentDisposition корректно генерирует
 * filename*=UTF-8''... с %20 для пробелов и fallback filename="...".
 */
public final class FileNames {

    private FileNames() {}

    /** attachment — «Скачать», браузер сохраняет файл. */
    public static String attachment(String filename) {
        return ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    /** inline — «Открыть в браузере». */
    public static String inline(String filename) {
        return ContentDisposition.inline()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    /**
     * Нормализует имя файла для использования внутри ZIP-архива
     * и скачивания на любую ОС (Windows, macOS, Linux).
     *
     * <p>Разрешает кириллицу и другие буквы Unicode.
     * Запрещает только реально опасные символы для файловых систем:
     * {@code \ / : * ? " < > |} и control-символы.
     * Убирает leading/trailing точки и пробелы.
     * Обрезает длинные имена (макс. 200 символов, включая расширение).
     * Экранирует Windows reserved names (CON, PRN, AUX, NUL, COM1..9, LPT1..9).</p>
     *
     * <p>Примеры:</p>
     * <pre>
     *   "Заявление.pdf"            → "Заявление.pdf"
     *   "Отчёт (2026).pdf"         → "Отчёт (2026).pdf"
     *   "file/with\slashes.pdf"    → "file_with_slashes.pdf"
     *   "  .hidden  "              → "hidden"
     *   "CON"                      → "_CON"
     * </pre>
     */
    public static String sanitizeZipEntry(String name) {
        if (name == null || name.isBlank()) return "file";

        // 1) trim + запрещённые символы → "_"
        String cleaned = name.trim()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");

        // 2) убрать leading/trailing точки и пробелы (Windows не любит)
        cleaned = cleaned.replaceAll("^[\\s.]+|[\\s.]+$", "");
        if (cleaned.isEmpty()) return "file";

        // 3) ограничить длину (255 байт на файл в большинстве ФС; берём 200 символов с запасом)
        if (cleaned.length() > 200) {
            int dot = cleaned.lastIndexOf('.');
            String base = dot > 0 ? cleaned.substring(0, dot) : cleaned;
            String ext  = dot > 0 ? cleaned.substring(dot)    : "";
            int maxBase = Math.max(1, 200 - ext.length());
            if (base.length() > maxBase) base = base.substring(0, maxBase);
            cleaned = base + ext;
        }

        // 4) Windows reserved names
        String upper = cleaned.toUpperCase();
        if (upper.matches("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$")) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

}
