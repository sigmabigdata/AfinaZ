package ru.example.ukep.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.DocumentTimelineService;

import java.util.Map;

/**
 * API для ленты событий документа (timeline).
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentTimelineController {

    private final DocumentTimelineService timelineService;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final PiiEncryptor pii;

    public DocumentTimelineController(DocumentTimelineService timelineService,
                                      DocumentRepository documentRepository,
                                      UserRepository userRepository,
                                      PiiEncryptor pii) {
        this.timelineService = timelineService;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.pii = pii;
    }

    @GetMapping("/{id}/timeline")
    public ResponseEntity<?> getTimeline(@PathVariable Long id,
                                         @AuthenticationPrincipal UserDetails p) {
        if (p == null) return ResponseEntity.status(401).build();

        User current = userRepository.findByEmailHash(pii.hash(p.getUsername())).orElse(null);
        if (current == null) return ResponseEntity.status(403).build();

        Document doc = documentRepository.findByIdWithSignatures(id).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();

        // Доступ: админ видит всё, клиент — только свой документ
        boolean isAdmin = current.getRole() == ru.example.ukep.entity.Role.ROLE_ADMIN;
        if (!isAdmin && !doc.getOwner().getId().equals(current.getId())) {
            return ResponseEntity.status(403).build();
        }

        return ResponseEntity.ok(Map.of(
                "documentId", id,
                "documentName", doc.getOriginalName(),
                "events", timelineService.timeline(id, 100)
        ));
    }
}
