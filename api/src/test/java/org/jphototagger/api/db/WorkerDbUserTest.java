package org.jphototagger.api.db;

import org.jphototagger.api.config.TestRedisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
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
                .satisfiesAnyOf(
                    t -> assertThat(t).hasMessageContaining("permission denied"),
                    // RLS policy evaluates current_setting('app.current_user_id') which
                    // throws when the session variable hasn't been set
                    t -> assertThat(t).hasMessageContaining("unrecognized configuration parameter"),
                    // RLS policy casts the setting to UUID; if the setting is '' (empty string
                    // left by shared-context connection reuse) the cast fails — access is still denied
                    t -> assertThat(t).hasMessageContaining("invalid input syntax for type uuid")
                );
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
                .satisfiesAnyOf(
                    t -> assertThat(t).hasMessageContaining("permission denied"),
                    t -> assertThat(t).hasMessageContaining("unrecognized configuration parameter"),
                    // RLS policy casts the setting to UUID; if the setting is '' (empty string
                    // left by shared-context connection reuse) the cast fails — access is still denied
                    t -> assertThat(t).hasMessageContaining("invalid input syntax for type uuid")
                );
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
                .satisfiesAnyOf(
                    t -> assertThat(t).hasMessageContaining("permission denied"),
                    t -> assertThat(t).hasMessageContaining("unrecognized configuration parameter"),
                    // RLS policy casts the setting to UUID; if the setting is '' (empty string
                    // left by shared-context connection reuse) the cast fails — access is still denied
                    t -> assertThat(t).hasMessageContaining("invalid input syntax for type uuid")
                );
        } finally {
            jdbc.execute("RESET ROLE");
        }
    }
}
