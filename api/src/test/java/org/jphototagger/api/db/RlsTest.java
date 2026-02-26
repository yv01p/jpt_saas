package org.jphototagger.api.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RlsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate txTemplate;

    @Test
    void rlsPreventsAccessToOtherUsersPhotos() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        // Transaction 1: Insert test data as superuser, COMMIT
        txTemplate.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
                userA, userA + "@test.com", "hash");
            jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
                userB, userB + "@test.com", "hash");
            jdbc.update("INSERT INTO photos (id, user_id, filename) VALUES (?, ?, ?)",
                photoId, userA, "test.jpg");
        });

        try {
            // Transaction 2: Query as jpt_app role with userB context
            txTemplate.executeWithoutResult(status -> {
                jdbc.execute("SET ROLE jpt_app");
                jdbc.execute("SET LOCAL app.current_user_id = '" + userB + "'");

                Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM photos WHERE id = ?",
                    Integer.class, photoId);
                assertThat(count).isEqualTo(0);

                jdbc.execute("RESET ROLE");
            });
        } finally {
            // Unconditionally reset role before cleanup to avoid RLS filtering out deletes
            try { jdbc.execute("RESET ROLE"); } catch (Exception ignored) {}
            jdbc.update("DELETE FROM photos WHERE id = ?", photoId);
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userA, userB);
        }
    }

    @Test
    void rlsPoliciesExist() {
        List<String> policyNames = jdbc.queryForList(
            "SELECT policyname FROM pg_policies WHERE policyname LIKE 'tenant_%' ORDER BY policyname",
            String.class);

        assertThat(policyNames).containsExactlyInAnyOrder(
            "tenant_users",
            "tenant_email_tokens",
            "tenant_photos",
            "tenant_photo_metadata",
            "tenant_keywords",
            "tenant_photo_keywords",
            "tenant_albums",
            "tenant_album_photos",
            "tenant_shares",
            "tenant_saved_searches"
        );
    }
}
