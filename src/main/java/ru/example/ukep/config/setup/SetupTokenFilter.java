package ru.example.ukep.config.setup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Фильтр валидации setup-токена.
 *
 * <p>Логика:</p>
 * <ol>
 *   <li>Если в HttpSession уже стоит {@link #SESSION_ATTR} и TTL не истёк —
 *       permit (пользователь уже авторизован в wizard).</li>
 *   <li>Если query {@code ?token=XXX} == {@link SetupModeProperties#getToken()} —
 *       создаём HttpSession, ставим {@link #SESSION_ATTR}=now, permit.</li>
 *   <li>Иначе — <b>404 Not Found</b> (скрываем существование setup-режима;
 *       атакующий не должен знать, что /setup вообще есть).</li>
 * </ol>
 *
 * <p><b>Constant-time comparison:</b> сравнение токенов через
 * {@link MessageDigest#isEqual(byte[], byte[])} — защита от timing-атак.
 * Токен длиной 64 символа (32 hex) — сравнение безопасно.</p>
 *
 * <p><b>Только в setup-режиме:</b> {@code @ConditionalOnProperty} на
 * app.setup.mode=true. В обычном режиме бин не создаётся.</p>
 */
@Component
@ConditionalOnProperty(name = "app.setup.mode", havingValue = "true")
public class SetupTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SetupTokenFilter.class);

    /** Атрибут HttpSession, куда пишется метка авторизации (Instant epoch millis). */
    public static final String SESSION_ATTR = "SETUP_AUTHORIZED_AT";

    private final SetupModeProperties props;

    public SetupTokenFilter(SetupModeProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        // Статика wizard'а доступна без токена — иначе браузер не загрузит CSS/JS
        String path = req.getRequestURI();
        return path.equals("/css/setup.css") || path.equals("/js/setup.js");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {

        // ── 1. Уже авторизован в этой сессии и TTL не истёк? ──
        HttpSession session = req.getSession(false);
        if (session != null) {
            Object tsObj = session.getAttribute(SESSION_ATTR);
            if (tsObj instanceof Long ts) {
                Instant authorizedAt = Instant.ofEpochMilli(ts);
                Instant expires = authorizedAt.plus(Duration.ofMinutes(props.getSessionTtlMinutes()));
                if (Instant.now().isBefore(expires)) {
                    chain.doFilter(req, res);
                    return;
                }
                // TTL истёк — сбрасываем, требуется токен заново
                session.removeAttribute(SESSION_ATTR);
                log.debug("Setup session expired (TTL {} min)", props.getSessionTtlMinutes());
            }
        }

        // ── 2. Токен в query? ──
        String tokenFromQuery = req.getParameter("token");
        if (tokenFromQuery != null && !tokenFromQuery.isEmpty() && props.isTokenConfigured()) {
            if (constantTimeEquals(tokenFromQuery, props.getToken())) {
                HttpSession newSession = req.getSession(true);
                newSession.setAttribute(SESSION_ATTR, Instant.now().toEpochMilli());
                newSession.setMaxInactiveInterval(props.getSessionTtlMinutes() * 60);
                log.info("Setup Mode authorized (ip={}, UA={})",
                        clientIp(req), userAgent(req));
                chain.doFilter(req, res);
                return;
            }
            log.warn("Setup Mode: wrong token (ip={})", clientIp(req));
        }

        // ── 3. Не авторизован — 404 (скрываем существование) ──
        res.setStatus(HttpServletResponse.SC_NOT_FOUND);
        res.setContentType("text/plain;charset=UTF-8");
        res.getWriter().write("Not Found");
    }

    /** Constant-time сравнение строк (через SHA-256 дайджесты). */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] ha = md.digest(a.getBytes(StandardCharsets.UTF_8));
            byte[] hb = md.digest(b.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(ha, hb);
        } catch (Exception e) {
            return false;
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return req.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        return ua != null && ua.length() > 200 ? ua.substring(0, 200) + "..." : ua;
    }
}
