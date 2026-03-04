package org.jphototagger.api.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class UserEntityTest {

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
    }

    @Autowired
    private EntityManager em;

    @Test
    void userPersistsAndLoads() {
        User user = new User();
        user.setEmail("test@example.com");
        user.setPasswordHash("$2a$12$hashedpassword");
        em.persist(user);
        em.flush();
        em.clear();

        User loaded = em.find(User.class, user.getId());
        assertThat(loaded.getEmail()).isEqualTo("test@example.com");
        assertThat(loaded.getQuotaBytes()).isEqualTo(10737418240L);
        assertThat(loaded.getUsedBytes()).isEqualTo(0L);
    }
}
