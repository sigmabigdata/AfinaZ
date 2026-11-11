package ru.example.ukep.service;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    private static final String USER_NOT_FOUND = "Пользователь не найден";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserService.class);

    public static final Duration LOGIN_TOKEN_TTL = Duration.ofHours(10);

    private final UserRepository userRepository;
    private final ru.example.ukep.security.PiiEncryptor pii;

    private final SettingsService settings;

    public UserService(UserRepository userRepository,
                       ru.example.ukep.security.PiiEncryptor pii,
                       SettingsService settings) {
        this.userRepository = userRepository;
        this.pii = pii;
        this.settings = settings;
    }

    // ============ Spring Security ============

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findByEmailHash(pii.hash(username))
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден: " + username));
        return org.springframework.security.core.userdetails.User
                .withUsername(u.getEmail())
                .password("{noop}magic")
                .disabled(!u.isEnabled())
                .authorities(List.of(new SimpleGrantedAuthority(u.getRole().name())))
                .build();
    }

    // ============ Magic-link для клиентов ============

    @Transactional
    public String generateLoginLink(String email, String baseUrl) {
        Optional<User> opt = userRepository.findByEmailHash(pii.hash(email));
        if (opt.isEmpty()) return null;
        User u = opt.get();
        if (!u.isEnabled()) return null;

        String token = UUID.randomUUID().toString().replace("-", "");
        u.setLoginToken(token);
        int ttlHours = settings.getIntOrDefault("auth.token_ttl_hours", 10);
        u.setLoginTokenExpires(Instant.now().plus(Duration.ofHours(ttlHours)));
        u.setLoginTokenUsedAt(null);
        userRepository.save(u);

        return baseUrl + "/login/confirm?token=" + token;
    }

    @Transactional
    public User consumeLoginToken(String token) {
        if (token == null || token.isBlank()) {
            log.warn("consumeLoginToken: token is null/blank");
            return null;
        }
        Optional<User> opt = userRepository.findByLoginToken(token);
        if (opt.isEmpty()) {
            log.warn("consumeLoginToken: no user with token (len={})", token.length());
            return null;
        }
        User u = opt.get();
        if (u.getLoginTokenExpires() == null || u.getLoginTokenExpires().isBefore(Instant.now())) {
            log.warn("consumeLoginToken: token expired for {} (expires={})",
                    u.getEmail(), u.getLoginTokenExpires());
            return null;
        }

        // Токен действителен до истечения TTL (10 часов).
        // Многоразовый — чтобы Gmail-prefetch не «съедал» единственный клик.
        Instant now = Instant.now();
        if (u.getLoginTokenUsedAt() == null) {
            u.setLoginTokenUsedAt(now);
        }
        u.setLastLoginAt(now);
        log.info("consumeLoginToken: success for {} (usedAt={})", u.getEmail(), u.getLoginTokenUsedAt());
        return userRepository.save(u);
    }

    // ============ Админ по сертификату ============

    @Transactional
    public User upsertAdminByCert(String cn, String snils, Role role) {
        String syntheticEmail = buildAdminEmail(cn, snils);
        String hash = pii.hash(syntheticEmail);
        User u = userRepository.findByEmailHash(hash).orElseGet(User::new);
        u.setEmail(syntheticEmail);
        u.setEmailHash(hash);
        u.setFullName(cn);
        u.setRole(role != null ? role : Role.ROLE_ADMIN);
        u.setEnabled(true);
        u.setLoginToken(null);
        u.setLoginTokenExpires(null);
        return userRepository.save(u);
    }

    /** Старый вызов без роли (совместимость). */
    @Transactional
    public User upsertAdminByCert(String cn, String snils) {
        return upsertAdminByCert(cn, snils, Role.ROLE_ADMIN);
    }

    public static String buildAdminEmail(String cn, String snils) {
        String safeCn = cn == null ? "admin" : cn.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("(^\\.)|(\\.$)", "");
        return safeCn + "+" + snils + "@ukep.local";
    }

    // ============ CRUD ============

    @Transactional
    public User adminCreate(String email, String fullName, String phone, Role role, boolean enabled) {
        if (email == null || email.isBlank())
            throw new IllegalArgumentException("Email обязателен");
        String emailHash = pii.hash(email);
        if (userRepository.existsByEmailHash(emailHash))
            throw new IllegalArgumentException("Email уже занят");

        User u = new User();
        u.setEmail(email.toLowerCase().trim());
        u.setEmailHash(emailHash);
        u.setFullName(fullName == null || fullName.isBlank() ? email : fullName.trim());
        if (phone != null && !phone.isBlank()) {
            u.setPhone(phone.trim());
            u.setPhoneHash(pii.hashPhone(phone));
        }
        u.setRole(role == null ? Role.ROLE_USER : role);
        u.setEnabled(enabled);
        return userRepository.save(u);
    }

    @Transactional
    public User adminUpdate(Long id, String email, String fullName, String phone,
                            Role role, boolean enabled) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));

        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(u.getEmail())) {
            String newHash = pii.hash(email);
            if (userRepository.existsByEmailHash(newHash))
                throw new IllegalArgumentException("Email уже занят");
            u.setEmail(email.toLowerCase().trim());
            u.setEmailHash(newHash);
        }
        if (fullName != null && !fullName.isBlank()) u.setFullName(fullName.trim());
        if (phone != null && !phone.isBlank()) {
            u.setPhone(phone.trim());
            u.setPhoneHash(pii.hashPhone(phone));
        } else {
            u.setPhone(null);
            u.setPhoneHash(null);
        }
        if (role != null) u.setRole(role);
        u.setEnabled(enabled);
        return userRepository.save(u);
    }

    /**
     * Удаление пользователя. currentAdminEmail — email текущего админа,
     * чтобы запретить удаление самого себя.
     */
    @Transactional
    public void adminDelete(Long id, String currentAdminEmail) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        if (currentAdminEmail != null && u.getEmail().equalsIgnoreCase(currentAdminEmail)) {
            throw new IllegalArgumentException("Нельзя удалить собственную учётную запись");
        }
        userRepository.delete(u);
    }

    @Transactional
    public void adminToggle(Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND));
        // Админов не блокируем — у них вход по сертификату, вне сессии их «заблокировать» нельзя
        if (u.getRole() == Role.ROLE_ADMIN) {
            throw new IllegalArgumentException("Нельзя заблокировать администратора");
        }
        u.setEnabled(!u.isEnabled());
        userRepository.save(u);
    }
}
