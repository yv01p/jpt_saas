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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class AlbumControllerTest {

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

    private UUID createUser(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, used_bytes) VALUES (?, ?, ?, ?)",
                id, email, "hash", 0);
        return id;
    }

    private UUID createPhoto(UUID userId, String filename) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status) VALUES (?, ?, ?, ?, ?)",
                id, userId, filename, 1000, "done");
        return id;
    }

    private UUID createAlbum(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO albums (id, user_id, name) VALUES (?, ?, ?)", id, userId, name);
        return id;
    }

    @Test
    void listAlbums_returnsPaginated() throws Exception {
        UUID user = createUser("alblist@test.com");
        createAlbum(user, "Vacation");
        createAlbum(user, "Family");

        mockMvc.perform(get("/albums")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void createAlbum_returnsCreated() throws Exception {
        UUID user = createUser("albcreate@test.com");

        mockMvc.perform(post("/albums")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "New Album")))
                        .cookie(jwtCookie(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("New Album"));
    }

    @Test
    void addPhotoToAlbum_enforcesOwnership() throws Exception {
        UUID user1 = createUser("albown1@test.com");
        UUID user2 = createUser("albown2@test.com");
        UUID album = createAlbum(user1, "User1Album");
        UUID photo = createPhoto(user2, "user2photo.jpg");

        // user1 tries to add user2's photo to user1's album -> 404
        mockMvc.perform(post("/albums/" + album + "/photos/" + photo)
                        .with(csrf())
                        .cookie(jwtCookie(user1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void addPhotoToAlbum_succeeds() throws Exception {
        UUID user = createUser("albadd@test.com");
        UUID album = createAlbum(user, "MyAlbum");
        UUID photo = createPhoto(user, "myphoto.jpg");

        mockMvc.perform(post("/albums/" + album + "/photos/" + photo)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk());
    }

    @Test
    void removePhotoFromAlbum_succeeds() throws Exception {
        UUID user = createUser("albrem@test.com");
        UUID album = createAlbum(user, "RemAlbum");
        UUID photo = createPhoto(user, "remphoto.jpg");
        jdbcTemplate.update("INSERT INTO album_photos (album_id, photo_id, user_id) VALUES (?, ?, ?)",
                album, photo, user);

        mockMvc.perform(delete("/albums/" + album + "/photos/" + photo)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAlbum_removesAlbum() throws Exception {
        UUID user = createUser("albdel@test.com");
        UUID album = createAlbum(user, "ToDelete");

        mockMvc.perform(delete("/albums/" + album)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/albums/" + album)
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNotFound());
    }
}
