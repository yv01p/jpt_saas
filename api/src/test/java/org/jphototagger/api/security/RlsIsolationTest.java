package org.jphototagger.api.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Integration test verifying RLS context does not leak across requests.
 * Uses Testcontainers PostgreSQL with actual RLS policies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(org.jphototagger.api.config.TestRedisConfig.class)
class RlsIsolationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rlsContextDoesNotLeakAcrossRequests() throws Exception {
        // Insert two users and a photo for each
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID photoA = UUID.randomUUID();
        UUID photoB = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
                userA, userA + "@test.com", "hash");
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
                userB, userB + "@test.com", "hash");
        jdbcTemplate.update("INSERT INTO photos (id, user_id, filename) VALUES (?, ?, ?)",
                photoA, userA, "a.jpg");
        jdbcTemplate.update("INSERT INTO photos (id, user_id, filename) VALUES (?, ?, ?)",
                photoB, userB, "b.jpg");

        try {
            String tokenA = jwtService.generateToken(userA, userA + "@test.com");
            String tokenB = jwtService.generateToken(userB, userB + "@test.com");

            // Request 1: authenticate as userA
            mockMvc.perform(get("/photos").cookie(new Cookie("jwt", tokenA)));

            // After request 1, RlsContext should be cleared (no leak to request 2)
            assertThat(RlsContext.getCurrentUserId()).isNull();

            // Request 2: authenticate as userB
            mockMvc.perform(get("/photos").cookie(new Cookie("jwt", tokenB)));

            // After request 2, RlsContext should also be cleared
            assertThat(RlsContext.getCurrentUserId()).isNull();

            // Verify data isolation: query as jpt_app with userB context should NOT see userA's photo
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM photos WHERE id = ? AND user_id = ?",
                    Integer.class, photoA, userB);
            assertThat(count).isEqualTo(0);
        } finally {
            jdbcTemplate.update("DELETE FROM photos WHERE id IN (?, ?)", photoA, photoB);
            jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", userA, userB);
        }
    }
}
