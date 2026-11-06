package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;
import ru.example.ukep.service.CrlService;

import java.util.List;
import java.util.Base64;

@Controller
@RequestMapping("/admin/crl")
public class CrlController {

    private static final String REDIR_CRL = "redirect:/admin/crl";

    private static final Logger log = LoggerFactory.getLogger(CrlController.class);

    private final CrlService crlService;
    private final ru.example.ukep.service.CrlOnDemandService crlOnDemandService;

    public CrlController(CrlService crlService,
                         ru.example.ukep.service.CrlOnDemandService crlOnDemandService) {
        this.crlService = crlService;
        this.crlOnDemandService = crlOnDemandService;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("crls", crlService.listAll());
        model.addAttribute("crlsDir", crlService.getCrlsDir().toString());
        long totalSize = crlService.getTotalSize();
        model.addAttribute("totalSize", totalSize);
        model.addAttribute("totalSizePretty", CrlService.prettySize(totalSize));
        model.addAttribute("totalCount", crlService.getCount());
        return "admin-crl";
    }

    @PostMapping("/upload")
    public RedirectView upload(@RequestParam("file") MultipartFile file,
                               RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("err", "Файл пустой");
            return new RedirectView(REDIR_CRL);
        }
        try {
            crlService.saveManual(file.getOriginalFilename(), file.getBytes());
            ra.addFlashAttribute("ok", "CRL загружен: " + file.getOriginalFilename()
                    + " (" + CrlService.prettySize(file.getSize()) + ")");
        } catch (Exception e) {
            log.error("CRL upload failed", e);
            ra.addFlashAttribute("err", "Не удалось загрузить: " + e.getMessage());
        }
        return new RedirectView(REDIR_CRL);
    }

    @PostMapping("/{name}/delete")
    public RedirectView delete(@PathVariable String name, RedirectAttributes ra) {
        try {
            crlService.delete(name);
            ra.addFlashAttribute("ok", "Удалён: " + name);
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка удаления: " + e.getMessage());
        }
        return new RedirectView(REDIR_CRL);
    }

    /** Удалить выбранные CRL по чекбоксам. */
    @PostMapping("/delete-selected")
    public RedirectView deleteSelected(@RequestParam(required = false) List<String> names,
                                       RedirectAttributes ra) {
        if (names == null || names.isEmpty()) {
            ra.addFlashAttribute("err", "Ничего не выбрано");
            return new RedirectView(REDIR_CRL);
        }
        int n = crlService.deleteMany(names);
        ra.addFlashAttribute("ok", "Удалено: " + n + " из " + names.size());
        return new RedirectView(REDIR_CRL);
    }

    /** Удалить все auto-*.crl. */
    @PostMapping("/delete-all-auto")
    public RedirectView deleteAllAuto(RedirectAttributes ra) {
        int n = crlService.deleteAllAuto();
        ra.addFlashAttribute("ok", "Удалено auto-*.crl: " + n);
        return new RedirectView(REDIR_CRL);
    }

    /**
     * Force-refresh: скачать свежие CRL для всех подписей в БД,
     * удалить осиротевшие auto-*.crl.
     */
    @PostMapping("/refresh-all")
    public RedirectView refreshAll(java.security.Principal auth, RedirectAttributes ra) {
        try {
            var result = crlOnDemandService.refreshAll(
                    auth != null ? auth.getName() : "admin");
            ra.addFlashAttribute("ok", String.format(
                    "Обновлено: скачано %d CRL, удалено осиротевших %d. "
                    + "Сертификатов: %d из %d подписей (ошибок парсинга: %d).",
                    result.crlDownloaded(), result.crlDeleted(),
                    result.uniqueCerts(), result.totalSignatures(),
                    result.parseErrors()));
        } catch (Exception e) {
            log.error("refresh-all failed", e);
            ra.addFlashAttribute("err", "Ошибка обновления: " + e.getMessage());
        }
        return new RedirectView(REDIR_CRL);
    }

    /**
     * Загрузка .sig файлов → скачивание CRL для сертификатов в них.
     * Использует BouncyCastle (через CrlOnDemandService), не openssl.
     */
    @PostMapping("/upload-sig")
    public RedirectView uploadSigFiles(@RequestParam("files") MultipartFile[] files,
                                        java.security.Principal auth,
                                        RedirectAttributes ra) {
        if (files == null || files.length == 0) {
            ra.addFlashAttribute("err", "Не выбрано ни одного файла");
            return new RedirectView(REDIR_CRL);
        }
        String actor = auth != null ? auth.getName() : "admin";
        int totalCrl = 0, processed = 0, failed = 0;
        StringBuilder details = new StringBuilder();

        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            try {
                if (file.isEmpty()) throw new IllegalArgumentException("пустой файл");
                byte[] bytes = file.getBytes();
                String sigBase64 = Base64.getEncoder().encodeToString(bytes);
                int n = crlOnDemandService.ensureForSignature(sigBase64, null, actor);
                totalCrl += n;
                processed++;
                details.append(name).append(" → ").append(n).append(" CRL; ");
            } catch (Exception e) {
                failed++;
                log.warn("upload-sig: {} failed: {}", name, e.getMessage());
                details.append(name).append(" → ошибка (").append(e.getMessage()).append("); ");
            }
        }

        String msg = String.format(
                "Обработано .sig: %d (ошибок: %d), скачано CRL: %d. %s",
                processed, failed, totalCrl, details.toString());

        if (failed > 0 && processed == 0) ra.addFlashAttribute("err", msg);
        else if (failed > 0) ra.addFlashAttribute("warn", msg);
        else ra.addFlashAttribute("ok", msg);

        return new RedirectView(REDIR_CRL);
    }

    /**
     * Сканирование папки /app/sigs/ — обработка всех .sig/.p7s/.der файлов.
     * Папка должна быть смонтирована в контейнер (см. docker-compose).
     */
    @PostMapping("/scan-sigs")
    public RedirectView scanSigsDir(java.security.Principal auth, RedirectAttributes ra) {
        java.nio.file.Path dir = java.nio.file.Paths.get("/app/sigs");
        if (!java.nio.file.Files.isDirectory(dir)) {
            ra.addFlashAttribute("err", "Папка /app/sigs не найдена. "
                    + "Смонтируйте её в docker-compose-prod.yml: "
                    + "./sigs:/app/sigs:ro");
            return new RedirectView(REDIR_CRL);
        }

        String actor = auth != null ? auth.getName() : "admin";
        int totalCrl = 0, processed = 0, failed = 0;
        StringBuilder details = new StringBuilder();

        try (var stream = java.nio.file.Files.list(dir)) {
            var files = stream
                    .filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString().toLowerCase();
                        return n.endsWith(".sig") || n.endsWith(".p7s") || n.endsWith(".der");
                    })
                    .sorted()
                    .toList();

            for (java.nio.file.Path f : files) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(f);
                    String sigBase64 = Base64.getEncoder().encodeToString(bytes);
                    int n = crlOnDemandService.ensureForSignature(sigBase64, null, actor);
                    totalCrl += n;
                    processed++;
                    details.append(f.getFileName()).append(" → ").append(n).append(" CRL; ");
                } catch (Exception e) {
                    failed++;
                    log.warn("scan-sigs: {} failed: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка сканирования: " + e.getMessage());
            return new RedirectView(REDIR_CRL);
        }

        String msg = String.format(
                "Сканирование /app/sigs: файлов %d, ошибок %d, скачано CRL %d. %s",
                processed, failed, totalCrl,
                details.length() > 500 ? details.substring(0, 500) + "..." : details.toString());

        if (failed > 0 && processed == 0) ra.addFlashAttribute("err", msg);
        else ra.addFlashAttribute("ok", msg);

        return new RedirectView(REDIR_CRL);
    }
}
