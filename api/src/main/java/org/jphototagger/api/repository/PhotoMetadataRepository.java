package org.jphototagger.api.repository;

import org.jphototagger.api.entity.PhotoMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PhotoMetadataRepository extends JpaRepository<PhotoMetadata, UUID> {

    List<PhotoMetadata> findByUserId(UUID userId);
}
