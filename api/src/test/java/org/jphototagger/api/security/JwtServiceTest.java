package org.jphototagger.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class JwtServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.flyway.url", pg::getJdbcUrl);
        registry.add("spring.flyway.user", pg::getUsername);
        registry.add("spring.flyway.password", pg::getPassword);
        registry.add("spring.auth-datasource.url", pg::getJdbcUrl);
        registry.add("spring.auth-datasource.username", pg::getUsername);
        registry.add("spring.auth-datasource.password", pg::getPassword);
    }

    @Autowired
    private JwtService jwtService;

    @Test
    void generateAndValidateToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "test@example.com");

        assertThat(jwtService.validateToken(token)).isTrue();
        assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    void expiredTokenIsInvalid() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "test@example.com", -1);

        assertThat(jwtService.validateToken(token)).isFalse();
    }

    @Test
    void startupFailsWithShortSecretInProdProfile() {
        // 33 chars is enough for JJWT (>= 32 bytes) but below our 43-char minimum.
        // Pass null environment so validateSecret treats it as non-dev/test.
        JwtService weakSecret = new JwtService(
                "a]3kR!9xZ#vL2mQ8wP5nT7bY0fH4jU6c1", 15, null);
        assertThatThrownBy(weakSecret::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }

    @Test
    void startupFailsWithDefaultSecretInProdProfile() {
        // The default dev secret contains "change-me" and should be rejected.
        JwtService defaultSecret = new JwtService(
                "dev-secret-change-me-in-prod-must-be-at-least-256-bits-long-ok",
                15, null);
        assertThatThrownBy(defaultSecret::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Default JWT_SECRET");
    }
}
