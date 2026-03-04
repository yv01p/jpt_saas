package org.jphototagger.api.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class PhotoKeywordId implements Serializable {

    private UUID photoId;
    private UUID keywordId;

    public PhotoKeywordId() {}

    public PhotoKeywordId(UUID photoId, UUID keywordId) {
        this.photoId = photoId;
        this.keywordId = keywordId;
    }

    public UUID getPhotoId() { return photoId; }
    public void setPhotoId(UUID photoId) { this.photoId = photoId; }

    public UUID getKeywordId() { return keywordId; }
    public void setKeywordId(UUID keywordId) { this.keywordId = keywordId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PhotoKeywordId that)) return false;
        return Objects.equals(photoId, that.photoId) && Objects.equals(keywordId, that.keywordId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(photoId, keywordId);
    }
}
