package org.jphototagger.api.controller;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class SearchControllerTest {

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
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, used_bytes) VALUES (?, ?, ?, ?)",
                id, email, "hash", 0);
        return id;
    }

    private void createPhotoWithCaption(UUID userId, String filename, String caption) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, caption, size_bytes, processing_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, userId, filename, caption, 1000, "DONE");
    }

    private UUID createPhoto(UUID userId, String filename) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id, userId, filename, 1000, "DONE");
        return id;
    }

    private void createPhotoMetadata(UUID photoId, UUID userId, String exifJson) {
        jdbcTemplate.update(
                "INSERT INTO photo_metadata (photo_id, user_id, exif_data) VALUES (?, ?, cast(? as jsonb))",
                photoId, userId, exifJson);
    }

    private UUID createKeyword(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO keywords (id, user_id, name) VALUES (?, ?, ?)",
                id, userId, name);
        return id;
    }

    private void linkPhotoKeyword(UUID photoId, UUID keywordId, UUID userId) {
        jdbcTemplate.update("INSERT INTO photo_keywords (photo_id, keyword_id, user_id) VALUES (?, ?, ?)",
                photoId, keywordId, userId);
    }

    @Test
    void fullTextSearch_returnsPaginatedResults() throws Exception {
        UUID user = createUser("search1@test.com");
        createPhotoWithCaption(user, "sunset1.jpg", "Beautiful sunset over the ocean");
        createPhotoWithCaption(user, "sunset2.jpg", "Sunset at the beach");
        createPhotoWithCaption(user, "mountain.jpg", "Mountain landscape");

        mockMvc.perform(get("/search")
                        .param("q", "sunset")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void exifSearch_returnsPaginatedResults() throws Exception {
        UUID user = createUser("searchexif@test.com");
        UUID photo1 = createPhoto(user, "canon1.jpg");
        createPhotoMetadata(photo1, user, "{\"Make\":\"Canon\"}");
        UUID photo2 = createPhoto(user, "nikon1.jpg");
        createPhotoMetadata(photo2, user, "{\"Make\":\"Nikon\"}");

        mockMvc.perform(get("/search/exif")
                        .param("field", "Make").param("value", "Canon")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void keywordSearch_returnsPaginatedResults() throws Exception {
        UUID user = createUser("searchkw@test.com");
        UUID photo1 = createPhoto(user, "sunset_kw.jpg");
        UUID photo2 = createPhoto(user, "mountain_kw.jpg");
        UUID keyword = createKeyword(user, "Sunset");
        linkPhotoKeyword(photo1, keyword, user);

        mockMvc.perform(get("/search/keyword")
                        .param("keywordId", keyword.toString())
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void fullTextSearch_doesNotReturnOtherUsersPhotos() throws Exception {
        UUID user1 = createUser("search2@test.com");
        UUID user2 = createUser("search3@test.com");
        createPhotoWithCaption(user1, "sunset.jpg", "Beautiful sunset");
        createPhotoWithCaption(user2, "sunset2.jpg", "Another sunset");

        mockMvc.perform(get("/search")
                        .param("q", "sunset")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
