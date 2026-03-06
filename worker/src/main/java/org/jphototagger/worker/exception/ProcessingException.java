package org.jphototagger.worker.exception;

/**
 * Unchecked exception thrown when image processing fails.
 * The future PhotoJobConsumer will catch this to mark the job as FAILED.
 */
public class ProcessingException extends RuntimeException {

    public ProcessingException(String message) {
        super(message);
    }

    public ProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
