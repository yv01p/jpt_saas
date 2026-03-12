package org.jphototagger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jphototagger.api.entity.PhotoMetadata;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
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

    private static final Set<String> IPTC_LOCATION_KEYS = Set.of(
            "iptc:sub-location", "iptc:city", "iptc:province-state",
            "iptc:country-primary location code", "iptc:country-primary location name",
            "sub-location", "city", "province-state",
            "country-primary location code", "country-primary location name"
    );

    private static final Set<String> XMP_LOCATION_KEYS = Set.of(
            "photoshop:city", "photoshop:state", "photoshop:country",
            "iptc4xmpcore:location", "xmp:location"
    );

    public PhotoMetadataResponse withoutGps() {
        Map<String, Object> filteredExif = filterGpsKeys(exifData);
        Map<String, Object> filteredIptc = filterLocationKeys(iptcData, IPTC_LOCATION_KEYS);
        Map<String, Object> filteredXmp = filterGpsAndLocationKeys(xmpData);
        return new PhotoMetadataResponse(photoId, null, null, filteredExif, filteredIptc, filteredXmp, extractedAt);
    }

    private static Map<String, Object> filterGpsKeys(Map<String, Object> data) {
        if (data == null) return null;
        return data.entrySet().stream()
                .filter(e -> {
                    String lower = e.getKey().toLowerCase();
                    return !lower.contains("gps") && !lower.startsWith("gps:");
                })
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Map<String, Object> filterGpsAndLocationKeys(Map<String, Object> data) {
        if (data == null) return null;
        return data.entrySet().stream()
                .filter(e -> {
                    String lower = e.getKey().toLowerCase();
                    return !lower.contains("gps") && !XMP_LOCATION_KEYS.contains(lower);
                })
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Map<String, Object> filterLocationKeys(Map<String, Object> data, Set<String> locationKeys) {
        if (data == null) return null;
        return data.entrySet().stream()
                .filter(e -> !locationKeys.contains(e.getKey().toLowerCase()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
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
