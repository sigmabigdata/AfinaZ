package ru.example.ukep.controller;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.KeysService;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;

@Controller
@RequestMapping("/admin/keys")
public class KeysController {

    private static final Logger log = LoggerFactory.getLogger(KeysController.class);

    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final String REDIR_KEYS = "redirect:/admin/keys";

    private final KeysService keys;
    private final AuditService audit;

    public KeysController(KeysService keys, AuditService audit) {
        this.keys = keys;
        this.audit = audit;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("keys", keys.listAll());
        model.addAttribute("lastBackupAt", keys.getLastBackupAt());
        model.addAttribute("downloadCount", keys.getDownloadCount());
        model.addAttribute("daysSinceLastBackup", keys.daysSinceLastBackup());
        model.addAttribute("minPasswordLength", MIN_PASSWORD_LENGTH);
        return "admin-keys";
    }

    /**
     * Скачать ZIP с ключами, защищённый паролем (AES-256).
     *
     * GET-вариант убран намеренно: пароль в query-строке попадёт в
     * access.log Caddy, Referer, историю браузера. POST с формой — безопаснее.
     */
    @PostMapping("/download")
    public Object download(@RequestParam String password,
                           @RequestParam String confirmPassword,
                           java.security.Principal auth,
                           RedirectAttributes ra) {
        // 1. Валидация пароля
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            ra.addFlashAttribute("err",
                    "Пароль должен быть не короче " + MIN_PASSWORD_LENGTH + " символов");
            return REDIR_KEYS;
        }
        if (!password.equals(confirmPassword)) {
            ra.addFlashAttribute("err", "Пароли не совпадают");
            return REDIR_KEYS;
        }

        try {
            Path fileKey = keys.getFileKeyPath();
            Path piiKey = keys.getPiiKeyPath();
            if (!Files.isReadable(fileKey) || !Files.isReadable(piiKey)) {
                ra.addFlashAttribute("err", "Файлы ключей недоступны");
                return REDIR_KEYS;
            }

            // 2. Собираем ZIP в память с AES-256 шифрованием (zip4j)
            byte[] zipBytes = buildEncryptedZip(fileKey, piiKey, password);

            // 3. Аудит + запись счётчика
            String zipName = "afina-keys-" + LocalDate.now() + ".zip";
            String who = auth != null ? auth.getName() : "unknown";
            log.warn("Key backup (password-protected) downloaded by {} ({} bytes)",
                    who, zipBytes.length);
            keys.recordBackupDownload(who);
            audit.keysDownload(who, zipName);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + zipName + "\"")
                    .body(zipBytes);

        } catch (Exception e) {
            log.error("Keys download failed", e);
            ra.addFlashAttribute("err", "Ошибка подготовки архива: " + e.getMessage());
            return REDIR_KEYS;
        }
    }

    /** Создаёт защищённый паролем ZIP в памяти. */
    private byte[] buildEncryptedZip(Path fileKey, Path piiKey, String password) throws Exception {
        // zip4j работает с файлами, поэтому соберём во временном каталоге.
        // Явно задаём права 0700 — только владелец (защита от подглядывания
        // через /tmp, Sonar java:S5443).
        Path tmpDir = Files.createTempDirectory(
                "afina-keys-",
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
        try (TempDirCleanup cleaner = new TempDirCleanup(tmpDir)) {
            Path tmpZip = tmpDir.resolve("afina-keys.zip");

            ZipParameters params = new ZipParameters();
            params.setCompressionMethod(CompressionMethod.DEFLATE);
            params.setEncryptFiles(true);
            params.setEncryptionMethod(EncryptionMethod.AES);
            params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);

            try (ZipFile zip = new ZipFile(tmpZip.toFile(), password.toCharArray())) {
                zip.addFile(fileKey.toFile(), params);
                zip.addFile(piiKey.toFile(), params);

                // README.txt — plaintext, чтобы человек увидел инструкции без пароля
                ZipParameters plain = new ZipParameters();
                plain.setCompressionMethod(CompressionMethod.DEFLATE);
                plain.setEncryptFiles(false);
                Path readme = tmpDir.resolve("README.txt");
                Files.writeString(readme, readmeText(), StandardCharsets.UTF_8);
                zip.addFile(readme.toFile(), plain);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Files.copy(tmpZip, baos);
            return baos.toByteArray();
        }
    }

    /** Автоудаление временного каталога при закрытии (try-with-resources). */
    private static final class TempDirCleanup implements AutoCloseable {
        private final Path dir;
        TempDirCleanup(Path dir) { this.dir = dir; }

        @Override
        public void close() {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(this::deleteQuietly);
            } catch (Exception ex) {
                log.warn("Temp cleanup (walk) failed: {}", ex.getMessage());
            }
        }

        private void deleteQuietly(Path p) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ex) {
                log.debug("Temp cleanup skip {}: {}", p, ex.getMessage());
            }
        }
    }

    private String readmeText() {
        return """
                Афина · Бэкап ключей шифрования
                Скачано: %s

                В архиве:
                - file.key — AES-256-GCM для файлов документов
                - pii.key  — AES-256-GCM для персональных данных (email, phone, ФИО)

                ⚠️  Без этих ключей восстановить данные невозможно.
                ⚠️  Храните архив в защищённом месте (1Password, сейф).
                ⚠️  Пароль от ZIP не хранится в приложении — если потеряете, архив не открыть.
                ⚠️  Никогда не пересылайте архив и пароль в одном канале.

                Файлы .key внутри защищены AES-256 (zip4j).
                Для распаковки: 7z x afina-keys-*.zip   (или zip4j, WinRAR 5+)
                """.formatted(LocalDate.now());
    }
}
