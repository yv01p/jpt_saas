package org.jphototagger.worker.consumer;

import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.PendingMessage;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.enums.ProcessingStatus;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.worker.config.WorkerProperties;
import org.jphototagger.worker.exception.ProcessingException;
import org.jphototagger.worker.pipeline.ImageProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PhotoJobConsumer.
 *
 * <p>All Redis and DB interactions are mocked — no containers needed.
 */
@ExtendWith(MockitoExtension.class)
class PhotoJobConsumerTest {

    @Mock private RedisCommands<String, String> redis;
    @Mock private PhotoRepository photoRepository;
    @Mock private ImageProcessor imageProcessor;

    private WorkerProperties workerProperties;
    private PhotoJobConsumer consumer;

    @BeforeEach
    void setUp() {
        workerProperties = new WorkerProperties();
        // Use a short max-retries for tests
        workerProperties.getStreams().setMaxRetries(3);
        consumer = new PhotoJobConsumer(redis, photoRepository, imageProcessor, workerProperties, "test-consumer");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Photo photoWith(ProcessingStatus status, String storageKey) {
        Photo photo = new Photo();
        photo.setId(UUID.randomUUID());
        photo.setUserId(UUID.randomUUID());
        photo.setFilename("test.jpg");
        photo.setStorageKey(storageKey);
        photo.setProcessingStatus(status);
        return photo;
    }

    private StreamMessage<String, String> message(String id, UUID photoId) {
        return new StreamMessage<>(PhotoJobConsumer.STREAM, id,
                Map.of("photo_id", photoId.toString()));
    }

    private PendingMessage pendingMessage(String id, long deliveryCount) {
        return new PendingMessage(id, "test-consumer", 1000L, deliveryCount);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void photoJobConsumer_updatesStatusToProcessing() {
        // Arrange
        UUID photoId = UUID.randomUUID();
        Photo photo = photoWith(ProcessingStatus.PENDING,
                photoId + "/originals/" + photoId + ".jpg");
        photo.setId(photoId);

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message("1-0", photoId)))
                .thenReturn(List.of());

        when(photoRepository.findById(photoId)).thenReturn(Optional.of(photo));

        // Act
        consumer.pollOnce();

        // Assert: status was set to PROCESSING before pipeline runs
        ArgumentCaptor<Photo> savedCaptor = ArgumentCaptor.forClass(Photo.class);
        verify(photoRepository, atLeastOnce()).save(savedCaptor.capture());
        boolean processingSetBeforeDone = savedCaptor.getAllValues().stream()
                .anyMatch(p -> p.getProcessingStatus() == ProcessingStatus.PROCESSING);
        assertThat(processingSetBeforeDone)
                .as("status must be set to PROCESSING before pipeline runs")
                .isTrue();
    }

    @Test
    void photoJobConsumer_validatesPhotoExistsBeforeProcessing() {
        // A photo that IS found — verify processing happens
        UUID photoId = UUID.randomUUID();
        Photo photo = photoWith(ProcessingStatus.PENDING,
                photoId + "/originals/" + photoId + ".jpg");
        photo.setId(photoId);

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message("1-0", photoId)))
                .thenReturn(List.of());

        when(photoRepository.findById(photoId)).thenReturn(Optional.of(photo));

        consumer.pollOnce();

        verify(imageProcessor).process(photoId);
    }

    @Test
    void photoJobConsumer_discardsJobForNonExistentPhoto() {
        // Arrange: photo_id in message but no DB row
        UUID photoId = UUID.randomUUID();
        String msgId = "2-0";

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId)))
                .thenReturn(List.of());

        when(photoRepository.findById(photoId)).thenReturn(Optional.empty());

        // Act
        consumer.pollOnce();

        // Assert: XACK called (message removed from PEL), no processing attempted
        verify(redis).xack(PhotoJobConsumer.STREAM, PhotoJobConsumer.GROUP, msgId);
        verify(imageProcessor, never()).process(any());
    }

    @Test
    void photoJobConsumer_setsStatusFailedAfterMaxRetries() {
        // Arrange: photo exists and pipeline throws on every delivery
        UUID photoId = UUID.randomUUID();
        Photo photo = photoWith(ProcessingStatus.PENDING,
                photoId + "/originals/" + photoId + ".jpg");
        photo.setId(photoId);
        String msgId = "3-0";

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId)))
                .thenReturn(List.of());

        when(photoRepository.findById(photoId)).thenReturn(Optional.of(photo));
        doThrow(new ProcessingException("pipeline failed")).when(imageProcessor).process(photoId);

        // Delivery count >= MAX_RETRIES triggers dead-letter
        int maxRetries = workerProperties.getStreams().getMaxRetries();
        PendingMessage pm = pendingMessage(msgId, maxRetries);
        when(redis.xpending(
                eq(PhotoJobConsumer.STREAM),
                eq(PhotoJobConsumer.GROUP),
                any(Range.class),
                any(Limit.class)))
                .thenReturn(List.of(pm));

        // Act
        consumer.pollOnce();

        // Assert: FAILED status saved, XACK called
        ArgumentCaptor<Photo> savedCaptor = ArgumentCaptor.forClass(Photo.class);
        verify(photoRepository, atLeastOnce()).save(savedCaptor.capture());
        boolean failedSaved = savedCaptor.getAllValues().stream()
                .anyMatch(p -> p.getProcessingStatus() == ProcessingStatus.FAILED);
        assertThat(failedSaved).as("processing_status must be FAILED after max retries").isTrue();

        verify(redis).xack(PhotoJobConsumer.STREAM, PhotoJobConsumer.GROUP, msgId);
    }

    @Test
    void photoJobConsumer_reprocessesPhotoWithProcessingStatus() {
        // A photo with PROCESSING status (recovered via XAUTOCLAIM after worker crash)
        // should be re-processed — not skipped.
        UUID photoId = UUID.randomUUID();
        Photo photo = photoWith(ProcessingStatus.PROCESSING,
                photoId + "/originals/" + photoId + ".jpg");
        photo.setId(photoId);

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message("4-0", photoId)))
                .thenReturn(List.of());

        when(photoRepository.findById(photoId)).thenReturn(Optional.of(photo));

        consumer.pollOnce();

        verify(imageProcessor).process(photoId);
    }

    @Test
    void photoJobConsumer_xacksAndSkipsMessageWithNullStorageKey() {
        // Photo exists but storage_key is null (upload incomplete — between Tx 1 and Tx 2).
        UUID photoId = UUID.randomUUID();
        Photo photo = photoWith(ProcessingStatus.PENDING, null);
        photo.setId(photoId);
        String msgId = "5-0";

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId)))
                .thenReturn(List.of());

        when(photoRepository.findById(photoId)).thenReturn(Optional.of(photo));

        consumer.pollOnce();

        // Must XACK (remove from PEL) and skip processing
        verify(redis).xack(PhotoJobConsumer.STREAM, PhotoJobConsumer.GROUP, msgId);
        verify(imageProcessor, never()).process(any());
    }

    @Test
    void photoJobConsumer_xacksAndSkipsPhotoWithDoneStatus() {
        // Already DONE — duplicate delivery; must XACK and skip.
        UUID photoId = UUID.randomUUID();
        Photo photo = photoWith(ProcessingStatus.DONE,
                photoId + "/originals/" + photoId + ".jpg");
        photo.setId(photoId);
        String msgId = "6-0";

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId)))
                .thenReturn(List.of());

        when(photoRepository.findById(photoId)).thenReturn(Optional.of(photo));

        consumer.pollOnce();

        verify(redis).xack(PhotoJobConsumer.STREAM, PhotoJobConsumer.GROUP, msgId);
        verify(imageProcessor, never()).process(any());
    }

    @Test
    void photoJobConsumer_xacksAndSkipsPhotoWithFailedStatus() {
        // Terminal FAILED state — must XACK and skip.
        UUID photoId = UUID.randomUUID();
        Photo photo = photoWith(ProcessingStatus.FAILED,
                photoId + "/originals/" + photoId + ".jpg");
        photo.setId(photoId);
        String msgId = "7-0";

        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of(message(msgId, photoId)))
                .thenReturn(List.of());

        when(photoRepository.findById(photoId)).thenReturn(Optional.of(photo));

        consumer.pollOnce();

        verify(redis).xack(PhotoJobConsumer.STREAM, PhotoJobConsumer.GROUP, msgId);
        verify(imageProcessor, never()).process(any());
    }

    @Test
    void consumer_usesStableConsumerNameAcrossRestarts() {
        // The consumer name passed to the constructor must appear in XREADGROUP calls.
        String consumerName = "test-consumer";

        UUID photoId = UUID.randomUUID();
        when(redis.xreadgroup(
                any(Consumer.class),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());

        consumer.pollOnce();

        ArgumentCaptor<Consumer<String>> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(redis).xreadgroup(
                consumerCaptor.capture(),
                any(XReadArgs.class),
                any(XReadArgs.StreamOffset.class));

        assertThat(consumerCaptor.getValue().getName()).isEqualTo(consumerName);
    }
}
