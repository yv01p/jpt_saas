package org.jphototagger.domain.metadata.mapping;

import org.jphototagger.domain.metadata.iptc.IptcField;
import java.util.HashMap;
import java.util.Map;

/**
 * Mapping zwischen
 * {@code com.imagero.reader.iptc.IptcField}
 * und dem Start eines
 * {@code com.adobe.xmp.properties.XMPPropertyInfo#getPath()}.
 *
 * Das Adobe-SDK fügt bei mehrfach vorkommenden Properties einen Index in
 * eckigen Klammern an, weshalb es keine vollständige Abdeckung geben kann.
 *
 * @author Elmar Baumann
 */
public final class IptcEntryXmpPathStartMapping {

    private static final Map<IptcField, String> XMP_PATH_START_OF_IPTC_ENTRY_META = new HashMap<>();

    static {
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.BYLINE, "dc:creator");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.CAPTION_ABSTRACT, "dc:description");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.COPYRIGHT_NOTICE, "dc:rights");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.KEYWORDS, "dc:subject");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.OBJECT_NAME, "dc:title");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.CONTENT_LOCATION_CODE, "Iptc4xmpCore:CountryCode");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.CONTENT_LOCATION_NAME, "Iptc4xmpCore:Location");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.DATE_CREATED, "Iptc4xmpCore:DateCreated");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.BYLINE_TITLE, "photoshop:AuthorsPosition");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.WRITER_EDITOR, "photoshop:CaptionWriter");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.CITY, "photoshop:City");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.COUNTRY_PRIMARY_LOCATION_NAME, "photoshop:Country");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.CREDIT, "photoshop:Credit");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.HEADLINE, "photoshop:Headline");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.SPECIAL_INSTRUCTIONS, "photoshop:Instructions");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.SOURCE, "photoshop:Source");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.PROVINCE_STATE, "photoshop:State");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.ORIGINAL_TRANSMISSION_REFERENCE, "photoshop:TransmissionReference");
        XMP_PATH_START_OF_IPTC_ENTRY_META.put(IptcField.URGENCY, "xap:Rating");
    }

    /**
     * Liefert den Start des XMP-Pfads für IPTC-Entry-Metadaten.
     *
     * @param  entryMeta  IPTC-Entry-Metadaten
     * @return Pfadstart oder null bei unzugeordneten Metadaten
     */
    public static String getXmpPathStartOfIptcEntryMeta(IptcField entryMeta) {
        if (entryMeta == null) {
            throw new NullPointerException("entryMeta == null");
        }

        return XMP_PATH_START_OF_IPTC_ENTRY_META.get(entryMeta);
    }

    private IptcEntryXmpPathStartMapping() {
    }
}
