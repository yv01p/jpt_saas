package org.jphototagger.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.jphototagger.api.config.TestRedisConfig;
import org.jphototagger.api.dto.LoginRequest;
import org.jphototagger.api.dto.RegisterRequest;
import org.jphototagger.api.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class AuthControllerTest {

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
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redisTemplate;

    private int emailCounter = 0;

    private String uniqueEmail() {
        return "user" + (++emailCounter) + "_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    @BeforeEach
    void cleanRedis() {
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // --- Registration ---

    @Test
    void registerCreatesUser() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(uniqueEmail(), "securePassword12"))))
                .andExpect(status().isAccepted());
    }

    @Test
    void registerRejectsShortPassword() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(uniqueEmail(), "short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerDuplicateEmailReturns202() throws Exception {
        // Anti-enumeration: duplicate email returns the same 202 as a new registration
        // so callers cannot determine if an email is already registered.
        String email = uniqueEmail();
        register(email, "securePassword12");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, "securePassword12"))))
                .andExpect(status().isAccepted());
    }

    // --- Login ---

    @Test
    void loginReturnsJwtCookie() throws Exception {
        String email = uniqueEmail();
        register(email, "securePassword12");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, "securePassword12"))))
                .andExpect(status().isOk())
                .andReturn();

        assertCookieAttributes(result, "jwt");
        assertCookieAttributes(result, "refresh");
    }

    @Test
    void loginReturnsGeneric401ForLockedAccount() throws Exception {
        String email = uniqueEmail();
        register(email, "securePassword12");

        // 5 failed attempts to lock the account
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest(email, "wrongPassword!!"))))
                    .andExpect(status().isUnauthorized());
        }

        // Now even correct password should return generic 401
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, "securePassword12"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void successfulLoginResetsFailedAttemptCounter() throws Exception {
        String email = uniqueEmail();
        register(email, "securePassword12");

        // 3 failed attempts (below lockout threshold)
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest(email, "wrongPassword!!"))))
                    .andExpect(status().isUnauthorized());
        }

        // Successful login
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, "securePassword12"))))
                .andExpect(status().isOk());

        // 4 more failed attempts should NOT lock the account (counter was reset)
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest(email, "wrongPassword!!"))))
                    .andExpect(status().isUnauthorized());
        }

        // Should still be able to login (only 4 attempts after reset, not 5)
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, "securePassword12"))))
                .andExpect(status().isOk());
    }

    // --- Refresh Token ---

    @Test
    void refreshReturnsNewJwtAndRefreshCookies() throws Exception {
        String email = uniqueEmail();
        register(email, "securePassword12");
        Cookie refreshCookie = loginAndGetRefreshCookie(email, "securePassword12");

        MvcResult result = mockMvc.perform(post("/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andReturn();

        assertCookieAttributes(result, "jwt");
        assertCookieAttributes(result, "refresh");
    }

    @Test
    void oldRefreshTokenIsInvalidAfterRotation() throws Exception {
        String email = uniqueEmail();
        register(email, "securePassword12");
        Cookie refreshCookie = loginAndGetRefreshCookie(email, "securePassword12");

        // Rotate once
        mockMvc.perform(post("/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk());

        // Old token should fail
        mockMvc.perform(post("/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replayOfConsumedTokenRevokesEntireFamily() throws Exception {
        String email = uniqueEmail();
        register(email, "securePassword12");
        Cookie originalRefreshCookie = loginAndGetRefreshCookie(email, "securePassword12");

        // Rotate to get T2
        MvcResult rotateResult = mockMvc.perform(post("/auth/refresh")
                        .cookie(originalRefreshCookie))
                .andExpect(status().isOk())
                .andReturn();
        Cookie t2Cookie = extractRefreshCookie(rotateResult);

        // Replay T1 (consumed token) — should trigger family revocation
        mockMvc.perform(post("/auth/refresh").cookie(originalRefreshCookie))
                .andExpect(status().isUnauthorized());

        // T2 should also be revoked (entire family)
        mockMvc.perform(post("/auth/refresh").cookie(t2Cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeInvalidatesAllRefreshTokens() throws Exception {
        String email = uniqueEmail();
        register(email, "securePassword12");
        Cookie refreshCookie = loginAndGetRefreshCookie(email, "securePassword12");

        // Simulate password change by revoking all tokens for user
        // We extract userId from the refresh token to revoke
        // In a real scenario, this would be called by a password change endpoint
        // For this test, we directly use RefreshTokenService
        UUID userId = refreshTokenServiceGetUserId(refreshCookie.getValue());
        assertThat(userId).isNotNull();

        // Inject RefreshTokenService to revoke all
        refreshTokenService.revokeAllForUser(userId);

        // Old refresh token should fail
        mockMvc.perform(post("/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Autowired RefreshTokenService refreshTokenService;

    @Test
    void expiredRefreshTokenReturns401() throws Exception {
        // Manually create a token, then delete it from Redis to simulate expiry
        String email = uniqueEmail();
        register(email, "securePassword12");
        Cookie refreshCookie = loginAndGetRefreshCookie(email, "securePassword12");

        // Delete the token from Redis to simulate expiry
        String hash = RefreshTokenService.sha256(refreshCookie.getValue());
        redisTemplate.delete("refresh:" + hash);

        // Note: for replay detection, the family set still has this hash,
        // so this will trigger replay detection and revoke family.
        // That's actually correct behavior — an expired token that's still
        // in the family set is treated as replay. For a truly expired token
        // (where Redis TTL expired and cleaned up everything), all keys are gone.
        // Let's simulate that by also removing from family sets.
        Set<String> familyKeys = redisTemplate.keys("refresh_family:*");
        if (familyKeys != null) {
            for (String fk : familyKeys) {
                redisTemplate.opsForSet().remove(fk, hash);
            }
        }
        Set<String> userKeys = redisTemplate.keys("user_refresh:*");
        if (userKeys != null) {
            for (String uk : userKeys) {
                redisTemplate.opsForSet().remove(uk, hash);
            }
        }

        mockMvc.perform(post("/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieAttributesAreSecureAndSameSiteLax() throws Exception {
        String email = uniqueEmail();
        register(email, "securePassword12");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, "securePassword12"))))
                .andExpect(status().isOk())
                .andReturn();

        // Check Set-Cookie headers for both jwt and refresh
        var setCookieHeaders = result.getResponse().getHeaders("Set-Cookie");
        assertThat(setCookieHeaders).hasSizeGreaterThanOrEqualTo(2);

        String jwtHeader = setCookieHeaders.stream()
                .filter(h -> h.startsWith("jwt=")).findFirst().orElseThrow();
        assertThat(jwtHeader).contains("Secure");
        assertThat(jwtHeader).contains("HttpOnly");
        assertThat(jwtHeader).contains("SameSite=Lax");

        String refreshHeader = setCookieHeaders.stream()
                .filter(h -> h.startsWith("refresh=")).findFirst().orElseThrow();
        assertThat(refreshHeader).contains("Secure");
        assertThat(refreshHeader).contains("HttpOnly");
        assertThat(refreshHeader).contains("SameSite=Lax");
    }

    // --- Helpers ---

    private void register(String email, String password) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, password))))
                .andExpect(status().isAccepted());
    }

    private Cookie loginAndGetRefreshCookie(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        return extractRefreshCookie(result);
    }

    private Cookie extractRefreshCookie(MvcResult result) {
        String refreshHeader = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("refresh="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No refresh cookie found"));

        // Extract value from "refresh=<value>; ..."
        String value = refreshHeader.split(";")[0].substring("refresh=".length());
        return new Cookie("refresh", value);
    }

    private void assertCookieAttributes(MvcResult result, String cookieName) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(cookieName + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + cookieName + " cookie found"));

        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("Secure");
        assertThat(header).contains("SameSite=Lax");
    }

    private UUID refreshTokenServiceGetUserId(String rawToken) {
        return refreshTokenService.getUserId(rawToken);
    }
}
