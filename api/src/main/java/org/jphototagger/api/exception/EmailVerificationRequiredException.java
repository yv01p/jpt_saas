package org.jphototagger.api.exception;

public class EmailVerificationRequiredException extends RuntimeException {
    public EmailVerificationRequiredException(String message) {
        super(message);
    }
}
