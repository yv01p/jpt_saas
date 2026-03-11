package org.jphototagger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jphototagger.api.entity.Photo;

import java.time.Instant;
import java.util.UUID;

public record PhotoResponse(
        UUID id,
        String filename,
        String caption,
        String title,
        String description,
        @JsonProperty("size_bytes") Long sizeBytes,
        @JsonProperty("taken_at") Instant takenAt,
        @JsonProperty("uploaded_at") Instant uploadedAt,
        @JsonProperty("deleted_at") Instant deletedAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("processing_status") String processingStatus,
        @JsonProperty("thumbnail_url") String thumbnailUrl,
        @JsonProperty("original_url") String originalUrl
) {
    public static PhotoResponse from(Photo photo) {
        return new PhotoResponse(
                photo.getId(),
                photo.getFilename(),
                photo.getCaption(),
                photo.getTitle(),
                photo.getDescription(),
                photo.getSizeBytes(),
                photo.getTakenAt(),
                photo.getUploadedAt(),
                photo.getDeletedAt(),
                photo.getUpdatedAt(),
                photo.getProcessingStatus() != null ? photo.getProcessingStatus().name() : null,
                null,
                null
        );
    }

    public static PhotoResponse from(Photo photo, String thumbnailUrl, String originalUrl) {
        return new PhotoResponse(
                photo.getId(),
                photo.getFilename(),
                photo.getCaption(),
                photo.getTitle(),
                photo.getDescription(),
                photo.getSizeBytes(),
                photo.getTakenAt(),
                photo.getUploadedAt(),
                photo.getDeletedAt(),
                photo.getUpdatedAt(),
                photo.getProcessingStatus() != null ? photo.getProcessingStatus().name() : null,
                thumbnailUrl,
                originalUrl
        );
    }
}
