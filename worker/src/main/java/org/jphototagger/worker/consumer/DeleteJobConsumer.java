package org.jphototagger.worker.consumer;

import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.jphototagger.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Single-threaded Redis Streams consumer for the {@code delete-jobs} stream.
 *
 * <p>Deletes original and thumbnail MinIO objects for purged photos.
 * Scale concurrency by running additional worker container instances.
 *
 * <p>Consumer group: {@code delete-processors}.
 *
 * <p>This class is instantiated and managed by {@link ConsumerConfig}; it is not
 * a {@code @Component} to avoid double-registration with the application context.
 *
 * @see ConsumerConfig
 */
public class DeleteJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeleteJobConsumer.class);

    // -------------------------------------------------------------------------
    // Constants — package-private so tests can reference them
    // -------------------------------------------------------------------------

    static final String STREAM = "delete-jobs";
    static final String GROUP  = "delete-processors";

    /**
     * Validates the UUID-based storage key format (SA3-F2).
     * Prevents injection of arbitrary MinIO paths from a compromised Redis instance.
     *
     * <p>Accepted pattern:
     * {@code {uuid}/(originals|thumbnails)/{uuid}[_sm|_md].{ext}}
     */
    private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
            "/(originals|thumbnails)/" +
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
            "(_sm|_md)?\\.[a-z0-9]+$");

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final RedisCommands<String, String> redisCommands;
    private final MinioClient minioClient;
    private final WorkerProperties workerProperties;
    private final String bucket;
    private final String consumerName;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructor used by tests and Spring (via {@link ConsumerConfig}).
     */
    DeleteJobConsumer(RedisCommands<String, String> redisCommands,
                      MinioClient minioClient,
                      WorkerProperties workerProperties,
                      String bucket,
                      String consumerName) {
        this.redisCommands   = redisCommands;
        this.minioClient     = minioClient;
        this.workerProperties = workerProperties;
        this.bucket          = bucket;
        this.consumerName    = consumerName;

        log.info("DeleteJobConsumer starting with consumerName={}", consumerName);
    }

    // -------------------------------------------------------------------------
    // Consumer group bootstrap
    // -------------------------------------------------------------------------

    /**
     * Creates the consumer group with MKSTREAM if it does not yet exist.
     */
    void ensureGroupExists() {
        try {
            redisCommands.xgroupCreate(
                    XReadArgs.StreamOffset.from(STREAM, "0"),
                    GROUP,
                    new io.lettuce.core.XGroupCreateArgs().mkstream(true));
            log.info("Consumer group '{}' created on stream '{}'", GROUP, STREAM);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists on stream '{}'", GROUP, STREAM);
            } else {
                log.warn("Unexpected error creating consumer group '{}': {}", GROUP, e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Main poll loop
    // -------------------------------------------------------------------------

    /**
     * Reads up to one message from the delete-jobs stream and processes it.
     */
    void pollOnce() {
        Consumer<String> consumer = Consumer.from(GROUP, consumerName);
        XReadArgs readArgs = XReadArgs.Builder.count(1).block(2000);

        List<StreamMessage<String, String>> messages = redisCommands.xreadgroup(
                consumer,
                readArgs,
                XReadArgs.StreamOffset.lastConsumed(STREAM));

        if (messages == null || messages.isEmpty()) {
            return;
        }

        processMessage(messages.get(0));
    }

    // -------------------------------------------------------------------------
    // Message processing
    // -------------------------------------------------------------------------

    private void processMessage(StreamMessage<String, String> msg) {
        String messageId  = msg.getId();
        Map<String, String> body = msg.getBody();

        String photoId    = body.get("photo_id");
        String originalKey = body.get("original_key");
        String thumbnailSm = body.get("thumbnail_sm");
        String thumbnailMd = body.get("thumbnail_md");

        // SA2-F5: Null/blank original_key guard — prevents cascading retry storm
        if (originalKey == null || originalKey.isBlank()) {
            log.error("delete-job received with null/blank originalKey — XACK and skip, photo_id={}",
                    sanitizeForLog(photoId));
            redisCommands.xack(STREAM, GROUP, messageId);
            return;
        }

        // SA3-F2: Format validation on original_key
        if (!isValidStorageKey(originalKey)) {
            log.error("delete-job originalKey failed format validation — XACK and skip, " +
                      "key={}, photo_id={}", sanitizeForLog(originalKey), sanitizeForLog(photoId));
            redisCommands.xack(STREAM, GROUP, messageId);
            return;
        }

        // XACK before deletes — at-most-once semantics: a crash after this point
        // loses the remaining deletes, which is acceptable (MinIO removeObject is
        // idempotent and OrphanReconciliationScheduler cleans originals weekly).
        // A crash before XACK would permanently orphan the PEL entry because there
        // is no XAUTOCLAIM recovery on this stream.
        redisCommands.xack(STREAM, GROUP, messageId);

        deleteObject(originalKey, photoId, messageId);

        // Delete thumbnails — skip if key is invalid (may be absent for photos
        // that never completed thumbnail generation), but log at WARN
        if (isValidStorageKey(thumbnailSm)) {
            deleteObject(thumbnailSm, photoId, messageId);
        } else {
            log.warn("thumbnail_sm key invalid format — skipping, photo_id={}", sanitizeForLog(photoId));
        }

        if (isValidStorageKey(thumbnailMd)) {
            deleteObject(thumbnailMd, photoId, messageId);
        } else {
            log.warn("thumbnail_md key invalid format — skipping, photo_id={}", sanitizeForLog(photoId));
        }

        log.info("Delete-job completed: photo_id={}, original_key={}", sanitizeForLog(photoId), originalKey);
    }

    // -------------------------------------------------------------------------
    // MinIO helpers
    // -------------------------------------------------------------------------

    /**
     * Attempts to delete a single MinIO object. On failure, logs at ERROR and
     * continues — XACK has already been issued before this method is called
     * (at-most-once delete semantics).
     */
    private void deleteObject(String key, String photoId, String messageId) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .build());
            log.debug("Deleted MinIO object: {}", key);
        } catch (Exception e) {
            log.error("Failed to delete MinIO object '{}' for photo_id={} (message={}) — " +
                      "XACK will still be issued (at-most-once delete semantics)",
                      key, sanitizeForLog(photoId), messageId, e);
            // Do NOT re-throw — caller must be able to XACK the message regardless.
        }
    }

    // -------------------------------------------------------------------------
    // Log sanitization (SA4-F7)
    // -------------------------------------------------------------------------

    /** Strips newlines and control characters from Redis-sourced values before logging. */
    private static String sanitizeForLog(String s) {
        return s == null ? "<null>" : s.replaceAll("[\r\n\t]", "_").replaceAll("[\u001B\\p{Cntrl}]", "?");
    }

    // -------------------------------------------------------------------------
    // Storage key format validation (SA3-F2)
    // -------------------------------------------------------------------------

    private boolean isValidStorageKey(String key) {
        return key != null && STORAGE_KEY_PATTERN.matcher(key).matches();
    }
}
