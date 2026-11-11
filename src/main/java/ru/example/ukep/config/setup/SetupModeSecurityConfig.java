package ru.example.ukep.config.setup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security chain для Setup Mode — активен только при app.setup.mode=true.
 *
 * <p><b>Приоритет:</b> {@code @Order(0)} — этот chain срабатывает <b>раньше</b>
 * {@code adminChain} ({@code @Order(1)}) и {@code userChain} ({@code @Order(2)}).
 * SecurityMatcher ограничен путями {@code /setup/**}, {@code /css/setup.css},
 * {@code /js/setup.js} — остальные запросы проходят мимо, к обычным chain'ам.</p>
 *
 * <p><b>Авторизация:</b> вся логика в {@link SetupTokenFilter}. Chain
 * пропускает всё, до чего фильтр донёс запрос (permitAll). Всё, что не
 * прошло токен — уже отсечено 404 в фильтре.</p>
 *
 * <p><b>Что защищено:</b> в setup-режиме <b>весь</b> остальной функционал
 * (login, admin, api) блокируется на уровне главного layout — но
 * формально это ответственность {@link ru.example.ukep.config.SecurityConfig}
 * (у которого securityMatcher не покрывает /setup, поэтому он не мешает).
 * Здесь только /setup/**.</p>
 *
 * <p><b>CSRF:</b> отключён — setup работает по одноразовому токену в URL,
 * а не по сессии с форм-логином. После успешной авторизации сессия живёт
 * только для доступа к wizard'у (60 мин), не для бизнес-операций.</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.setup.mode", havingValue = "true")
public class SetupModeSecurityConfig {

    /**
     * Отдельный SecurityFilterChain для wizard'а.
     * SecurityMatcher — только /setup/**, /css/setup.css, /js/setup.js.
     */
    @Bean
    @Order(0)
    @SuppressWarnings("java:S4502")  // CSRF disabled — justified (одноразовый токен в URL)
    public SecurityFilterChain setupChain(
            HttpSecurity http,
            SetupTokenFilter setupTokenFilter) throws Exception {

        http
                .securityMatcher("/setup", "/setup/**", "/css/setup.css", "/js/setup.js")
                .addFilterBefore(setupTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        // Статика wizard'а — без токена (иначе браузер не загрузит CSS/JS)
                        .requestMatchers("/css/setup.css", "/js/setup.js").permitAll()
                        // Всё остальное — фильтр уже проверил токен/сессию
                        .anyRequest().permitAll()
                )
                .csrf(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()));

        return http.build();
    }
}
