package org.jphototagger.api.repository;

import org.jphototagger.api.entity.Photo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    Optional<Photo> findByUserIdAndContentHash(UUID userId, String contentHash);
}
