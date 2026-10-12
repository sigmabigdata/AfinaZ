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

@Controller
@RequestMapping("/admin/crl")
public class CrlController {

    private static final String REDIR_CRL = "redirect:/admin/crl";

    private static final Logger log = LoggerFactory.getLogger(CrlController.class);

    private final CrlService crlService;

    public CrlController(CrlService crlService) {
        this.crlService = crlService;
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
}
