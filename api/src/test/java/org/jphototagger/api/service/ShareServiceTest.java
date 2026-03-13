package org.jphototagger.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.Cookie;
import org.jphototagger.api.config.TestRedisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import org.hamcrest.Matchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Share Token Service (Task 5.1).
 *
 * <p>Tests use Testcontainers PostgreSQL so RLS and real SQL are exercised.
 * The share_reader DataSource is also overridden to point to the test container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class ShareServiceTest {

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
        registry.add("app.share-reader.jdbc-url", pg::getJdbcUrl);
        registry.add("app.share-reader.username", pg::getUsername);
        registry.add("app.share-reader.password", pg::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ShareService shareService;
    @Autowired org.jphototagger.api.security.JwtService jwtService;

    private UUID userId;
    private UUID photoId;
    private String jwtToken;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        photoId = UUID.randomUUID();

        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash, email_verified) VALUES (?, ?, ?, true)",
            userId, userId + "@test.com", "hash");
        jdbcTemplate.update(
            "INSERT INTO photos (id, user_id, filename, processing_status) VALUES (?, ?, ?, ?)",
            photoId, userId, "test.jpg", "DONE");

        jwtToken = jwtService.generateToken(userId, userId + "@test.com");
    }

    @AfterEach
    void cleanup() {
        // Cleanup in reverse dependency order
        jdbcTemplate.update("DELETE FROM shares WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@test.com')");
        jdbcTemplate.update("DELETE FROM photos WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@test.com')");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE '%@test.com'");
    }

    @Test
    void createShare_stores256BitTokenHash() {
        var result = shareService.createShare(userId, "photo", photoId, false);

        assertThat(result.plaintextToken()).hasSize(43); // 256-bit Base64url = 43 chars
        assertThat(result.share().getTokenHash()).hasSize(64); // SHA-256 hex = 64 chars
        // Plaintext token should NOT be the hash
        assertThat(result.share().getTokenHash()).isNotEqualTo(result.plaintextToken());
        // Hash must be SHA-256 of the plaintext
        String expectedHash = RefreshTokenService.sha256(result.plaintextToken());
        assertThat(result.share().getTokenHash()).isEqualTo(expectedHash);
    }

    @Test
    void createShare_returnsPlaintextTokenOnce() throws Exception {
        // POST /shares returns token in response
        var requestBody = objectMapper.writeValueAsString(Map.of(
            "resourceType", "photo",
            "resourceId", photoId.toString(),
            "includeGps", false));

        mockMvc.perform(post("/shares")
                .cookie(new Cookie("jwt", jwtToken))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.token").isString());

        // GET /shares list does NOT include token
        mockMvc.perform(get("/shares")
                .cookie(new Cookie("jwt", jwtToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].token").doesNotExist());
    }

    @Test
    void lookupShare_byHashedToken() {
        var result = shareService.createShare(userId, "photo", photoId, false);
        String token = result.plaintextToken();

        // Should find share by plaintext token
        var lookup = shareService.lookupShare(token);
        assertThat(lookup.shareData()).isNotNull();
        assertThat(lookup.shareData().get("resource_type")).isEqualTo("photo");
    }

    @Test
    void expiredShareReturns404() {
        // Insert a share that is already expired
        String tokenHash = RefreshTokenService.sha256("expiredtokenfourtythreecharslongpad");
        jdbcTemplate.update(
            "INSERT INTO shares (id, user_id, resource_type, resource_id, token_hash, expires_at) " +
            "VALUES (?, ?, 'photo', ?, ?, now() - interval '1 day')",
            UUID.randomUUID(), userId, photoId,
            RefreshTokenService.sha256("expiredtokenfourtythreecharslongpad"));

        // lookupShare uses the actual token string — so use a token that hashes to the inserted hash
        // Instead, let's create a share via service and then expire it manually
        var result = shareService.createShare(userId, "photo", photoId, false);
        String shareId = result.share().getId().toString();

        // Expire it
        jdbcTemplate.update("UPDATE shares SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", shareId);

        // Lookup should return 404
        assertThatThrownBy(() -> shareService.lookupShare(result.plaintextToken()))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shareToDeletedPhotoReturns404() {
        // Soft-delete the photo
        jdbcTemplate.update("UPDATE photos SET deleted_at = now() WHERE id = ?", photoId);

        // Creating a share on a deleted photo should fail
        assertThatThrownBy(() -> shareService.createShare(userId, "photo", photoId, false))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shareStripsGpsByDefault() throws Exception {
        // Insert photo_metadata with GPS-containing EXIF data for the test photo
        String exifWithGps = "{\"Make\":\"Canon\",\"GPS Latitude\":\"48.8566\",\"GPS Longitude\":\"2.3522\",\"Model\":\"EOS R5\"}";
        jdbcTemplate.update(
            "INSERT INTO photo_metadata (photo_id, user_id, exif_data) VALUES (?, ?, ?::jsonb)",
            photoId, userId, exifWithGps);

        // Create a share with includeGps=false
        var result = shareService.createShare(userId, "photo", photoId, false);
        String token = result.plaintextToken();

        // Look up the share via HTTP — GPS fields must be absent from the response
        mockMvc.perform(get("/share/" + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.photo.exif_data").exists())
            .andExpect(jsonPath("$.photo.exif_data").value(Matchers.not(Matchers.containsString("GPS Latitude"))))
            .andExpect(jsonPath("$.photo.exif_data").value(Matchers.not(Matchers.containsString("GPS Longitude"))))
            .andExpect(jsonPath("$.photo.exif_data").value(Matchers.containsString("Make")))
            .andExpect(jsonPath("$.photo.exif_data").value(Matchers.containsString("Model")));
    }

    @Test
    void unauthenticatedShareLookup_succeedsWithoutUserContext() throws Exception {
        // Create a share via the service
        var result = shareService.createShare(userId, "photo", photoId, false);

        // Access the share endpoint WITHOUT authentication
        mockMvc.perform(get("/share/" + result.plaintextToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.share").exists());
    }

    @Test
    void deleteShare_byOtherUser_returns404() throws Exception {
        // Create a share owned by userId
        var result = shareService.createShare(userId, "photo", photoId, false);
        UUID shareId = result.share().getId();

        // Create another user
        UUID otherUserId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash, email_verified) VALUES (?, ?, ?, true)",
            otherUserId, otherUserId + "@test.com", "hash");
        String otherToken = jwtService.generateToken(otherUserId, otherUserId + "@test.com");

        // Try to delete with the other user — should get 404
        mockMvc.perform(delete("/shares/" + shareId)
                .cookie(new Cookie("jwt", otherToken))
                .with(csrf()))
            .andExpect(status().isNotFound());
    }
}
