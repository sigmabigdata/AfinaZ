package ru.example.ukep.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S5853")  // несколько assertThat на одном объекте — идиоматично для тестов
class UserServiceTest {

    @Mock UserRepository userRepository;

    @TempDir Path tempDir;

    private PiiEncryptor pii;
    private UserService service;

    @BeforeEach
    void setUp() throws Exception {
        Path keyPath = tempDir.resolve("pii.key");
        byte[] rawKey = new byte[32];
        new java.security.SecureRandom().nextBytes(rawKey);
        Files.writeString(keyPath, Base64.getEncoder().encodeToString(rawKey),
                StandardCharsets.UTF_8);
        pii = new PiiEncryptor(keyPath.toString());
        service = new UserService(userRepository, pii);
    }

    private User activeUser() {
        User u = new User();
        u.setId(1L);
        u.setEmail("user@example.com");
        u.setEmailHash(pii.hash("user@example.com"));
        u.setFullName("Ivanov Ivan");
        u.setRole(Role.ROLE_USER);
        u.setEnabled(true);
        return u;
    }

    @Test
    @DisplayName("loadUserByUsername для несуществующего → UsernameNotFoundException")
    void loadUserNotFound() {
        when(userRepository.findByEmailHash(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("missing@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("loadUserByUsername возвращает UserDetails с ролью")
    void loadUserSuccess() {
        User u = activeUser();
        when(userRepository.findByEmailHash(pii.hash(u.getEmail()))).thenReturn(Optional.of(u));

        var details = service.loadUserByUsername(u.getEmail());
        assertThat(details.getUsername()).isEqualTo("user@example.com");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("generateLoginLink для несуществующего → null")
    void generateLoginLinkUnknown() {
        when(userRepository.findByEmailHash(anyString())).thenReturn(Optional.empty());
        assertThat(service.generateLoginLink("unknown@example.com", "https://afina")).isNull();
    }

    @Test
    @DisplayName("generateLoginLink для заблокированного → null")
    void generateLoginLinkDisabled() {
        User u = activeUser();
        u.setEnabled(false);
        when(userRepository.findByEmailHash(anyString())).thenReturn(Optional.of(u));
        assertThat(service.generateLoginLink("user@example.com", "https://afina")).isNull();
    }

    @Test
    @DisplayName("generateLoginLink — токен сохранён, URL содержит токен")
    void generateLoginLinkSuccess() {
        User u = activeUser();
        when(userRepository.findByEmailHash(anyString())).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String url = service.generateLoginLink("user@example.com", "https://afina.example");
        assertThat(url).startsWith("https://afina.example/login/confirm?token=");
        assertThat(u.getLoginToken()).isNotBlank();
        assertThat(u.getLoginTokenExpires()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("consumeLoginToken(null/blank) → null")
    void consumeNull() {
        assertThat(service.consumeLoginToken(null)).isNull();
        assertThat(service.consumeLoginToken("")).isNull();
        assertThat(service.consumeLoginToken("  ")).isNull();
    }

    @Test
    @DisplayName("consumeLoginToken неизвестный → null")
    void consumeUnknown() {
        when(userRepository.findByLoginToken(anyString())).thenReturn(Optional.empty());
        assertThat(service.consumeLoginToken("deadbeef")).isNull();
    }

    @Test
    @DisplayName("consumeLoginToken истёкший → null")
    void consumeExpired() {
        User u = activeUser();
        u.setLoginToken("expired");
        u.setLoginTokenExpires(Instant.now().minusSeconds(60));
        when(userRepository.findByLoginToken("expired")).thenReturn(Optional.of(u));

        assertThat(service.consumeLoginToken("expired")).isNull();
    }

    @Test
    @DisplayName("consumeLoginToken валидный → user, lastLoginAt установлен")
    void consumeValid() {
        User u = activeUser();
        u.setLoginToken("valid");
        u.setLoginTokenExpires(Instant.now().plusSeconds(60));
        when(userRepository.findByLoginToken("valid")).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.consumeLoginToken("valid");
        assertThat(result).isNotNull();
        assertThat(result.getLastLoginAt()).isNotNull();
        assertThat(result.getLoginTokenUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("upsertAdminByCert — создаёт админа с синтетическим email")
    void upsertAdminByCertCreate() {
        when(userRepository.findByEmailHash(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User admin = service.upsertAdminByCert("Ivanov Ivan", "12345678901");
        assertThat(admin.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(admin.getFullName()).isEqualTo("Ivanov Ivan");
        assertThat(admin.getEmail()).contains("12345678901").contains("@ukep.local");
    }

    @Test
    @DisplayName("buildAdminEmail — SNILS + домен")
    void buildAdminEmail() {
        String email = UserService.buildAdminEmail("Ivanov", "12345");
        assertThat(email).endsWith("@ukep.local");
        assertThat(email).contains("+12345");
    }

    @Test
    @DisplayName("adminDelete самого себя → IAE")
    void adminDeleteSelfThrows() {
        User u = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> service.adminDelete(1L, "user@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("adminToggle админа → IAE")
    void adminToggleAdminThrows() {
        User admin = activeUser();
        admin.setRole(Role.ROLE_ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.adminToggle(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("adminToggle клиента переключает enabled")
    void adminToggleClient() {
        User u = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adminToggle(1L);
        assertThat(u.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("adminCreate с занятым email → IAE")
    void adminCreateDuplicateEmail() {
        when(userRepository.existsByEmailHash(anyString())).thenReturn(true);
        assertThatThrownBy(() ->
                service.adminCreate("user@example.com", "Ivanov", null, Role.ROLE_USER, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("adminCreate — email в lowercase, fullName fallback = исходный email")
    void adminCreateLowercasesEmail() {
        when(userRepository.existsByEmailHash(anyString())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User u = service.adminCreate("USER@Example.COM", null, null, Role.ROLE_USER, true);
        assertThat(u.getEmail()).isEqualTo("user@example.com");
        assertThat(u.getFullName()).isEqualToIgnoringCase("USER@Example.COM");
    }
}
