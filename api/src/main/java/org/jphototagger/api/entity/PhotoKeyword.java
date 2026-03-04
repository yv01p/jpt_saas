package org.jphototagger.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "photo_keywords")
@IdClass(PhotoKeywordId.class)
public class PhotoKeyword {

    @Id
    @Column(name = "photo_id")
    private UUID photoId;

    @Id
    @Column(name = "keyword_id")
    private UUID keywordId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    public UUID getPhotoId() { return photoId; }
    public void setPhotoId(UUID photoId) { this.photoId = photoId; }

    public UUID getKeywordId() { return keywordId; }
    public void setKeywordId(UUID keywordId) { this.keywordId = keywordId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PhotoKeyword that)) return false;
        return photoId != null && keywordId != null
            && photoId.equals(that.photoId) && keywordId.equals(that.keywordId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
