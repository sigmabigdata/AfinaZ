package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.DocumentFolder;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.FolderService;

/**
 * Админские эндпоинты для работы с разделами клиента.
 * Вызываются из карточки клиента ({@code /admin/users/{id}}).
 */
@Controller
@RequestMapping("/admin/users/{userId}/folders")
public class AdminFolderController {

    private static final Logger log = LoggerFactory.getLogger(AdminFolderController.class);

    private final FolderService folderService;
    private final UserRepository userRepository;
    private final PiiEncryptor pii;

    public AdminFolderController(FolderService folderService,
                                 UserRepository userRepository,
                                 PiiEncryptor pii) {
        this.folderService = folderService;
        this.userRepository = userRepository;
        this.pii = pii;
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
    }

    private User currentAdmin(java.security.Principal principal) {
        if (principal == null) throw new IllegalArgumentException("Не аутентифицирован");
        return userRepository.findByEmailHash(pii.hash(principal.getName()))
                .orElseThrow(() -> new IllegalArgumentException("Админ не найден"));
    }

    @PostMapping("/create")
    public String create(@PathVariable Long userId,
                         @RequestParam("name") String name,
                         RedirectAttributes ra) {
        User user = getUser(userId);
        try {
            DocumentFolder f = folderService.create(user, name);
            log.info("Админ создал раздел '{}' у клиента {}", f.getName(), user.getEmail());
            ra.addFlashAttribute("ok", "Раздел создан: " + f.getName());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/admin/users/" + userId;
    }

    @PostMapping("/{folderId}/rename")
    public String rename(@PathVariable Long userId,
                         @PathVariable Long folderId,
                         @RequestParam("name") String name,
                         RedirectAttributes ra) {
        User user = getUser(userId);
        try {
            folderService.rename(folderId, user, name);
            ra.addFlashAttribute("ok", "Раздел переименован");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/admin/users/" + userId;
    }

    /** Админ может удалить любой раздел, даже с подписанными документами. */
    @PostMapping("/{folderId}/delete")
    public String delete(@PathVariable Long userId,
                         @PathVariable Long folderId,
                         java.security.Principal principal,
                         RedirectAttributes ra) {
        try {
            User admin = currentAdmin(principal);
            folderService.delete(folderId, admin);
            ra.addFlashAttribute("ok", "Раздел удалён");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/admin/users/" + userId;
    }
}
