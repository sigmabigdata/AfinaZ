package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.PowerOfAttorney;
import ru.example.ukep.service.PowerOfAttorneyService;

import java.util.List;

@Controller
@RequestMapping("/admin/poa")
public class PowerOfAttorneyController {

    private static final Logger log = LoggerFactory.getLogger(PowerOfAttorneyController.class);
    private static final String REDIR = "redirect:/admin/poa";

    private final PowerOfAttorneyService service;

    public PowerOfAttorneyController(PowerOfAttorneyService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        List<PowerOfAttorney> poas = service.listAll();
        model.addAttribute("poas", poas);
        model.addAttribute("total", poas.size());
        model.addAttribute("active", poas.stream().filter(PowerOfAttorney::isEffective).count());
        model.addAttribute("expired", poas.stream().filter(PowerOfAttorney::isExpired).count());
        return "admin-poa";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         java.security.Principal principal,
                         RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("err", "Файл не выбран");
            return REDIR;
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".xml")) {
            ra.addFlashAttribute("err", "Ожидается .xml файл ЭМЧД");
            return REDIR;
        }
        try {
            PowerOfAttorney poa = service.upload(file.getBytes(),
                    principal != null ? principal.getName() : "unknown");
            ra.addFlashAttribute("ok", "Доверенность загружена: " + poa.getFio()
                    + " (действительна до " + poa.getValidUntil() + ")");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        } catch (Exception e) {
            log.error("POA upload failed", e);
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         @RequestParam("active") boolean active,
                         java.security.Principal principal,
                         RedirectAttributes ra) {
        try {
            service.setActive(id, active, principal != null ? principal.getName() : "unknown");
            ra.addFlashAttribute("ok", active ? "Доверенность активирована" : "Доверенность деактивирована");
        } catch (Exception e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         java.security.Principal principal,
                         RedirectAttributes ra) {
        try {
            service.delete(id, principal != null ? principal.getName() : "unknown");
            ra.addFlashAttribute("ok", "Доверенность удалена");
        } catch (Exception e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return REDIR;
    }
}
