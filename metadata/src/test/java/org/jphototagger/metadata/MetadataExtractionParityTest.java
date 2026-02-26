package org.jphototagger.metadata;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import java.io.File;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies metadata-extractor correctly reads EXIF, IPTC, and XMP from a
 * sample image, catching regressions from the Imagero replacement (Task 0.8).
 */
public class MetadataExtractionParityTest {

    private static File sampleImage;

    @BeforeAll
    static void setUp() {
        URL url = MetadataExtractionParityTest.class.getResource("/samples/test-metadata.jpg");
        assertNotNull(url, "Sample image not found on classpath");
        sampleImage = new File(url.getFile());
        assertTrue(sampleImage.exists(), "Sample image file does not exist");
    }

    // --- EXIF tests ---

    @Test
    void exifCameraMake() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertNotNull(ifd0, "ExifIFD0Directory missing");
        assertEquals("TestCamera", ifd0.getString(ExifIFD0Directory.TAG_MAKE));
    }

    @Test
    void exifCameraModel() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertNotNull(ifd0, "ExifIFD0Directory missing");
        assertEquals("Model X100", ifd0.getString(ExifIFD0Directory.TAG_MODEL));
    }

    @Test
    void exifDateTime() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertNotNull(ifd0, "ExifIFD0Directory missing");
        assertEquals("2025:06:15 14:30:00", ifd0.getString(ExifIFD0Directory.TAG_DATETIME));
    }

    @Test
    void exifOrientation() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertNotNull(ifd0, "ExifIFD0Directory missing");
        assertEquals(1, ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION));
    }

    @Test
    void exifGpsCoordinates() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        assertNotNull(gps, "GpsDirectory missing");

        // Latitude: 48°51'23.76" N = 48.8566
        // Longitude: 2°21'7.92" E = 2.3522
        var geoLocation = gps.getGeoLocation();
        assertNotNull(geoLocation, "GeoLocation missing");
        assertEquals(48.8566, geoLocation.getLatitude(), 0.001);
        assertEquals(2.3522, geoLocation.getLongitude(), 0.001);
    }

    // --- IPTC tests ---

    @Test
    void iptcObjectName() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        IptcDirectory iptc = metadata.getFirstDirectoryOfType(IptcDirectory.class);
        assertNotNull(iptc, "IptcDirectory missing");
        assertEquals("Test Photo Title", iptc.getString(IptcDirectory.TAG_OBJECT_NAME));
    }

    @Test
    void iptcByline() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        IptcDirectory iptc = metadata.getFirstDirectoryOfType(IptcDirectory.class);
        assertNotNull(iptc, "IptcDirectory missing");
        assertEquals("John Photographer", iptc.getString(IptcDirectory.TAG_BY_LINE));
    }

    @Test
    void iptcCaption() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        IptcDirectory iptc = metadata.getFirstDirectoryOfType(IptcDirectory.class);
        assertNotNull(iptc, "IptcDirectory missing");
        assertEquals("A test image for metadata extraction", iptc.getString(IptcDirectory.TAG_CAPTION));
    }

    @Test
    void iptcKeywords() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        IptcDirectory iptc = metadata.getFirstDirectoryOfType(IptcDirectory.class);
        assertNotNull(iptc, "IptcDirectory missing");
        List<String> keywords = iptc.getKeywords();
        assertNotNull(keywords);
        assertTrue(keywords.contains("test"), "Missing keyword 'test'");
        assertTrue(keywords.contains("metadata"), "Missing keyword 'metadata'");
        assertTrue(keywords.contains("sample"), "Missing keyword 'sample'");
    }

    // --- XMP tests ---

    @Test
    void xmpDublinCoreTitle() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        XmpDirectory xmp = metadata.getFirstDirectoryOfType(XmpDirectory.class);
        assertNotNull(xmp, "XmpDirectory missing");
        var xmpMeta = xmp.getXMPMeta();
        assertNotNull(xmpMeta);
        String title = xmpMeta.getArrayItem("http://purl.org/dc/elements/1.1/", "title", 1).getValue();
        assertEquals("XMP Test Title", title);
    }

    @Test
    void xmpDublinCoreDescription() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        XmpDirectory xmp = metadata.getFirstDirectoryOfType(XmpDirectory.class);
        assertNotNull(xmp, "XmpDirectory missing");
        var xmpMeta = xmp.getXMPMeta();
        String description = xmpMeta.getArrayItem("http://purl.org/dc/elements/1.1/", "description", 1).getValue();
        assertEquals("XMP test description", description);
    }

    @Test
    void xmpDublinCoreCreator() throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(sampleImage);
        XmpDirectory xmp = metadata.getFirstDirectoryOfType(XmpDirectory.class);
        assertNotNull(xmp, "XmpDirectory missing");
        var xmpMeta = xmp.getXMPMeta();
        String creator = xmpMeta.getArrayItem("http://purl.org/dc/elements/1.1/", "creator", 1).getValue();
        assertEquals("XMP Creator Name", creator);
    }

    // App-level IptcMetadata integration test omitted: IptcEntry.getData()
    // requires NetBeans Lookup (Preferences service) which is unavailable in
    // unit tests. The direct metadata-extractor IPTC tests above verify the
    // extraction layer that IptcMetadata delegates to.
}
