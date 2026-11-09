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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиентский дашборд: разделы + документы.
 * Новые разделы — сверху (сортировка по createdAt DESC).
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

        String username = auth.getName();
        User user = userRepository.findByEmailHash(pii.hash(username)).orElse(null);
        if (user == null) {
            log.warn("Dashboard: user not found for '{}' (hash {})", username, pii.hash(username));
            return "redirect:/login?expired";
        }

        // Разделы (новые сверху)
        List<DocumentFolder> folders = folderService.listForOwner(user);


        // Документы по разделам
        Map<Long, List<Document>> byFolder = new LinkedHashMap<>();
        for (DocumentFolder f : folders) {
            byFolder.put(f.getId(),
                    documentRepository.findAllByFolderOrderByUploadedAtDesc(f));
        }


        // Простой DTO для JS
        List<Map<String, Object>> folderOptions = new java.util.ArrayList<>();
        for (DocumentFolder f : folders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("name", f.getName());
            folderOptions.add(m);
        }

        long totalDocs = documentRepository.countByOwner(user);
        long signedDocs = documentRepository.countByOwnerAndSignedTrue(user);

        model.addAttribute("user", user);
        model.addAttribute("folders", folders);
        model.addAttribute("documentsByFolder", byFolder);
        model.addAttribute("defaultFolderId", folders.isEmpty() ? 0L : folders.get(0).getId());
        model.addAttribute("folderOptions", folderOptions);
        model.addAttribute("allDocsCount", totalDocs);
        model.addAttribute("allSignedCount", signedDocs);

        return "dashboard";
    }
}
