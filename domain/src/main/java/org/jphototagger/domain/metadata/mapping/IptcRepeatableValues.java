package org.jphototagger.domain.metadata.mapping;

import org.jphototagger.domain.metadata.iptc.IptcField;
import java.util.HashMap;
import java.util.Map;

/**
 * Returns whether an {@code com.imagero.reader.iptc.IptcField} contains
 * repeatable values.
 *
 * @author Elmar Baumann
 */
public final class IptcRepeatableValues {

    private static final Map<IptcField, Boolean> IS_REPEATABLE = new HashMap<>();

    static {
        IS_REPEATABLE.put(IptcField.BYLINE_TITLE, true);
        IS_REPEATABLE.put(IptcField.BYLINE, true);
        IS_REPEATABLE.put(IptcField.CAPTION_ABSTRACT, false);
        IS_REPEATABLE.put(IptcField.CITY, false);
        IS_REPEATABLE.put(IptcField.CONTENT_LOCATION_CODE, true);
        IS_REPEATABLE.put(IptcField.CONTENT_LOCATION_NAME, true);
        IS_REPEATABLE.put(IptcField.COPYRIGHT_NOTICE, false);
        IS_REPEATABLE.put(IptcField.COUNTRY_PRIMARY_LOCATION_NAME, false);
        IS_REPEATABLE.put(IptcField.CREDIT, false);
        IS_REPEATABLE.put(IptcField.HEADLINE, false);
        IS_REPEATABLE.put(IptcField.KEYWORDS, true);
        IS_REPEATABLE.put(IptcField.OBJECT_NAME, false);
        IS_REPEATABLE.put(IptcField.ORIGINAL_TRANSMISSION_REFERENCE, false);
        IS_REPEATABLE.put(IptcField.PROVINCE_STATE, false);
        IS_REPEATABLE.put(IptcField.SOURCE, false);
        IS_REPEATABLE.put(IptcField.SPECIAL_INSTRUCTIONS, false);
        IS_REPEATABLE.put(IptcField.WRITER_EDITOR, true);
    }

    /**
     * Returns whether an {@code com.imagero.reader.iptc.IptcField} contains
     * repeatable values.
     *
     * @param  meta metadata
     * @return true if repeatable
     * @throws IllegalArgumentException if metadata is unknown
     */
    public static boolean isRepeatable(IptcField meta) {
        if (meta == null) {
            throw new NullPointerException("meta == null");
        }

        Boolean repeatable = IS_REPEATABLE.get(meta);

        return (repeatable == null)
                ? false
                : repeatable;
    }

    private IptcRepeatableValues() {
    }
}
