package org.jphototagger.api.repository;

import org.jphototagger.api.entity.Keyword;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KeywordRepository extends JpaRepository<Keyword, UUID> {

    List<Keyword> findByUserId(UUID userId);

    List<Keyword> findByUserIdAndParentIdIsNull(UUID userId);

    Page<Keyword> findByUserIdOrderByNameAsc(UUID userId, Pageable pageable);

    /**
     * Recursive CTE to fetch a keyword and all its descendants.
     * [v4 SA-5] ORDER BY is hardcoded. Pageable must not include Sort.
     */
    @Query(value = "WITH RECURSIVE subtree AS ("
            + "  SELECT id, user_id, name, parent_id, updated_at FROM keywords "
            + "  WHERE id = :rootId AND user_id = :userId "
            + "  UNION ALL "
            + "  SELECT k.id, k.user_id, k.name, k.parent_id, k.updated_at FROM keywords k "
            + "  INNER JOIN subtree s ON k.parent_id = s.id"
            + ") SELECT * FROM subtree ORDER BY name LIMIT 1000",
            nativeQuery = true)
    List<Keyword> findSubtree(@Param("userId") UUID userId, @Param("rootId") UUID rootId);
}
