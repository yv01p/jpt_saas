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
import org.springframework.test.web.servlet.MvcResult;
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
class SavedSearchControllerTest {

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

    @Test
    void savedSearchCRUD() throws Exception {
        UUID user = createUser("sscrud@test.com");

        // Create
        MvcResult createResult = mockMvc.perform(post("/saved-searches")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "Sunsets", "queryJson", "{\"q\":\"sunset\"}")))
                        .cookie(jwtCookie(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sunsets"))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // Read
        mockMvc.perform(get("/saved-searches/" + id)
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sunsets"));

        // List
        mockMvc.perform(get("/saved-searches")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // Update
        mockMvc.perform(put("/saved-searches/" + id)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "Sunsets Updated", "queryJson", "{\"q\":\"sunset beach\"}")))
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sunsets Updated"));

        // Delete
        mockMvc.perform(delete("/saved-searches/" + id)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNoContent());

        // Verify deleted
        mockMvc.perform(get("/saved-searches/" + id)
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void savedSearch_returns404ForOtherUser() throws Exception {
        UUID user1 = createUser("ssown1@test.com");
        UUID user2 = createUser("ssown2@test.com");

        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO saved_searches (id, user_id, name, query_json) VALUES (?, ?, ?, ?::jsonb)",
                id, user1, "Private", "{\"q\":\"test\"}");

        mockMvc.perform(get("/saved-searches/" + id)
                        .cookie(jwtCookie(user2)))
                .andExpect(status().isNotFound());
    }
}
