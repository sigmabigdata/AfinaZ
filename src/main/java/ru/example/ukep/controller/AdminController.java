package ru.example.ukep.controller;

import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.EmailService;
import ru.example.ukep.service.UserService;
import ru.example.ukep.util.FileNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final String UNKNOWN = "unknown";
    private static final String USER_NOT_FOUND = "Пользователь не найден";
    private static final String DOC_NOT_FOUND = "Документ не найден";
    private static final String REDIR_USERS = "redirect:/admin/users/";
    private static final String CONTENT_DISP = "attachment; filename*=UTF-8''";

    private final UserRepository userRepository;
    private final PiiEncryptor pii;
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final UserService userService;
    private final AuditService audit;
    private final EmailService emailService;

    @org.springframework.beans.factory.annotation.Value("${app.base-url}")
    private String baseUrl;

    private final ru.example.ukep.service.FolderService folderService;
    private final ru.example.ukep.service.AdminCredentialsFileService adminFile;

    @SuppressWarnings("java:S107")  // 8 зависимостей — рефакторинг в builder отложен
    public AdminController(UserRepository userRepository,
                           DocumentRepository documentRepository,
                           DocumentService documentService,
                           UserService userService,
                           EmailService emailService,
                           PiiEncryptor pii,
                           AuditService audit,
                           ru.example.ukep.service.FolderService folderService,
                           ru.example.ukep.service.AdminCredentialsFileService adminFile) {
        this.userRepository = userRepository;
        this.pii = pii;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.userService = userService;
        this.emailService = emailService;
        this.audit = audit;
        this.folderService = folderService;
        this.adminFile = adminFile;
    }

    // ==================== ДАШБОРД ====================
    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("usersTotal", userRepository.count());
        model.addAttribute("usersActive", userRepository.countByEnabledTrue());
        model.addAttribute("usersPending", userRepository.countByEnabledFalse());
        model.addAttribute("docsTotal", documentRepository.count());
        model.addAttribute("docsSigned", documentRepository.countBySignedTrue());
        model.addAttribute("docsUnsigned", documentRepository.countBySignedFalse());
        model.addAttribute("recentDocs",
                documentRepository.findAllWithOwner().stream().limit(5).toList());
        return "admin";
    }

    // ==================== КЛИЕНТЫ ====================
    @GetMapping("/users")
    public String users(@RequestParam(required = false) String status,
                        @RequestParam(required = false) String q,
                        Model model) {
        Boolean ef = null;
        if ("active".equalsIgnoreCase(status)) ef = true;
        else if ("pending".equalsIgnoreCase(status)) ef = false;

        String qNorm = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();
        String qHash = (qNorm == null) ? null : pii.hash(qNorm);

        // Фильтрация в памяти (SQL-поиск с шифрованием PII ненадёжен)
        final Boolean efFinal = ef;
        final String qFinal = qNorm;
        final String hashFinal = qHash;

        java.util.List<User> users = userRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .filter(u -> efFinal == null || u.isEnabled() == efFinal)
                .filter(u -> qFinal == null
                        || (u.getFullName() != null
                            && u.getFullName().toLowerCase().contains(qFinal))
                        || (hashFinal != null && hashFinal.equals(u.getEmailHash())))
                .toList();

        model.addAttribute("users", users);
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("q", qNorm == null ? "" : qNorm);
        return "admin-users";
    }

    @GetMapping("/users/{id}")
    public String userCard(@PathVariable Long id,
                           java.security.Principal principal,
                           Model model) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        User admin = userRepository.findByEmailHash(pii.hash(principal.getName()))
                .orElse(null);

        var folders = folderService.listForOwner(user);
        var byFolder = new java.util.LinkedHashMap<Long, java.util.List<Document>>();
        for (var f : folders) {
            byFolder.put(f.getId(),
                    documentRepository.findAllByFolderOrderByUploadedAtDesc(f));
        }

        model.addAttribute("user", user);
        model.addAttribute("currentAdminId", admin != null ? admin.getId() : null);
        model.addAttribute("folders", folders);
        model.addAttribute("documentsByFolder", byFolder);
        model.addAttribute("docsTotal", documentRepository.countByOwner(user));
        model.addAttribute("docsSigned", documentRepository.countByOwnerAndSignedTrue(user));
        // SNILS для редактирования (из admins.env)
        if (user.getRole() == Role.ROLE_ADMIN || user.getRole() == Role.ROLE_EMPLOYEE) {
            adminFile.readAll().stream()
                    .filter(r -> r.cn().equalsIgnoreCase(user.getFullName()))
                    .findFirst()
                    .ifPresent(r -> model.addAttribute("snils", r.snils()));
        }
        return "admin-user-card";
    }

    @PostMapping("/users/{id}/send-login-link")
    public String sendLoginLink(@PathVariable Long id, RedirectAttributes ra) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        if (u.getRole() == Role.ROLE_ADMIN) {
            ra.addFlashAttribute("err", "Администратор входит по сертификату");
            return REDIR_USERS + id;
        }
        if (!u.isEnabled()) {
            ra.addFlashAttribute("err", "Клиент заблокирован");
            return REDIR_USERS + id;
        }
        String link = userService.generateLoginLink(u.getEmail(), baseUrl);
        if (link == null) {
            ra.addFlashAttribute("err", "Не удалось создать ссылку");
        } else {
            emailService.sendLoginLink(u.getEmail(), link, u.getFullName());
            ra.addFlashAttribute("ok", "Ссылка отправлена на " + u.getEmail());
        }
        return REDIR_USERS + id;
    }

    // ==================== CRUD ====================
    @GetMapping("/users/new")
    public String newUserForm(Model model) {
        model.addAttribute("mode", "create");
        return "admin-user-edit";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam(required = false) String email,
                             @RequestParam(required = false) String fullName,
                             @RequestParam(required = false) String phone,
                             @RequestParam(defaultValue = "false") boolean enabled,
                             @RequestParam(required = false) String role,
                             @RequestParam(required = false) String snils,
                             java.security.Principal auth,
                             RedirectAttributes ra) {
        try {
            Role desiredRole = resolveDesiredRole(role, auth);
            String who = auth != null ? auth.getName() : UNKNOWN;

            // Сотрудник/Администратор — создаём через admins.env + upsertAdminByCert
            if (desiredRole == Role.ROLE_EMPLOYEE || desiredRole == Role.ROLE_ADMIN) {
                if (fullName == null || fullName.isBlank()) {
                    throw new IllegalArgumentException("ФИО обязательно для сотрудника/администратора");
                }
                if (snils == null || snils.isBlank()) {
                    throw new IllegalArgumentException("СНИЛС обязателен для сотрудника/администратора");
                }
                User u = createStaffUser(fullName.trim(), snils.trim(), desiredRole);
                audit.userCreate(who, u.getId(), u.getEmail());
                ra.addFlashAttribute("ok", "Сотрудник создан: " + fullName + " (" + desiredRole.name() + ")");
                return REDIR_USERS + u.getId();
            }

            // Клиент — обычный флоу
            User u = userService.adminCreate(email, fullName, phone, desiredRole, enabled);
            audit.userCreate(who, u.getId(), u.getEmail());
            ra.addFlashAttribute("ok", "Клиент создан: " + u.getEmail());
            return REDIR_USERS + u.getId();
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return "redirect:/admin/users/new";
        }
    }

    /** Создаёт сотрудника: пишет admins.env и создаёт/обновляет User. */
    private User createStaffUser(String fullName, String snils, Role role) {
        var existing = adminFile.readAll().stream()
                .filter(r -> r.cn().equalsIgnoreCase(fullName))
                .toList();
        var list = new java.util.ArrayList<>(adminFile.readAll());
        list.removeIf(r -> r.cn().equalsIgnoreCase(fullName));
        list.add(new ru.example.ukep.service.AdminCredentialsFileService.AdminRecord(
                fullName, snils, role));
        adminFile.writeAll(list);
        return userService.upsertAdminByCert(fullName, snils, role);
    }

    @GetMapping("/users/{id}/edit")
    public String editUserForm(@PathVariable Long id, Model model) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        model.addAttribute("mode", "edit");
        model.addAttribute("user", user);
        if (user.getRole() == Role.ROLE_ADMIN || user.getRole() == Role.ROLE_EMPLOYEE) {
            adminFile.readAll().stream()
                    .filter(r -> r.cn().equalsIgnoreCase(user.getFullName()))
                    .findFirst()
                    .ifPresent(r -> model.addAttribute("snils", r.snils()));
        }
        return "admin-user-edit";
    }

    @PostMapping("/users/{id}")
    public String updateUser(@PathVariable Long id,
                             @RequestParam(required = false) String email,
                             @RequestParam(required = false) String fullName,
                             @RequestParam(required = false) String phone,
                             @RequestParam(defaultValue = "false") boolean enabled,
                             @RequestParam(required = false) String role,
                             @RequestParam(required = false) String snils,
                             java.security.Principal auth,
                             RedirectAttributes ra) {
        try {
            Role desiredRole = resolveDesiredRole(role, auth);
            String who = auth != null ? auth.getName() : UNKNOWN;

            // Сотрудник/Администратор — синхронизируем admins.env
            if (desiredRole == Role.ROLE_EMPLOYEE || desiredRole == Role.ROLE_ADMIN) {
                if (fullName == null || fullName.isBlank() || snils == null || snils.isBlank()) {
                    throw new IllegalArgumentException("ФИО и СНИЛС обязательны для этой роли");
                }
                User existing = userRepository.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
                // удаляем из admins.env старое имя (если было), пишем новое
                var list = new java.util.ArrayList<>(adminFile.readAll());
                list.removeIf(r -> r.cn().equalsIgnoreCase(existing.getFullName()));
                list.removeIf(r -> r.cn().equalsIgnoreCase(fullName));
                list.add(new ru.example.ukep.service.AdminCredentialsFileService.AdminRecord(
                        fullName, snils, desiredRole));
                adminFile.writeAll(list);

                User u = userService.upsertAdminByCert(fullName, snils, desiredRole);
                // enabled/phone всё же обновим
                userService.adminUpdate(u.getId(),
                        u.getEmail(), fullName, phone, desiredRole, enabled);
                audit.userUpdate(who, u.getId(), u.getEmail());
                ra.addFlashAttribute("ok", "Сотрудник сохранён, admins.env обновлён");
                return REDIR_USERS + u.getId();
            }

            userService.adminUpdate(id, email, fullName, phone, desiredRole, enabled);
            audit.userUpdate(who, id, email);
            ra.addFlashAttribute("ok", "Сохранено");
            return REDIR_USERS + id;
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return REDIR_USERS + id + "/edit";
        }
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable Long id,
                             java.security.Principal principal,
                             RedirectAttributes ra) {
        try {
            userService.adminDelete(id, principal.getName());
            audit.userDelete(principal.getName(), id, "user#" + id);
            ra.addFlashAttribute("ok", "Пользователь удалён");
            return "redirect:/admin/users";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return REDIR_USERS + id;
        }
    }

    @PostMapping("/users/{id}/toggle")
    public String toggleUser(@PathVariable Long id,
                             @RequestParam(required = false) String back,
                             RedirectAttributes ra) {
        try {
            userService.adminToggle(id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return "card".equals(back)
                ? REDIR_USERS + id
                : "redirect:/admin/users";
    }

    // ==================== ДОКУМЕНТЫ ====================
    @GetMapping("/documents/{id}/view")
    public ResponseEntity<Resource> viewDocument(@PathVariable Long id) throws IOException {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(DOC_NOT_FOUND));
        Resource r = documentService.loadAsResource(doc);
        MediaType mt = doc.getContentType() != null
                ? MediaType.parseMediaType(doc.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.inline(doc.getOriginalName()))
                .body(r);
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id) throws IOException {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(DOC_NOT_FOUND));
        Resource r = documentService.loadAsResource(doc);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        FileNames.attachment(doc.getOriginalName()))
                .body(r);
    }

    @GetMapping("/documents/{id}/signature/download")
    public ResponseEntity<byte[]> downloadSignature(@PathVariable Long id) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(DOC_NOT_FOUND));
        if (!doc.isSigned() || doc.getSignatureBase64() == null) return ResponseEntity.notFound().build();
        byte[] sig = Base64.getDecoder().decode(doc.getSignatureBase64().replaceAll("\\s+", ""));
        String n = doc.getOriginalName() + ".sig";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, FileNames.attachment(n))
                .body(sig);
    }

    @GetMapping("/users/{id}/signatures.zip")
    public ResponseEntity<byte[]> downloadAllSignatures(@PathVariable Long id) throws IOException {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));

        List<Document> docs = documentRepository.findAllByOwnerOrderByUploadedAtDesc(user);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Set<String> usedNames = new HashSet<>();
        int total = 0;

        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (Document d : docs) {
                List<DocumentSignature> sigs = documentService.listSignatures(d.getId());
                if (sigs.isEmpty()) continue;

                String base = FileNames.sanitizeZipEntry(stripExtension(d.getOriginalName()));
                for (int i = 0; i < sigs.size(); i++) {
                    DocumentSignature sig = sigs.get(i);
                    byte[] bytes = Base64.getDecoder().decode(sig.getSignatureBase64().replaceAll("\s+", ""));
                    String entry = uniqueEntryName(base + "_sig_" + (i + 1) + ".sig", usedNames);
                    zip.putNextEntry(new ZipEntry(entry));
                    zip.write(bytes);
                    zip.closeEntry();
                    total++;
                }
            }
        }

        if (total == 0) return ResponseEntity.notFound().build();

        String zn = "signatures_user_" + user.getId() + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, FileNames.attachment(zn))
                .body(baos.toByteArray());
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

    // ==================== ДОКУМЕНТЫ (загрузка/удаление админом) ====================

    /** Страница раздела клиента — документы этого раздела. */
    @GetMapping("/users/{userId}/folders/{folderId}")
    public String userFolder(@PathVariable Long userId,
                             @PathVariable Long folderId,
                             java.security.Principal principal,
                             Model model) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        var folder = folderService.getById(folderId);
        if (!folder.getOwner().getId().equals(userId)) {
            throw new IllegalArgumentException("Раздел не принадлежит этому клиенту");
        }
        User admin = userRepository.findByEmailHash(pii.hash(principal.getName()))
                .orElse(null);

        List<Document> docs = documentRepository.findAllByFolderOrderByUploadedAtDesc(folder);
        long signed = docs.stream().filter(Document::isSigned).count();

        model.addAttribute("user", user);
        model.addAttribute("folder", folder);
        model.addAttribute("documents", docs);
        model.addAttribute("total", docs.size());
        model.addAttribute("signed", signed);
        model.addAttribute("unsigned", docs.size() - signed);
        model.addAttribute("currentAdminId", admin != null ? admin.getId() : null);
        return "admin-user-folder";
    }

    /** Загрузить НЕСКОЛЬКО документов в раздел клиента за один раз. */
    @PostMapping("/users/{userId}/folders/{folderId}/upload")
    public String uploadForUser(@PathVariable Long userId,
                                @PathVariable Long folderId,
                                @RequestParam("files") List<MultipartFile> files,
                                java.security.Principal principal,
                                RedirectAttributes ra) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        var folder = folderService.getOwned(folderId, user);
        String who = principal != null ? principal.getName() : UNKNOWN;

        int okCount = 0;
        int failCount = 0;
        StringBuilder errs = new StringBuilder();
        List<String> okNames = new java.util.ArrayList<>();

        if (files != null) {
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                try {
                    Document doc = documentService.upload(f, user, folder);
                    audit.documentUpload(who, doc.getId(), doc.getOriginalName());
                    okCount++;
                    okNames.add(doc.getOriginalName());
                } catch (Exception e) {
                    failCount++;
                    String n = f.getOriginalFilename() != null ? f.getOriginalFilename() : "?";
                    errs.append(n).append(": ").append(e.getMessage()).append("; ");
                }
            }
        }

        if (okCount > 0) {
            String msg = "Загружено файлов: " + okCount;
            if (okNames.size() <= 3) {
                msg += " — " + String.join(", ", okNames);
            }
            if (failCount > 0) msg += " (ошибок: " + failCount + ")";
            ra.addFlashAttribute("ok", msg);
        }
        if (failCount > 0) {
            ra.addFlashAttribute("err", "Не удалось загрузить: " + errs);
        }
        if (okCount == 0 && failCount == 0) {
            ra.addFlashAttribute("err", "Не выбрано ни одного файла");
        }

        return "redirect:/admin/users/" + userId + "/folders/" + folderId;
    }

    /** Удалить документ клиента. */
    @PostMapping("/documents/{id}/delete")
    public String deleteDocument(@PathVariable Long id,
                                 java.security.Principal auth,
                                 RedirectAttributes ra) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(DOC_NOT_FOUND));
        Long ownerId = doc.getOwner().getId();
        Long folderId = (doc.getFolder() != null) ? doc.getFolder().getId() : null;
        try {
            String name = doc.getOriginalName();
            boolean wasSigned = doc.isSigned();
            documentService.deleteAsAdmin(id);
            audit.documentDelete(auth != null ? auth.getName() : UNKNOWN,
                    id, name, wasSigned);
            ra.addFlashAttribute("ok", "Документ удалён");
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Не удалось удалить: " + e.getMessage());
        }
        return folderId != null
                ? "redirect:/admin/users/" + ownerId + "/folders/" + folderId
                : REDIR_USERS + ownerId;
    }

    /** Скачать конкретную подпись (админ). */
    @GetMapping("/documents/{id}/signatures/{sigId}/download")
    public ResponseEntity<byte[]> downloadSignature(@PathVariable Long id,
                                                    @PathVariable Long sigId) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(DOC_NOT_FOUND));
        DocumentSignature sig = documentService.getSignature(sigId);
        if (!sig.getDocument().getId().equals(doc.getId())) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Base64.getDecoder().decode(sig.getSignatureBase64().replaceAll("\\s+", ""));
        String fileName = doc.getOriginalName() + ".sig";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, FileNames.attachment(fileName))
                .body(bytes);
    }

    /** Удалить подпись (админ). */
    @PostMapping("/documents/{id}/signatures/{sigId}/delete")
    public String deleteSignature(@PathVariable Long id,
                                  @PathVariable Long sigId,
                                  RedirectAttributes ra) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(DOC_NOT_FOUND));
        DocumentSignature sig = documentService.getSignature(sigId);
        if (!sig.getDocument().getId().equals(doc.getId())) {
            ra.addFlashAttribute("err", "Подпись не относится к документу");
            return REDIR_USERS + doc.getOwner().getId();
        }
        Long ownerId = doc.getOwner().getId();
        Long folderId = (doc.getFolder() != null) ? doc.getFolder().getId() : null;
        try {
            documentService.deleteSignature(sigId);
            ra.addFlashAttribute("ok", "Подпись удалена");
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Не удалось удалить: " + e.getMessage());
        }
        return folderId != null
                ? "redirect:/admin/users/" + ownerId + "/folders/" + folderId
                : REDIR_USERS + ownerId;
    }
    /** Какую роль может выставить текущий пользователь. */
    private Role resolveDesiredRole(String requested, java.security.Principal auth) {
        User me = userRepository.findByEmailHash(pii.hash(auth.getName())).orElse(null);
        boolean isFullAdmin = me != null && me.getRole() == Role.ROLE_ADMIN;

        if (requested == null || requested.isBlank()) {
            return Role.ROLE_USER;
        }
        if (!isFullAdmin) {
            // Сотрудник может создавать только клиентов
            return Role.ROLE_USER;
        }
        return switch (requested) {
            case "ROLE_ADMIN" -> Role.ROLE_ADMIN;
            case "ROLE_EMPLOYEE" -> Role.ROLE_EMPLOYEE;
            default -> Role.ROLE_USER;
        };
    }

    /** Массовые операции: block / unblock / delete. */
    @PostMapping("/users/bulk")
    public String bulkUsers(@RequestParam("action") String action,
                            @RequestParam(value = "ids", required = false) java.util.List<Long> ids,
                            java.security.Principal auth,
                            RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("err", "Не выбрано ни одного пользователя");
            return "redirect:/admin/users";
        }
        String who = auth != null ? auth.getName() : UNKNOWN;
        int ok = 0, fail = 0;
        for (Long id : ids) {
            try {
                User u = userRepository.findById(id).orElseThrow();
                if (u.getRole() == Role.ROLE_ADMIN) {
                    fail++; continue; // админа не трогаем
                }
                switch (action) {
                    case "block" -> {
                        if (!u.isEnabled()) { fail++; continue; }
                        if (u.getRole() == Role.ROLE_EMPLOYEE) {
                            u.setEnabled(false);
                            userRepository.save(u);
                        } else {
                            userService.adminToggle(id);
                        }
                        ok++;
                    }
                    case "unblock" -> {
                        if (u.isEnabled()) { fail++; continue; }
                        if (u.getRole() == Role.ROLE_EMPLOYEE) {
                            u.setEnabled(true);
                            userRepository.save(u);
                        } else {
                            userService.adminToggle(id);
                        }
                        ok++;
                    }
                    case "delete" -> {
                        userService.adminDelete(id, who);
                        audit.userDelete(who, id, "bulk");
                        ok++;
                    }
                    default -> throw new IllegalArgumentException("Неизвестное действие");
                }
            } catch (Exception e) {
                fail++;
            }
        }
        ra.addFlashAttribute("ok", "Готово: " + ok + " успешно, " + fail + " пропущено");
        return "redirect:/admin/users";
    }

}
