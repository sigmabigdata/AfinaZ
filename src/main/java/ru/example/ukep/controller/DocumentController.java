package ru.example.ukep.controller;

import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.DocumentService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/documents")
public class DocumentController {

    private static final String REDIR_DASHBOARD = "redirect:/dashboard";
    private static final String CONTENT_DISP = "attachment; filename*=UTF-8''";

    private final DocumentService documentService;
    private final UserRepository userRepository;
    private final PiiEncryptor pii;
    private final ru.example.ukep.service.FolderService folderService;

    public DocumentController(DocumentService documentService, UserRepository userRepository,
                              PiiEncryptor pii,
                              ru.example.ukep.service.FolderService folderService) {
        this.documentService = documentService;
        this.userRepository = userRepository;
        this.pii = pii;
        this.folderService = folderService;
    }

    private User current(UserDetails p) {
        return userRepository.findByEmailHash(pii.hash(p.getUsername())).orElseThrow();
    }

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

    /**
     * Скачать ZIP: документ + подпись.
     * Имена уникальны на случай, если расширение документа совпадает.
     */
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

    /** Скачать конкретную подпись. */
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

    /** Удалить подпись (если она своя). */
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
}
