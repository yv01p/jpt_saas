package org.jphototagger.domain.metadata.iptc;

/**
 * IPTC IIM Record 2 dataset fields used by JPhotoTagger.
 * Drop-in replacement for {@code com.imagero.reader.iptc.IPTCEntryMeta}.
 */
public enum IptcField {

    OBJECT_NAME(5),
    URGENCY(10),
    KEYWORDS(25),
    CONTENT_LOCATION_CODE(26),
    CONTENT_LOCATION_NAME(27),
    SPECIAL_INSTRUCTIONS(40),
    DATE_CREATED(55),
    BYLINE(80),
    BYLINE_TITLE(85),
    CITY(90),
    PROVINCE_STATE(95),
    COUNTRY_PRIMARY_LOCATION_NAME(101),
    ORIGINAL_TRANSMISSION_REFERENCE(103),
    HEADLINE(105),
    CREDIT(110),
    SOURCE(115),
    COPYRIGHT_NOTICE(116),
    CAPTION_ABSTRACT(120),
    WRITER_EDITOR(122);

    private final int datasetNumber;

    IptcField(int datasetNumber) {
        this.datasetNumber = datasetNumber;
    }

    public int getDatasetNumber() {
        return datasetNumber;
    }

    /**
     * Returns the IptcField for the given IPTC IIM dataset number.
     *
     * @param datasetNumber the IPTC IIM Record 2 dataset number
     * @return the matching IptcField
     * @throws IllegalArgumentException if no constant matches
     */
    public static IptcField fromDatasetNumber(int datasetNumber) {
        for (IptcField field : values()) {
            if (field.datasetNumber == datasetNumber) {
                return field;
            }
        }
        throw new IllegalArgumentException("Unknown IPTC dataset number: " + datasetNumber);
    }
}
