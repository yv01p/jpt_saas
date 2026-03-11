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
class PhotoMetadataControllerTest {

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

    private static final String GPS_EXIF_JSON =
            "{\"GPS:GPSLatitude\": 48.8566, \"GPS:GPSLongitude\": 2.3522, \"Make\": \"Canon\"}";

    private Cookie jwtCookie(UUID userId) {
        String token = jwtService.generateToken(userId, userId + "@test.com");
        return new Cookie("jwt", token);
    }

    private UUID createUser(String email, boolean showGps) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, used_bytes, show_gps) VALUES (?, ?, ?, ?, ?)",
                id, email, "hash", 0, showGps);
        return id;
    }

    private UUID createPhoto(UUID userId, String filename) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status) VALUES (?, ?, ?, ?, ?)",
                id, userId, filename, 1000, "DONE");
        return id;
    }

    private void createMetadata(UUID photoId, UUID userId, String exifJson) {
        jdbcTemplate.update(
                "INSERT INTO photo_metadata (photo_id, user_id, exif_data) VALUES (?, ?, ?::jsonb)",
                photoId, userId, exifJson);
    }

    @Test
    void getMetadata_withShowGpsFalse_stripsGpsFields() throws Exception {
        // SA4-F1: when user has show_gps=false, GPS fields must not be in response
        UUID userId = createUser("nogps@test.com", false);
        UUID photoId = createPhoto(userId, "nogps.jpg");
        createMetadata(photoId, userId, GPS_EXIF_JSON);

        mockMvc.perform(get("/photos/" + photoId + "/metadata")
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gps_latitude").doesNotExist())
                .andExpect(jsonPath("$.gps_longitude").doesNotExist())
                .andExpect(jsonPath("$.exif_data['GPS:GPSLatitude']").doesNotExist())
                .andExpect(jsonPath("$.exif_data['GPS:GPSLongitude']").doesNotExist())
                .andExpect(jsonPath("$.exif_data.Make").value("Canon"));
    }

    @Test
    void getMetadata_withShowGpsTrue_includesGpsFields() throws Exception {
        UUID userId = createUser("withgps@test.com", true);
        UUID photoId = createPhoto(userId, "withgps.jpg");
        createMetadata(photoId, userId, GPS_EXIF_JSON);

        mockMvc.perform(get("/photos/" + photoId + "/metadata")
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gps_latitude").value(48.8566))
                .andExpect(jsonPath("$.gps_longitude").value(2.3522))
                .andExpect(jsonPath("$.exif_data['GPS:GPSLatitude']").value(48.8566))
                .andExpect(jsonPath("$.exif_data['GPS:GPSLongitude']").value(2.3522))
                .andExpect(jsonPath("$.exif_data.Make").value("Canon"));
    }
}
