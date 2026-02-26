package org.jphototagger.lib.net;

import org.jphototagger.lib.util.Version;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * @author Elmar Baumann
 */
public class VersionCheckTest {

    public VersionCheckTest() {
    }

    @BeforeAll
    public static void setUpClass() throws Exception {
    }

    @AfterAll
    public static void tearDownClass() throws Exception {
    }

    /**
     * Test of existsNewer method, of class NetVersion.
     * @throws Exception
     */
    @Test
    @Disabled("Requires local HTTP server")
    public void testExistsNewer() throws Exception {
        final String urlHtml = "http://localhost/fotografie/tipps/computer/lightroom/imagemetadataviewer.html";
        final String versionDelimiter = ".";
        Version compareToVersion = new Version(0, 7, 2);
        boolean expResult = true;
        boolean result = compareToVersion.compareTo(NetVersion.getOverHttp(urlHtml, versionDelimiter)) < 0;

        assertEquals(expResult, result);
    }
}
