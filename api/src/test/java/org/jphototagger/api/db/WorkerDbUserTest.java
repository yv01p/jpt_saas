package org.jphototagger.api.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class WorkerDbUserTest {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void resetRole() {
        try { jdbc.execute("RESET ROLE"); } catch (Exception ignored) {}
    }

    @Test
    void workerDbUserRoleExists() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM pg_roles WHERE rolname = 'worker_db_user'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void workerCannotAccessUsersTable() {
        jdbc.execute("SET ROLE worker_db_user");
        try {
            assertThatThrownBy(() ->
                jdbc.queryForObject("SELECT count(*) FROM users", Integer.class))
                .rootCause()
                .hasMessageContaining("permission denied");
        } finally {
            jdbc.execute("RESET ROLE");
        }
    }

    @Test
    void workerCannotDeleteFromPhotos() {
        jdbc.execute("SET ROLE worker_db_user");
        try {
            assertThatThrownBy(() ->
                jdbc.execute("DELETE FROM photos WHERE id = '00000000-0000-0000-0000-000000000000'"))
                .rootCause()
                .hasMessageContaining("permission denied");
        } finally {
            jdbc.execute("RESET ROLE");
        }
    }

    @Test
    void workerCannotAccessSharesTable() {
        jdbc.execute("SET ROLE worker_db_user");
        try {
            assertThatThrownBy(() ->
                jdbc.queryForObject("SELECT count(*) FROM shares", Integer.class))
                .rootCause()
                .hasMessageContaining("permission denied");
        } finally {
            jdbc.execute("RESET ROLE");
        }
    }
}
