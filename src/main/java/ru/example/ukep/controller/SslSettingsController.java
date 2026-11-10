package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.service.SslCertificateService;

import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/admin/ssl")
public class SslSettingsController {

    private static final Logger log = LoggerFactory.getLogger(SslSettingsController.class);

    private final SslCertificateService ssl;

    public SslSettingsController(SslCertificateService ssl) {
        this.ssl = ssl;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("hasCert", ssl.hasCertificate());
        return "admin-ssl";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("certFile") MultipartFile certFile,
                         @RequestParam("keyFile") MultipartFile keyFile,
                         RedirectAttributes ra) {
        try {
            if (certFile.isEmpty() || keyFile.isEmpty()) {
                ra.addFlashAttribute("err", "Оба файла обязательны");
                return "redirect:/admin/ssl";
            }
            if (certFile.getSize() > 1024 * 1024 || keyFile.getSize() > 1024 * 1024) {
                ra.addFlashAttribute("err", "Файл слишком большой (макс. 1 МБ)");
                return "redirect:/admin/ssl";
            }
            String certPem = new String(certFile.getBytes(), StandardCharsets.UTF_8);
            String keyPem = new String(keyFile.getBytes(), StandardCharsets.UTF_8);
            if (!certPem.contains("BEGIN CERTIFICATE")) {
                ra.addFlashAttribute("err", "Не похоже на PEM-сертификат (нет BEGIN CERTIFICATE)");
                return "redirect:/admin/ssl";
            }
            if (!keyPem.contains("PRIVATE KEY")) {
                ra.addFlashAttribute("err", "Не похоже на PEM-ключ (нет PRIVATE KEY)");
                return "redirect:/admin/ssl";
            }
            var result = ssl.validate(certPem, keyPem);
            if (!result.valid()) {
                ra.addFlashAttribute("err", "Проверка не пройдена: " + result.error());
                return "redirect:/admin/ssl";
            }
            ssl.save(certPem, keyPem);
            try {
                ssl.restartAngie();
                ra.addFlashAttribute("ok", "Сертификат загружен и применён. Subject: " + result.subject());
            } catch (Exception e) {
                log.error("Angie restart failed", e);
                ra.addFlashAttribute("warn", "Сертификат сохранён, но Angie не перезапустился: " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("SSL upload failed", e);
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return "redirect:/admin/ssl";
    }

    @PostMapping("/delete")
    public String delete(RedirectAttributes ra) {
        try {
            ssl.delete();
            ssl.restartAngie();
            ra.addFlashAttribute("ok", "Сертификат удалён, Angie перезапущен с self-signed");
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return "redirect:/admin/ssl";
    }
}
