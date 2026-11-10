package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.ukep.entity.AuditEvent;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.AuditEventRepository;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.DocumentVisualizationRepository;
import ru.example.ukep.repository.UserRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Экспорт всех данных клиента в ZIP-архив.
 * Используется для комплаенса (152-ФЗ) и по запросу клиента.
 */
@Service
public class ClientExportService {

    private static final Logger log = LoggerFactory.getLogger(ClientExportService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVisualizationRepository visRepository;
    private final AuditEventRepository auditRepository;
    private final DocumentService documentService;

    public ClientExportService(UserRepository userRepository,
                               DocumentRepository documentRepository,
                               DocumentVisualizationRepository visRepository,
                               AuditEventRepository auditRepository,
                               DocumentService documentService) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.visRepository = visRepository;
        this.auditRepository = auditRepository;
        this.documentService = documentService;
    }

    @Transactional(readOnly = true)
    public byte[] exportClient(Long userId) throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        List<Document> docs = documentRepository.findAllByOwnerOrderByUploadedAtDesc(user);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            // 1. README.txt
            addTextFile(zip, "README.txt", buildReadme(user, docs.size()));

            // 2. metadata.json (профиль клиента)
            addTextFile(zip, "metadata.json", buildMetadataJson(user));

            // 3. documents/ (файлы + .sig)
            exportDocuments(zip, docs);

            // 4. visualizations/ (PDF-штампы)
            exportVisualizations(zip, docs);

            // 5. audit.csv (события по клиенту и его документам)
            addTextFile(zip, "audit.csv", buildAuditCsv(user, docs));
        }

        log.info("Client export: user={}, documents={}, size={} B",
                user.getId(), docs.size(), baos.size());
        return baos.toByteArray();
    }

    private void exportDocuments(ZipOutputStream zip, List<Document> docs) throws IOException {
        Set<String> usedNames = new HashSet<>();
        for (Document doc : docs) {
            String baseName = sanitize(stripExtension(doc.getOriginalName()));
            String docEntry = uniqueEntry("documents/" + baseName + getExtension(doc.getOriginalName()), usedNames);

            try {
                byte[] content = documentService.getBytes(doc);
                zip.putNextEntry(new ZipEntry(docEntry));
                zip.write(content);
                zip.closeEntry();
            } catch (Exception e) {
                log.warn("Failed to export doc {}: {}", doc.getId(), e.getMessage());
                continue;
            }

            // Подписи
            List<DocumentSignature> sigs = doc.getSignatures();
            if (sigs != null && !sigs.isEmpty()) {
                for (int i = 0; i < sigs.size(); i++) {
                    DocumentSignature sig = sigs.get(i);
                    try {
                        byte[] sigBytes = Base64.getDecoder().decode(
                                sig.getSignatureBase64().replaceAll("\\s+", ""));
                        String sigEntry = uniqueEntry(
                                "signatures/" + baseName + "_sig_" + (i + 1) + ".sig", usedNames);
                        zip.putNextEntry(new ZipEntry(sigEntry));
                        zip.write(sigBytes);
                        zip.closeEntry();
                    } catch (Exception e) {
                        log.warn("Failed to export signature {}: {}", sig.getId(), e.getMessage());
                    }
                }
            }
        }
    }

    private void exportVisualizations(ZipOutputStream zip, List<Document> docs) {
        for (Document doc : docs) {
            visRepository.findByDocument(doc).ifPresent(vis -> {
                try {
                    java.nio.file.Path p = java.nio.file.Paths.get("/app/storage/documents/visualizations")
                            .resolve(vis.getStoredName());
                    if (java.nio.file.Files.isRegularFile(p)) {
                        String entry = "visualizations/vis_" + doc.getId() + ".pdf";
                        zip.putNextEntry(new ZipEntry(entry));
                        zip.write(java.nio.file.Files.readAllBytes(p));
                        zip.closeEntry();
                    }
                } catch (Exception e) {
                    log.warn("Failed to export visualization for doc {}: {}", doc.getId(), e.getMessage());
                }
            });
        }
    }

    private String buildReadme(User user, int docCount) {
        return """
                ПАО "НБД-Банк" · Экспорт данных клиента
                ============================================================
                
                Клиент:     %s
                Email:      %s
                Роль:       %s
                Статус:     %s
                Создан:     %s
                
                Документов: %d
                
                Структура архива:
                  README.txt            — этот файл
                  metadata.json         — профиль клиента
                  documents/            — оригинальные файлы документов
                  signatures/           — отсоединённые подписи (.sig)
                  visualizations/       — PDF с визуальными штампами
                  audit.csv             — события аудита (CSV, UTF-8)
                
                Все подписи — CAdES-BES (ГОСТ). Проверить можно:
                  1) Через КриптоПро CADESCOM
                  2) Или любым ГОСТ-совместимым криптопровайдером
                
                Сгенерировано: %s
                """.formatted(
                user.getFullName(),
                user.getEmail(),
                user.getRole().name(),
                user.isEnabled() ? "Активен" : "Заблокирован",
                user.getCreatedAt() != null ? TS.format(user.getCreatedAt()) : "—",
                docCount,
                TS.format(java.time.Instant.now()));
    }

    private String buildMetadataJson(User user) {
        return """
                {
                  "id": %d,
                  "fullName": "%s",
                  "email": "%s",
                  "role": "%s",
                  "enabled": %b,
                  "createdAt": "%s",
                  "lastLoginAt": %s
                }
                """.formatted(
                user.getId(),
                escapeJson(user.getFullName()),
                escapeJson(user.getEmail()),
                user.getRole().name(),
                user.isEnabled(),
                user.getCreatedAt() != null ? user.getCreatedAt().toString() : "",
                user.getLastLoginAt() != null ? "\"" + user.getLastLoginAt().toString() + "\"" : "null");
    }

    private String buildAuditCsv(User user, List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("event_time,event_type,result,actor_email,target_type,target_id,details\n");

        // События по самому пользователю
        List<AuditEvent> userEvents = auditRepository.findAllByOrderByEventTimeDesc(PageRequest.of(0, 1000))
                .stream()
                .filter(e -> ("USER".equals(e.getTargetType()) && String.valueOf(user.getId()).equals(e.getTargetId()))
                        || (e.getActorEmail() != null && e.getActorEmail().equals(user.getEmail())))
                .toList();

        for (AuditEvent e : userEvents) {
            sb.append(String.format("%s,%s,%s,%s,%s,%s,%s\n",
                    CSV_DATE.format(e.getEventTime()),
                    csv(e.getEventType()),
                    csv(e.getResult()),
                    csv(e.getActorEmail()),
                    csv(e.getTargetType()),
                    csv(e.getTargetId()),
                    csv(e.getDetails())));
        }

        // События по документам пользователя
        Set<Long> docIds = new HashSet<>();
        for (Document d : docs) docIds.add(d.getId());

        List<AuditEvent> docEvents = auditRepository.findAllByOrderByEventTimeDesc(PageRequest.of(0, 2000))
                .stream()
                .filter(e -> "DOCUMENT".equals(e.getTargetType())
                        && e.getTargetId() != null
                        && isNumeric(e.getTargetId())
                        && docIds.contains(Long.parseLong(e.getTargetId())))
                .toList();

        for (AuditEvent e : docEvents) {
            sb.append(String.format("%s,%s,%s,%s,%s,%s,%s\n",
                    CSV_DATE.format(e.getEventTime()),
                    csv(e.getEventType()),
                    csv(e.getResult()),
                    csv(e.getActorEmail()),
                    csv(e.getTargetType()),
                    csv(e.getTargetId()),
                    csv(e.getDetails())));
        }

        return sb.toString();
    }

    private void addTextFile(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        // ВАЖНО: не используем try-with-resources — PrintWriter.close() закроет
        // и ZipOutputStream, после чего следующий putNextEntry() бросит IOException.
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        pw.write(content);
        pw.flush();
        zip.closeEntry();
    }

    private static String sanitize(String s) {
        if (s == null) return "file";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String stripExtension(String name) {
        if (name == null) return "file";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String getExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private static String uniqueEntry(String desired, Set<String> used) {
        if (used.add(desired)) return desired;
        int dot = desired.lastIndexOf('.');
        String base = dot > 0 ? desired.substring(0, dot) : desired;
        String ext = dot > 0 ? desired.substring(dot) : "";
        int i = 1;
        String candidate;
        do {
            candidate = base + "_" + i + ext;
            i++;
        } while (!used.add(candidate));
        return candidate;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** true, если строка состоит только из цифр (без знака, без пробелов). */
    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
