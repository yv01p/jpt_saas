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
class KeywordControllerTest {

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

    private UUID createKeyword(UUID userId, String name, UUID parentId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO keywords (id, user_id, name, parent_id) VALUES (?, ?, ?, ?)",
                id, userId, name, parentId);
        return id;
    }

    @Test
    void listKeywords_returnsHierarchicalTree() throws Exception {
        UUID user = createUser("kwlist@test.com");
        UUID nature = createKeyword(user, "Nature", null);
        createKeyword(user, "Sunset", nature);

        mockMvc.perform(get("/keywords")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.name=='Sunset')].parentId").value(nature.toString()));
    }

    @Test
    void getKeywordSubtree_usesRecursiveCTE() throws Exception {
        UUID user = createUser("kwsubtree@test.com");
        UUID animals = createKeyword(user, "Animals", null);
        UUID dogs = createKeyword(user, "Dogs", animals);
        createKeyword(user, "Labrador", dogs);

        mockMvc.perform(get("/keywords/" + animals + "/subtree")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void createKeyword_returnsCreated() throws Exception {
        UUID user = createUser("kwcreate@test.com");

        mockMvc.perform(post("/keywords")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Landscape")))
                        .cookie(jwtCookie(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Landscape"));
    }

    @Test
    void getKeyword_returns404ForOtherUser() throws Exception {
        UUID user1 = createUser("kwown1@test.com");
        UUID user2 = createUser("kwown2@test.com");
        UUID kw = createKeyword(user1, "Private", null);

        mockMvc.perform(get("/keywords/" + kw)
                        .cookie(jwtCookie(user2)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteKeyword_removesKeyword() throws Exception {
        UUID user = createUser("kwdel@test.com");
        UUID kw = createKeyword(user, "ToDelete", null);

        mockMvc.perform(delete("/keywords/" + kw)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/keywords/" + kw)
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNotFound());
    }
}
