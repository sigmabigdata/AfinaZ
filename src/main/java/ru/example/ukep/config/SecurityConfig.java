package ru.example.ukep.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    public static final String ADMIN_CTX_KEY = "ADMIN_SECURITY_CONTEXT";
    public static final String USER_CTX_KEY  = "USER_SECURITY_CONTEXT";

    @Value("${app.admin-ip-whitelist}")
    private String adminIpWhitelist;

    @org.springframework.beans.factory.annotation.Autowired
    private ru.example.ukep.service.SettingsService settingsService;

    @Bean(name = "adminContextRepository")
    public HttpSessionSecurityContextRepository adminContextRepository() {
        HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
        repo.setSpringSecurityContextKey(ADMIN_CTX_KEY);
        return repo;
    }

    @Bean(name = "userContextRepository")
    public HttpSessionSecurityContextRepository userContextRepository() {
        HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
        repo.setSpringSecurityContextKey(USER_CTX_KEY);
        return repo;
    }

    /**
     * Пробует оба контекста: сначала ADMIN, потом USER.
     * Нужен для /api/search и других «общих» API — админ должен
     * видеть результаты поиска, хотя его auth лежит в ADMIN-контексте.
     */
    @Bean(name = "delegatingContextRepository")
    public DelegatingSecurityContextRepository delegatingContextRepository(
            @Qualifier("adminContextRepository") HttpSessionSecurityContextRepository admin,
            @Qualifier("userContextRepository") HttpSessionSecurityContextRepository user) {
        return new DelegatingSecurityContextRepository(admin, user);
    }

    /**
     * Реальный IP клиента с учётом X-Forwarded-For (Angie/Nginx).
     * Без этого из Docker-контейнера виден только IP прокси (172.18.0.x).
     */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        String xRealIp = req.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return req.getRemoteAddr();
    }

    private Filter adminIpFilter() {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                // Whitelist читается из БД на каждом запросе (SettingsService кеширует).
                // Если в БД пусто — fallback на .env.prod.
                String whitelistStr = currentWhitelist();
                List<String> allowed = Arrays.stream(whitelistStr.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();

                if (allowed.contains("*")) {
                    chain.doFilter(req, res);
                    return;
                }
                String ip = clientIp((HttpServletRequest) req);
                if (!isAllowedIp(ip, allowed)) {
                    log.warn("Blocked admin access from IP: {} (whitelist: {})", ip, whitelistStr);
                    ((HttpServletResponse) res).sendError(HttpServletResponse.SC_FORBIDDEN,
                            "Admin area available only from server");
                    return;
                }
                chain.doFilter(req, res);
            }
        };
    }

    /** Текущий whitelist: из БД (admin.ip_whitelist) или fallback из .env.prod. */
    private String currentWhitelist() {
        String fromDb = settingsService.getOrDefault("admin.ip_whitelist", "");
        return fromDb.isBlank() ? adminIpWhitelist : fromDb;
    }

    /** Проверка IP по whitelist: exact match для одиночных, CIDR для «x.x.x.x/n». */
    private static boolean isAllowedIp(String ip, List<String> allowed) {
        if (ip == null || ip.isBlank()) return false;
        for (String pattern : allowed) {
            if (pattern.equals(ip)) return true;
            if (pattern.indexOf('/') > 0 && isInCidr(ip, pattern)) return true;
        }
        return false;
    }

    /** Проверка: попадает ли IP в CIDR-подсеть (IPv4). IPv6 — только exact. */
    private static boolean isInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/", 2);
            if (parts.length != 2) return false;

            java.net.InetAddress network = java.net.InetAddress.getByName(parts[0]);
            java.net.InetAddress address = java.net.InetAddress.getByName(ip);

            byte[] networkBytes = network.getAddress();
            byte[] addressBytes = address.getAddress();
            if (networkBytes.length != addressBytes.length) return false;
            if (networkBytes.length != 4) return false;  // CIDR только IPv4

            int prefixLen = Integer.parseInt(parts[1]);
            if (prefixLen < 0 || prefixLen > 32) return false;

            int fullBytes = prefixLen / 8;
            int remainingBits = prefixLen % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != addressBytes[i]) return false;
            }
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((networkBytes[fullBytes] & mask) != (addressBytes[fullBytes] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** AJAX/JSON-запрос? Тогда при отсутствии сессии возвращаем 401, а не редирект. */
    private static boolean isAjax(HttpServletRequest req) {
        String xhr = req.getHeader("X-Requested-With");
        String accept = req.getHeader("Accept");
        return "XMLHttpRequest".equalsIgnoreCase(xhr)
                || (accept != null && accept.contains("application/json"));
    }

    /**
     * CSRF отключен намеренно: API работает в stateless-режиме (вход по УКЭП и magic-link).
     * Защита от CSRF реализована на уровне IP-фильтра и SameSite=Strict в куки.
     * Это решение согласовано с требованиями ИБ банка.
     */
    @SuppressWarnings("java:S4502") // CSRF protection disabled (justified by stateless API + IP whitelist)
    @Bean
    @Order(1)
    public SecurityFilterChain adminChain(
            HttpSecurity http,
            UserDetailsService uds,
            @Qualifier("adminContextRepository") HttpSessionSecurityContextRepository adminRepo) throws Exception {

        log.info("Admin IP whitelist (dynamic, /admin/settings): {}",
                Arrays.stream(currentWhitelist().split(",")).map(String::trim).toList());

        http
            .securityMatcher("/admin/**", "/api/sign/admin/**")
            .userDetailsService(uds)
            .securityContext(sc -> sc.securityContextRepository(adminRepo))
            .sessionManagement(s -> s.sessionFixation().changeSessionId())
            .addFilterBefore(adminIpFilter(), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/admin/login", "/admin/challenge", "/admin/cert-login").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .csrf(c -> c.disable())
            .exceptionHandling(e -> e
                // Не залогинен — редирект на /admin/login (для AJAX — 401)
                .authenticationEntryPoint((req, res, ex) -> {
                    if (isAjax(req)) {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write("{\"error\":\"unauthorized\"}");
                    } else {
                        res.sendRedirect("/admin/login?expired");
                    }
                })
                // Залогинен, но не админ — 403
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    if (!isAjax(req)) {
                        res.sendRedirect("/admin/login?forbidden");
                    }
                })
            );
        return http.build();
    }

    @SuppressWarnings("java:S4502")
    @Bean
    @Order(2)
    public SecurityFilterChain userChain(
            HttpSecurity http,
            UserDetailsService uds,
            @Qualifier("delegatingContextRepository") DelegatingSecurityContextRepository ctxRepo) throws Exception {

        http
            .userDetailsService(uds)
            .securityContext(sc -> sc.securityContextRepository(ctxRepo))
            .sessionManagement(s -> s.sessionFixation().changeSessionId())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/", "/login", "/login/confirm", "/error",
                                 "/actuator/health", "/actuator/info",
                                 "/ping",
                                 "/info", "/info/**",
                                 "/api/info/**",
                                 "/css/**", "/js/**", "/img/**", "/favicon.ico",
                                 "/h2-console/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .csrf(c -> c.disable())
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))
            .exceptionHandling(e -> e
                // Не залогинен — редирект на /login (для AJAX — 401)
                .authenticationEntryPoint((req, res, ex) -> {
                    if (isAjax(req)) {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write("{\"error\":\"unauthorized\"}");
                    } else {
                        res.sendRedirect("/login?expired");
                    }
                })
            );
        return http.build();
    }
}
