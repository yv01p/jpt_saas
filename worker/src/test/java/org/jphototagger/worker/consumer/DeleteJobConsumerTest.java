package org.jphototagger.worker.consumer;

import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.jphototagger.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeleteJobConsumer.
 *
 * <p>All Redis and MinIO interactions are mocked.
 */
@ExtendWith(MockitoExtension.class)
class DeleteJobConsumerTest {

    @Mock private RedisCommands<String, String> redis;
    @Mock private MinioClient minioClient;

    private WorkerProperties workerProperties;
    private DeleteJobConsumer consumer;

    @BeforeEach
    void setUp() {
        workerProperties = new WorkerProperties();
        consumer = new DeleteJobConsumer(redis, minioClient, workerProperties, "jpt-photos", "test-consumer");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private StreamMessage<String, String> message(String id, UUID photoId,
                                                   String originalKey,
                                                   String thumbnailSm,
                                                   String thumbnailMd) {
        Map<String, String> body = new java.util.HashMap<>();
        body.put("photo_id", photoId != null ? photoId.toString() : null);
        body.put("original_key", originalKey);
        body.put("thumbnail_sm", thumbnailSm);
        body.put("thumbnail_md", thumbnailMd);
        return new StreamMessage<>(DeleteJobConsumer.STREAM, id, body);
    }

    private String validOriginalKey(UUID userId, UUID photoId) {
        return userId + "/originals/" + photoId + ".jpg";
    }

    private String validSmKey(UUID userId, UUID photoId) {
        return userId + "/thumbnails/" + photoId + "_sm.jpg";
    }

    private String validMdKey(UUID userId, UUID photoId) {
        return userId + "/thumbnails/" + photoId + "_md.jpg";
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void deleteJobConsumer_deletesOriginalAndAllThumbnails() throws Exception {
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String msgId = "1-0";
        String origKey = validOriginalKey(userId, photoId);
        String smKey   = validSmKey(userId, photoId);
        String mdKey   = validMdKey(userId, photoId);

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId, origKey, smKey, mdKey)))
                .thenReturn(List.of());

        consumer.pollOnce();

        // Verify three distinct MinIO removes: original, sm, md
        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient, times(3)).removeObject(captor.capture());

        List<RemoveObjectArgs> removes = captor.getAllValues();
        assertThat(removes).extracting(r -> {
            try {
                java.lang.reflect.Method m = RemoveObjectArgs.class.getMethod("object");
                return (String) m.invoke(r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).containsExactlyInAnyOrder(origKey, smKey, mdKey);

        verify(redis).xack(DeleteJobConsumer.STREAM, DeleteJobConsumer.GROUP, msgId);
    }

    @Test
    void deleteJobConsumer_xacksAndSkipsMessageWithNullOriginalKey() throws Exception {
        // SA2-F5: null original_key must XACK and skip without any MinIO call.
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String msgId = "2-0";

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId, null,
                        validSmKey(userId, photoId), validMdKey(userId, photoId))))
                .thenReturn(List.of());

        consumer.pollOnce();

        verify(redis).xack(DeleteJobConsumer.STREAM, DeleteJobConsumer.GROUP, msgId);
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteJobConsumer_xacksAndSkipsMessageWithBlankOriginalKey() throws Exception {
        // Blank (not null) original_key is equally invalid — same guard applies.
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String msgId = "3-0";

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId, "   ",
                        validSmKey(userId, photoId), validMdKey(userId, photoId))))
                .thenReturn(List.of());

        consumer.pollOnce();

        verify(redis).xack(DeleteJobConsumer.STREAM, DeleteJobConsumer.GROUP, msgId);
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteJobConsumer_rejectsMessageWithInvalidStorageKeyFormat() throws Exception {
        // SA3-F2: originalKey passes null/blank check but fails format validation.
        // Example: a non-UUID-prefixed path that could result from a Redis-compromise injection.
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String msgId = "4-0";
        String badKey = "admin/backup/db-dump.sql";

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId, badKey,
                        validSmKey(userId, photoId), validMdKey(userId, photoId))))
                .thenReturn(List.of());

        consumer.pollOnce();

        verify(redis).xack(DeleteJobConsumer.STREAM, DeleteJobConsumer.GROUP, msgId);
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteJobConsumer_xacksAndLogsOnMinioDeleteFailure() throws Exception {
        // C2: MinIO failure must not orphan the message in the PEL.
        // XACK must be called even when removeObject throws, because there is no
        // XAUTOCLAIM retry mechanism on the delete-jobs stream.
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String msgId = "6-0";
        String origKey = validOriginalKey(userId, photoId);
        String smKey   = validSmKey(userId, photoId);
        String mdKey   = validMdKey(userId, photoId);

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId, origKey, smKey, mdKey)))
                .thenReturn(List.of());

        // All MinIO calls throw
        doThrow(new RuntimeException("MinIO unavailable"))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        consumer.pollOnce();

        // XACK must still be issued despite the MinIO failure
        verify(redis).xack(DeleteJobConsumer.STREAM, DeleteJobConsumer.GROUP, msgId);
    }

    @Test
    void deleteJobConsumer_skipsInvalidThumbnailKeyButDeletesOthers() throws Exception {
        // SA3-F2: If thumbnail keys have invalid format (e.g., absent for photos that
        // never completed thumbnail generation), skip the individual MinIO call
        // but still delete the original and the other valid thumbnail.
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String msgId = "5-0";
        String origKey  = validOriginalKey(userId, photoId);
        String smKey    = validSmKey(userId, photoId);
        String badMdKey = "bad-format-key";

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId, origKey, smKey, badMdKey)))
                .thenReturn(List.of());

        consumer.pollOnce();

        // Only 2 removes: original + sm (md skipped due to invalid format)
        verify(minioClient, times(2)).removeObject(any(RemoveObjectArgs.class));
        verify(redis).xack(DeleteJobConsumer.STREAM, DeleteJobConsumer.GROUP, msgId);
    }
}
