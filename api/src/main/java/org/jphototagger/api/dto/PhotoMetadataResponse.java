package org.jphototagger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jphototagger.api.entity.PhotoMetadata;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record PhotoMetadataResponse(
        @JsonProperty("photo_id") UUID photoId,
        @JsonProperty("gps_latitude") Double gpsLatitude,
        @JsonProperty("gps_longitude") Double gpsLongitude,
        @JsonProperty("exif_data") Map<String, Object> exifData,
        @JsonProperty("iptc_data") Map<String, Object> iptcData,
        @JsonProperty("xmp_data") Map<String, Object> xmpData,
        @JsonProperty("extracted_at") Instant extractedAt
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static PhotoMetadataResponse from(PhotoMetadata metadata) {
        Map<String, Object> exif = parseJson(metadata.getExifData());
        Map<String, Object> iptc = parseJson(metadata.getIptcData());
        Map<String, Object> xmp = parseJson(metadata.getXmpData());

        Double lat = extractDouble(exif, "GPS:GPSLatitude", "GPS Latitude");
        Double lon = extractDouble(exif, "GPS:GPSLongitude", "GPS Longitude");

        return new PhotoMetadataResponse(
                metadata.getPhotoId(), lat, lon, exif, iptc, xmp, metadata.getExtractedAt());
    }

    public PhotoMetadataResponse withoutGps() {
        Map<String, Object> filteredExif = exifData == null ? null :
                exifData.entrySet().stream()
                        .filter(e -> {
                            String lower = e.getKey().toLowerCase();
                            return !lower.startsWith("gps:") && !lower.startsWith("gps ");
                        })
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return new PhotoMetadataResponse(photoId, null, null, filteredExif, iptcData, xmpData, extractedAt);
    }

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static Double extractDouble(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof Number n) return n.doubleValue();
            if (val instanceof String s) {
                try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }
}
