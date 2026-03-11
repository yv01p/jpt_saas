package org.jphototagger.worker.consumer;

import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScriptOutputType;
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
import org.jphototagger.worker.pipeline.ImageProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for startup recovery and XAUTOCLAIM logic.
 *
 * <p>All Redis and DB interactions are mocked.
 */
@ExtendWith(MockitoExtension.class)
class StartupRecoveryTest {

    @Mock private RedisCommands<String, String> redis;
    @Mock private PhotoRepository photoRepository;
    @Mock private ImageProcessor imageProcessor;

    private WorkerProperties workerProperties;
    private PhotoJobConsumer consumer;

    private static final String INSTANCE_ID = "test-instance";

    @BeforeEach
    void setUp() {
        workerProperties = new WorkerProperties();
        workerProperties.getStreams().setMaxRetries(3);
        // ImageProcessor not invoked by recovery tests but required by constructor
        consumer = new PhotoJobConsumer(redis, photoRepository, imageProcessor, workerProperties, INSTANCE_ID);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Photo pendingPhoto(UUID photoId) {
        Photo photo = new Photo();
        photo.setId(photoId);
        photo.setUserId(UUID.randomUUID());
        photo.setFilename("photo.jpg");
        photo.setStorageKey(photo.getUserId() + "/originals/" + photoId + ".jpg");
        photo.setProcessingStatus(ProcessingStatus.PENDING);
        return photo;
    }

    private PendingMessage pelEntry(UUID photoId) {
        return new PendingMessage("pel-" + photoId, "some-consumer", 1000L, 1L);
    }

    // =========================================================================
    // Startup recovery — PEL dedup tests
    // =========================================================================

    @Test
    void startupRecovery_doesNotReenqueuePhotosAlreadyInPel() {
        // Lock acquired
        when(redis.set(eq(PhotoJobConsumer.RECOVERY_LOCK_KEY), anyString(), any()))
                .thenReturn("OK");

        UUID photoId = UUID.randomUUID();
        String pelMsgId = "123-0";

        // PEL has one entry for this photo — size 1 < PEL_PAGE_SIZE(1000), pagination ends
        PendingMessage pelEntry = new PendingMessage(pelMsgId, "some-consumer", 1000L, 1L);
        when(redis.xpending(
                eq(PhotoJobConsumer.STREAM), eq(PhotoJobConsumer.GROUP),
                any(Range.class), any(Limit.class)))
                .thenReturn(List.of(pelEntry));

        // XRANGE for the PEL entry returns the stream message body containing photo_id.
        // paginatePel() now issues one batched xrange(stream, range, limit) per page.
        StreamMessage<String, String> streamMsg = new StreamMessage<>(
                PhotoJobConsumer.STREAM, pelMsgId, Map.of("photo_id", photoId.toString()));
        when(redis.xrange(eq(PhotoJobConsumer.STREAM), eq(Range.create(pelMsgId, pelMsgId)), any(Limit.class)))
                .thenReturn(List.of(streamMsg));

        // DB returns the same photo as PENDING — would be re-enqueued if dedup filter is empty
        Photo pending = pendingPhoto(photoId);
        when(photoRepository.findPendingOrProcessingForRecovery(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pending)));

        // Lock refresh succeeds
        when(redis.eval(anyString(), eq(ScriptOutputType.STATUS),
                any(String[].class), any(String[].class)))
                .thenReturn("OK");

        consumer.performStartupRecovery();

        // Photo is in PEL → dedup filter must prevent re-enqueue
        verify(redis, never()).xadd(eq(PhotoJobConsumer.STREAM), anyMap());
    }

    // =========================================================================
    // XAUTOCLAIM tests
    // =========================================================================

    @Test
    void xautoclaim_invokesRedisXautoclaimOnPhotoJobsStream() {
        // Verify that reclaimIdleMessages() reaches Redis with an XAUTOCLAIM call on the
        // correct stream. XAutoClaimArgs.minIdleTime has no public getter; verifying the
        // exact value via reflection is fragile (a Lettuce field rename breaks the test at
        // runtime with NoSuchFieldException, not a compile error). The idle-time value is
        // better verified end-to-end in an integration test against a real Redis container.
        ClaimedMessages<String, String> empty = new ClaimedMessages<>("0-0", List.of());
        when(redis.xautoclaim(eq(PhotoJobConsumer.STREAM), any(XAutoClaimArgs.class)))
                .thenReturn(empty);

        consumer.reclaimIdleMessages();

        verify(redis, times(1)).xautoclaim(eq(PhotoJobConsumer.STREAM), any(XAutoClaimArgs.class));
    }

    // =========================================================================
    // Startup recovery — distributed lock tests
    // =========================================================================

    @Test
    void startupRecovery_onlyOneInstanceReenqueuesWhenLockContested() {
        // Instance that does NOT acquire the lock must skip recovery entirely.
        // Simulate SET NX returning null (lock not acquired).
        when(redis.set(
                eq(PhotoJobConsumer.RECOVERY_LOCK_KEY),
                anyString(),
                any()))
                .thenReturn(null); // lock not acquired

        consumer.performStartupRecovery();

        // No XADD calls — recovery skipped
        verify(redis, never()).xadd(anyString(), anyMap());
        // No PEL scan either
        verify(redis, never()).xpending(anyString(), anyString(), any(Range.class), any(Limit.class));
    }

    @Test
    void startupRecovery_pelPaginationCoversAllEntries() {
        // Lock acquired
        when(redis.set(
                eq(PhotoJobConsumer.RECOVERY_LOCK_KEY),
                anyString(),
                any()))
                .thenReturn("OK");

        // First PEL page: 1000 entries (triggers next page fetch)
        List<PendingMessage> page1 = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            page1.add(new PendingMessage("pel-" + i, "c1", 1000L, 1L));
        }
        // Second PEL page: fewer than 1000 (signals end of pagination)
        List<PendingMessage> page2 = List.of(
                new PendingMessage("pel-1001", "c1", 1000L, 1L));

        when(redis.xpending(
                eq(PhotoJobConsumer.STREAM),
                eq(PhotoJobConsumer.GROUP),
                any(Range.class),
                any(Limit.class)))
                .thenReturn(page1)
                .thenReturn(page2);

        // No PENDING/PROCESSING photos in DB — nothing to re-enqueue
        when(photoRepository.findPendingOrProcessingForRecovery(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        // Lock refresh succeeds
        when(redis.eval(anyString(), eq(ScriptOutputType.STATUS),
                any(String[].class), any(String[].class)))
                .thenReturn("OK");

        consumer.performStartupRecovery();

        // Verify xpending was called at least twice (pagination)
        verify(redis, atLeast(2)).xpending(
                eq(PhotoJobConsumer.STREAM),
                eq(PhotoJobConsumer.GROUP),
                any(Range.class),
                any(Limit.class));
    }

    @Test
    void startupRecovery_abortsWhenLockExpiredMidScan() {
        // Lock acquired initially
        when(redis.set(
                eq(PhotoJobConsumer.RECOVERY_LOCK_KEY),
                anyString(),
                any()))
                .thenReturn("OK");

        // Empty PEL
        when(redis.xpending(
                eq(PhotoJobConsumer.STREAM),
                eq(PhotoJobConsumer.GROUP),
                any(Range.class),
                any(Limit.class)))
                .thenReturn(List.of());

        // Lock refresh returns null immediately — lock lost before any DB batch is fetched
        when(redis.eval(anyString(), eq(ScriptOutputType.STATUS),
                any(String[].class), any(String[].class)))
                .thenReturn(null);

        consumer.performStartupRecovery();

        // No DB scan and no re-enqueue should happen after lock is lost
        verify(photoRepository, never()).findPendingOrProcessingForRecovery(any(Pageable.class));
        verify(redis, never()).xadd(eq(PhotoJobConsumer.STREAM), anyMap());
    }
}
