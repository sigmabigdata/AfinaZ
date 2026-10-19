package ru.example.ukep.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService();
    }

    @Test
    @DisplayName("N вызовов проходят, N+1 — блокируется")
    void limitRespected() {
        String key = "login:1.2.3.4:user@example.com";
        Duration window = Duration.ofMinutes(15);
        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsume(key, 5, window)).isTrue();
        }
        assertThat(service.tryConsume(key, 5, window)).isFalse();
    }

    @Test
    void differentKeysIndependent() {
        Duration window = Duration.ofMinutes(15);
        assertThat(service.tryConsume("a", 2, window)).isTrue();
        assertThat(service.tryConsume("a", 2, window)).isTrue();
        assertThat(service.tryConsume("a", 2, window)).isFalse();
        assertThat(service.tryConsume("b", 2, window)).isTrue();
        assertThat(service.tryConsume("b", 2, window)).isTrue();
        assertThat(service.tryConsume("b", 2, window)).isFalse();
    }

    @SuppressWarnings("java:S2925")  // sleep обязателен для теста истечения окна
    @Test
    void windowResets() throws Exception {
        String key = "challenge:1.2.3.4";
        Duration window = Duration.ofMillis(100);
        assertThat(service.tryConsume(key, 1, window)).isTrue();
        assertThat(service.tryConsume(key, 1, window)).isFalse();
        Thread.sleep(150);
        assertThat(service.tryConsume(key, 1, window)).isTrue();
    }

    @Test
    void retryAfterPositive() {
        String key = "certlogin:1.2.3.4";
        service.tryConsume(key, 1, Duration.ofMinutes(1));
        assertThat(service.retryAfterSeconds(key)).isBetween(1L, 60L);
    }

    @Test
    void retryAfterUnknownKey() {
        assertThat(service.retryAfterSeconds("never-seen")).isZero();
    }

    @Test
    void retryAfterMinimumOneSecond() {
        String key = "confirm:1.2.3.4";
        service.tryConsume(key, 1, Duration.ofMillis(500));
        assertThat(service.retryAfterSeconds(key)).isPositive();
    }
}
