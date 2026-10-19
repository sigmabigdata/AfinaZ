package ru.example.ukep.controller;

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

@Controller
public class DashboardController {

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

        User user = userRepository.findByEmailHash(pii.hash(auth.getName())).orElseThrow();

        // Legacy: документы без раздела → перенести в «Без раздела»
        folderService.migrateUnfiled(user);

        List<DocumentFolder> folders = folderService.listForOwner(user);
        Map<Long, List<Document>> byFolder = new LinkedHashMap<>();
        for (DocumentFolder f : folders) {
            byFolder.put(f.getId(),
                    documentRepository.findAllByFolderOrderByUploadedAtDesc(f));
        }

        model.addAttribute("user", user);
        model.addAttribute("folders", folders);
        model.addAttribute("documentsByFolder", byFolder);
        return "dashboard";
    }
}
