package org.jphototagger.worker.pipeline;

import org.apache.tika.Tika;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.enums.ProcessingStatus;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.worker.exception.ProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
    private final Tika tika = new Tika();

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

        // Derive MIME type from extension for validation and RAW detection
        String mimeType = extToMime(ext);

        // Step 2: Validate MIME type
        try {
            tikaValidator.validateMimeType(mimeType);
        } catch (ProcessingException e) {
            markFailed(photo);
            throw e;
        }

        // Mark as processing
        photo.setProcessingStatus(ProcessingStatus.PROCESSING);
        photoRepository.save(photo);

        try {
            // Step 3 + 4: Generate thumbnails (handles RAW internally)
            thumbnailGenerator.generate(photoId, userId, storageKey, mimeType);

            // Step 5: Extract metadata — use a temp path; MetadataExtractor
            // downloads separately from MinIO for its own read.
            // We pass the storageKey so it can resolve the file; the extractor
            // receives the tmpFile from ThumbnailGenerator's already-downloaded copy
            // in a shared context — but since ThumbnailGenerator cleans up, we pass
            // storageKey as a sentinel and MetadataExtractor downloads its own copy.
            // For simplicity and correctness, create a temp file path for the extractor.
            java.nio.file.Path tmpForMeta = null;
            try {
                tmpForMeta = java.nio.file.Files.createTempFile(
                        java.nio.file.Path.of("/tmp"), photoId.toString(), "." + ext);
                thumbnailGenerator.downloadFromMinio(storageKey, tmpForMeta);
                metadataExtractor.extract(photoId, userId, tmpForMeta);
            } finally {
                if (tmpForMeta != null) {
                    try {
                        java.nio.file.Files.deleteIfExists(tmpForMeta);
                    } catch (java.io.IOException e) {
                        log.warn("Failed to delete metadata temp file: {}", tmpForMeta, e);
                    }
                }
            }

            // Step 6: Mark DONE
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
    }

    private void markFailed(Photo photo) {
        try {
            photo.setProcessingStatus(ProcessingStatus.FAILED);
            photoRepository.save(photo);
        } catch (Exception e) {
            log.error("Failed to mark photo {} as FAILED", photo.getId(), e);
        }
    }

    /**
     * Maps a file extension to a MIME type for pipeline routing.
     * Falls back to {@code image/jpeg} for unknown extensions.
     */
    private String extToMime(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png"         -> "image/png";
            case "tiff", "tif" -> "image/tiff";
            case "cr2"         -> "image/x-canon-cr2";
            case "nef"         -> "image/x-nikon-nef";
            case "arw"         -> "image/x-sony-arw";
            case "dng"         -> "image/x-adobe-dng";
            case "heic"        -> "image/heic";
            case "webp"        -> "image/webp";
            default            -> "image/jpeg";
        };
    }
}
