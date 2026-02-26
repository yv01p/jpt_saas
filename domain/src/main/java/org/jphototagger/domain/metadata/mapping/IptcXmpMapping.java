package org.jphototagger.domain.metadata.mapping;

import org.jphototagger.domain.metadata.iptc.IptcField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jphototagger.domain.metadata.MetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpDcCreatorMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpDcDescriptionMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpDcRightsMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpDcSubjectsSubjectMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpDcTitleMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpIptc4XmpCoreDateCreatedMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpIptc4xmpcoreLocationMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopAuthorspositionMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopCaptionwriterMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopCityMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopCountryMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopCreditMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopHeadlineMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopInstructionsMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopSourceMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopStateMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpPhotoshopTransmissionReferenceMetaDataValue;
import org.jphototagger.domain.metadata.xmp.XmpRatingMetaDataValue;

/**
 * Mapping between IPTC Entry Metadata and XMP metadata values.
 *
 * @author Elmar Baumann
 */
public final class IptcXmpMapping {

    private static final Map<IptcField, MetaDataValue> XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META = new HashMap<>();
    private static final Map<MetaDataValue, IptcField> IPTC_ENTRY_META_OF_XMP_META_DATA_VALUE = new HashMap<>();

    static {
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.COPYRIGHT_NOTICE, XmpDcRightsMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.CAPTION_ABSTRACT, XmpDcDescriptionMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.OBJECT_NAME, XmpDcTitleMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.HEADLINE, XmpPhotoshopHeadlineMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.CITY, XmpPhotoshopCityMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.PROVINCE_STATE, XmpPhotoshopStateMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.COUNTRY_PRIMARY_LOCATION_NAME, XmpPhotoshopCountryMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.ORIGINAL_TRANSMISSION_REFERENCE, XmpPhotoshopTransmissionReferenceMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.SPECIAL_INSTRUCTIONS, XmpPhotoshopInstructionsMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.CREDIT, XmpPhotoshopCreditMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.SOURCE, XmpPhotoshopSourceMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.KEYWORDS, XmpDcSubjectsSubjectMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.BYLINE, XmpDcCreatorMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.CONTENT_LOCATION_NAME, XmpIptc4xmpcoreLocationMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.DATE_CREATED, XmpIptc4XmpCoreDateCreatedMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.WRITER_EDITOR, XmpPhotoshopCaptionwriterMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.BYLINE_TITLE, XmpPhotoshopAuthorspositionMetaDataValue.INSTANCE);
        XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.put(IptcField.URGENCY, XmpRatingMetaDataValue.INSTANCE);

        for (IptcField iptcEntryMeta : XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.keySet()) {
            IPTC_ENTRY_META_OF_XMP_META_DATA_VALUE.put(XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.get(iptcEntryMeta), iptcEntryMeta);
        }
    }

    public static MetaDataValue getXmpMetaDataValueOfIptcEntryMeta(IptcField iptcEntryMeta) {
        if (iptcEntryMeta == null) {
            throw new NullPointerException("iptcEntryMeta == null");
        }

        return XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.get(iptcEntryMeta);
    }

    public static IptcField getIptcEntryMetaOfXmpMetaDataValue(MetaDataValue xmpMetaDataValue) {
        if (xmpMetaDataValue == null) {
            throw new NullPointerException("xmpMetaDataValue == null");
        }

        return IPTC_ENTRY_META_OF_XMP_META_DATA_VALUE.get(xmpMetaDataValue);
    }

    public static List<IPTCEntryMetaDataValue> getAllMappings() {
        List<IPTCEntryMetaDataValue> iptcEntryMetaMetaDataValues = new ArrayList<>();
        Set<IptcField> iptcEntryMetas = XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.keySet();

        for (IptcField iptcEntryMeta : iptcEntryMetas) {
            iptcEntryMetaMetaDataValues.add(new IPTCEntryMetaDataValue(iptcEntryMeta, XMP_META_DATA_VALUE_OF_IPTC_ENTRY_META.get(iptcEntryMeta)));
        }

        return iptcEntryMetaMetaDataValues;
    }

    private IptcXmpMapping() {
    }
}
