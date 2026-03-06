package org.jphototagger.api.security;

import jakarta.servlet.http.Cookie;
import org.jphototagger.api.config.TestRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
        registry.add("app.rate-limit.auth", () -> "3");
        registry.add("app.rate-limit.general", () -> "5");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanRedis() {
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

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

        // First 3 upload requests pass the rate limiter (POST to /photos — the upload endpoint)
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/photos")
                            .with(csrf())
                            .cookie(jwt));
        }

        // 4th upload request should be rate limited
        mockMvc.perform(post("/photos")
                        .with(csrf())
                        .cookie(jwt))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("Too Many Requests"));
    }

    @Test
    void authEndpointRateLimitRejects4thLoginAttemptFromSameIp() throws Exception {
        // app.rate-limit.auth=3 (via @DynamicPropertySource) — IP-keyed, no JWT needed
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is(not(429)));
        }

        // 4th attempt from same IP (127.0.0.1 in MockMvc) must be rate limited
        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("Too Many Requests"));
    }

    @Test
    void authEndpointRateLimitAlsoAppliesToRegisterEndpoint() throws Exception {
        // The IP bucket is shared across all /auth/ paths
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is(not(429)));
        }

        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
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
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("Too Many Requests"));
    }
}
