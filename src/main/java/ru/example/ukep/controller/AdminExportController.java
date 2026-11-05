package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.ClientExportService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Экспорт данных клиента в ZIP (GDPR-стиль).
 * Доступно только администратору.
 */
@Controller
@RequestMapping("/admin/users")
public class AdminExportController {

    private static final Logger log = LoggerFactory.getLogger(AdminExportController.class);

    private final ClientExportService exportService;
    private final UserRepository userRepository;
    private final AuditService audit;

    public AdminExportController(ClientExportService exportService,
                                 UserRepository userRepository,
                                 AuditService audit) {
        this.exportService = exportService;
        this.userRepository = userRepository;
        this.audit = audit;
    }

    @GetMapping("/{id}/export-zip")
    public Object exportZip(@PathVariable Long id, java.security.Principal principal) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] zip = exportService.exportClient(id);
            String fileName = "client_" + id + "_export_"
                    + java.time.LocalDate.now() + ".zip";
            audit.event("CLIENT_EXPORT", "SUCCESS",
                    principal != null ? principal.getName() : "unknown",
                    "USER", String.valueOf(id), user.getFullName(),
                    "ZIP-экспорт всех данных (" + zip.length + " B)");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''"
                                    + URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                    .body(zip);
        } catch (Exception e) {
            log.error("Export failed for user {}", id, e);
            return ResponseEntity.status(500).build();
        }
    }
}
