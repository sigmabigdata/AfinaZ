package ru.example.ukep.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.config.SecurityConfig;
import ru.example.ukep.entity.User;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.EmailService;
import ru.example.ukep.service.UserService;

import java.util.List;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String REDIR_DASHBOARD = "redirect:/dashboard";

    private final UserService userService;
    private final EmailService emailService;
    private final AuditService audit;
    private final HttpSessionSecurityContextRepository userContextRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    public AuthController(UserService userService,
                          EmailService emailService,
                          AuditService audit,
                          @Qualifier("userContextRepository")
                          HttpSessionSecurityContextRepository userContextRepository) {
        this.userService = userService;
        this.emailService = emailService;
        this.audit = audit;
        this.userContextRepository = userContextRepository;
    }

    @GetMapping("/")
    public String root(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return "redirect:/login";
        return REDIR_DASHBOARD;
    }

    @GetMapping("/login")
    public String loginPage(Authentication auth) {
        if (auth != null && auth.isAuthenticated()) return REDIR_DASHBOARD;
        return "login";
    }

    @PostMapping("/login")
    public String requestLoginLink(@RequestParam("email") String email, Model model) {
        String link = userService.generateLoginLink(email, baseUrl);
        if (link != null) {
            emailService.sendLoginLink(email, link);
        } else {
            log.info("Login link requested for unknown/blocked email: {}", email);
        }
        model.addAttribute("email", email);
        return "login-sent";
    }

    @GetMapping("/login/confirm")
    public String confirmLogin(@RequestParam("token") String token,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               Model model) {
        log.info("confirmLogin: token received len={}", token == null ? 0 : token.length());
        User user = userService.consumeLoginToken(token);
        if (user == null) {
            log.warn("confirmLogin: token invalid/expired");
            audit.loginFail("token:" + (token != null && token.length() > 8
                    ? token.substring(0, 8) + "..." : "?"), "Недействительный/истёкший токен");
            model.addAttribute("error", "Ссылка недействительна или истекла");
            return "login";
        }
        log.info("confirmLogin: user {} logged in", user.getEmail());

        var details = org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password("{noop}magic")
                .authorities(List.of(new SimpleGrantedAuthority(user.getRole().name())))
                .build();

        var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        userContextRepository.saveContext(ctx, request, response);
        audit.loginSuccess(user.getEmail(), user.getRole().name());

        return REDIR_DASHBOARD;
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SecurityConfig.USER_CTX_KEY);
        }
        SecurityContextHolder.clearContext();
        return "redirect:/login?logout";
    }
}
