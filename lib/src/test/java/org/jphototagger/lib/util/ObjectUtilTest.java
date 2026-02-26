package org.jphototagger.lib.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author Elmar Baumann
 */
public class ObjectUtilTest {

    @Test
    public void testFirstNonNull() {
        String s1 = "1";
        String s2 = null;
        String s3 = "3";
        String actual = ObjectUtil.firstNonNull(s1, s2, s3);

        Assertions.assertEquals(s1, actual);

        s1 = null;
        s2 = "2";
        s3 = "3";
        actual = ObjectUtil.firstNonNull(s1, s2, s3);

        Assertions.assertEquals(s2, actual);

        s1 = null;
        s2 = null;
        s3 = "3";
        actual = ObjectUtil.firstNonNull(s1, s2, s3);

        Assertions.assertEquals(s3, actual);

        s1 = null;
        s2 = null;
        s3 = null;
        actual = ObjectUtil.firstNonNull(s1, s2, s3);

        Assertions.assertNull(actual);
    }
}
