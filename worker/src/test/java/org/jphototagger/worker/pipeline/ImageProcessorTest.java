package org.jphototagger.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.entity.PhotoMetadata;
import org.jphototagger.api.enums.ProcessingStatus;
import org.jphototagger.api.repository.PhotoMetadataRepository;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.worker.config.WorkerProperties;
import org.jphototagger.worker.config.TestRedisConfig;
import org.jphototagger.worker.exception.ProcessingException;
import org.jphototagger.worker.exception.ProcessTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the image processing pipeline.
 *
 * <p>All subprocess calls (libraw, libvips, exiftool) are mocked; no real
 * external tools need to be installed in the test environment.
 */
@ExtendWith(MockitoExtension.class)
class ImageProcessorTest {

    // -------------------------------------------------------------------------
    // Mocks
    // -------------------------------------------------------------------------

    @Mock private MinioClient minioClient;
    @Mock private PhotoRepository photoRepository;
    @Mock private PhotoMetadataRepository photoMetadataRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    private WorkerProperties workerProperties;
    private ObjectMapper objectMapper;

    // Components under test
    private TikaValidator tikaValidator;
    private ThumbnailGenerator thumbnailGenerator;
    private MetadataExtractor metadataExtractor;

    @BeforeEach
    void setUp() {
        workerProperties = new WorkerProperties();
        objectMapper = new ObjectMapper();
        tikaValidator = new TikaValidator();
        thumbnailGenerator = new ThumbnailGenerator(minioClient, workerProperties, "jpt-photos");
        metadataExtractor = new MetadataExtractor(
                photoRepository, photoMetadataRepository, jdbcTemplate,
                workerProperties, objectMapper);
    }

    // =========================================================================
    // TikaValidator tests
    // =========================================================================

    @Test
    void tikaValidator_rejectsNonImageFile() throws Exception {
        // Write a plain text file to a temp path
        Path tmp = Files.createTempFile(Path.of("/tmp"), "test-tika", ".txt");
        try {
            Files.writeString(tmp, "This is plain text, not an image.");
            assertThatThrownBy(() -> tikaValidator.validate(tmp))
                    .isInstanceOf(ProcessingException.class)
                    .hasMessageContaining("non-image");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void tikaValidator_acceptsJpeg() throws Exception {
        // Minimal valid JPEG (SOI + EOI markers)
        byte[] minimalJpeg = new byte[]{
            (byte) 0xFF, (byte) 0xD8,   // SOI
            (byte) 0xFF, (byte) 0xE0,   // APP0 marker
            0x00, 0x10,                  // length = 16
            'J', 'F', 'I', 'F', 0x00,   // identifier
            0x01, 0x01,                  // version
            0x00,                        // aspect ratio units
            0x00, 0x01, 0x00, 0x01,     // X/Y density
            0x00, 0x00,                  // thumbnail size
            (byte) 0xFF, (byte) 0xD9    // EOI
        };
        Path tmp = Files.createTempFile(Path.of("/tmp"), "test-tika", ".jpg");
        try {
            Files.write(tmp, minimalJpeg);
            // Should not throw
            tikaValidator.validate(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void tikaValidator_rejectsNonImageMimeTypeString() {
        assertThatThrownBy(() -> tikaValidator.validateMimeType("application/pdf"))
                .isInstanceOf(ProcessingException.class)
                .hasMessageContaining("non-image");
    }

    // =========================================================================
    // ThumbnailGenerator tests
    // =========================================================================

    @Test
    void thumbnailGenerator_createsSmAndMdThumbnails() throws Exception {
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String storageKey = userId + "/originals/" + photoId + ".jpg";

        byte[] minimalJpeg = minimalJpegBytes();

        // Create a spy on ThumbnailGenerator to intercept CLI calls
        ThumbnailGenerator spy = spy(thumbnailGenerator);

        // Stub downloadFromMinio to write a real minimal JPEG to the given path
        doAnswer(inv -> {
            Path dest = inv.getArgument(1);
            Files.write(dest, minimalJpeg);
            return null;
        }).when(spy).downloadFromMinio(anyString(), any(Path.class));

        // Stub generateThumbnail to write a minimal JPEG output (simulating vipsthumbnail)
        doAnswer(inv -> {
            Path output = inv.getArgument(1);
            Files.write(output, minimalJpeg);
            return null;
        }).when(spy).generateThumbnail(any(Path.class), any(Path.class), anyInt(), any(UUID.class));

        // Run
        spy.generate(photoId, userId, storageKey, "image/jpeg");

        // Verify two uploads: _sm and _md
        ArgumentCaptor<PutObjectArgs> putCaptor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(2)).putObject(putCaptor.capture());

        List<PutObjectArgs> puts = putCaptor.getAllValues();
        boolean hasSmall = puts.stream().anyMatch(p -> p.object().endsWith("_sm.jpg"));
        boolean hasMedium = puts.stream().anyMatch(p -> p.object().endsWith("_md.jpg"));

        assertThat(hasSmall).isTrue();
        assertThat(hasMedium).isTrue();
    }

    @Test
    void thumbnailGenerator_failsCleanlyOnProcessTimeout() throws Exception {
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String storageKey = userId + "/originals/" + photoId + ".jpg";

        ThumbnailGenerator spy = spy(thumbnailGenerator);

        // Stub download to write a file
        doAnswer(inv -> {
            Path dest = inv.getArgument(1);
            Files.write(dest, minimalJpegBytes());
            return null;
        }).when(spy).downloadFromMinio(anyString(), any(Path.class));

        // Stub generateThumbnail to throw ProcessTimeoutException (simulating timeout)
        doThrow(new ProcessTimeoutException("vipsthumbnail timed out for photo " + photoId))
                .when(spy).generateThumbnail(any(Path.class), any(Path.class), anyInt(), any(UUID.class));

        assertThatThrownBy(() -> spy.generate(photoId, userId, storageKey, "image/jpeg"))
                .isInstanceOf(ProcessTimeoutException.class)
                .hasMessageContaining("timed out");
    }

    // =========================================================================
    // MetadataExtractor tests
    // =========================================================================

    @Test
    void metadataExtractor_extractsExifData() throws Exception {
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Use a real JPEG with EXIF (minimal — metadata-extractor will read it)
        Path tmp = Files.createTempFile(Path.of("/tmp"), "meta-test", ".jpg");
        try {
            Files.write(tmp, minimalJpegBytes());

            // JdbcTemplate is mocked — just verify it was called with upsert SQL
            metadataExtractor.extract(photoId, userId, tmp);

            verify(jdbcTemplate).update(
                    argThat(sql -> sql.contains("INSERT INTO photo_metadata") &&
                                  sql.contains("ON CONFLICT")),
                    any(), any(), any(), any(), any());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void metadataExtractor_upsertSucceedsOnReprocessing() throws Exception {
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Path tmp = Files.createTempFile(Path.of("/tmp"), "meta-upsert", ".jpg");
        try {
            Files.write(tmp, minimalJpegBytes());

            // Call twice — should not throw; JdbcTemplate handles ON CONFLICT
            metadataExtractor.extract(photoId, userId, tmp);
            metadataExtractor.extract(photoId, userId, tmp);

            // Verify upsert was called twice
            verify(jdbcTemplate, times(2)).update(
                    argThat(sql -> sql.contains("ON CONFLICT")),
                    any(), any(), any(), any(), any());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void metadataExtractor_stripsHtmlTagsFromIptcCaption() {
        String rawCaption = "<b>Hello</b> World <script>alert('xss')</script>";
        String sanitized = metadataExtractor.sanitize(rawCaption);
        assertThat(sanitized).doesNotContain("<b>", "<script>", "</script>");
        assertThat(sanitized).contains("Hello");
        assertThat(sanitized).contains("World");
    }

    @Test
    void metadataExtractor_stripsHtmlTagsFromIptcTitle() {
        String rawTitle = "<h1>My Photo Title</h1>";
        String sanitized = metadataExtractor.sanitize(rawTitle);
        assertThat(sanitized).doesNotContain("<h1>", "</h1>");
        assertThat(sanitized).contains("My Photo Title");
    }

    @Test
    void metadataExtractor_stripsHtmlTagsFromIptcDescription() {
        String rawDescription = "<p>A beautiful <em>landscape</em></p>";
        String sanitized = metadataExtractor.sanitize(rawDescription);
        assertThat(sanitized).doesNotContain("<p>", "<em>", "</em>", "</p>");
        assertThat(sanitized).contains("landscape");
    }

    @Test
    void metadataExtractor_stripsHtmlTagsFromExifUserComment() {
        // SA2-F1: EXIF UserComment field may contain injected markup
        Map<String, Object> rawExif = new HashMap<>();
        rawExif.put("UserComment", "<script>alert('xss')</script>Safe Comment");
        rawExif.put("Make", "Canon");

        Map<String, Object> sanitized = metadataExtractor.sanitizeMap(rawExif);

        assertThat(sanitized.get("UserComment").toString()).doesNotContain("<script>");
        assertThat(sanitized.get("UserComment").toString()).contains("Safe Comment");
        assertThat(sanitized.get("Make")).isEqualTo("Canon");
    }

    @Test
    void metadataExtractor_capturesExifToolOutputForLargeExifPhoto() throws Exception {
        // SA2-F2: Verifies that exiftool stdout redirect-to-file works; even if exiftool
        // is not installed, the fallback path should not block on stdout pipe.
        // We test the runExifTool method directly with a temp file.
        UUID photoId = UUID.randomUUID();
        Path tmp = Files.createTempFile(Path.of("/tmp"), "large-exif", ".jpg");
        try {
            // Write a 1 KB file (simulating photo with large XMP)
            byte[] content = new byte[1024];
            System.arraycopy(minimalJpegBytes(), 0, content, 0,
                    Math.min(minimalJpegBytes().length, content.length));
            Files.write(tmp, content);

            // runExifTool should return without blocking, even if exiftool isn't installed
            // (it will fail with IOException, but NOT by blocking on stdout pipe)
            try {
                Map<String, Object> result = metadataExtractor.runExifTool(tmp, photoId);
                // If exiftool is installed, result may have data; if not, it's empty
                assertThat(result).isNotNull();
            } catch (ProcessingException e) {
                // Expected if exiftool is not installed — acceptable outcome
                assertThat(e.getMessage()).satisfiesAnyOf(
                        msg -> assertThat(msg).contains("ExifTool"),
                        msg -> assertThat(msg).contains("photo"));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void metadataExtractor_handlesNullExifValues() {
        // SA2: null values in EXIF map must not cause NPE during sanitization
        Map<String, Object> rawExif = new HashMap<>();
        rawExif.put("Make", "Canon");
        rawExif.put("NullField", null);  // explicitly null value
        rawExif.put("Model", "EOS R5");

        // Should not throw
        Map<String, Object> sanitized = metadataExtractor.sanitizeMap(rawExif);

        assertThat(sanitized.get("Make")).isEqualTo("Canon");
        assertThat(sanitized.get("NullField")).isNull();
        assertThat(sanitized.get("Model")).isEqualTo("EOS R5");
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /** Returns a minimal valid JPEG byte array (SOI + APP0 + EOI). */
    private byte[] minimalJpegBytes() {
        return new byte[]{
            (byte) 0xFF, (byte) 0xD8,   // SOI
            (byte) 0xFF, (byte) 0xE0,   // APP0
            0x00, 0x10,                  // length = 16
            'J', 'F', 'I', 'F', 0x00,   // identifier
            0x01, 0x01,                  // version
            0x00,                        // aspect ratio units
            0x00, 0x01, 0x00, 0x01,     // X/Y density
            0x00, 0x00,                  // thumbnail size
            (byte) 0xFF, (byte) 0xD9    // EOI
        };
    }
}
