package org.jphototagger.api.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class AlbumPhotoId implements Serializable {

    private UUID albumId;
    private UUID photoId;

    public AlbumPhotoId() {}

    public AlbumPhotoId(UUID albumId, UUID photoId) {
        this.albumId = albumId;
        this.photoId = photoId;
    }

    public UUID getAlbumId() { return albumId; }
    public void setAlbumId(UUID albumId) { this.albumId = albumId; }

    public UUID getPhotoId() { return photoId; }
    public void setPhotoId(UUID photoId) { this.photoId = photoId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AlbumPhotoId that)) return false;
        return Objects.equals(albumId, that.albumId) && Objects.equals(photoId, that.photoId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(albumId, photoId);
    }
}
