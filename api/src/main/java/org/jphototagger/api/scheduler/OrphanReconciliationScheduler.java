package org.jphototagger.api.scheduler;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Weekly scheduler that identifies MinIO objects with no matching {@code photos}
 * row and enqueues them for deletion.
 *
 * <p>To avoid false positives from in-progress uploads (SA1-F4), objects
 * created within the last 2 hours are excluded. This gives the upload
 * pipeline ample time to complete Tx 2 (storage_key update) before an
 * object is considered orphaned.
 *
 * <p>To avoid OOM, user IDs are streamed from the DB and MinIO objects are
 * iterated lazily per user prefix.
 *
 * <p>Note: the {@code @Transactional(readOnly = true)} annotation holds a single
 * DB connection for the duration of the full reconciliation run (up to 2 hours).
 * On deployments with connection pool sizes below 10, consider configuring a
 * dedicated scheduler connection pool or restructuring to process users in
 * short-lived transactions.
 */
@Component
public class OrphanReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrphanReconciliationScheduler.class);

    /** Objects younger than this are excluded to avoid racing with in-progress uploads (SA1-F4). */
    private static final Duration RECENCY_THRESHOLD = Duration.ofHours(2);

    /** Maximum IDs per {@code findAllById} query to stay within PostgreSQL's 65,535-parameter limit. */
    private static final int ID_BATCH_SIZE = 1_000;

    private final UserRepository userRepository;
    private final PhotoRepository photoRepository;
    private final MinioClient minioInternalClient;
    private final PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer;
    private final String bucket;

    public OrphanReconciliationScheduler(
            UserRepository userRepository,
            PhotoRepository photoRepository,
            @Qualifier("minioInternalClient") MinioClient minioInternalClient,
            PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer,
            @Value("${minio.bucket}") String bucket) {
        this.userRepository = userRepository;
        this.photoRepository = photoRepository;
        this.minioInternalClient = minioInternalClient;
        this.photoDeleteJobEnqueuer = photoDeleteJobEnqueuer;
        this.bucket = bucket;
    }

    @Scheduled(cron = "0 0 4 * * SUN")
    @SchedulerLock(name = "orphanReconciliation", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    @Transactional(readOnly = true)
    public void reconcileOrphans() {
        log.info("OrphanReconciliationScheduler: starting orphan reconciliation");
        int orphansFound = 0;

        try (Stream<UUID> userIds = userRepository.streamAllIds()) {
            for (UUID userId : (Iterable<UUID>) userIds::iterator) {
                orphansFound += reconcileUser(userId);
            }
        }

        log.info("OrphanReconciliationScheduler: enqueued {} orphaned objects for deletion",
                orphansFound);
    }

    private int reconcileUser(UUID userId) {
        String prefix = userId + "/originals/";

        Iterable<Result<Item>> objects = minioInternalClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .recursive(false)
                        .build());

        // Pass 1: collect all parseable (photoId -> objectKey) pairs from MinIO listing.
        // Keeps only entries under the expected originals prefix.
        // Objects younger than RECENCY_THRESHOLD are skipped to avoid racing with
        // in-progress uploads that have completed the MinIO PUT but not yet committed
        // Tx 2 (storage_key update). See SA1-F4.
        ZonedDateTime recencyCutoff = ZonedDateTime.now().minus(RECENCY_THRESHOLD);
        Map<UUID, String> candidateKeys = new HashMap<>();
        for (Result<Item> result : objects) {
            try {
                Item item = result.get();
                if (item.isDir()) {
                    continue;
                }

                // Skip recently-created objects — upload may still be in progress.
                // null lastModified treated as "recent enough to skip" (conservative safe default). SA4-F8.
                if (item.lastModified() == null || item.lastModified().isAfter(recencyCutoff)) {
                    if (item.lastModified() == null) {
                        log.warn("OrphanReconciliationScheduler: null lastModified for key={}, skipping (treating as recent)", item.objectName());
                    }
                    continue;
                }

                String objectKey = item.objectName();

                // Skip non-originals paths
                if (!objectKey.startsWith(userId + "/originals/")) {
                    continue;
                }

                // Parse photo_id from key: {userId}/originals/{photoId}.{ext}
                UUID photoId = extractPhotoId(objectKey);
                if (photoId == null) {
                    log.warn("OrphanReconciliationScheduler: could not parse photo_id from key={}", objectKey);
                    continue;
                }

                candidateKeys.put(photoId, objectKey);

            } catch (Exception e) {
                log.error("OrphanReconciliationScheduler: error processing MinIO object", e);
            }
        }

        if (candidateKeys.isEmpty()) {
            return 0;
        }

        // Pass 2: batch DB query — find which candidate IDs actually exist.
        // Partitioned into ID_BATCH_SIZE chunks to avoid exceeding PostgreSQL's
        // 65,535 bound-parameter limit on the generated IN-clause.
        List<UUID> candidateIds = new ArrayList<>(candidateKeys.keySet());
        Set<UUID> existingIds = findExistingIds(candidateIds);

        // Pass 3: enqueue delete-jobs only for true orphans (not in DB).
        int count = 0;
        for (Map.Entry<UUID, String> entry : candidateKeys.entrySet()) {
            UUID photoId  = entry.getKey();
            String objectKey = entry.getValue();
            if (!existingIds.contains(photoId)) {
                photoDeleteJobEnqueuer.enqueueOrphan(userId, photoId, objectKey);
                count++;
                log.debug("OrphanReconciliationScheduler: orphan enqueued key={}", objectKey);
            }
        }

        return count;
    }

    /**
     * Queries the DB for which IDs in {@code candidateIds} exist as {@code photos} rows,
     * partitioning into batches of {@value #ID_BATCH_SIZE} to stay within PostgreSQL's
     * per-statement bound-parameter limit.
     */
    private Set<UUID> findExistingIds(List<UUID> candidateIds) {
        Set<UUID> existingIds = new HashSet<>();
        for (int i = 0; i < candidateIds.size(); i += ID_BATCH_SIZE) {
            List<UUID> batch = candidateIds.subList(i, Math.min(i + ID_BATCH_SIZE, candidateIds.size()));
            photoRepository.findAllById(batch).forEach(p -> existingIds.add(p.getId()));
        }
        return existingIds;
    }

    /**
     * Extracts the photo UUID from an object key of the form
     * {@code {userId}/originals/{photoId}.{ext}}.
     *
     * @return the parsed UUID, or {@code null} if parsing fails
     */
    private UUID extractPhotoId(String objectKey) {
        try {
            int lastSlash = objectKey.lastIndexOf('/');
            if (lastSlash < 0) {
                return null;
            }
            String filename = objectKey.substring(lastSlash + 1);
            int dot = filename.lastIndexOf('.');
            String uuidStr = dot >= 0 ? filename.substring(0, dot) : filename;
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
