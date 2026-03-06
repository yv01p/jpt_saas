package org.jphototagger.worker.pipeline;

import org.apache.tika.Tika;
import org.jphototagger.worker.exception.ProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Validates that a file is an image using Apache Tika content detection.
 * Rejects non-image MIME types before any further processing.
 */
@Component
public class TikaValidator {

    private static final Logger log = LoggerFactory.getLogger(TikaValidator.class);

    private final Tika tika = new Tika();

    /**
     * Detects the MIME type of the given file and throws {@link ProcessingException}
     * if it is not an image type.
     *
     * @param file the file to validate
     * @throws ProcessingException if the MIME type does not start with {@code image/}
     */
    public void validate(Path file) {
        String mimeType;
        try {
            mimeType = tika.detect(file.toFile());
        } catch (IOException e) {
            throw new ProcessingException("Failed to detect MIME type for file: " + file, e);
        }
        if (!mimeType.startsWith("image/")) {
            throw new ProcessingException(
                    "Rejected non-image content type: " + mimeType + " for file: " + file);
        }
        log.debug("TikaValidator accepted MIME type: {} for file: {}", mimeType, file);
    }

    /**
     * Validates a MIME type string directly (without reading a file).
     *
     * @param mimeType the MIME type to validate
     * @throws ProcessingException if the MIME type does not start with {@code image/}
     */
    public void validateMimeType(String mimeType) {
        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new ProcessingException("Rejected non-image content type: " + mimeType);
        }
    }
}
