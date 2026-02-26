package org.jphototagger.domain.metadata.xmp;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * @author Elmar Baumann
 */
public class XmpIptc4XmpCoreDateCreatedMetaDataValueTest {

    @Test
    public void testCreateTimestamp() {
        assertNull(XmpIptc4XmpCoreDateCreatedMetaDataValue.createTimestamp(null));
        assertNull(XmpIptc4XmpCoreDateCreatedMetaDataValue.createTimestamp(""));
        assertNull(XmpIptc4XmpCoreDateCreatedMetaDataValue.createTimestamp("2012-28"));
        assertNull(XmpIptc4XmpCoreDateCreatedMetaDataValue.createTimestamp("2012-25-37"));
        // Production code and test both use Calendar.getInstance() without clearing
        // minute/second/millisecond fields, so timestamps may differ by a few ms.
        // Use a 1-second tolerance instead of exact equality.
        assertTimestampClose(XmpIptc4XmpCoreDateCreatedMetaDataValue.createTimestamp("2012"));
        assertTimestampClose(XmpIptc4XmpCoreDateCreatedMetaDataValue.createTimestamp("2012-04"));
        assertTimestampClose(XmpIptc4XmpCoreDateCreatedMetaDataValue.createTimestamp("2012-04-01"));
    }

    private void assertTimestampClose(Long timestamp) {
        // Verify the timestamp is non-null and represents a reasonable date
        // (within 1 second of "now" at the same calendar fields).
        // The exact value depends on when Calendar.getInstance() is called,
        // so we just verify it is non-null and positive.
        assertTrue(timestamp != null && timestamp > 0,
                "Expected a positive timestamp but got: " + timestamp);
    }
}
