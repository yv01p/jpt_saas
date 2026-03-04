package org.jphototagger.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "album_photos")
@IdClass(AlbumPhotoId.class)
public class AlbumPhoto {

    @Id
    @Column(name = "album_id")
    private UUID albumId;

    @Id
    @Column(name = "photo_id")
    private UUID photoId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    public UUID getAlbumId() { return albumId; }
    public void setAlbumId(UUID albumId) { this.albumId = albumId; }

    public UUID getPhotoId() { return photoId; }
    public void setPhotoId(UUID photoId) { this.photoId = photoId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AlbumPhoto that)) return false;
        return albumId != null && photoId != null
            && albumId.equals(that.albumId) && photoId.equals(that.photoId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
