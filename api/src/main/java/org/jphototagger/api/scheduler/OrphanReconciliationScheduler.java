package org.jphototagger.api.scheduler;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Weekly scheduler that identifies MinIO objects with no matching {@code photos}
 * row and enqueues them for deletion.
 *
 * <p>To avoid OOM, user IDs are streamed from the DB and MinIO objects are
 * iterated lazily per user prefix.
 */
@Component
public class OrphanReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrphanReconciliationScheduler.class);

    private final UserRepository userRepository;
    private final PhotoRepository photoRepository;
    private final MinioClient minioInternalClient;
    private final StringRedisTemplate redisTemplate;
    private final String bucket;

    public OrphanReconciliationScheduler(
            UserRepository userRepository,
            PhotoRepository photoRepository,
            @Qualifier("minioInternalClient") MinioClient minioInternalClient,
            StringRedisTemplate redisTemplate,
            @Value("${minio.bucket}") String bucket) {
        this.userRepository = userRepository;
        this.photoRepository = photoRepository;
        this.minioInternalClient = minioInternalClient;
        this.redisTemplate = redisTemplate;
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
        int count = 0;

        Iterable<Result<Item>> objects = minioInternalClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .recursive(false)
                        .build());

        for (Result<Item> result : objects) {
            try {
                Item item = result.get();
                if (item.isDir()) {
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

                // Check if photo row exists
                if (photoRepository.existsById(photoId)) {
                    continue;
                }

                // True orphan — enqueue delete-job
                Map<String, String> msg = Map.of(
                        "photo_id", photoId.toString(),
                        "original_key", objectKey,
                        "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
                        "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
                );
                redisTemplate.opsForStream().add("delete-jobs", msg);
                count++;
                log.debug("OrphanReconciliationScheduler: orphan enqueued key={}", objectKey);

            } catch (Exception e) {
                log.error("OrphanReconciliationScheduler: error processing MinIO object", e);
            }
        }

        return count;
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
