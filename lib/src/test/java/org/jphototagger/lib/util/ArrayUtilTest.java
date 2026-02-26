package org.jphototagger.lib.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the Class {@code org.jphototagger.lib.util.ArrayUtil}.
 *
 * @author Elmar Baumann
 */
public class ArrayUtilTest {

    public ArrayUtilTest() {
    }

    @BeforeAll
    public static void setUpClass() throws Exception {
    }

    @AfterAll
    public static void tearDownClass() throws Exception {
    }

    @BeforeEach
    public void setUp() {
    }

    @AfterEach
    public void tearDown() {
    }

    /**
     * Test of toStringArray method, of class ArrayUtil.
     */
    @Test
    public void testToStringArray() {
        Object[] array = new Object[]{new Integer(12), "Eiscreme", new Double(25.5)};
        String[] expResult = new String[]{"12", "Eiscreme", "25.5"};
        String[] result = ArrayUtil.toStringArray(array);

        assertArrayEquals(expResult, result);

        URI uri = null;

        try {
            uri = new URI("http://www.elmar-baumann.de");
            array = new Object[]{uri};
            expResult = new String[]{uri.toString()};
            result = ArrayUtil.toStringArray(array);
            assertArrayEquals(expResult, result);
        } catch (URISyntaxException ex) {
            Logger.getLogger(ArrayUtilTest.class.getName()).log(Level.SEVERE,
                    null, ex);
        }

        array = new Object[]{};
        expResult = new String[]{};
        result = ArrayUtil.toStringArray(array);
        assertArrayEquals(expResult, result);
        array = new Object[]{"a", null, "b"};

        try {
            ArrayUtil.toStringArray(array);
            fail("IllegalArgumentException was not thrown");
        } catch (IllegalArgumentException ex) {
            // ok
        }

        try {
            ArrayUtil.toStringArray(null);
            fail("NullpointerException was not thrown");
        } catch (NullPointerException ex) {
            // ok
        }
    }
}
