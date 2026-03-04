package org.jphototagger.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.jphototagger.api.config.TestRedisConfig;
import org.jphototagger.api.security.JwtService;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class PhotoControllerTest {

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
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;

    private Cookie jwtCookie(UUID userId) {
        String token = jwtService.generateToken(userId, userId + "@test.com");
        return new Cookie("jwt", token);
    }

    private UUID createUser(String email, long usedBytes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, used_bytes) VALUES (?, ?, ?, ?)",
                id, email, "hash", usedBytes);
        return id;
    }

    private UUID createPhoto(UUID userId, String filename, long sizeBytes, String processingStatus,
                             Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, userId, filename, sizeBytes, processingStatus, deletedAt != null ?
                        java.sql.Timestamp.from(deletedAt) : null);
        return id;
    }

    @Test
    void listPhotos_returnsOnlyUsersPhotos() throws Exception {
        UUID user1 = createUser("list1@test.com", 0);
        UUID user2 = createUser("list2@test.com", 0);
        createPhoto(user1, "u1p1.jpg", 1000, "done", null);
        createPhoto(user1, "u1p2.jpg", 2000, "done", null);
        createPhoto(user2, "u2p1.jpg", 1000, "done", null);
        createPhoto(user2, "u2p2.jpg", 2000, "done", null);

        mockMvc.perform(get("/photos")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void getPhoto_returns404ForOtherUsersPhoto() throws Exception {
        UUID user1 = createUser("get1@test.com", 0);
        UUID user2 = createUser("get2@test.com", 0);
        UUID photoId = createPhoto(user1, "secret.jpg", 1000, "done", null);

        mockMvc.perform(get("/photos/" + photoId)
                        .cookie(jwtCookie(user2)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePhoto_softDeletesAndDecrementsQuota() throws Exception {
        UUID user = createUser("del@test.com", 5000);
        UUID photoId = createPhoto(user, "todelete.jpg", 5000, "done", null);

        mockMvc.perform(delete("/photos/" + photoId)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNoContent());

        // Verify soft delete
        Instant deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM photos WHERE id = ?", Instant.class, photoId);
        assertThat(deletedAt).isNotNull();

        // Verify quota decremented
        Long usedBytes = jdbcTemplate.queryForObject(
                "SELECT used_bytes FROM users WHERE id = ?", Long.class, user);
        assertThat(usedBytes).isEqualTo(0);
    }

    @Test
    void getPhotoStatus_returnsProcessingStatus() throws Exception {
        UUID user = createUser("status@test.com", 0);
        UUID photoId = createPhoto(user, "processing.jpg", 1000, "processing", null);

        mockMvc.perform(get("/photos/" + photoId + "/status")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(photoId.toString()))
                .andExpect(jsonPath("$.processing_status").value("processing"));
    }

    @Test
    void trashView_returnsDeletedPhotos() throws Exception {
        UUID user = createUser("trash@test.com", 0);
        createPhoto(user, "active.jpg", 1000, "done", null);
        createPhoto(user, "deleted.jpg", 2000, "done", Instant.now());

        mockMvc.perform(get("/photos/trash")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].filename").value("deleted.jpg"));
    }

    @Test
    void restorePhoto_clearsDeletedAtAndIncrementsQuota() throws Exception {
        UUID user = createUser("restore@test.com", 0);
        UUID photoId = createPhoto(user, "restore.jpg", 5000, "done", Instant.now());

        mockMvc.perform(post("/photos/" + photoId + "/restore")
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk());

        // Verify restore
        Object deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM photos WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp("deleted_at"),
                photoId);
        assertThat(deletedAt).isNull();

        // Verify quota incremented
        Long usedBytes = jdbcTemplate.queryForObject(
                "SELECT used_bytes FROM users WHERE id = ?", Long.class, user);
        assertThat(usedBytes).isEqualTo(5000);
    }

    @Test
    void deletePhoto_secondDeleteReturns404() throws Exception {
        UUID user = createUser("deldel@test.com", 5000);
        UUID photoId = createPhoto(user, "deldel.jpg", 5000, "done", null);

        // First delete succeeds
        mockMvc.perform(delete("/photos/" + photoId)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNoContent());

        // Second delete returns 404
        mockMvc.perform(delete("/photos/" + photoId)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPhoto_returnsTrashedPhotoAs404() throws Exception {
        UUID user = createUser("trashed@test.com", 0);
        UUID photoId = createPhoto(user, "trashed.jpg", 1000, "done", Instant.now());

        mockMvc.perform(get("/photos/" + photoId)
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNotFound());
    }
}
