package org.jphototagger.api.db;

import org.jphototagger.api.config.TestRedisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class SchemaTest {

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

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {
        "users", "email_tokens", "photos", "photo_metadata",
        "keywords", "photo_keywords", "albums", "album_photos",
        "shares", "saved_searches"
    })
    void tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
            Integer.class, tableName);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void photosTableHasProcessingStatus() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = 'photos' AND column_name = 'processing_status'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void photosTableHasDeletedAt() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = 'photos' AND column_name = 'deleted_at'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void deduplicationConstraintExists() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints WHERE constraint_name = 'uq_user_content_hash'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
