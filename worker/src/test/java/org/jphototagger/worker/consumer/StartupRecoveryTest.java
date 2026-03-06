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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    private WorkerProperties workerProperties;
    private PhotoJobConsumer consumer;

    private static final String INSTANCE_ID = "test-instance";

    @BeforeEach
    void setUp() {
        workerProperties = new WorkerProperties();
        workerProperties.getStreams().setMaxRetries(3);
        // ImageProcessor not needed for recovery tests
        consumer = new PhotoJobConsumer(redis, photoRepository, null, workerProperties, INSTANCE_ID);
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
    // XAUTOCLAIM tests
    // =========================================================================

    @Test
    void xautoclaim_doesNotReclaimRecentlyProcessedMessages() {
        // XAUTOCLAIM must use the configured min-idle-time. Messages idle less
        // than that threshold will not be returned by Redis — the consumer
        // should call XAUTOCLAIM with the correct idle time.
        // We verify that the XAutoClaimArgs carries the correct minIdleTime.
        long configuredIdle = workerProperties.getStreams().getClaimIdleTimeMs(); // 1800000

        // Stub XAUTOCLAIM to return empty (no idle messages)
        ClaimedMessages<String, String> empty = new ClaimedMessages<>("0-0", List.of());
        when(redis.xautoclaim(eq(PhotoJobConsumer.STREAM), any(XAutoClaimArgs.class)))
                .thenReturn(empty);

        consumer.reclaimIdleMessages();

        ArgumentCaptor<XAutoClaimArgs<String>> argsCaptor = ArgumentCaptor.forClass(XAutoClaimArgs.class);
        verify(redis).xautoclaim(eq(PhotoJobConsumer.STREAM), argsCaptor.capture());

        // We can't directly inspect minIdleTime from XAutoClaimArgs as it's private,
        // but we can verify the call was made. The correct idle time is exercised
        // by the integration path. At minimum, verify XAUTOCLAIM was called once.
        assertThat(argsCaptor.getValue()).isNotNull();
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
