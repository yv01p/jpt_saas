package org.jphototagger.api.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.repository.PhotoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Permanently removes soft-deleted photos whose {@code deleted_at} timestamp
 * has exceeded the configured retention window, and enqueues MinIO delete-jobs
 * for each purged photo.
 *
 * <p>Also cleans up photo rows with a null {@code storage_key} that were never
 * completed (compensating-Tx recovery for failed uploads).
 */
@Component
public class TrashPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);

    private final PhotoRepository photoRepository;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;

    public TrashPurgeScheduler(
            PhotoRepository photoRepository,
            StringRedisTemplate redisTemplate,
            JdbcTemplate jdbcTemplate,
            @Value("${jpt.trash.retention-days:30}") int retentionDays) {
        this.photoRepository = photoRepository;
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "trashPurge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeTrash() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("TrashPurgeScheduler: purging photos deleted before {} (retention={} days)",
                cutoff, retentionDays);

        List<Photo> batch;
        int totalPurged = 0;

        do {
            batch = photoRepository.findPurgeableBatch(cutoff);
            if (batch.isEmpty()) {
                break;
            }
            enqueueDeleteJobsBatch(batch);
            deletePhotosBatch(batch);
            totalPurged += batch.size();
            log.debug("TrashPurgeScheduler: purged batch of {} photos (total so far: {})",
                    batch.size(), totalPurged);
            // Do NOT advance page — deleted rows are gone; next query at page 0 returns the next batch.
        } while (!batch.isEmpty());

        log.info("TrashPurgeScheduler: purged {} photos in total", totalPurged);

        // Compensating-Tx cleanup: photo rows where upload never completed
        purgeNullStorageKeyPhotos();
    }

    /**
     * Enqueues a delete-job for each photo in the batch.
     * Adds messages to the Redis stream individually; Lettuce handles multiplexing.
     */
    private void enqueueDeleteJobsBatch(List<Photo> batch) {
        for (Photo photo : batch) {
            UUID photoId = photo.getId();
            UUID userId = photo.getUserId();
            Map<String, String> msg = Map.of(
                    "photo_id", photoId.toString(),
                    "original_key", photo.getStorageKey(),
                    "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
                    "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
            );
            redisTemplate.opsForStream().add("delete-jobs", msg);
        }
    }

    private void deletePhotosBatch(List<Photo> batch) {
        List<UUID> ids = batch.stream().map(Photo::getId).toList();
        photoRepository.deleteAllById(ids);
    }

    /**
     * Cleans up photo rows where {@code storage_key IS NULL} and
     * {@code created_at < now() - INTERVAL '1 hour'} — these are upload
     * compensating-Tx failures. Uses a CTE to atomically decrement
     * {@code used_bytes} on the owning user.
     *
     * <p>No delete-job is enqueued — the storage_key is unknown; true MinIO
     * orphans are handled by {@link OrphanReconciliationScheduler}.
     */
    private void purgeNullStorageKeyPhotos() {
        int rows = jdbcTemplate.update(
                "WITH deleted AS (" +
                "    DELETE FROM photos" +
                "    WHERE storage_key IS NULL" +
                "    AND deleted_at IS NULL" +
                "    AND uploaded_at < now() - INTERVAL '1 hour'" +
                "    RETURNING user_id, COALESCE(size_bytes, 0) AS size_bytes" +
                ")" +
                "UPDATE users u" +
                "  SET used_bytes = GREATEST(0, u.used_bytes - d.size_bytes)" +
                "  FROM deleted d" +
                "  WHERE u.id = d.user_id"
        );
        if (rows > 0) {
            log.info("TrashPurgeScheduler: removed {} null-storage_key photo rows", rows);
        }
    }
}
