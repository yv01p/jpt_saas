package org.jphototagger.exif.formatter.nikon;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.makernotes.NikonType2MakernoteDirectory;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jphototagger.exif.ExifIfd;
import org.jphototagger.exif.ExifMakerNotes;
import org.jphototagger.exif.ExifTag;
import org.jphototagger.exif.ExifTags;

/**
 * @author Elmar Baumann
 */
public final class NikonMakerNotes implements ExifMakerNotes {

    private static final String PROPERTY_FILE_PREFIX = "org/jphototagger/exif/formatter/nikon/NikonExifMakerNote_";
    private static final Collection<NikonMakerNote> MAKER_NOTES = new ArrayList<>();

    static {
        int index = 0;
        final int maxIndex = 100;

        while (index <= maxIndex) {
            try {

                // Better than catching an exception?
                // java.util.jar.JarFile jarFile = new java.util.jar.JarFile(".../JPhotoTagger.jar");
                // Enumeration entries = jarFile.entries();
                // while (entries.hasMoreElements()) {
                // java.util.zip.ZipEntry entry = (java.util.zip.ZipEntry) entries.nextElement();
                // if (entry.getName().startsWith(PROPERTY_FILE_PREFIX) ...
                // }
                ResourceBundle bundle = ResourceBundle.getBundle(PROPERTY_FILE_PREFIX + Integer.toString(index++));

                MAKER_NOTES.add(new NikonMakerNote(bundle));
            } catch (Throwable t) {
                index = maxIndex + 1;
            }
        }
    }

    private static NikonMakerNote get(ExifTags exifTags, byte[] rawValue) {
        for (NikonMakerNote makerNote : MAKER_NOTES) {
            if (makerNote.matches(exifTags, rawValue)) {
                return makerNote;
            }
        }

        return null;
    }

    @Override
    public void add(File file, ExifTags exifTags, ExifTag makerNoteTag) {
        if (file == null) {
            throw new NullPointerException("file == null");
        }

        if (exifTags == null) {
            throw new NullPointerException("exifTags == null");
        }

        if (makerNoteTag == null) {
            throw new NullPointerException("makerNoteTag == null");
        }

        add(file, makerNoteTag, exifTags);
    }

    private void add(File file, ExifTag exifMakerNote, ExifTags exifTags) {
        assert exifMakerNote.parseProperties().equals(ExifTag.Properties.MAKER_NOTE);

        NikonMakerNote nikonMakerNote = NikonMakerNotes.get(exifTags, exifMakerNote.getRawValue());

        if (nikonMakerNote == null) {
            return;
        }

        List<ExifTag> allMakerNoteTags = new ArrayList<>();

        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);
            NikonType2MakernoteDirectory directory = metadata.getFirstDirectoryOfType(NikonType2MakernoteDirectory.class);

            if (directory != null) {
                for (Tag tag : directory.getTags()) {
                    int tagType = tag.getTagType();
                    byte[] rawValue = directory.getByteArray(tagType);
                    String stringValue = tag.getDescription();
                    String name = tag.getTagName();

                    ExifTag exifTag = new ExifTag(
                            tagType,
                            7, // UNDEFINED
                            1,
                            -1L,
                            rawValue,
                            stringValue,
                            18761, // little endian
                            name,
                            ExifIfd.MAKER_NOTE);

                    allMakerNoteTags.add(exifTag);
                }
            }

            exifTags.addMakerNoteTags(nikonMakerNote.getDisplayableMakerNotesOf(allMakerNoteTags));
            exifTags.setMakerNoteDescription(nikonMakerNote.getDescription());
            mergeMakerNoteTags(exifTags, nikonMakerNote.getTagIdsEqualInExifIfd());
        } catch (Throwable t) {
            Logger.getLogger(NikonMakerNotes.class.getName()).log(Level.SEVERE, null, t);
        }
    }

    private static void mergeMakerNoteTags(ExifTags exifTags, List<NikonMakerNoteTagIdExifTagId> equalTagIds) {
        for (NikonMakerNoteTagIdExifTagId nikonMakerNoteTagIdExifTagId : equalTagIds) {
            ExifTag makerNoteTag = exifTags.findmakerNoteTagByTagId(nikonMakerNoteTagIdExifTagId.getNikonMakerNoteTagId());

            if (makerNoteTag != null) {
                ExifTag exifTag = exifTags.findExifTagByTagId(nikonMakerNoteTagIdExifTagId.getExifTagId());

                exifTags.removeFromMakerNoteTags(makerNoteTag);

                // prefering existing tag
                if (exifTag == null) {
                    exifTags.addExifTag(new ExifTag(nikonMakerNoteTagIdExifTagId.getExifTagId(), makerNoteTag.getIntValueType(),
                            makerNoteTag.getValueCount(), makerNoteTag.getValueOffset(),
                            makerNoteTag.getRawValue(), makerNoteTag.getStringValue(),
                            makerNoteTag.getByteOrderId(), makerNoteTag.getName(), ExifIfd.EXIF));
                }
            }
        }
    }
}
