package org.jphototagger.api.repository;

import org.jphototagger.api.entity.Keyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KeywordRepository extends JpaRepository<Keyword, UUID> {

    List<Keyword> findByUserId(UUID userId);

    List<Keyword> findByUserIdAndParentIdIsNull(UUID userId);
}
