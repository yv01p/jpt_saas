package org.jphototagger.api.repository;

import org.jphototagger.api.entity.AlbumPhoto;
import org.jphototagger.api.entity.AlbumPhotoId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlbumPhotoRepository extends JpaRepository<AlbumPhoto, AlbumPhotoId> {

    void deleteByAlbumIdAndPhotoIdAndUserId(UUID albumId, UUID photoId, UUID userId);

    List<AlbumPhoto> findByAlbumIdAndUserId(UUID albumId, UUID userId);
}
