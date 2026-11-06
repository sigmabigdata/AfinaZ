package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentFolder;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.FolderService;

import java.util.List;

/**
 * Клиентский дашборд: плоский список всех документов клиента.
 * Разделы (папки) в клиентском UI больше не показываются —
 * документы грузятся в скрытую папку «Без раздела».
 */
@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final UserRepository userRepository;
    private final PiiEncryptor pii;
    private final FolderService folderService;
    private final DocumentRepository documentRepository;

    public DashboardController(UserRepository userRepository,
                               PiiEncryptor pii,
                               FolderService folderService,
                               DocumentRepository documentRepository) {
        this.userRepository = userRepository;
        this.pii = pii;
        this.folderService = folderService;
        this.documentRepository = documentRepository;
    }

    @GetMapping("/dashboard")
    public String dashboard(Authentication auth, Model model) {
        if (auth == null || !auth.isAuthenticated()) {
            return "redirect:/login";
        }
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) return "redirect:/admin";

        // Безопасный поиск: НЕ бросаем NoSuchElementException → не 500
        String username = auth.getName();
        User user = userRepository.findByEmailHash(pii.hash(username)).orElse(null);
        if (user == null) {
            log.warn("Dashboard: user not found for '{}' (hash {})", username, pii.hash(username));
            return "redirect:/login?expired";
        }

        // Дефолтная папка (создаётся автоматически, если нет) — нужна для загрузки
        DocumentFolder defaultFolder;
        try {
            defaultFolder = folderService.ensureDefault(user);
        } catch (Exception e) {
            log.error("Dashboard: ensureDefault failed for user {}", user.getId(), e);
            return "redirect:/login?error=internal";
        }

        // Плоский список всех документов
        List<Document> documents = documentRepository.findAllByOwnerOrderByUploadedAtDesc(user);

        long totalDocs = documents.size();
        long signedDocs = documents.stream().filter(Document::isSigned).count();

        model.addAttribute("user", user);
        model.addAttribute("documents", documents);
        model.addAttribute("defaultFolderId", defaultFolder.getId());
        model.addAttribute("allDocsCount", totalDocs);
        model.addAttribute("allSignedCount", signedDocs);

        return "dashboard";
    }
}
