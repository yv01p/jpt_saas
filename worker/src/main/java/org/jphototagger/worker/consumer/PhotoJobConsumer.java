package org.jphototagger.worker.consumer;

import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.Range.Boundary;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.models.stream.PendingMessage;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.enums.ProcessingStatus;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.worker.config.WorkerProperties;
import org.jphototagger.worker.exception.ProcessingException;
import org.jphototagger.worker.pipeline.ImageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single-threaded Redis Streams consumer for the {@code photo-jobs} stream.
 *
 * <p>Scale concurrency by running additional worker container instances, not by
 * adding threads per instance.
 *
 * <p>Consumer group: {@code photo-processors}.
 * Consumer name: {@code HOSTNAME + "-" + PID}.
 *
 * <p>This class is instantiated and managed by {@link ConsumerConfig}; it is not
 * a {@code @Component} to avoid double-registration with the application context.
 */
public class PhotoJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(PhotoJobConsumer.class);

    // -------------------------------------------------------------------------
    // Constants — package-private so tests can reference them without reflection
    // -------------------------------------------------------------------------

    static final String STREAM = "photo-jobs";
    static final String GROUP  = "photo-processors";

    /** Redis key for the distributed startup-recovery lock. */
    static final String RECOVERY_LOCK_KEY = "worker:startup-recovery-lock";

    /** Lock TTL in milliseconds (5 minutes). */
    private static final long RECOVERY_LOCK_TTL_MS = 300_000L;

    /** DB page size for startup-recovery scan. */
    private static final int RECOVERY_PAGE_SIZE = 100;

    /** PEL pagination batch size. */
    private static final int PEL_PAGE_SIZE = 1000;

    /** XAUTOCLAIM cursor start — begins from the oldest PEL entry. */
    private static final String AUTOCLAIM_START_ID = "0-0";

    /** Lua script that refreshes the lock only if this instance still owns it. */
    private static final String REFRESH_LOCK_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then\n" +
            "  return redis.call('SET', KEYS[1], ARGV[1], 'XX', 'PX', ARGV[2])\n" +
            "else\n" +
            "  return nil\n" +
            "end";

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final RedisCommands<String, String> redisCommands;
    private final PhotoRepository photoRepository;
    private final ImageProcessor imageProcessor;
    private final WorkerProperties workerProperties;
    private final String consumerName;

    // -------------------------------------------------------------------------
    // Constructor — called by Spring or directly from tests
    // -------------------------------------------------------------------------

    /**
     * Primary constructor used by tests and by Spring (via the {@link PhotoJobConsumerFactory} bean).
     */
    PhotoJobConsumer(RedisCommands<String, String> redisCommands,
                     PhotoRepository photoRepository,
                     ImageProcessor imageProcessor,
                     WorkerProperties workerProperties,
                     String consumerName) {
        this.redisCommands  = redisCommands;
        this.photoRepository = photoRepository;
        this.imageProcessor  = imageProcessor;
        this.workerProperties = workerProperties;
        this.consumerName    = consumerName;

        log.info("PhotoJobConsumer starting with consumerName={}", consumerName);
    }

    // -------------------------------------------------------------------------
    // Consumer group bootstrap — called once at startup
    // -------------------------------------------------------------------------

    /**
     * Creates the consumer group with MKSTREAM if it does not yet exist.
     * Idempotent — safe to call on every startup.
     */
    void ensureGroupExists() {
        try {
            redisCommands.xgroupCreate(
                    XReadArgs.StreamOffset.from(STREAM, "0"),
                    GROUP,
                    new io.lettuce.core.XGroupCreateArgs().mkstream(true));
            log.info("Consumer group '{}' created on stream '{}'", GROUP, STREAM);
        } catch (Exception e) {
            // BUSYGROUP means the group already exists — expected on restarts
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists on stream '{}'", GROUP, STREAM);
            } else {
                log.warn("Unexpected error creating consumer group '{}': {}", GROUP, e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Main poll loop — driven by ConsumerScheduler
    // -------------------------------------------------------------------------

    /**
     * Reads up to one message from the stream, processes it, and returns.
     * One-in-flight-at-a-time per instance.
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

        StreamMessage<String, String> msg = messages.get(0);
        processMessage(msg);
    }

    // -------------------------------------------------------------------------
    // Message processing
    // -------------------------------------------------------------------------

    private void processMessage(StreamMessage<String, String> msg) {
        String messageId = msg.getId();
        String photoIdStr = msg.getBody().get("photo_id");

        if (photoIdStr == null || photoIdStr.isBlank()) {
            log.error("Received message {} with null/blank photo_id — XACK and skip", messageId);
            redisCommands.xack(STREAM, GROUP, messageId);
            return;
        }

        UUID photoId;
        try {
            photoId = UUID.fromString(photoIdStr);
        } catch (IllegalArgumentException e) {
            log.error("Received message {} with invalid photo_id '{}' — XACK and skip",
                    messageId, photoIdStr);
            redisCommands.xack(STREAM, GROUP, messageId);
            return;
        }

        Optional<Photo> maybePhoto = photoRepository.findById(photoId);
        if (maybePhoto.isEmpty()) {
            log.warn("Photo {} not found in DB — XACK and skip (message={})", photoId, messageId);
            redisCommands.xack(STREAM, GROUP, messageId);
            return;
        }

        Photo photo = maybePhoto.get();

        // Route on terminal / already-processed states
        ProcessingStatus status = photo.getProcessingStatus();
        if (status == ProcessingStatus.DONE) {
            log.info("Photo {} already DONE — XACK and skip (duplicate delivery)", photoId);
            redisCommands.xack(STREAM, GROUP, messageId);
            return;
        }
        if (status == ProcessingStatus.FAILED) {
            log.info("Photo {} is in terminal FAILED state — XACK and skip", photoId);
            redisCommands.xack(STREAM, GROUP, messageId);
            return;
        }

        // Null storage_key guard — upload never completed (between Tx 1 and Tx 2)
        if (photo.getStorageKey() == null) {
            log.error("photo {} has null storage_key — XACK and skip; " +
                      "TrashPurgeScheduler will clean up", photoId);
            redisCommands.xack(STREAM, GROUP, messageId);
            return;
        }

        // PENDING or PROCESSING (PROCESSING = XAUTOCLAIM reclaim of crashed worker)
        photo.setProcessingStatus(ProcessingStatus.PROCESSING);
        photoRepository.save(photo);

        try {
            imageProcessor.process(photoId);
            // ImageProcessor sets DONE internally, but we also XACK here
            redisCommands.xack(STREAM, GROUP, messageId);
        } catch (ProcessingException e) {
            log.error("Processing failed for photo {} (message={}): {}", photoId, messageId, e.getMessage());
            handleRetryOrDeadLetter(photo, messageId, photoId);
        } catch (Exception e) {
            log.error("Unexpected error processing photo {} (message={})", photoId, messageId, e);
            handleRetryOrDeadLetter(photo, messageId, photoId);
        }
    }

    /**
     * After a processing failure, check the delivery count via XPENDING.
     * If {@code >= MAX_RETRIES}, mark FAILED and XACK (dead-letter).
     * Otherwise, leave unacknowledged — XAUTOCLAIM will retry after the idle window.
     */
    private void handleRetryOrDeadLetter(Photo photo, String messageId, UUID photoId) {
        int maxRetries = workerProperties.getStreams().getMaxRetries();

        List<PendingMessage> pending = redisCommands.xpending(
                STREAM, GROUP,
                Range.create(messageId, messageId),
                Limit.from(1));

        long deliveryCount = pending.isEmpty() ? 0L : pending.get(0).getRedeliveryCount();

        if (deliveryCount >= maxRetries) {
            log.error("Photo {} exceeded max retries ({}) — marking FAILED and dead-lettering",
                    photoId, maxRetries);
            try {
                photo.setProcessingStatus(ProcessingStatus.FAILED);
                photoRepository.save(photo);
            } catch (Exception ex) {
                log.error("Failed to mark photo {} as FAILED in DB", photoId, ex);
            }
            redisCommands.xack(STREAM, GROUP, messageId);
            // Optionally write to dead-letter stream for inspection
            try {
                redisCommands.xadd("dead-letter", Map.of(
                        "photo_id", photoId.toString(),
                        "message_id", messageId,
                        "reason", "max_retries_exceeded"));
            } catch (Exception ex) {
                log.warn("Failed to write to dead-letter stream for photo {}", photoId, ex);
            }
        } else {
            log.warn("Photo {} failed (delivery {}); leaving unACKed for XAUTOCLAIM retry",
                    photoId, deliveryCount);
        }
    }

    // -------------------------------------------------------------------------
    // XAUTOCLAIM — scheduled every 5 minutes
    // -------------------------------------------------------------------------

    /**
     * Claims messages that have been idle longer than {@code claim-idle-time-ms}
     * (default 30 minutes). These belong to crashed or hung workers.
     * Called by {@link ConsumerScheduler} on a fixed-delay schedule.
     */
    void reclaimIdleMessages() {
        long idleMs = workerProperties.getStreams().getClaimIdleTimeMs();
        Consumer<String> consumer = Consumer.from(GROUP, consumerName);

        XAutoClaimArgs<String> args = new XAutoClaimArgs<String>()
                .consumer(consumer)
                .minIdleTime(idleMs)
                .startId(AUTOCLAIM_START_ID)
                .count(10);

        ClaimedMessages<String, String> claimed = redisCommands.xautoclaim(STREAM, args);
        List<StreamMessage<String, String>> messages = claimed.getMessages();

        if (messages == null || messages.isEmpty()) {
            return;
        }

        log.info("XAUTOCLAIM reclaimed {} idle message(s) on stream '{}'", messages.size(), STREAM);
        for (StreamMessage<String, String> msg : messages) {
            processMessage(msg);
        }
    }

    // -------------------------------------------------------------------------
    // Startup recovery — re-enqueue PENDING/PROCESSING photos not in PEL
    // -------------------------------------------------------------------------

    /**
     * On startup, re-enqueues photos whose processing was interrupted without
     * leaving a PEL entry (e.g., crash before XREADGROUP).
     *
     * <p>Uses a distributed Redis lock so only one worker instance runs recovery.
     */
    void performStartupRecovery() {
        String instanceId = consumerName;

        // Acquire distributed lock (NX = set only if not exists)
        String lockResult = redisCommands.set(
                RECOVERY_LOCK_KEY, instanceId,
                SetArgs.Builder.nx().px(RECOVERY_LOCK_TTL_MS));

        if (!"OK".equals(lockResult)) {
            log.info("Startup recovery: lock not acquired — another instance is running recovery");
            return;
        }

        log.info("Startup recovery: acquired lock — scanning for orphaned PENDING/PROCESSING photos");

        try {
            // Step 1: paginate full PEL into a set for deduplication
            Set<String> pelPhotoIds = paginatePel();

            // Step 2: page through PENDING/PROCESSING DB rows
            int page = 0;
            Page<Photo> dbPage;
            do {
                // Refresh lock before processing each batch (ownership-verified Lua script).
                // If the script returns nil, another instance has stolen the lock (e.g., due
                // to a GC pause exceeding the TTL). Abort immediately — do not enqueue this
                // batch to prevent two instances from concurrently re-enqueuing the same photos.
                String refreshResult = redisCommands.eval(
                        REFRESH_LOCK_SCRIPT,
                        ScriptOutputType.STATUS,
                        new String[]{RECOVERY_LOCK_KEY},
                        instanceId, String.valueOf(RECOVERY_LOCK_TTL_MS));

                if (refreshResult == null) {
                    log.error("Startup recovery: lock lost mid-scan — aborting to prevent " +
                              "duplicate re-enqueue by concurrent instance");
                    return;
                }

                dbPage = photoRepository.findPendingOrProcessingForRecovery(
                        PageRequest.of(page, RECOVERY_PAGE_SIZE));

                for (Photo photo : dbPage.getContent()) {
                    String photoIdStr = photo.getId().toString();
                    if (!pelPhotoIds.contains(photoIdStr)) {
                        log.info("Startup recovery: re-enqueuing photo {}", photoIdStr);
                        redisCommands.xadd(STREAM, Map.of("photo_id", photoIdStr));
                    }
                }

                page++;
            } while (dbPage.hasNext());

            log.info("Startup recovery: completed successfully");
        } finally {
            // Release lock if we still own it
            try {
                redisCommands.eval(
                        "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                        "  return redis.call('DEL', KEYS[1]) " +
                        "else return 0 end",
                        ScriptOutputType.INTEGER,
                        new String[]{RECOVERY_LOCK_KEY},
                        instanceId);
            } catch (Exception e) {
                log.warn("Startup recovery: failed to release lock", e);
            }
        }
    }

    /**
     * Paginates the full PEL for {@code STREAM}/{@code GROUP}, collecting all
     * {@code photo_id} values into a set for O(1) deduplication.
     *
     * <p>Uses page size of 1000 to handle large-scale outages correctly.
     * COUNT 100 would miss PEL entries beyond position 100.
     */
    private Set<String> paginatePel() {
        Set<String> pelPhotoIds = new HashSet<>();
        String cursor = "-";

        List<PendingMessage> page;
        do {
            Range<String> range = "-".equals(cursor)
                    ? Range.create("-", "+")
                    : Range.from(Boundary.excluding(cursor), Boundary.unbounded());
            page = redisCommands.xpending(
                    STREAM, GROUP,
                    range,
                    Limit.from(PEL_PAGE_SIZE));

            for (PendingMessage msg : page) {
                // The PEL doesn't store message body fields — we use the message ID here.
                // The actual photo_id is embedded in the message body and we can only
                // deduplicate by message ID at the PEL level. We store raw IDs; the
                // re-enqueue check uses photo_id from DB rows and matches them against
                // stream message bodies via a stream range scan where possible.
                //
                // For the common case (startup after crash), the stream still contains
                // the original message whose body has photo_id. We treat pelPhotoIds as
                // a set of stream message IDs and cross-reference via XRANGE when needed.
                // However, because the spec says to collect photo_ids from PEL messages,
                // and PendingMessage only has stream message ID (not body), we do a
                // targeted XRANGE to fetch the body for each PEL entry.
                fetchPhotoIdFromStream(msg.getId()).ifPresent(pelPhotoIds::add);
            }

            if (!page.isEmpty()) {
                cursor = page.get(page.size() - 1).getId();
            }
        } while (page.size() == PEL_PAGE_SIZE);

        return pelPhotoIds;
    }

    /**
     * Fetches a single stream message by ID to extract its {@code photo_id} field.
     */
    private Optional<String> fetchPhotoIdFromStream(String messageId) {
        try {
            List<StreamMessage<String, String>> msgs = redisCommands.xrange(
                    STREAM,
                    Range.create(messageId, messageId));
            if (msgs != null && !msgs.isEmpty()) {
                return Optional.ofNullable(msgs.get(0).getBody().get("photo_id"));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch stream message {} for PEL deduplication", messageId, e);
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Static factory helper for consumer name
    // -------------------------------------------------------------------------

    /**
     * Builds a stable consumer name from {@code HOSTNAME} env var + PID.
     * Falls back to {@code InetAddress}, then a random UUID.
     */
    static String buildConsumerName() {
        String hostname = Optional.ofNullable(System.getenv("HOSTNAME"))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> {
                    try {
                        return InetAddress.getLocalHost().getHostName();
                    } catch (UnknownHostException e) {
                        return UUID.randomUUID().toString();
                    }
                });
        return hostname + "-" + ProcessHandle.current().pid();
    }
}
