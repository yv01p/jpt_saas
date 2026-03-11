package org.jphototagger.api.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.repository.PhotoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Daily scheduler that permanently removes accounts where {@code email_verified = false}
 * and {@code created_at < now() - INTERVAL '7 days'}.
 *
 * <p>Strict deletion order:
 * <ol>
 *   <li>Query storage keys for all user photos.</li>
 *   <li>Enqueue MinIO delete-jobs in Redis (pipeline) — committed to Redis before any DB row is removed.</li>
 *   <li>Delete DB records (user cascade removes photos, keywords, albums, saved searches).</li>
 * </ol>
 *
 * <p>Uses {@code authJdbcTemplate} (BYPASSRLS) for user-level operations.
 */
@Component
public class UnverifiedAccountPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(UnverifiedAccountPurgeScheduler.class);

    private final JdbcTemplate authJdbcTemplate;
    private final TransactionTemplate authTxTemplate;
    private final PhotoRepository photoRepository;
    private final PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer;

    public UnverifiedAccountPurgeScheduler(
            @Qualifier("authJdbcTemplate") JdbcTemplate authJdbcTemplate,
            @Qualifier("authDataSource") DataSource authDataSource,
            PhotoRepository photoRepository,
            PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer) {
        this.authJdbcTemplate = authJdbcTemplate;
        this.authTxTemplate = new TransactionTemplate(new DataSourceTransactionManager(authDataSource));
        this.photoRepository = photoRepository;
        this.photoDeleteJobEnqueuer = photoDeleteJobEnqueuer;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "unverifiedAccountPurge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeUnverifiedAccounts() {
        log.info("UnverifiedAccountPurgeScheduler: querying stale unverified accounts");

        // Query all unverified users older than 7 days via authJdbcTemplate (BYPASSRLS)
        List<UUID> staleUserIds = authJdbcTemplate.query(
                "SELECT id FROM users WHERE email_verified = false "
                        + "AND created_at < now() - INTERVAL '7 days'",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")));

        if (staleUserIds.isEmpty()) {
            log.info("UnverifiedAccountPurgeScheduler: no stale unverified accounts found");
            return;
        }

        log.info("UnverifiedAccountPurgeScheduler: purging {} stale unverified accounts",
                staleUserIds.size());

        for (UUID userId : staleUserIds) {
            purgeUser(userId);
        }

        log.info("UnverifiedAccountPurgeScheduler: purge complete");
    }

    private void purgeUser(UUID userId) {
        // Step 1: Query photos with storage keys for this user (need these for MinIO cleanup)
        List<Photo> photos = photoRepository.findAllByUserIdWithStorageKey(userId);

        // Step 2: Enqueue MinIO delete-jobs BEFORE any DB row is removed.
        // Jobs are in Redis before any DB delete — crash-safe.
        if (!photos.isEmpty()) {
            enqueueDeleteJobsBatch(photos);
        }

        // Step 3: Delete all DB records atomically using the auth datasource transaction.
        // A single transaction ensures no orphaned child rows if the process crashes mid-purge.
        authTxTemplate.executeWithoutResult(status -> {
            // album_photos has NO ON DELETE CASCADE from photos — must go before photos.
            // photo_keywords/photo_metadata DO have ON DELETE CASCADE from photos — auto-removed.
            authJdbcTemplate.update("DELETE FROM album_photos WHERE user_id = ?", userId);
            authJdbcTemplate.update("DELETE FROM photos WHERE user_id = ?", userId);
            // albums, saved_searches, shares, email_tokens, keywords have no cascade from photos — delete directly.
            authJdbcTemplate.update("DELETE FROM albums WHERE user_id = ?", userId);
            authJdbcTemplate.update("DELETE FROM saved_searches WHERE user_id = ?", userId);
            authJdbcTemplate.update("DELETE FROM shares WHERE user_id = ?", userId);
            authJdbcTemplate.update("DELETE FROM email_tokens WHERE user_id = ?", userId);
            // keywords: self-referencing parent_id FK has no cascade — break hierarchy first, then delete all
            authJdbcTemplate.update("UPDATE keywords SET parent_id = NULL WHERE user_id = ?", userId);
            authJdbcTemplate.update("DELETE FROM keywords WHERE user_id = ?", userId);
            // Step 4: Delete the user row
            authJdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        });

        log.debug("UnverifiedAccountPurgeScheduler: purged user={} ({} photos queued for MinIO delete)",
                userId, photos.size());
    }

    private void enqueueDeleteJobsBatch(List<Photo> photos) {
        photoDeleteJobEnqueuer.enqueue(photos);
    }
}
