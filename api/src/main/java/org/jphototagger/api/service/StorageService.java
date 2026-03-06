package org.jphototagger.api.service;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Provides object storage operations backed by MinIO.
 *
 * <p>Bucket path layout:
 * <ul>
 *   <li>Original: {@code {userId}/originals/{photoId}.{ext}}</li>
 *   <li>Small thumbnail: {@code {userId}/thumbnails/{photoId}_sm.jpg}</li>
 *   <li>Medium thumbnail: {@code {userId}/thumbnails/{photoId}_md.jpg}</li>
 * </ul>
 *
 * <p>All I/O (upload, download, delete) uses {@code minioInternalClient}.
 * Pre-signed URL generation uses {@code minioPublicClient} so that the returned
 * URLs begin with the public-facing hostname, not the internal Docker hostname.
 */
@Service
public class StorageService {

    /** Expiry for thumbnail pre-signed URLs: 15 minutes. */
    static final int THUMBNAIL_EXPIRY_SECONDS = 900;

    /** Expiry for original pre-signed URLs: 1 hour. */
    static final int ORIGINAL_EXPIRY_SECONDS = 3600;

    private final MinioClient minioInternalClient;
    private final MinioClient minioPublicClient;
    private final String bucket;

    public StorageService(
            @Qualifier("minioInternalClient") MinioClient minioInternalClient,
            @Qualifier("minioPublicClient") MinioClient minioPublicClient,
            @Value("${minio.bucket}") String bucket) {
        this.minioInternalClient = minioInternalClient;
        this.minioPublicClient = minioPublicClient;
        this.bucket = bucket;
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    public String originalKey(UUID userId, UUID photoId, String ext) {
        return userId + "/originals/" + photoId + "." + ext;
    }

    public String thumbnailSmKey(UUID userId, UUID photoId) {
        return userId + "/thumbnails/" + photoId + "_sm.jpg";
    }

    public String thumbnailMdKey(UUID userId, UUID photoId) {
        return userId + "/thumbnails/" + photoId + "_md.jpg";
    }

    // -------------------------------------------------------------------------
    // I/O operations — minioInternalClient only
    // -------------------------------------------------------------------------

    /**
     * Uploads an object to MinIO using the internal client.
     *
     * @param objectKey   the bucket-relative object key
     * @param data        the data stream to upload
     * @param size        the size of the data in bytes (-1 for unknown)
     * @param contentType the MIME type of the object
     */
    public void upload(String objectKey, InputStream data, long size, String contentType) {
        try {
            minioInternalClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(data, size, -1)
                            .contentType(contentType)
                            .build());
        } catch (Exception e) {
            throw new StorageException("Failed to upload object: " + objectKey, e);
        }
    }

    /**
     * Downloads an object from MinIO using the internal client.
     *
     * @param objectKey the bucket-relative object key
     * @return the raw object response (caller must close)
     */
    public GetObjectResponse download(String objectKey) {
        try {
            return minioInternalClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build());
        } catch (Exception e) {
            throw new StorageException("Failed to download object: " + objectKey, e);
        }
    }

    /**
     * Deletes an object from MinIO using the internal client.
     *
     * @param objectKey the bucket-relative object key
     */
    public void delete(String objectKey) {
        try {
            minioInternalClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build());
        } catch (Exception e) {
            throw new StorageException("Failed to delete object: " + objectKey, e);
        }
    }

    // -------------------------------------------------------------------------
    // Pre-signed URL generation — minioPublicClient only
    // -------------------------------------------------------------------------

    /**
     * Generates a pre-signed GET URL for a thumbnail, valid for 15 minutes.
     * Uses {@code minioPublicClient} so the URL begins with the configured
     * public-facing URL.
     *
     * @param objectKey the bucket-relative object key
     * @return pre-signed URL beginning with the public URL
     */
    public String generateThumbnailPresignedUrl(String objectKey) {
        return generatePresignedUrl(objectKey, THUMBNAIL_EXPIRY_SECONDS);
    }

    /**
     * Generates a pre-signed GET URL for an original photo, valid for 1 hour.
     * Uses {@code minioPublicClient} so the URL begins with the configured
     * public-facing URL.
     *
     * @param objectKey the bucket-relative object key
     * @return pre-signed URL beginning with the public URL
     */
    public String generateOriginalPresignedUrl(String objectKey) {
        return generatePresignedUrl(objectKey, ORIGINAL_EXPIRY_SECONDS);
    }

    private String generatePresignedUrl(String objectKey, int expirySeconds) {
        try {
            return minioPublicClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(expirySeconds, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            throw new StorageException("Failed to generate pre-signed URL for: " + objectKey, e);
        }
    }

    // -------------------------------------------------------------------------
    // Unchecked wrapper for storage errors
    // -------------------------------------------------------------------------

    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
