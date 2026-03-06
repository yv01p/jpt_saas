package org.jphototagger.worker.pipeline;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.jphototagger.worker.config.WorkerProperties;
import org.jphototagger.worker.exception.ProcessingException;
import org.jphototagger.worker.exception.ProcessTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Generates small (300px) and medium (1200px) thumbnails from an original photo.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Download original from MinIO to tmpfs ({@code /tmp}).</li>
 *   <li>For RAW files: use {@code dcraw_emu} (libraw) to extract embedded JPEG.</li>
 *   <li>Use {@code vipsthumbnail} (libvips) to resize to sm and md sizes.</li>
 *   <li>Upload thumbnails to MinIO.</li>
 *   <li>Clean up all temp files in finally blocks.</li>
 * </ol>
 *
 * <p>All CLI calls use explicit argument arrays — never shell strings (SA2-F3).
 * ProcessBuilder timeout prevents runaway processes (SA2-F2).
 */
@Component
public class ThumbnailGenerator {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailGenerator.class);

    /** RAW MIME types that require libraw pre-processing. */
    static final Set<String> RAW_MIME_TYPES = Set.of(
            "image/x-canon-cr2",
            "image/x-nikon-nef",
            "image/x-sony-arw",
            "image/x-adobe-dng"
    );

    static final int THUMB_SM_SIZE = 300;
    static final int THUMB_MD_SIZE = 1200;

    private final MinioClient minioClient;
    private final WorkerProperties workerProperties;
    private final String bucket;

    public ThumbnailGenerator(MinioClient minioClient,
                               WorkerProperties workerProperties,
                               @Value("${minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.workerProperties = workerProperties;
        this.bucket = bucket;
    }

    /**
     * Downloads the original, generates thumbnails, and uploads them to MinIO.
     *
     * @param photoId    photo UUID (used for object key construction)
     * @param userId     user UUID (used for object key construction)
     * @param storageKey object key of the original in MinIO
     * @param mimeType   detected MIME type (determines RAW vs non-RAW path)
     */
    public void generate(UUID photoId, UUID userId, String storageKey, String mimeType) {
        String ext = storageKey.contains(".")
                ? storageKey.substring(storageKey.lastIndexOf('.') + 1)
                : "jpg";

        Path tmpOriginal = null;
        Path tmpJpeg = null;
        Path tmpSmall = null;
        Path tmpMedium = null;

        try {
            // Download original to tmpfs
            tmpOriginal = File.createTempFile(photoId.toString(), "." + ext, new File("/tmp")).toPath();
            downloadFromMinio(storageKey, tmpOriginal);

            // For RAW: extract embedded JPEG using libraw (dcraw_emu)
            Path sourceForVips;
            if (RAW_MIME_TYPES.contains(mimeType)) {
                tmpJpeg = File.createTempFile(photoId + "-raw", ".jpg", new File("/tmp")).toPath();
                extractRawToJpeg(tmpOriginal, tmpJpeg, photoId);
                sourceForVips = tmpJpeg;
            } else {
                sourceForVips = tmpOriginal;
            }

            // Generate thumbnails with vipsthumbnail
            tmpSmall  = File.createTempFile(photoId + "-sm", ".jpg", new File("/tmp")).toPath();
            tmpMedium = File.createTempFile(photoId + "-md", ".jpg", new File("/tmp")).toPath();

            generateThumbnail(sourceForVips, tmpSmall, THUMB_SM_SIZE, photoId);
            generateThumbnail(sourceForVips, tmpMedium, THUMB_MD_SIZE, photoId);

            // Upload thumbnails
            String smKey = userId + "/thumbnails/" + photoId + "_sm.jpg";
            String mdKey = userId + "/thumbnails/" + photoId + "_md.jpg";

            uploadToMinio(smKey, tmpSmall);
            uploadToMinio(mdKey, tmpMedium);

            log.info("Thumbnails generated for photo {}: sm={}, md={}", photoId, smKey, mdKey);

        } catch (IOException e) {
            throw new ProcessingException("I/O error during thumbnail generation for photo " + photoId, e);
        } finally {
            deleteQuietly(tmpOriginal);
            deleteQuietly(tmpJpeg);
            deleteQuietly(tmpSmall);
            deleteQuietly(tmpMedium);
        }
    }

    /**
     * Downloads an object from MinIO to a local path.
     */
    void downloadFromMinio(String objectKey, Path destination) {
        try {
            try (InputStream stream = minioClient.getObject(
                    io.minio.GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build())) {
                Files.copy(stream, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new ProcessingException("Failed to download from MinIO: " + objectKey, e);
        }
    }

    /**
     * Uses {@code dcraw_emu} (libraw) to extract an embedded JPEG from a RAW file.
     */
    void extractRawToJpeg(Path rawFile, Path jpegOutput, UUID photoId) {
        int timeout = workerProperties.getProcess().getTimeoutMinutes();
        try {
            // dcraw_emu -e extracts the embedded thumbnail; output file is rawFile.thumb.jpg
            // We redirect output to our desired path by pointing dcraw_emu at the input and
            // then moving the result.
            Path thumbFile = rawFile.resolveSibling(rawFile.getFileName() + ".thumb.jpg");
            ProcessBuilder pb = new ProcessBuilder(
                    "dcraw_emu", "-e", "-T", rawFile.toString());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            boolean completed = process.waitFor(timeout, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                throw new ProcessTimeoutException("dcraw_emu timed out for photo " + photoId);
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new ProcessingException(
                        "dcraw_emu failed with exit code " + exitCode + " for photo " + photoId);
            }
            // Move dcraw_emu output to our desired destination
            if (Files.exists(thumbFile)) {
                Files.move(thumbFile, jpegOutput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                // Fallback: use the original RAW as-is (libvips can handle some RAW formats)
                Files.copy(rawFile, jpegOutput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (ProcessTimeoutException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException("dcraw_emu interrupted for photo " + photoId, e);
        } catch (IOException e) {
            throw new ProcessingException("Failed to run dcraw_emu for photo " + photoId, e);
        }
    }

    /**
     * Uses {@code vipsthumbnail} to resize an image to the given size.
     */
    void generateThumbnail(Path sourceFile, Path outputFile, int size, UUID photoId) {
        int timeout = workerProperties.getProcess().getTimeoutMinutes();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "vipsthumbnail",
                    sourceFile.toString(),
                    "--size", size + "x" + size,
                    "-o", outputFile.toString() + "[Q=85]");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = startProcess(pb);
            boolean completed = process.waitFor(timeout, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                throw new ProcessTimeoutException(
                        "vipsthumbnail timed out (size=" + size + ") for photo " + photoId);
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new ProcessingException(
                        "vipsthumbnail failed with exit code " + exitCode
                        + " (size=" + size + ") for photo " + photoId);
            }
        } catch (ProcessTimeoutException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException(
                    "vipsthumbnail interrupted (size=" + size + ") for photo " + photoId, e);
        } catch (IOException e) {
            throw new ProcessingException(
                    "Failed to run vipsthumbnail (size=" + size + ") for photo " + photoId, e);
        }
    }

    /**
     * Starts a process from a {@link ProcessBuilder}.
     * Package-private so tests can spy on it and inject a mock {@link Process}.
     */
    Process startProcess(ProcessBuilder pb) throws IOException {
        return pb.start();
    }

    /**
     * Uploads a local file to MinIO.
     */
    void uploadToMinio(String objectKey, Path source) {
        try (FileInputStream fis = new FileInputStream(source.toFile())) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(fis, Files.size(source), -1)
                            .contentType("image/jpeg")
                            .build());
        } catch (Exception e) {
            throw new ProcessingException("Failed to upload thumbnail to MinIO: " + objectKey, e);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp file: {}", path, e);
        }
    }
}
