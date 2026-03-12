package org.jphototagger.api.repository;

import org.jphototagger.api.entity.PhotoKeyword;
import org.jphototagger.api.entity.PhotoKeywordId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PhotoKeywordRepository extends JpaRepository<PhotoKeyword, PhotoKeywordId> {

    List<PhotoKeyword> findByPhotoIdAndUserId(UUID photoId, UUID userId);

    void deleteByPhotoIdAndKeywordIdAndUserId(UUID photoId, UUID keywordId, UUID userId);
}
