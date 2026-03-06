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
import org.jphototagger.worker.exception.ProcessingException;
import org.jphototagger.worker.exception.ProcessTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

        // Stub download to write a real file so generate() proceeds past download
        doAnswer(inv -> {
            Path dest = inv.getArgument(1);
            Files.write(dest, minimalJpegBytes());
            return null;
        }).when(spy).downloadFromMinio(anyString(), any(Path.class));

        // Create a mock Process that reports it did NOT complete within the timeout
        Process mockProcess = mock(Process.class);
        when(mockProcess.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);

        // Stub startProcess so generate() → generateThumbnail() receives our mock Process
        doReturn(mockProcess).when(spy).startProcess(any(ProcessBuilder.class));

        // generate() must throw ProcessTimeoutException and must call destroyForcibly()
        assertThatThrownBy(() -> spy.generate(photoId, userId, storageKey, "image/jpeg"))
                .isInstanceOf(ProcessTimeoutException.class)
                .hasMessageContaining("timed out");

        verify(mockProcess).destroyForcibly();
    }

    // =========================================================================
    // MetadataExtractor tests
    // =========================================================================

    @Test
    void metadataExtractor_extractsExifData() throws Exception {
        UUID photoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Create a real 1×1 JPEG with a known Make tag embedded so metadata-extractor
        // can return at least one EXIF key. We write a standard JFIF JPEG and rely on
        // metadata-extractor to detect the JFIF/Exif segments it can read.
        Path tmp = File.createTempFile("meta-test-", ".jpg", new File("/tmp")).toPath();
        try {
            BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "jpg", tmp.toFile());

            // Capture all arguments passed to jdbcTemplate.update()
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
            metadataExtractor.extract(photoId, userId, tmp);

            verify(jdbcTemplate).update(
                    argThat(sql -> sql.contains("INSERT INTO photo_metadata") &&
                                  sql.contains("ON CONFLICT")),
                    any(), any(),
                    argThat(exifJson -> {
                        // metadata-extractor returns at least the JFIF directory entries
                        // for any valid JPEG — the JSON must not be the empty object "{}"
                        String json = (String) exifJson;
                        return json != null && !json.equals("{}");
                    }),
                    any(), any());
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
        // SA2-F2: Verifies that the ProcessBuilder for exiftool has stdout redirected
        // to a file (not PIPE) before waitFor() is called, preventing pipe buffer
        // exhaustion on photos with large XMP blocks.
        //
        // We call createExifToolProcessBuilder() directly and assert that the returned
        // ProcessBuilder's redirectOutput() is set to Redirect.to(outputFile), not PIPE.
        Path tmp = File.createTempFile("large-exif-", ".jpg", new File("/tmp")).toPath();
        File outputFile = File.createTempFile("exiftool-verify-", ".json", new File("/tmp"));
        try {
            Files.write(tmp, minimalJpegBytes());

            ProcessBuilder pb = metadataExtractor.createExifToolProcessBuilder(tmp, outputFile);

            assertThat(pb.redirectOutput())
                    .as("stdout must be redirected to a file (SA2-F2), not PIPE or INHERIT")
                    .isNotEqualTo(ProcessBuilder.Redirect.PIPE)
                    .isNotEqualTo(ProcessBuilder.Redirect.INHERIT)
                    .isEqualTo(ProcessBuilder.Redirect.to(outputFile));
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(outputFile.toPath());
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
