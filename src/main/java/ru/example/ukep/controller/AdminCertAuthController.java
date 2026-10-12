package ru.example.ukep.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.dto.CertLoginRequest;
import ru.example.ukep.entity.User;
import ru.example.ukep.service.AdminCredentialsFileService;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.SignatureVerifier;
import ru.example.ukep.service.UserService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class AdminCertAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminCertAuthController.class);
    private static final String K_ERROR = "error";

    private final AdminCredentialsFileService adminFile;
    private final UserService userService;
    private final SignatureVerifier signatureVerifier;
    private final AuditService audit;
    private final HttpSessionSecurityContextRepository adminContextRepository;

    @Value("${app.crl-path:./kontur-q-2025.crl}")
    private String crlPath;

    /** Одноразовые челленджи: значение → время создания. Хранятся 5 минут. */
    private final Map<String, Long> challenges = new ConcurrentHashMap<>();

    public AdminCertAuthController(AdminCredentialsFileService adminFile,
                                   UserService userService,
                                   SignatureVerifier signatureVerifier,
                                   AuditService audit,
                                   @Qualifier("adminContextRepository")
                                   HttpSessionSecurityContextRepository adminContextRepository) {
        this.adminFile = adminFile;
        this.userService = userService;
        this.signatureVerifier = signatureVerifier;
        this.audit = audit;
        this.adminContextRepository = adminContextRepository;
    }

    @GetMapping("/admin/login")
    public String loginPage() {
        return "admin-login";
    }

    /** Генерирует одноразовый челлендж. Возвращает только его значение. */
    @GetMapping("/admin/challenge")
    @ResponseBody
    public ResponseEntity<Map<String, String>> getChallenge() {
        cleanOldChallenges();
        String value = UUID.randomUUID().toString().replace("-", "")
                + "-" + System.currentTimeMillis();
        challenges.put(value, System.currentTimeMillis());
        return ResponseEntity.ok(Map.of("challenge", value));
    }

    @PostMapping("/admin/cert-login")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> certLogin(@RequestBody CertLoginRequest req,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        try {
            // 1. Проверяем и потребляем челлендж
            ResponseEntity<Map<String, Object>> challengeError = checkChallenge(req.getChallenge());
            if (challengeError != null) {
                return challengeError;
            }

            // 2. Проверяем CN + СНИЛС в admins.env
            var admin = adminFile.find(req.getCn(), req.getSnils());
            if (admin.isEmpty()) {
                if (log.isWarnEnabled()) {
                    log.warn("Cert login rejected: CN={}, SNILS={}",
                            req.getCn(), maskSnils(req.getSnils()));
                }
                return ResponseEntity.status(403).body(
                        Map.of(K_ERROR, "Пользователь не является администратором"));
            }
            if (log.isInfoEnabled()) {
                log.info("Admin CN/SNILS matched: {} / {}",
                        admin.get().cn(), maskSnils(admin.get().snils()));
            }

            // 3. Криптографическая проверка подписи челленджа
            byte[] challengeBytes = req.getChallenge().getBytes(StandardCharsets.UTF_8);
            Map<String, Object> result =
                    signatureVerifier.verifyDetached(challengeBytes, req.getSignatureBase64());

            // 4. Сверяем CN в подписи с CN из admins.env
            String signerCn = String.valueOf(
                    result.getOrDefault("signerSubject", "")).trim();
            if (signerCn.isEmpty() || !signerCn.equalsIgnoreCase(admin.get().cn())) {
                if (log.isWarnEnabled()) {
                    log.warn("CN mismatch: signer='{}', expected='{}'",
                            signerCn, admin.get().cn());
                }
                return ResponseEntity.status(403).body(
                        Map.of(K_ERROR, "CN в сертификате не совпадает с CN в admins.env"));
            }

            // 5. Создаём/находим запись в БД и логиним
            User user = userService.upsertAdminByCert(admin.get().cn(), admin.get().snils());

            var details = org.springframework.security.core.userdetails.User
                    .withUsername(user.getEmail())
                    .password("{noop}cert")
                    .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .build();

            var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            adminContextRepository.saveContext(ctx, request, response);

            if (log.isInfoEnabled()) {
                log.info("Admin logged in via cert: {}", admin.get().cn());
            }
            audit.adminLoginSuccess(admin.get().cn());
            return ResponseEntity.ok(Map.of("success", true, "redirect", "/admin"));
        } catch (Exception e) {
            log.error("Cert login failed", e);
            audit.adminLoginFail("unknown", e.getMessage());
            return ResponseEntity.status(403).body(
                    Map.of(K_ERROR, "Не удалось войти: " + e.getMessage()));
        }
    }

    /** Logout админа — чистит только ADMIN_SECURITY_CONTEXT. */
    @PostMapping("/admin/logout")
    public String logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ru.example.ukep.config.SecurityConfig.ADMIN_CTX_KEY);
        }
        SecurityContextHolder.clearContext();
        return "redirect:/admin/login?logout";
    }

    /**
     * Проверяет и потребляет одноразовый челлендж.
     * Возвращает badRequest-ответ при ошибке или null, если челлендж валиден.
     */
    private ResponseEntity<Map<String, Object>> checkChallenge(String challenge) {
        if (challenge == null || challenge.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(K_ERROR, "Челлендж не передан"));
        }
        Long createdAt = challenges.remove(challenge);
        if (createdAt != null && System.currentTimeMillis() - createdAt <= 300_000) {
            return null;
        }
        if (log.isWarnEnabled()) {
            log.warn("Challenge invalid or expired (present)");
        }
        return ResponseEntity.badRequest().body(Map.of(K_ERROR, "Челлендж недействителен"));
    }

    private void cleanOldChallenges() {
        long now = System.currentTimeMillis();
        challenges.entrySet().removeIf(e -> now - e.getValue() > 600_000);
    }

    private String maskSnils(String snils) {
        if (snils == null || snils.length() < 4) return "***";
        return "***" + snils.substring(snils.length() - 4);
    }
}
