package org.jphototagger.iptc;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jphototagger.api.preferences.Preferences;
import org.jphototagger.domain.metadata.iptc.IptcField;
import org.jphototagger.lib.util.StringUtil;
import org.openide.util.Lookup;

/**
 * IPTC entry in an image file. Decodes data (getData()) as a string using
 * the charset configured in Preferences (default ISO-8859-1).
 *
 * @author Elmar Baumann
 */
public final class IptcEntry {

    private final String name;
    private final byte[] data;
    private final int recordNumber;
    private final int datasetNumber;
    private final IptcField entryMeta;

    /**
     * Creates a new IPTC entry.
     *
     * @param name          human-readable field name
     * @param data          raw data bytes
     * @param recordNumber  IPTC record number
     * @param datasetNumber IPTC dataset number
     * @param entryMeta     the IPTC field enum constant
     */
    public IptcEntry(String name, byte[] data, int recordNumber, int datasetNumber, IptcField entryMeta) {
        if (name == null) {
            throw new NullPointerException("name == null");
        }
        if (data == null) {
            throw new NullPointerException("data == null");
        }
        if (entryMeta == null) {
            throw new NullPointerException("entryMeta == null");
        }

        this.name = name;
        this.data = Arrays.copyOf(data, data.length);
        this.recordNumber = recordNumber;
        this.datasetNumber = datasetNumber;
        this.entryMeta = entryMeta;
    }

    /**
     * Returns the name of the IPTC property.
     *
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the record number of the IPTC property.
     *
     * @return record number
     */
    public int getRecordNumber() {
        return recordNumber;
    }

    /**
     * Returns the data of the IPTC property decoded as a string.
     *
     * @return data
     */
    public String getData() {
        return getEncodedData();
    }

    /**
     * Returns the dataset number of the IPTC property.
     *
     * @return dataset number
     */
    public int getDataSetNumber() {
        return datasetNumber;
    }

    public IptcField getEntryMeta() {
        return entryMeta;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof IptcEntry) {
            IptcEntry otherEntry = (IptcEntry) o;

            return (recordNumber == otherEntry.recordNumber) && (datasetNumber == otherEntry.datasetNumber)
                    && getData().equals(otherEntry.getData());
        }

        return false;
    }

    @Override
    public int hashCode() {
        int hash = 5;

        hash = 83 * hash + this.recordNumber;
        hash = 83 * hash + this.datasetNumber;

        return hash;
    }

    private String getEncodedData() {
        try {
            Preferences prefs = Lookup.getDefault().lookup(Preferences.class);
            String iptcCharset = prefs.getString(IptcPreferencesKeys.KEY_IPTC_CHARSET);
            if (!StringUtil.hasContent(iptcCharset)) {
                iptcCharset = "ISO-8859-1";
            }

            String encodedData = new String(data, iptcCharset);

            return encodedData.trim();
        } catch (Throwable t) {
            Logger.getLogger(IptcEntry.class.getName()).log(Level.SEVERE, null, t);
        }

        return "";
    }
}
