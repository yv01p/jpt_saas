package org.jphototagger.worker.exception;

/**
 * Thrown when a CLI tool (libraw, libvips, exiftool) exceeds its allotted timeout.
 */
public class ProcessTimeoutException extends ProcessingException {

    public ProcessTimeoutException(String message) {
        super(message);
    }

    public ProcessTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
