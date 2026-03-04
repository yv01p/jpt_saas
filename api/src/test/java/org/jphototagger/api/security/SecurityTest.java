package org.jphototagger.api.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(org.jphototagger.api.config.TestRedisConfig.class)
class SecurityTest {

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
        // Auth datasource uses same Testcontainers PG as superuser
        // (jpt_auth role is created by V4 migration, but tests connect as superuser)
        registry.add("spring.auth-datasource.url", pg::getJdbcUrl);
        registry.add("spring.auth-datasource.username", pg::getUsername);
        registry.add("spring.auth-datasource.password", pg::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void unauthenticatedRequestToProtectedPathReturns401() throws Exception {
        mockMvc.perform(get("/photos"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedRequestToProtectedPathReturns404WhenNoController() throws Exception {
        // No PhotoController exists yet, so an authenticated request to /photos
        // should pass security (not 401) but get 404 (no handler mapped).
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "test@example.com");

        mockMvc.perform(get("/photos").cookie(new Cookie("jwt", token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicPathActuatorHealthIsAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void publicPathAuthIsAccessible() throws Exception {
        // /auth/** is public — GET /auth/login returns 405 (Method Not Allowed)
        // because AuthController only maps POST, but NOT 401 (security allows it through).
        mockMvc.perform(get("/auth/login"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void invalidJwtTokenReturns401() throws Exception {
        mockMvc.perform(get("/photos").cookie(new Cookie("jwt", "invalid-token")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredJwtTokenReturns401() throws Exception {
        UUID userId = UUID.randomUUID();
        String expiredToken = jwtService.generateToken(userId, "test@example.com", -1);

        mockMvc.perform(get("/photos").cookie(new Cookie("jwt", expiredToken)))
                .andExpect(status().isUnauthorized());
    }
}
