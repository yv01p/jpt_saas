package org.jphototagger.worker.pipeline;

import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.enums.ProcessingStatus;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.worker.exception.ProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Orchestrates the image processing pipeline for a single photo.
 *
 * <p>Pipeline steps:
 * <ol>
 *   <li>Fetch photo from DB (storage_key, user_id, ext).</li>
 *   <li>{@link TikaValidator} — reject non-image MIME types early.</li>
 *   <li>{@link ThumbnailGenerator} — download original, generate sm/md thumbnails, upload.</li>
 *   <li>{@link MetadataExtractor} — extract EXIF/IPTC/XMP, write to DB.</li>
 *   <li>Update {@code processing_status} to DONE.</li>
 * </ol>
 *
 * <p>If any step throws {@link ProcessingException} the status is set to FAILED.
 * The future {@code PhotoJobConsumer} (Task 3.4) will catch {@link ProcessingException}.
 */
@Component
public class ImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessor.class);

    private final PhotoRepository photoRepository;
    private final TikaValidator tikaValidator;
    private final ThumbnailGenerator thumbnailGenerator;
    private final MetadataExtractor metadataExtractor;

    public ImageProcessor(PhotoRepository photoRepository,
                          TikaValidator tikaValidator,
                          ThumbnailGenerator thumbnailGenerator,
                          MetadataExtractor metadataExtractor) {
        this.photoRepository = photoRepository;
        this.tikaValidator = tikaValidator;
        this.thumbnailGenerator = thumbnailGenerator;
        this.metadataExtractor = metadataExtractor;
    }

    /**
     * Processes the photo identified by {@code photoId}.
     *
     * @param photoId the UUID of the photo to process
     * @throws ProcessingException if any pipeline step fails
     */
    public void process(UUID photoId) {
        // Step 1: Fetch photo from DB
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ProcessingException("Photo not found: " + photoId));

        String storageKey = photo.getStorageKey();
        UUID userId = photo.getUserId();

        if (storageKey == null || storageKey.isBlank()) {
            markFailed(photo);
            throw new ProcessingException("Photo has no storage_key: " + photoId);
        }

        // Parse extension from storage key: {userId}/originals/{photoId}.{ext}
        String ext = storageKey.contains(".")
                ? storageKey.substring(storageKey.lastIndexOf('.') + 1)
                : "jpg";

        // Download the original once to tmpfs; reuse for both Tika validation,
        // thumbnail generation, and metadata extraction (avoids triple download).
        Path tmpOriginal = null;
        try {
            tmpOriginal = File.createTempFile(
                    photoId.toString(), "." + ext, new File("/tmp")).toPath();
        } catch (IOException e) {
            markFailed(photo);
            throw new ProcessingException("Failed to create temp file for photo " + photoId, e);
        }

        try {
            thumbnailGenerator.downloadFromMinio(storageKey, tmpOriginal);

            // Step 2: Content-based MIME detection via Tika — validates actual file bytes.
            String mimeType;
            try {
                mimeType = tikaValidator.detectAndValidate(tmpOriginal);
            } catch (ProcessingException e) {
                markFailed(photo);
                throw e;
            }

            // Mark as processing
            photo.setProcessingStatus(ProcessingStatus.PROCESSING);
            photoRepository.save(photo);

            try {
                // Step 3: Generate thumbnails (ThumbnailGenerator downloads its own copy)
                thumbnailGenerator.generate(photoId, userId, storageKey, mimeType);

                // Step 4: Extract metadata from the already-downloaded local file
                metadataExtractor.extract(photoId, userId, tmpOriginal);

                // Step 5: Mark DONE
                photo.setProcessingStatus(ProcessingStatus.DONE);
                photoRepository.save(photo);
                log.info("Photo {} processed successfully", photoId);

            } catch (ProcessingException e) {
                markFailed(photo);
                throw e;
            } catch (Exception e) {
                markFailed(photo);
                throw new ProcessingException("Unexpected error processing photo " + photoId, e);
            }

        } finally {
            try {
                Files.deleteIfExists(tmpOriginal);
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", tmpOriginal, e);
            }
        }
    }

    private void markFailed(Photo photo) {
        try {
            photo.setProcessingStatus(ProcessingStatus.FAILED);
            photoRepository.save(photo);
        } catch (Exception e) {
            log.error("Failed to mark photo {} as FAILED", photo.getId(), e);
        }
    }

}
