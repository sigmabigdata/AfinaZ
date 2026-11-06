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
}
