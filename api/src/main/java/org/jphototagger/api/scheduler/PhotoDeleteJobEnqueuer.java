package org.jphototagger.api.scheduler;

import org.jphototagger.api.entity.Photo;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared component that enqueues photo delete-jobs onto the {@code delete-jobs}
 * Redis stream.  Centralises the message-format so all schedulers stay in sync.
 */
@Component
public class PhotoDeleteJobEnqueuer {

    private final StringRedisTemplate redisTemplate;

    public PhotoDeleteJobEnqueuer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Enqueues a delete-job for each {@link Photo} in {@code photos} using a
     * Lettuce pipeline, sending all XADD commands in a single round-trip.
     */
    public void enqueue(List<Photo> photos) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Photo photo : photos) {
                UUID photoId = photo.getId();
                UUID userId  = photo.getUserId();
                Map<String, String> msg = Map.of(
                        "photo_id",     photoId.toString(),
                        "original_key", photo.getStorageKey(),
                        "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
                        "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
                );
                redisTemplate.opsForStream().add("delete-jobs", msg);
            }
            return null;
        });
    }

    /**
     * Enqueues a single delete-job for an orphaned MinIO object where no
     * {@code photos} row exists.  The {@code originalKey} is taken directly
     * from the MinIO listing; thumbnails are derived from {@code userId} /
     * {@code photoId}.
     */
    public void enqueueOrphan(UUID userId, UUID photoId, String originalKey) {
        Map<String, String> msg = Map.of(
                "photo_id",     photoId.toString(),
                "original_key", originalKey,
                "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
                "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
        );
        redisTemplate.opsForStream().add("delete-jobs", msg);
    }
}
