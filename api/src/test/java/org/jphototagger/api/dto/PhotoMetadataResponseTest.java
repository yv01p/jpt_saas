package org.jphototagger.api.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PhotoMetadataResponseTest {

    private static final UUID PHOTO_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @Test
    void withoutGps_stripsExifGpsKeys() {
        var response = new PhotoMetadataResponse(
                PHOTO_ID, 40.0, -74.0,
                Map.of("GPS:GPSLatitude", 40.0, "GPS:GPSLongitude", -74.0, "Make", "Canon"),
                null, null, NOW);

        var filtered = response.withoutGps();

        assertNull(filtered.gpsLatitude());
        assertNull(filtered.gpsLongitude());
        assertEquals(Map.of("Make", "Canon"), filtered.exifData());
    }

    @Test
    void withoutGps_stripsXmpGpsKeys() {
        var response = new PhotoMetadataResponse(
                PHOTO_ID, 40.0, -74.0, null, null,
                Map.of("exif:GPSLatitude", "40.0", "exif:GPSLongitude", "-74.0", "dc:title", "Test"),
                NOW);

        var filtered = response.withoutGps();

        assertEquals(1, filtered.xmpData().size());
        assertEquals("Test", filtered.xmpData().get("dc:title"));
        assertNull(filtered.xmpData().get("exif:GPSLatitude"));
    }

    @Test
    void withoutGps_stripsIptcLocationKeys() {
        var response = new PhotoMetadataResponse(
                PHOTO_ID, 40.0, -74.0, null,
                Map.of("City", "New York", "Province-State", "NY",
                        "Sub-location", "Manhattan", "Country-Primary Location Code", "US",
                        "IPTC:Keywords", "photo"),
                null, NOW);

        var filtered = response.withoutGps();

        assertEquals(1, filtered.iptcData().size());
        assertEquals("photo", filtered.iptcData().get("IPTC:Keywords"));
    }

    @Test
    void withoutGps_stripsXmpLocationKeys() {
        var response = new PhotoMetadataResponse(
                PHOTO_ID, null, null, null, null,
                Map.of("photoshop:City", "Paris", "photoshop:State", "Ile-de-France",
                        "photoshop:Country", "France", "Iptc4xmpCore:Location", "Eiffel Tower",
                        "xmp:Location", "48.8566,2.3522", "dc:creator", "Photographer"),
                NOW);

        var filtered = response.withoutGps();

        assertEquals(1, filtered.xmpData().size());
        assertEquals("Photographer", filtered.xmpData().get("dc:creator"));
        assertNull(filtered.xmpData().get("photoshop:City"));
        assertNull(filtered.xmpData().get("photoshop:State"));
        assertNull(filtered.xmpData().get("photoshop:Country"));
        assertNull(filtered.xmpData().get("Iptc4xmpCore:Location"));
        assertNull(filtered.xmpData().get("xmp:Location"));
    }

    @Test
    void withoutGps_handlesNullMaps() {
        var response = new PhotoMetadataResponse(PHOTO_ID, null, null, null, null, null, NOW);

        var filtered = response.withoutGps();

        assertNull(filtered.exifData());
        assertNull(filtered.iptcData());
        assertNull(filtered.xmpData());
    }
}
