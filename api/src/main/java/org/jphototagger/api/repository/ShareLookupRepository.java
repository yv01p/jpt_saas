package org.jphototagger.api.repository;

import javax.sql.DataSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Unauthenticated share lookups using the share_reader BYPASSRLS DataSource.
 * Only this class has access to the BYPASSRLS DataSource — it is not registered as a Spring bean.
 */
public class ShareLookupRepository {

    private final JdbcTemplate jdbc;

    public ShareLookupRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Look up a share by its token hash. Returns the share record if found and not expired.
     * Uses BYPASSRLS to access shares table without tenant filtering.
     */
    public Optional<Map<String, Object>> findShareByTokenHash(String tokenHash) {
        var results = jdbc.queryForList(
            "SELECT s.id, s.user_id, s.resource_type, s.resource_id, " +
            "       s.expires_at, s.permissions, s.include_gps " +
            "FROM shares s " +
            "WHERE s.token_hash = ? AND (s.expires_at IS NULL OR s.expires_at > now())",
            tokenHash);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Look up photos in an album for a public share. Returns paginated results.
     * Joins album_photos and photos, filtering out deleted photos.
     */
    public Page<Map<String, Object>> findAlbumPhotos(UUID albumId, Pageable pageable) {
        var count = jdbc.queryForObject(
            "SELECT count(*) FROM album_photos ap " +
            "JOIN photos p ON ap.photo_id = p.id " +
            "WHERE ap.album_id = ? AND p.deleted_at IS NULL",
            Long.class, albumId);
        var rows = jdbc.queryForList(
            "SELECT p.id, p.filename, p.caption, p.title, p.description, " +
            "       p.size_bytes, p.taken_at, p.uploaded_at, p.processing_status, p.storage_key " +
            "FROM album_photos ap " +
            "JOIN photos p ON ap.photo_id = p.id " +
            "WHERE ap.album_id = ? AND p.deleted_at IS NULL " +
            "ORDER BY p.taken_at ASC NULLS LAST, p.uploaded_at ASC " +
            "LIMIT ? OFFSET ?",
            albumId, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(rows, pageable, count == null ? 0 : count);
    }

    /**
     * Look up a photo by ID for a public share (used when share resource_type = 'photo').
     * Also JOINs photo_metadata to return EXIF/IPTC/XMP data for GPS stripping.
     */
    public Optional<Map<String, Object>> findPhotoById(UUID photoId) {
        var results = jdbc.queryForList(
            "SELECT p.id, p.filename, p.caption, p.title, p.description, " +
            "       p.size_bytes, p.taken_at, p.uploaded_at, p.processing_status, p.storage_key, " +
            "       pm.exif_data, pm.iptc_data, pm.xmp_data " +
            "FROM photos p " +
            "LEFT JOIN photo_metadata pm ON pm.photo_id = p.id " +
            "WHERE p.id = ? AND p.deleted_at IS NULL",
            photoId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
