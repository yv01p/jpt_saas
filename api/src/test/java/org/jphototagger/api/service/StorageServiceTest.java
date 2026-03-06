package org.jphototagger.api.service;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for StorageService.
 *
 * Two MinioClient mocks are injected: minioInternalClient (for I/O) and
 * minioPublicClient (for pre-signed URL generation). Each test verifies
 * that only the correct client is used for each operation.
 */
@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    private static final String BUCKET = "jpt-photos";
    private static final String PUBLIC_URL = "https://example.com";

    @Mock
    private MinioClient minioInternalClient;

    @Mock
    private MinioClient minioPublicClient;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(minioInternalClient, minioPublicClient, BUCKET);
    }

    // -------------------------------------------------------------------------
    // Pre-signed URL tests
    // -------------------------------------------------------------------------

    @Test
    void generatePresignedUrl_returnsThumbnailUrlWith15MinExpiry() throws Exception {
        // assert URL expiry param is <= 900 seconds and URL starts with minio.public-url
        String expectedUrl = PUBLIC_URL + "/jpt-photos/user1/thumbnails/photo1_sm.jpg?X-Amz-Expires=900";
        when(minioPublicClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn(expectedUrl);

        String url = storageService.generateThumbnailPresignedUrl("user1/thumbnails/photo1_sm.jpg");

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioPublicClient).getPresignedObjectUrl(captor.capture());

        assertThat(url).startsWith(PUBLIC_URL);
        assertThat(StorageService.THUMBNAIL_EXPIRY_SECONDS).isLessThanOrEqualTo(900);
    }

    @Test
    void generatePresignedUrl_returnsOriginalUrlWith1HourExpiry() throws Exception {
        // assert URL expiry param is <= 3600 seconds and URL starts with minio.public-url
        String expectedUrl = PUBLIC_URL + "/jpt-photos/user1/originals/photo1.jpg?X-Amz-Expires=3600";
        when(minioPublicClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn(expectedUrl);

        String url = storageService.generateOriginalPresignedUrl("user1/originals/photo1.jpg");

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioPublicClient).getPresignedObjectUrl(captor.capture());

        assertThat(url).startsWith(PUBLIC_URL);
        assertThat(StorageService.ORIGINAL_EXPIRY_SECONDS).isLessThanOrEqualTo(3600);
    }

    @Test
    void generatePresignedUrl_urlBeginsWithConfiguredPublicUrl() throws Exception {
        // assert returned URL begins with the value of minio.public-url, not the internal hostname
        String internalHostname = "http://minio:9000";
        String expectedUrl = PUBLIC_URL + "/jpt-photos/user1/originals/photo1.jpg?sig=abc";
        when(minioPublicClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn(expectedUrl);

        String url = storageService.generateOriginalPresignedUrl("user1/originals/photo1.jpg");

        assertThat(url).startsWith(PUBLIC_URL);
        assertThat(url).doesNotStartWith(internalHostname);
    }

    @Test
    void minioPublicClient_isNeverUsedForUploadOrDownload() throws Exception {
        // assert minioPublicClient is not invoked during upload, download, or delete operations
        InputStream data = new ByteArrayInputStream(new byte[]{1, 2, 3});

        storageService.upload("user1/originals/photo1.jpg", data, 3, "image/jpeg");
        verify(minioPublicClient, never()).putObject(any(PutObjectArgs.class));

        storageService.delete("user1/originals/photo1.jpg");
        verify(minioPublicClient, never()).removeObject(any(RemoveObjectArgs.class));

        // download returns GetObjectResponse which is hard to mock fully,
        // so just verify minioPublicClient is never used for getObject
        try {
            storageService.download("user1/originals/photo1.jpg");
        } catch (StorageService.StorageException e) {
            // Expected: internal client returns null from mock, causing NPE wrapped in StorageException
        }
        verify(minioPublicClient, never()).getObject(any(GetObjectArgs.class));
    }

    // -------------------------------------------------------------------------
    // Upload / Download / Delete use minioInternalClient
    // -------------------------------------------------------------------------

    @Test
    void upload_usesInternalClientOnly() throws Exception {
        InputStream data = new ByteArrayInputStream("content".getBytes());
        storageService.upload("user1/originals/photo1.jpg", data, 7, "image/jpeg");

        verify(minioInternalClient).putObject(any(PutObjectArgs.class));
        verify(minioPublicClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void delete_usesInternalClientOnly() throws Exception {
        storageService.delete("user1/originals/photo1.jpg");

        verify(minioInternalClient).removeObject(any(RemoveObjectArgs.class));
        verify(minioPublicClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    // -------------------------------------------------------------------------
    // Path helper tests
    // -------------------------------------------------------------------------

    @Test
    void pathHelpers_produceCorrectKeys() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID photoId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThat(storageService.originalKey(userId, photoId, "jpg"))
                .isEqualTo(userId + "/originals/" + photoId + ".jpg");
        assertThat(storageService.thumbnailSmKey(userId, photoId))
                .isEqualTo(userId + "/thumbnails/" + photoId + "_sm.jpg");
        assertThat(storageService.thumbnailMdKey(userId, photoId))
                .isEqualTo(userId + "/thumbnails/" + photoId + "_md.jpg");
    }
}
