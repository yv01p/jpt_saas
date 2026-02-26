package org.jphototagger.iptc;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.iptc.IptcDirectory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jphototagger.domain.metadata.iptc.Iptc;
import org.jphototagger.domain.metadata.iptc.IptcField;

/**
 * IPTC metadata of an image file.
 *
 * @author Elmar Baumann, Tobias Stening
 */
public final class IptcMetadata {

    private static final Logger LOGGER = Logger.getLogger(IptcMetadata.class.getName());

    /**
     * Returns {@code IptcEntry} instances of an image file.
     *
     * @param  imageFile image file or null
     * @return           Metadata or empty list if the image has no IPTC
     *                   metadata or when errors occur
     */
    public static List<IptcEntry> getIptcEntries(File imageFile) {
        List<IptcEntry> metadata = new ArrayList<>();

        if ((imageFile != null) && imageFile.exists() && IptcSupport.INSTANCE.canReadIptc(imageFile)) {
            try {
                LOGGER.log(Level.INFO, "Reading IPTC from image file ''{0}'', size {1} Bytes",
                        new Object[]{imageFile, imageFile.length()});

                Metadata drewMetadata = ImageMetadataReader.readMetadata(imageFile);

                for (IptcDirectory directory : drewMetadata.getDirectoriesOfType(IptcDirectory.class)) {
                    for (Tag tag : directory.getTags()) {
                        int tagType = tag.getTagType();

                        // Skip version info (dataset 0 in record 2)
                        if (tagType == 0) {
                            continue;
                        }

                        IptcField field;
                        try {
                            field = IptcField.fromDatasetNumber(tagType);
                        } catch (IllegalArgumentException ex) {
                            LOGGER.log(Level.FINE, "Skipping unknown IPTC dataset number: {0}", tagType);
                            continue;
                        }

                        byte[] rawData = directory.getByteArray(tagType);
                        if (rawData == null) {
                            continue;
                        }

                        IptcEntry entry = new IptcEntry(tag.getTagName(), rawData, 2, tagType, field);

                        if (hasContent(entry) && !metadata.contains(entry)) {
                            metadata.add(entry);
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, null, t);
            }
        }

        return metadata;
    }

    private static boolean hasContent(IptcEntry entry) {
        return (entry.getData() != null) && !entry.getData().trim().isEmpty();
    }

    /**
     * Filters IPTC entries.
     *
     * @param  entries IPTC entries
     * @param  filter  filter
     * @return         filtered entries
     */
    public static List<IptcEntry> getFilteredEntries(List<IptcEntry> entries, IptcField filter) {
        if (entries == null) {
            throw new NullPointerException("entries == null");
        }

        if (filter == null) {
            throw new NullPointerException("filter == null");
        }

        List<IptcEntry> filteredEntries = new ArrayList<>();

        for (IptcEntry entry : entries) {
            if (entry.getEntryMeta().equals(filter)) {
                filteredEntries.add(entry);
            }
        }

        return filteredEntries;
    }

    /**
     * Returns a {@code Iptc} instance of an image file.
     *
     * @param  imageFile image file or null
     * @return           IPTC of that image file or null if the image has no
     *                   IPTC metadata or when errors occur
     */
    public static Iptc getIptc(File imageFile) {
        Iptc iptc = null;
        List<IptcEntry> iptcEntries = getIptcEntries(imageFile);

        if (iptcEntries.size() > 0) {
            iptc = new Iptc();

            for (IptcEntry iptcEntry : iptcEntries) {
                IptcField iptcField = iptcEntry.getEntryMeta();

                iptc.setValue(iptcField, iptcEntry.getData());
            }
        }

        return iptc;
    }

    private IptcMetadata() {
    }
}
