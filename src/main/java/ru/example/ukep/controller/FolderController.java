package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.DocumentFolder;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.FolderService;


/**
 * Управление разделами (папками) документов.
 *
 * <p>Клиент работает только со своими разделами. Админ — с разделами любого
 * клиента через {@link AdminController}. Здесь — только клиентские эндпоинты
 * и создание из карточки админа (через {@link AdminFolderController}, см. ниже).</p>
 */
@Controller
@RequestMapping("/folders")
public class FolderController {

    private static final Logger log = LoggerFactory.getLogger(FolderController.class);
    private static final String REDIR_DASHBOARD = "redirect:/dashboard";

    private final FolderService folderService;
    private final UserRepository userRepository;
    private final PiiEncryptor pii;
    private final AuditService audit;

    public FolderController(FolderService folderService,
                            UserRepository userRepository,
                            PiiEncryptor pii,
                            AuditService audit) {
        this.folderService = folderService;
        this.userRepository = userRepository;
        this.pii = pii;
        this.audit = audit;
    }

    private User current(UserDetails p) {
        return userRepository.findByEmailHash(pii.hash(p.getUsername())).orElseThrow();
    }

    /** Создать раздел (клиент). */
    @PostMapping("/create")
    public String create(@RequestParam("name") String name,
                         @AuthenticationPrincipal UserDetails p,
                         RedirectAttributes ra) {
        User user = current(p);
        try {
            DocumentFolder f = folderService.create(user, name);
            log.info("Клиент {} создал раздел '{}'", user.getEmail(), f.getName());
            audit.folderCreate(user.getEmail(), f.getId(), f.getName());
            ra.addFlashAttribute("ok", "Раздел создан: " + f.getName());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return REDIR_DASHBOARD;
    }

    /** Переименовать раздел (клиент). */
    @PostMapping("/{id}/rename")
    public String rename(@PathVariable Long id,
                         @RequestParam("name") String name,
                         @AuthenticationPrincipal UserDetails p,
                         RedirectAttributes ra) {
        User user = current(p);
        try {
            folderService.rename(id, user, name);
            audit.folderRename(user.getEmail(), id, name);
            ra.addFlashAttribute("ok", "Раздел переименован");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return REDIR_DASHBOARD;
    }

    /** Удалить раздел (клиент). Блокируется, если есть подписанные документы. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UserDetails p,
                         RedirectAttributes ra) {
        User user = current(p);
        try {
            DocumentFolder f = folderService.getOwned(id, user);
            String name = f.getName();
            long signedCount = folderService.countSignedInFolder(id, user);
            folderService.delete(id, user);
            audit.folderDelete(user.getEmail(), id, name, signedCount);
            ra.addFlashAttribute("ok", "Раздел удалён");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return REDIR_DASHBOARD;
    }
}
