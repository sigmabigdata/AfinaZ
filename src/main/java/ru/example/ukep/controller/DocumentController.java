package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.DocumentSignatureRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.FolderService;
import ru.example.ukep.service.SignatureVerifier;
import ru.example.ukep.service.SignatureVisualizationService;
import ru.example.ukep.service.SigningCertificateService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);
    private static final String REDIR_DASHBOARD = "redirect:/dashboard";
    private static final String CONTENT_DISP = "attachment; filename*=UTF-8''";

    private final DocumentService documentService;
    private final UserRepository userRepository;
    private final PiiEncryptor pii;
    private final FolderService folderService;
    private final SigningCertificateService certificateService;
    private final ru.example.ukep.service.CrlOnDemandService crlOnDemandService;
    private final DocumentRepository documentRepository;
    private final DocumentSignatureRepository signatureRepository;
    private final SignatureVerifier signatureVerifier;
    private final SignatureVisualizationService visualizationService;
    private final AuditService audit;

    public DocumentController(DocumentService documentService,
                              UserRepository userRepository,
                              PiiEncryptor pii,
                              FolderService folderService,
                              SigningCertificateService certificateService,
                              ru.example.ukep.service.CrlOnDemandService crlOnDemandService,
                              DocumentRepository documentRepository,
                              DocumentSignatureRepository signatureRepository,
                              SignatureVerifier signatureVerifier,
                              SignatureVisualizationService visualizationService,
                              AuditService audit) {
        this.documentService = documentService;
        this.userRepository = userRepository;
        this.pii = pii;
        this.folderService = folderService;
        this.certificateService = certificateService;
        this.crlOnDemandService = crlOnDemandService;
        this.documentRepository = documentRepository;
        this.signatureRepository = signatureRepository;
        this.signatureVerifier = signatureVerifier;
        this.visualizationService = visualizationService;
        this.audit = audit;
    }

    private User current(UserDetails p) {
        return userRepository.findByEmailHash(pii.hash(p.getUsername())).orElseThrow();
    }

    // ═══════════════════════════════════════════════════════
    //  Загрузка / просмотр / скачивание
    // ═══════════════════════════════════════════════════════

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam("folderId") Long folderId,
                         @AuthenticationPrincipal UserDetails p) throws IOException {
        User owner = current(p);
        var folder = folderService.getOwned(folderId, owner);
        documentService.upload(file, owner, folder);
        return REDIR_DASHBOARD;
    }

    @GetMapping("/{id}/view")
    public ResponseEntity<Resource> view(@PathVariable Long id,
                                         @AuthenticationPrincipal UserDetails p) throws IOException {
        Document doc = documentService.getOwned(id, current(p));
        Resource r = documentService.loadAsResource(doc);
        MediaType mt = doc.getContentType() != null
                ? MediaType.parseMediaType(doc.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" +
                        URLEncoder.encode(doc.getOriginalName(), StandardCharsets.UTF_8))
                .body(r);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @AuthenticationPrincipal UserDetails p) throws IOException {
        Document doc = documentService.getOwned(id, current(p));
        Resource r = documentService.loadAsResource(doc);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISP +
                        URLEncoder.encode(doc.getOriginalName(), StandardCharsets.UTF_8))
                .body(r);
    }

    @GetMapping("/{id}/signature/download")
    public ResponseEntity<byte[]> downloadSignature(@PathVariable Long id,
                                                    @AuthenticationPrincipal UserDetails p) {
        Document doc = documentService.getOwned(id, current(p));
        if (!doc.isSigned() || doc.getSignatureBase64() == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] sigBytes = Base64.getDecoder().decode(
                doc.getSignatureBase64().replaceAll("\\s+", ""));

        String baseName = stripExtension(doc.getOriginalName());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISP +
                        URLEncoder.encode(baseName + ".sig", StandardCharsets.UTF_8))
                .body(sigBytes);
    }

    @GetMapping("/{id}/download-signed")
    public ResponseEntity<byte[]> downloadSignedZip(@PathVariable Long id,
                                                    @AuthenticationPrincipal UserDetails p) throws IOException {
        Document doc = documentService.getOwned(id, current(p));
        if (!doc.isSigned() || doc.getSignatureBase64() == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] docBytes = documentService.getBytes(doc);
        byte[] sigBytes = Base64.getDecoder().decode(
                doc.getSignatureBase64().replaceAll("\\s+", ""));

        String base = stripExtension(doc.getOriginalName());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            String docEntry = uniqueEntryName(doc.getOriginalName(), usedNames);
            zip.putNextEntry(new ZipEntry(docEntry));
            zip.write(docBytes);
            zip.closeEntry();

            String sigEntry = uniqueEntryName(base + ".sig", usedNames);
            zip.putNextEntry(new ZipEntry(sigEntry));
            zip.write(sigBytes);
            zip.closeEntry();
        }

        String zipName = base + "_signed.zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISP +
                        URLEncoder.encode(zipName, StandardCharsets.UTF_8))
                .body(baos.toByteArray());
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UserDetails p) throws IOException {
        documentService.delete(id, current(p));
        return REDIR_DASHBOARD;
    }

    // ═══════════════════════════════════════════════════════
    //  Подписи — скачивание/удаление
    // ═══════════════════════════════════════════════════════

    @GetMapping("/{id}/signatures/{sigId}/download")
    public ResponseEntity<byte[]> downloadSignature(@PathVariable Long id,
                                                    @PathVariable Long sigId,
                                                    @AuthenticationPrincipal UserDetails p) {
        Document doc = documentService.getOwned(id, current(p));
        DocumentSignature sig = documentService.getSignature(sigId);
        if (!sig.getDocument().getId().equals(doc.getId())) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Base64.getDecoder().decode(sig.getSignatureBase64().replaceAll("\\s+", ""));
        String base = stripExtension(doc.getOriginalName());
        String fileName = base + "_sig_" + sig.getId() + ".sig";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISP +
                        URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                .body(bytes);
    }

    @PostMapping("/{id}/signatures/{sigId}/delete")
    public String deleteSignature(@PathVariable Long id,
                                  @PathVariable Long sigId,
                                  @AuthenticationPrincipal UserDetails p) {
        Document doc = documentService.getOwned(id, current(p));
        DocumentSignature sig = documentService.getSignature(sigId);
        if (!sig.getDocument().getId().equals(doc.getId())) {
            throw new IllegalArgumentException("Подпись не относится к документу");
        }
        documentService.deleteSignature(sigId);
        return REDIR_DASHBOARD;
    }

    // ═══════════════════════════════════════════════════════
    //  Клиент: визуализация
    // ═══════════════════════════════════════════════════════

    /** Скачать PDF-визуализацию подписи (штампы). */
    @GetMapping("/{id}/visualization")
    public Object visualization(@PathVariable Long id,
                                 @RequestParam(defaultValue = "false") boolean regen,
                                 @AuthenticationPrincipal UserDetails p) {
        Document doc = documentService.getOwned(id, current(p));
        try {
            var vis = visualizationService.findFor(doc);
            byte[] bytes;
            if (vis.isPresent() && !regen) {
                bytes = visualizationService.loadBytes(vis.get());
            } else {
                var generated = visualizationService.generate(doc);
                bytes = visualizationService.loadBytes(generated);
                audit.event("VISUALIZATION_GENERATE", "SUCCESS",
                        p.getUsername(), "DOCUMENT", String.valueOf(id), null, "client");
            }
            String fileName = "ВИЗУАЛИЗАЦИЯ_"
                    + stripExtension(doc.getOriginalName()) + ".pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISP +
                            URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                    .body(bytes);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "Ошибка" : e.getMessage();
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", msg));
        } catch (Exception e) {
            log.error("visualization failed for doc {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Пересоздать визуализацию. */
    @PostMapping("/{id}/visualization/regenerate")
    public String regenerateVisualization(@PathVariable Long id,
                                           @AuthenticationPrincipal UserDetails p,
                                           RedirectAttributes ra) {
        Document doc = documentService.getOwned(id, current(p));
        try {
            var vis = visualizationService.generate(doc);
            ra.addFlashAttribute("ok", "Визуализация обновлена (v" + vis.getVersion() + ")");
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return REDIR_DASHBOARD;
    }

    // ═══════════════════════════════════════════════════════
    //  Клиент: загрузка .sig и доверенность
    // ═══════════════════════════════════════════════════════

    /** Загрузить .sig из файла (клиент). */
    @PostMapping("/{id}/upload-signature")
    public String uploadSignature(@PathVariable Long id,
                                   @RequestParam("file") MultipartFile file,
                                   @AuthenticationPrincipal UserDetails p,
                                   RedirectAttributes ra) {
        Document doc = documentService.getOwned(id, current(p));
        try {
            if (file.isEmpty()) throw new IllegalArgumentException("Файл пустой");
            byte[] sigBytes = file.getBytes();
            String sigBase64 = Base64.getEncoder().encodeToString(sigBytes);

            var result = signatureVerifier.verifyDetachedBasic(
                    documentService.getBytes(doc), sigBase64);
            String subject = String.valueOf(result.getOrDefault("signerSubject", "—"));
            String serial = String.valueOf(result.getOrDefault("signerSerial", "—"));

            documentService.addSignature(id, null, sigBase64, subject, serial);
            audit.event("DOC_UPLOAD_SIG", "SUCCESS", p.getUsername(),
                    "DOCUMENT", String.valueOf(id), subject, "client upload .sig");

            // Скачать CRL для сертификатов этой подписи
            try {
                int crlCount = crlOnDemandService.ensureForSignature(
                        sigBase64, id, p.getUsername());
                if (crlCount > 0) {
                    log.info("CRL downloaded from .sig: {} for doc {}", crlCount, id);
                }
            } catch (Exception crlEx) {
                log.warn("CRL download failed after .sig upload: {}", crlEx.getMessage());
            }

            try { visualizationService.generate(doc); } catch (Exception ignored) {}

            ra.addFlashAttribute("ok", "Подпись добавлена. Подписант: " + subject);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", "Подпись не принята: " + e.getMessage());
        } catch (Exception e) {
            log.warn("upload-signature failed for doc {}: {}", id, e.getMessage());
            ra.addFlashAttribute("err", "Ошибка загрузки .sig: " + e.getMessage());
        }
        return REDIR_DASHBOARD;
    }

    /** Применить/отключить доверенность к конкретной подписи (клиент). */
    @PostMapping("/{id}/signatures/{sigId}/apply-poa")
    @org.springframework.transaction.annotation.Transactional
    public String applyPoaSignature(@PathVariable Long id,
                                     @PathVariable Long sigId,
                                     @RequestParam boolean apply,
                                     @AuthenticationPrincipal UserDetails p,
                                     RedirectAttributes ra) {
        Document doc = documentService.getOwned(id, current(p));
        var sigOpt = doc.getSignatures().stream()
                .filter(s -> s.getId().equals(sigId)).findFirst();
        if (sigOpt.isEmpty()) {
            ra.addFlashAttribute("err", "Подпись не относится к документу");
            return REDIR_DASHBOARD;
        }
        try {
            signatureRepository.updateApplyPoa(sigId, apply);
            audit.event("SIG_APPLY_POA", "SUCCESS", p.getUsername(),
                    "SIGNATURE", String.valueOf(sigId), null,
                    apply ? "Доверенность применена (client)" : "Доверенность отключена (client)");
            try {
                Document fresh = documentRepository.findByIdWithSignatures(id).orElseThrow();
                visualizationService.generate(fresh);
            } catch (Exception ignored) {}
            ra.addFlashAttribute("ok", apply ? "Доверенность применена" : "Доверенность отключена");
        } catch (Exception e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return REDIR_DASHBOARD;
    }

    // ═══════════════════════════════════════════════════════
    //  Справка о подписании (PDF)
    // ═══════════════════════════════════════════════════════

    @GetMapping("/{id}/signing-certificate")
    public ResponseEntity<byte[]> signingCertificate(@PathVariable Long id,
                                                     @AuthenticationPrincipal UserDetails p)
            throws IOException {
        Document doc = documentService.getOwned(id, current(p));
        try {
            byte[] pdf = certificateService.generate(id);
            String fileName = "СПРАВКА_" + stripExtension(doc.getOriginalName()) + ".pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISP +
                            URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                    .body(pdf);
        } catch (Exception e) {
            log.error("signing-certificate failed for doc {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Утилиты
    // ═══════════════════════════════════════════════════════

    private static String stripExtension(String name) {
        if (name == null) return "file";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String uniqueEntryName(String desired, Set<String> used) {
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
}
