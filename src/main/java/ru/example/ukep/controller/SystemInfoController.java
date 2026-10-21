package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.BackupService;
import ru.example.ukep.service.LogService;
import ru.example.ukep.service.SystemInfoService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Controller
@RequestMapping("/admin/system")
public class SystemInfoController {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoController.class);

    private static final String UNKNOWN = "unknown";
    private static final String REDIR = "redirect:/admin/system";

    private final SystemInfoService sys;
    private final BackupService backups;
    private final LogService logs;
    private final AuditService audit;
    private final ru.example.ukep.service.CleanupService cleanup;

    public SystemInfoController(SystemInfoService sys,
                                BackupService backups,
                                LogService logs,
                                AuditService audit,
                                ru.example.ukep.service.CleanupService cleanup) {
        this.sys = sys;
        this.backups = backups;
        this.logs = logs;
        this.audit = audit;
        this.cleanup = cleanup;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("uptime", formatDuration(sys.getJvmUptime().getSeconds()));
        model.addAttribute("memory", sys.getMemory());
        model.addAttribute("cpu", sys.getCpu());
        model.addAttribute("disk", sys.getDisk());
        model.addAttribute("db", sys.getDatabaseStats());
        model.addAttribute("storage", sys.getStorageSize());
        model.addAttribute("runtime", sys.getRuntimeInfo());
        model.addAttribute("backups", backups.list());
        model.addAttribute("checks", sys.runDiagnostics());
        return "admin-system";
    }

    // ==================== БЭКАПЫ ====================

    @PostMapping("/backups/create")
    public String createBackup(java.security.Principal auth, RedirectAttributes ra) {
        try {
            String name = backups.create();
            String who = auth != null ? auth.getName() : UNKNOWN;
            audit.backupCreate(who, name);
            ra.addFlashAttribute("ok", "Бэкап создан: " + name);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ra.addFlashAttribute("err", "Операция прервана");
        } catch (Exception e) {
            log.error("Backup create failed", e);
            ra.addFlashAttribute("err", "Ошибка создания бэкапа: " + e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/backups/{name}/delete")
    public String deleteBackup(@PathVariable String name, RedirectAttributes ra) {
        try {
            backups.delete(name);
            ra.addFlashAttribute("ok", "Бэкап удалён: " + name);
        } catch (Exception e) {
            log.error("Backup delete failed", e);
            ra.addFlashAttribute("err", "Ошибка удаления: " + e.getMessage());
        }
        return REDIR;
    }

    @PostMapping("/backups/{name}/restore")
    public RedirectView restoreBackup(@PathVariable String name,
                                      @RequestParam String confirm,
                                      java.security.Principal auth,
                                      RedirectAttributes ra) {
        if (!"RESTORE".equals(confirm)) {
            ra.addFlashAttribute("err", "Неверное подтверждение. Введите RESTORE.");
            return new RedirectView(REDIR);
        }
        try {
            String safety = backups.restore(name);
            String who = auth != null ? auth.getName() : UNKNOWN;
            audit.backupRestore(who, name);
            ra.addFlashAttribute("ok",
                    "БД восстановлена из " + name +
                    ". Safety-бэкап: " + safety +
                    ". Перезапустите приложение!");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ra.addFlashAttribute("err", "Операция прервана");
        } catch (Exception e) {
            log.error("Backup restore failed", e);
            ra.addFlashAttribute("err", "Ошибка восстановления: " + e.getMessage());
        }
        return new RedirectView(REDIR);
    }

    @GetMapping("/backups/{name}/download")
    public ResponseEntity<byte[]> downloadBackup(@PathVariable String name) {
        try {
            byte[] data = backups.read(name);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" +
                            URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    // ==================== ЛОГИ ====================

    @GetMapping("/logs")
    @ResponseBody
    public String logs(@RequestParam(defaultValue = "200") int lines) {
        int n = Math.min(Math.max(lines, 10), 2000);
        return String.join("\n", logs.tail(n));
    }

    // ==================== ПЕРЕЗАПУСК ====================

    /**
     * Перезапуск приложения.
     * Приложение завершает себя (System.exit), а Docker (restart: unless-stopped)
     * поднимает его заново с новым пулом соединений к БД.
     */
    @PostMapping("/restart")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> restart(java.security.Principal auth) {
        log.warn("Restart requested by admin");
        audit.appRestart(auth != null ? auth.getName() : UNKNOWN);
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            log.warn("Exiting for restart");
            System.exit(0);
        }, "restart-thread").start();
        return ResponseEntity.ok(Map.of("ok", true, "message", "Перезапуск через 1 сек"));
    }

    // ==================== ОЧИСТКА МУСОРА ====================

    @GetMapping("/cleanup/analyze")
    @ResponseBody
    public java.util.Map<String, Object> cleanupAnalyze() {
        var report = cleanup.analyze();
        var list = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var cat : report.categories()) {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("key", cat.key());
            m.put("title", cat.title());
            m.put("bytes", cat.bytes());
            m.put("sizePretty", ru.example.ukep.service.CleanupService.formatBytes(cat.bytes()));
            m.put("count", cat.count());
            m.put("note", cat.note());
            list.add(m);
        }
        return java.util.Map.of(
                "totalBytes", report.totalBytes(),
                "totalPretty", ru.example.ukep.service.CleanupService.formatBytes(report.totalBytes()),
                "categories", list);
    }

    @PostMapping("/cleanup/{type}")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> cleanupRun(
            @PathVariable String type,
            java.security.Principal auth) {
        String who = auth != null ? auth.getName() : UNKNOWN;
        try {
            ru.example.ukep.service.CleanupService.CleanResult r = switch (type) {
                case "logs"      -> cleanup.cleanLogs();
                case "crls"      -> cleanup.cleanCrls();
                case "backups"   -> cleanup.cleanBackups();
                case "orphans"   -> cleanup.cleanOrphanFiles();
                case "orphanvis" -> cleanup.cleanOrphanVisualizations();
                case "tmp"       -> cleanup.cleanTmp();
                case "apt"       -> cleanup.cleanAptCache();
                case "journal"   -> cleanup.cleanJournal();
                case "docker"    -> cleanup.cleanDocker();
                default -> throw new IllegalArgumentException("Неизвестная категория: " + type);
            };
            audit.event("CLEANUP_" + type.toUpperCase(), "SUCCESS", who,
                    "SYSTEM", type, null, r.message());
            log.info("cleanup[{}] by {}: {} ({} байт)", type, who, r.message(), r.freedBytes());
            return ResponseEntity.ok(java.util.Map.of(
                    "ok", true,
                    "category", r.category(),
                    "freedBytes", r.freedBytes(),
                    "freedPretty", ru.example.ukep.service.CleanupService.formatBytes(r.freedBytes()),
                    "deletedCount", r.deletedCount(),
                    "message", r.message()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("cleanup[{}] failed", type, e);
            audit.event("CLEANUP_" + type.toUpperCase(), "FAIL", who,
                    "SYSTEM", type, null, e.getMessage());
            return ResponseEntity.status(500).body(java.util.Map.of(
                    "ok", false, "error", e.getMessage()));
        }
    }

    private String formatDuration(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("д ");
        if (h > 0) sb.append(h).append("ч ");
        if (m > 0) sb.append(m).append("м ");
        sb.append(s).append("с");
        return sb.toString();
    }
}
