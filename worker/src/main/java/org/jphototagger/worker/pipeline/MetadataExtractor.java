package org.jphototagger.worker.pipeline;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.entity.PhotoMetadata;
import org.jphototagger.api.repository.PhotoMetadataRepository;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.worker.config.WorkerProperties;
import org.jphototagger.worker.exception.ProcessingException;
import org.jphototagger.worker.exception.ProcessTimeoutException;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Extracts EXIF, IPTC, and XMP metadata from a photo file.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Primary: {@code metadata-extractor} Java library (fast, no subprocess).</li>
 *   <li>Fallback: {@code exiftool -fast2 -json} via ProcessBuilder if primary extraction
 *       yields no EXIF data. stdout is redirected to a temp file before {@code waitFor()}
 *       to prevent pipe buffer exhaustion on large XMP blocks (SA2-F2).</li>
 * </ol>
 *
 * <p>All text fields are sanitized with {@code Jsoup.parse(s).text()} before DB write (SA2-F1).
 * {@code HashMap.put()} is used (not {@code Collectors.toMap()}) to handle null EXIF values.
 *
 * <p>DB write uses a native upsert so reprocessing a photo never duplicates rows.
 */
@Component
public class MetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(MetadataExtractor.class);

    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository photoMetadataRepository;
    private final JdbcTemplate jdbcTemplate;
    private final WorkerProperties workerProperties;
    private final ObjectMapper objectMapper;

    public MetadataExtractor(PhotoRepository photoRepository,
                              PhotoMetadataRepository photoMetadataRepository,
                              JdbcTemplate jdbcTemplate,
                              WorkerProperties workerProperties,
                              ObjectMapper objectMapper) {
        this.photoRepository = photoRepository;
        this.photoMetadataRepository = photoMetadataRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.workerProperties = workerProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts metadata from {@code tmpFile} and writes it to the database.
     * Also populates {@code caption}, {@code title}, and {@code description} on the
     * {@code photos} row from IPTC/XMP data.
     *
     * @param photoId the photo UUID
     * @param userId  the owner's user UUID
     * @param tmpFile the local file to extract from (must already be on tmpfs)
     */
    public void extract(UUID photoId, UUID userId, Path tmpFile) {
        Map<String, Object> exifData = new HashMap<>();
        Map<String, Object> iptcData = new HashMap<>();
        Map<String, Object> xmpData = new HashMap<>();

        String rawCaption = null;
        String rawTitle = null;
        String rawDescription = null;

        // ---- Primary: metadata-extractor ----
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(tmpFile.toFile());

            for (Directory directory : metadata.getDirectories()) {
                for (Tag tag : directory.getTags()) {
                    String key = directory.getName() + "." + tag.getTagName();
                    String value = tag.getDescription();
                    if (directory instanceof ExifIFD0Directory) {
                        exifData.put(tag.getTagName(), value);
                    } else if (directory instanceof IptcDirectory) {
                        iptcData.put(tag.getTagName(), value);
                    } else if (directory instanceof XmpDirectory) {
                        xmpData.put(tag.getTagName(), value);
                    } else {
                        // Put everything else in exifData keyed with directory name prefix
                        exifData.put(key, value);
                    }
                }
            }

            // Extract well-known IPTC fields
            IptcDirectory iptcDir = metadata.getFirstDirectoryOfType(IptcDirectory.class);
            if (iptcDir != null) {
                rawCaption = iptcDir.getString(IptcDirectory.TAG_CAPTION);
                rawTitle = iptcDir.getString(IptcDirectory.TAG_OBJECT_NAME);
                rawDescription = iptcDir.getString(IptcDirectory.TAG_CAPTION); // same field
            }

            // Fall back to XMP for title/description if IPTC is empty
            if (rawTitle == null || rawTitle.isBlank()) {
                rawTitle = getXmpString(xmpData, "dc:title");
            }
            if (rawDescription == null || rawDescription.isBlank()) {
                rawDescription = getXmpString(xmpData, "dc:description");
            }

        } catch (Exception e) {
            log.warn("metadata-extractor failed for photo {}, falling back to exiftool: {}",
                    photoId, e.getMessage());
            // Fall through to exiftool fallback
        }

        // ---- Fallback: exiftool -fast2 ----
        if (exifData.isEmpty()) {
            exifData = runExifTool(tmpFile, photoId);
        }

        // ---- Sanitize all text fields (SA2-F1) ----
        Map<String, Object> sanitizedExifData = sanitizeMap(exifData);
        Map<String, Object> sanitizedIptcData = sanitizeMap(iptcData);
        Map<String, Object> sanitizedXmpData  = sanitizeMap(xmpData);

        String safeCaption     = sanitize(rawCaption);
        String safeTitle       = sanitize(rawTitle);
        String safeDescription = sanitize(rawDescription);

        // ---- Write metadata to DB (upsert) ----
        String exifJson = toJson(sanitizedExifData);
        String iptcJson = toJson(sanitizedIptcData);
        String xmpJson  = toJson(sanitizedXmpData);

        jdbcTemplate.update(
                "INSERT INTO photo_metadata (photo_id, user_id, exif_data, iptc_data, xmp_data, extracted_at) " +
                "VALUES (?::uuid, ?::uuid, ?::jsonb, ?::jsonb, ?::jsonb, now()) " +
                "ON CONFLICT (photo_id) DO UPDATE SET " +
                "  exif_data = EXCLUDED.exif_data, " +
                "  iptc_data = EXCLUDED.iptc_data, " +
                "  xmp_data = EXCLUDED.xmp_data, " +
                "  extracted_at = EXCLUDED.extracted_at",
                photoId.toString(), userId.toString(), exifJson, iptcJson, xmpJson);

        // ---- Update photos table with caption/title/description ----
        if (safeCaption != null || safeTitle != null || safeDescription != null) {
            Photo photo = photoRepository.findById(photoId).orElse(null);
            if (photo != null) {
                if (safeCaption != null)     photo.setCaption(safeCaption);
                if (safeTitle != null)       photo.setTitle(safeTitle);
                if (safeDescription != null) photo.setDescription(safeDescription);
                photoRepository.save(photo);
            }
        }

        log.info("Metadata extracted for photo {}: exif={} fields, iptc={} fields, xmp={} fields",
                photoId, sanitizedExifData.size(), sanitizedIptcData.size(), sanitizedXmpData.size());
    }

    /**
     * Runs {@code exiftool -fast2 -json} and parses the JSON output.
     * stdout is redirected to a temp file before {@code waitFor()} to prevent
     * pipe buffer exhaustion on large XMP blocks (SA2-F2).
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> runExifTool(Path tmpFile, UUID photoId) {
        int timeout = workerProperties.getProcess().getTimeoutMinutes();
        File outputFile = null;
        try {
            outputFile = File.createTempFile("exiftool-", ".json", new File("/tmp"));
            ProcessBuilder pb = new ProcessBuilder(
                    "exiftool", "-fast2", "-json", tmpFile.toString());
            pb.redirectOutput(outputFile);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            boolean completed = process.waitFor(timeout, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                throw new ProcessTimeoutException("ExifTool timed out for photo " + photoId);
            }
            String json = Files.readString(outputFile.toPath());
            if (json == null || json.isBlank()) {
                return new HashMap<>();
            }
            // exiftool returns a JSON array with one object
            List<Map<String, Object>> list = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            if (list != null && !list.isEmpty()) {
                return new HashMap<>(list.get(0));
            }
            return new HashMap<>();
        } catch (ProcessTimeoutException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException("ExifTool interrupted for photo " + photoId, e);
        } catch (IOException e) {
            throw new ProcessingException("Failed to run ExifTool for photo " + photoId, e);
        } finally {
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile.toPath());
                } catch (IOException e) {
                    log.error("Failed to delete ExifTool temp file: {}", outputFile, e);
                }
            }
        }
    }

    /**
     * Sanitizes all String values in a map using Jsoup text extraction (SA2-F1).
     * Uses HashMap.put() to handle null values safely (avoids NPE from Collectors.toMap).
     */
    Map<String, Object> sanitizeMap(Map<String, Object> rawMap) {
        Map<String, Object> sanitized = new HashMap<>();
        rawMap.forEach((k, v) ->
                sanitized.put(k, v instanceof String s ? Jsoup.parse(s).text() : v));
        return sanitized;
    }

    /**
     * Sanitizes a single string field.
     */
    String sanitize(String s) {
        return s != null ? Jsoup.parse(s).text() : null;
    }

    private String getXmpString(Map<String, Object> xmpData, String key) {
        Object val = xmpData.get(key);
        return val instanceof String s ? s : null;
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (IOException e) {
            log.warn("Failed to serialize metadata map to JSON", e);
            return "{}";
        }
    }
}
