package org.jphototagger.api.repository;

import org.jphototagger.api.entity.Album;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlbumRepository extends JpaRepository<Album, UUID> {

    List<Album> findByUserId(UUID userId);
}
