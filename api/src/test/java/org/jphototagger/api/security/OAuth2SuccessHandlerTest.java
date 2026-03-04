package org.jphototagger.api.security;

import org.jphototagger.api.config.TestRedisConfig;
import org.jphototagger.api.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class OAuth2SuccessHandlerTest {

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

    @Autowired OAuth2SuccessHandler handler;
    @Autowired @Qualifier("authJdbcTemplate") JdbcTemplate authJdbc;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void clean() {
        authJdbc.update("DELETE FROM email_tokens");
        authJdbc.update("DELETE FROM users");
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void oauthLoginCreatesNewUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationToken auth = buildOAuth2Token("google", "google-123", "newuser@example.com");

        handler.onAuthenticationSuccess(request, response, auth);

        // Assert: user created in DB with oauth_provider and oauth_id set
        List<Map<String, Object>> users = authJdbc.queryForList(
                "SELECT * FROM users WHERE email = ?", "newuser@example.com");
        assertThat(users).hasSize(1);
        assertThat(users.get(0).get("oauth_provider")).isEqualTo("google");
        assertThat(users.get(0).get("oauth_id")).isEqualTo("google-123");
        assertThat(users.get(0).get("password_hash")).isNull();

        // Assert: JWT cookie is issued and redirects to frontend
        assertCookiePresent(response, "jwt");
        assertCookiePresent(response, "refresh");
        assertCookieSecure(response, "jwt");
        assertCookieSecure(response, "refresh");
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void oauthLoginBlocksIfEmailExistsWithPassword() throws Exception {
        // Pre-create user with email + password_hash
        UUID userId = UUID.randomUUID();
        authJdbc.update(
                "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, " +
                        "failed_login_attempts, email_verified, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 10737418240, 0, 0, true, NOW(), NOW())",
                userId, "existing@example.com", "$2a$12$somehash");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationToken auth = buildOAuth2Token("google", "google-456", "existing@example.com");

        handler.onAuthenticationSuccess(request, response, auth);

        // Assert: redirects to login with email_conflict error
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=email_conflict");

        // Assert: no JWT cookie issued
        assertNoCookie(response, "jwt");
    }

    @Test
    void oauthLoginSucceedsForExistingOAuthUser() throws Exception {
        // Pre-create user with oauth_provider=google, oauth_id=google-789
        UUID userId = UUID.randomUUID();
        authJdbc.update(
                "INSERT INTO users (id, email, oauth_provider, oauth_id, quota_bytes, used_bytes, " +
                        "failed_login_attempts, email_verified, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 10737418240, 0, 0, true, NOW(), NOW())",
                userId, "oauthuser@example.com", "google", "google-789");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationToken auth = buildOAuth2Token("google", "google-789", "oauthuser@example.com");

        handler.onAuthenticationSuccess(request, response, auth);

        // Assert: JWT cookie is issued, redirects, no new user created
        assertCookiePresent(response, "jwt");
        assertCookiePresent(response, "refresh");
        assertThat(response.getRedirectedUrl()).isEqualTo("/");

        List<Map<String, Object>> users = authJdbc.queryForList(
                "SELECT * FROM users WHERE email = ?", "oauthuser@example.com");
        assertThat(users).hasSize(1); // no duplicate
    }

    @Test
    void oauthLoginBlocksIfDifferentOAuthProvider() throws Exception {
        // Pre-create user with oauth_provider=github
        UUID userId = UUID.randomUUID();
        authJdbc.update(
                "INSERT INTO users (id, email, oauth_provider, oauth_id, quota_bytes, used_bytes, " +
                        "failed_login_attempts, email_verified, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 10737418240, 0, 0, true, NOW(), NOW())",
                userId, "crossover@example.com", "github", "github-111");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Try to log in with Google using the same email
        OAuth2AuthenticationToken auth = buildOAuth2Token("google", "google-222", "crossover@example.com");

        handler.onAuthenticationSuccess(request, response, auth);

        // Assert: redirects to login with provider_mismatch error
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=provider_mismatch");

        // Assert: no JWT cookie issued
        assertNoCookie(response, "jwt");
    }

    @Test
    void oauthLoginHandlesMissingEmail() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Build token with no email
        OAuth2AuthenticationToken auth = buildOAuth2TokenNoEmail("google", "google-noemail");

        handler.onAuthenticationSuccess(request, response, auth);

        // Assert: redirects to login with no_email error
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=no_email");

        // Assert: no JWT cookie issued
        assertNoCookie(response, "jwt");
    }

    // --- Helpers ---

    private OAuth2AuthenticationToken buildOAuth2Token(String provider, String oauthId, String email) {
        OidcIdToken idToken = new OidcIdToken(
                "mock-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("sub", oauthId, "email", email, "iss", "https://accounts.google.com"));

        OidcUser oidcUser = new DefaultOidcUser(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                idToken);

        return new OAuth2AuthenticationToken(oidcUser,
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                provider);
    }

    private OAuth2AuthenticationToken buildOAuth2TokenNoEmail(String provider, String oauthId) {
        OidcIdToken idToken = new OidcIdToken(
                "mock-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("sub", oauthId, "iss", "https://accounts.google.com"));

        OidcUser oidcUser = new DefaultOidcUser(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                idToken,
                "sub");  // use "sub" as name attribute since no email

        return new OAuth2AuthenticationToken(oidcUser,
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                provider);
    }

    private void assertCookiePresent(MockHttpServletResponse response, String name) {
        boolean found = response.getHeaders("Set-Cookie").stream()
                .anyMatch(h -> h.startsWith(name + "=") && !h.startsWith(name + "=;"));
        assertThat(found).as("Cookie '%s' should be present with a value", name).isTrue();
    }

    private void assertNoCookie(MockHttpServletResponse response, String name) {
        boolean found = response.getHeaders("Set-Cookie").stream()
                .anyMatch(h -> h.startsWith(name + "=") && !h.startsWith(name + "=;"));
        assertThat(found).as("Cookie '%s' should not be present", name).isFalse();
    }

    private void assertCookieSecure(MockHttpServletResponse response, String name) {
        String header = response.getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " cookie found"));
        assertThat(header).contains("Secure");
        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("SameSite=Lax");
    }
}
