package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.example.ukep.entity.Role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Хранит список администраторов в файле (по умолчанию ./admins.env).
 * Формат: CN|SNILS — по одной записи на строку.
 * Строки, начинающиеся с # — комментарии.
 */
@Service
public class AdminCredentialsFileService {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialsFileService.class);

    @Value("${app.admins-file:./admins.env}")
    private String adminsFilePath;

    public synchronized List<AdminRecord> readAll() {
        Path path = Path.of(adminsFilePath);
        List<AdminRecord> result = new ArrayList<>();
        if (!Files.exists(path)) return result;

        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    AdminRecord rec = parseLine(s);
                    if (rec != null) result.add(rec);
                }
            }
        } catch (IOException e) {
            log.error("Не удалось прочитать файл админов: {}", adminsFilePath, e);
        }
        return result;
    }

    /** Разбирает строку «CN|SNILS». Возвращает null, если строка невалидна. */
    private AdminRecord parseLine(String s) {
        String[] parts = s.split("\\|", 3);
        if (parts.length < 2) return null;
        String cn = normalizeCn(parts[0]);
        String snils = normalizeSnils(parts[1].trim());
        if (cn.isEmpty() || snils.isEmpty()) return null;
        Role role = Role.ROLE_ADMIN;  // default
        if (parts.length >= 3) {
            String r = parts[2].trim().toLowerCase();
            if ("employee".equals(r) || "сотрудник".equals(r) || "role_employee".equals(r)) {
                role = Role.ROLE_EMPLOYEE;
            }
        }
        return new AdminRecord(cn, snils, role);
    }

    /** Ищет администратора по CN и СНИЛС (с нормализацией Unicode и пробелов). */
    public Optional<AdminRecord> find(String cn, String snils) {
        if (cn == null || snils == null) return Optional.empty();
        String cnNorm = normalizeCn(cn);
        String snilsNorm = normalizeSnils(snils);
        log.debug("find admin: cnNorm='{}', snilsNorm='{}'", cnNorm, snilsNorm);
        return readAll().stream()
                .filter(r -> {
                    String fileCn = normalizeCn(r.cn());
                    boolean cnMatch = fileCn.equalsIgnoreCase(cnNorm);
                    boolean snilsMatch = r.snils().equals(snilsNorm);
                    if (!cnMatch || !snilsMatch) {
                        log.debug("no match: file cn='{}', file snils='{}'", fileCn, r.snils());
                    }
                    return cnMatch && snilsMatch;
                })
                .findFirst();
    }

    /** Нормализация ФИО: NFC + удаление лишних пробелов + trim. */
    private String normalizeCn(String cn) {
        if (cn == null) return "";
        String n = Normalizer.normalize(cn, Normalizer.Form.NFC);
        n = n.replaceAll("\\s+", " ").trim();
        return n;
    }

    /** Убирает пробелы/дефисы из СНИЛС: "123-456-789 01" -> "12345678901". */
    private String normalizeSnils(String snils) {
        return snils == null ? "" : snils.replaceAll("\\D", "");
    }

    public record AdminRecord(String cn, String snils, Role role) {}
}
