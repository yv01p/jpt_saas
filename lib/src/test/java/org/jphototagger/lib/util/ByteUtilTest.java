package org.jphototagger.lib.util;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * @author Elmar Baumann
 */
public class ByteUtilTest {
    public ByteUtilTest() {}

    @BeforeAll
    public static void setUpClass() throws Exception {}

    @AfterAll
    public static void tearDownClass() throws Exception {}

    /**
     * Test of toInt method, of class ByteUtil.
     */
    @Test
    public void testToInt() {
        assertEquals(0, ByteUtil.toInt((byte) 0x0));
        assertEquals(8, ByteUtil.toInt((byte) 0x8));
        assertEquals(9, ByteUtil.toInt((byte) 0x9));
        assertEquals(10, ByteUtil.toInt((byte) 0xA));
        assertEquals(15, ByteUtil.toInt((byte) 0xF));
        assertEquals(100, ByteUtil.toInt((byte) 0x64));
        assertEquals(255, ByteUtil.toInt((byte) 0xFF));
    }
}
