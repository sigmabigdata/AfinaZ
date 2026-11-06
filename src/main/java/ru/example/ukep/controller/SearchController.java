package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Глобальный поиск для Command Palette (⌘K).
 *
 * Ищет: страницы, действия, пользователей (админ), документы.
 * originalName зашифровано — поиск по нему невозможен, но id и ФИО владельца работают.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final PiiEncryptor pii;

    public SearchController(UserRepository userRepository,
                            DocumentRepository documentRepository,
                            PiiEncryptor pii) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.pii = pii;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(@RequestParam(value = "q", required = false) String q,
                                                       @RequestParam(defaultValue = "10") int limit,
                                                       @AuthenticationPrincipal UserDetails p) {
        try {
            return doSearch(q, limit, p);
        } catch (Exception e) {
            log.error("Search failed: q='{}', limit={}, user={}",
                    q, limit, p != null ? p.getUsername() : "anonymous", e);
            java.util.Map<String, Object> err = new java.util.LinkedHashMap<>();
            err.put("error", "Ошибка поиска: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            err.put("pages", java.util.List.of());
            err.put("actions", java.util.List.of());
            err.put("users", java.util.List.of());
            err.put("documents", java.util.List.of());
            return ResponseEntity.ok(err);
        }
    }

    private ResponseEntity<Map<String, Object>> doSearch(String q, int limit, UserDetails p) {
        if (p == null) return ResponseEntity.status(401).build();

        String query = q == null ? "" : q.trim().toLowerCase();
        int safeLimit = Math.min(Math.max(limit, 1), 30);

        User me = userRepository.findByEmailHash(pii.hash(p.getUsername())).orElse(null);
        if (me == null) {
            log.warn("Search: user not found for '{}' (hash {})",
                    p.getUsername(), pii.hash(p.getUsername()));
            return ResponseEntity.status(403).build();
        }
        boolean isAdmin = me.getRole() == ru.example.ukep.entity.Role.ROLE_ADMIN;

        // Если запрос пустой — возвращаем только статические секции
        if (query.isEmpty()) {
            return ResponseEntity.ok(safeMap(
                    staticPages(isAdmin),
                    staticActions(isAdmin),
                    List.of(),
                    List.of()
            ));
        }

        // 1. Страницы
        List<Map<String, Object>> pages = staticPages(isAdmin).stream()
                .filter(m -> matches((String) m.get("label"), query))
                .toList();

        // 2. Действия
        List<Map<String, Object>> actions = staticActions(isAdmin).stream()
                .filter(m -> matches((String) m.get("label"), query))
                .toList();

        // 3. Пользователи (только админ)
        List<Map<String, Object>> users = new ArrayList<>();
        if (isAdmin) {
            String hash = pii.hash(query);
            userRepository.findAllByOrderByCreatedAtDesc().stream()
                    .filter(u -> u.getFullName() != null
                            && u.getFullName().toLowerCase().contains(query)
                            || hash.equals(u.getEmailHash()))
                    .limit(safeLimit)
                    .forEach(u -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", u.getId());
                        m.put("label", u.getFullName());
                        m.put("sub", u.getEmail());
                        m.put("url", "/admin/users/" + u.getId());
                        users.add(m);
                    });
        }

        // 4. Документы — по id (exact) или по ФИО владельца
        List<Map<String, Object>> documents = new ArrayList<>();
        try {
            // exact по id
            if (query.matches("\\d+")) {
                Long docId = Long.parseLong(query);
                documentRepository.findByIdWithOwner(docId).ifPresent(d -> {
                    if (isAdmin || d.getOwner().getId().equals(me.getId())) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", d.getId());
                        m.put("label", d.getOriginalName());
                        m.put("sub", "Документ #" + d.getId() + " · " + d.getOwner().getFullName());
                        m.put("url", (isAdmin ? "/admin/users/" + d.getOwner().getId()
                                : "/dashboard"));
                        documents.add(m);
                    }
                });
            }
            // fuzzy по ФИО владельца
            List<Document> allDocs = isAdmin
                    ? documentRepository.findAllWithOwner()
                    : documentRepository.findAllByOwnerOrderByUploadedAtDesc(me);
            for (Document d : allDocs) {
                if (documents.size() >= safeLimit) break;
                if (documents.stream().anyMatch(m -> m.get("id").equals(d.getId()))) continue;
                if (d.getOwner() != null && d.getOwner().getFullName() != null
                        && d.getOwner().getFullName().toLowerCase().contains(query)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("label", d.getOriginalName());
                    m.put("sub", "Документ #" + d.getId() + " · " + d.getOwner().getFullName());
                    m.put("url", isAdmin ? "/admin/users/" + d.getOwner().getId() : "/dashboard");
                    documents.add(m);
                }
            }
        } catch (Exception e) {
            log.debug("search documents failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(safeMap(pages, actions, users, documents));
    }

    private static boolean matches(String label, String q) {
        return label != null && label.toLowerCase().contains(q);
    }

    private static List<Map<String, Object>> staticPages(boolean isAdmin) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (isAdmin) {
            list.add(page("Дашборд", "📊", "/admin", "g d"));
            list.add(page("Клиенты", "👥", "/admin/users", "g u"));
            list.add(page("Шаблоны документов", "📄", "/admin/templates", "g t"));
            list.add(page("Мониторинг", "📈", "/admin/monitor", "g m"));
            list.add(page("CRL", "🔐", "/admin/crl", null));
            list.add(page("Аудит", "📋", "/admin/audit", "g a"));
            list.add(page("Ключи шифрования", "🔑", "/admin/keys", null));
            list.add(page("Настройки", "⚙", "/admin/settings", "g s"));
            list.add(page("Доверенности", "🏛", "/admin/poa", "g p"));
            list.add(page("Система", "💻", "/admin/system", null));
        } else {
            list.add(page("Мои документы", "📁", "/dashboard", "g d"));
        }
        return list;
    }

    private static List<Map<String, Object>> staticActions(boolean isAdmin) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (isAdmin) {
            list.add(action("Создать клиента", "➕", "/admin/users/new"));
            list.add(action("Создать шаблон", "✨", "/admin/templates/new"));
            list.add(action("Создать бэкап", "💾", "/admin/system"));
            list.add(action("Выйти", "🚪", "/admin/logout"));
        } else {
            list.add(action("Создать раздел", "➕", "/dashboard"));
            list.add(action("Документ из шаблона", "✨", "/dashboard"));
            list.add(action("Выйти", "🚪", "/logout"));
        }
        return list;
    }

    private static Map<String, Object> page(String label, String icon, String url, String hotkey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "page");
        m.put("label", label);
        m.put("icon", icon);
        m.put("url", url);
        m.put("hotkey", hotkey);
        return m;
    }

    private static Map<String, Object> action(String label, String icon, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "action");
        m.put("label", label);
        m.put("icon", icon);
        m.put("url", url);
        return m;
    }

    /** Безопасный Map, принимает null (в отличие от Map.of). */
    private static java.util.Map<String, Object> safeMap(
            java.util.List<?> pages,
            java.util.List<?> actions,
            java.util.List<?> users,
            java.util.List<?> documents) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("pages", pages == null ? List.of() : pages);
        m.put("actions", actions == null ? List.of() : actions);
        m.put("users", users == null ? List.of() : users);
        m.put("documents", documents == null ? List.of() : documents);
        return m;
    }
}
