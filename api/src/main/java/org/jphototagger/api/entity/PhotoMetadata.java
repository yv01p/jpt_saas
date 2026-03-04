package org.jphototagger.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "photo_metadata")
public class PhotoMetadata {

    @Id
    @Column(name = "photo_id")
    private UUID photoId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exif_data", columnDefinition = "jsonb")
    private String exifData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "iptc_data", columnDefinition = "jsonb")
    private String iptcData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "xmp_data", columnDefinition = "jsonb")
    private String xmpData;

    public UUID getPhotoId() { return photoId; }
    public void setPhotoId(UUID photoId) { this.photoId = photoId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getExifData() { return exifData; }
    public void setExifData(String exifData) { this.exifData = exifData; }

    public String getIptcData() { return iptcData; }
    public void setIptcData(String iptcData) { this.iptcData = iptcData; }

    public String getXmpData() { return xmpData; }
    public void setXmpData(String xmpData) { this.xmpData = xmpData; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PhotoMetadata that)) return false;
        return photoId != null && photoId.equals(that.photoId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
