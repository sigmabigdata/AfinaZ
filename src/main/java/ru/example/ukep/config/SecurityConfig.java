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
        List<String> allowed = Arrays.stream(adminIpWhitelist.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                String ip = clientIp((HttpServletRequest) req);
                if (!allowed.contains(ip)) {
                    log.warn("Blocked admin access from IP: {}", ip);
                    ((HttpServletResponse) res).sendError(HttpServletResponse.SC_FORBIDDEN,
                            "Admin area available only from server");
                    return;
                }
                chain.doFilter(req, res);
            }
        };
    }

    /** AJAX/JSON-запрос? Тогда при отсутствии сессии возвращаем 401, а не редирект. */
    private static boolean isAjax(HttpServletRequest req) {
        String xhr = req.getHeader("X-Requested-With");
        String accept = req.getHeader("Accept");
        return "XMLHttpRequest".equalsIgnoreCase(xhr)
                || (accept != null && accept.contains("application/json"));
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminChain(
            HttpSecurity http,
            UserDetailsService uds,
            @Qualifier("adminContextRepository") HttpSessionSecurityContextRepository adminRepo) throws Exception {

        log.info("Admin IP whitelist: {}",
                Arrays.stream(adminIpWhitelist.split(",")).map(String::trim).toList());

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

    @Bean
    @Order(2)
    public SecurityFilterChain userChain(
            HttpSecurity http,
            UserDetailsService uds,
            @Qualifier("userContextRepository") HttpSessionSecurityContextRepository userRepo) throws Exception {

        http
            .userDetailsService(uds)
            .securityContext(sc -> sc.securityContextRepository(userRepo))
            .sessionManagement(s -> s.sessionFixation().changeSessionId())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/", "/login", "/login/confirm", "/error",
                                 "/actuator/health", "/actuator/info",
                                 "/ping",
                                 "/css/**", "/js/**", "/img/**", "/favicon.ico",
                                 "/h2-console/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
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
