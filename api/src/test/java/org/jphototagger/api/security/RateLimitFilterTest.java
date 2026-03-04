package org.jphototagger.api.security;

import jakarta.servlet.http.Cookie;
import org.jphototagger.api.config.TestRedisConfig;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class RateLimitFilterTest {

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

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;

    private Cookie jwtCookie(UUID userId) {
        String token = jwtService.generateToken(userId, userId + "@test.com");
        return new Cookie("jwt", token);
    }

    private UUID createUser(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, used_bytes) VALUES (?, ?, ?, ?)",
                id, email, "hash", 0);
        return id;
    }

    @Test
    void uploadRateLimitRejects4thUploadInTestProfile() throws Exception {
        // application-test.yml: app.rate-limit.upload=3
        UUID userId = createUser("upload-rl-" + UUID.randomUUID() + "@test.com");
        Cookie jwt = jwtCookie(userId);

        // First 3 upload requests succeed (POST to /photos/{id}/restore as an upload-like endpoint)
        for (int i = 0; i < 3; i++) {
            UUID photoId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status, deleted_at) "
                            + "VALUES (?, ?, ?, ?, ?, NOW())",
                    photoId, userId, "upload" + i + ".jpg", 1000, "done");
            mockMvc.perform(post("/photos/" + photoId + "/restore")
                            .with(csrf())
                            .cookie(jwt))
                    .andExpect(status().isOk());
        }

        // 4th upload request should be rate limited
        UUID photoId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW())",
                photoId, userId, "upload3.jpg", 1000, "done");
        mockMvc.perform(post("/photos/" + photoId + "/restore")
                        .with(csrf())
                        .cookie(jwt))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Too Many Requests"));
    }

    @Test
    void generalRateLimitRejects6thRequestInTestProfile() throws Exception {
        // application-test.yml: app.rate-limit.general=5
        UUID userId = createUser("general-rl-" + UUID.randomUUID() + "@test.com");
        Cookie jwt = jwtCookie(userId);

        // First 5 GET requests succeed
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/photos")
                            .param("page", "0").param("size", "1")
                            .cookie(jwt))
                    .andExpect(status().isOk());
        }

        // 6th request should be rate limited
        mockMvc.perform(get("/photos")
                        .param("page", "0").param("size", "1")
                        .cookie(jwt))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Too Many Requests"));
    }
}
