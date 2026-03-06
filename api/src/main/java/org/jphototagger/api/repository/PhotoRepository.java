package org.jphototagger.api.repository;

import org.jphototagger.api.entity.Photo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    Page<Photo> findByUserIdAndDeletedAtIsNullOrderByUploadedAtDesc(
            UUID userId, Pageable pageable);

    Page<Photo> findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
            UUID userId, Pageable pageable);

    /**
     * Full-text search using PostgreSQL tsvector.
     * <p>
     * [v4 SA-5] ORDER BY is hardcoded in the SQL. The Pageable parameter must be
     * constructed without Sort (e.g. {@code PageRequest.of(page, size)}) to prevent
     * user-supplied sort properties from reaching the native query.
     */
    @Query(value = "SELECT * FROM photos WHERE user_id = :userId AND deleted_at IS NULL "
            + "AND search_vector @@ plainto_tsquery('english', :query) "
            + "ORDER BY uploaded_at DESC",
            countQuery = "SELECT count(*) FROM photos WHERE user_id = :userId AND deleted_at IS NULL "
                    + "AND search_vector @@ plainto_tsquery('english', :query)",
            nativeQuery = true)
    Page<Photo> searchByText(@Param("userId") UUID userId,
                             @Param("query") String query,
                             Pageable pageable);

    /**
     * Returns up to 500 photos eligible for permanent deletion (soft-deleted before cutoff).
     * Always query at page 0 — deleted rows are gone, so the next call naturally returns the next batch.
     * LIMIT 500 is embedded in the SQL; no Pageable is used to avoid Hibernate adding FETCH FIRST.
     */
    @Query(value = "SELECT * FROM photos WHERE deleted_at < :cutoff AND storage_key IS NOT NULL"
            + " ORDER BY deleted_at ASC LIMIT 500",
            nativeQuery = true)
    List<Photo> findPurgeableBatch(@Param("cutoff") Instant cutoff);

    /**
     * Returns all photos belonging to a user that have a non-null storage_key.
     */
    @Query("SELECT p FROM Photo p WHERE p.userId = :userId AND p.storageKey IS NOT NULL")
    List<Photo> findAllByUserIdWithStorageKey(@Param("userId") UUID userId);


    /**
     * Returns a paginated batch of PENDING or PROCESSING photos that are not
     * soft-deleted. Used by the worker startup recovery scan to re-enqueue
     * photos that were interrupted without leaving a PEL entry.
     */
    @Query("SELECT p FROM Photo p WHERE p.processingStatus IN " +
           "  (org.jphototagger.api.enums.ProcessingStatus.PENDING, " +
           "   org.jphototagger.api.enums.ProcessingStatus.PROCESSING) " +
           "AND p.deletedAt IS NULL " +
           "ORDER BY p.uploadedAt ASC")
    Page<Photo> findPendingOrProcessingForRecovery(Pageable pageable);

    Optional<Photo> findByUserIdAndContentHash(UUID userId, String contentHash);

    Optional<Photo> findByUserIdAndContentHashAndDeletedAtIsNull(UUID userId, String contentHash);

    /**
     * EXIF field search using JSONB @> operator.
     * [v4 SA-5] ORDER BY is hardcoded in the SQL.
     */
    @Query(value = "SELECT p.* FROM photos p JOIN photo_metadata pm ON pm.photo_id = p.id "
            + "WHERE pm.user_id = :userId AND p.deleted_at IS NULL "
            + "AND pm.exif_data @> cast(:jsonFilter as jsonb) "
            + "ORDER BY p.uploaded_at DESC",
            countQuery = "SELECT count(*) FROM photos p JOIN photo_metadata pm ON pm.photo_id = p.id "
                    + "WHERE pm.user_id = :userId AND p.deleted_at IS NULL "
                    + "AND pm.exif_data @> cast(:jsonFilter as jsonb)",
            nativeQuery = true)
    Page<Photo> searchByExif(@Param("userId") UUID userId,
                             @Param("jsonFilter") String jsonFilter,
                             Pageable pageable);

    /**
     * Keyword search joining through photo_keywords.
     * [v4 SA-5] ORDER BY is hardcoded in the SQL.
     */
    @Query(value = "SELECT p.* FROM photos p JOIN photo_keywords pk ON pk.photo_id = p.id "
            + "WHERE pk.user_id = :userId AND pk.keyword_id = :keywordId AND p.deleted_at IS NULL "
            + "ORDER BY p.uploaded_at DESC",
            countQuery = "SELECT count(*) FROM photos p JOIN photo_keywords pk ON pk.photo_id = p.id "
                    + "WHERE pk.user_id = :userId AND pk.keyword_id = :keywordId AND p.deleted_at IS NULL",
            nativeQuery = true)
    Page<Photo> searchByKeyword(@Param("userId") UUID userId,
                                @Param("keywordId") UUID keywordId,
                                Pageable pageable);
}
