package org.jphototagger.api.controller;

import jakarta.persistence.EntityNotFoundException;
import org.jphototagger.api.dto.ErrorResponse;
import org.jphototagger.api.exception.UnsupportedMediaTypeException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleNotFoundReturns404() {
        var response = handler.handleNotFound(new EntityNotFoundException("Photo not found"));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("Not Found");
        assertThat(response.getBody().status()).isEqualTo(404);
    }

    @Test
    void handleDataIntegrityReturns409() {
        var response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("duplicate key"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("Resource already exists");
        assertThat(response.getBody().status()).isEqualTo(409);
    }

    @Test
    void handleIllegalArgumentReturns400WithGenericMessage() {
        var response = handler.handleIllegalArgument(
                new IllegalArgumentException("Invalid UUID string: attacker-controlled-value"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("Invalid request parameters");
        assertThat(response.getBody().status()).isEqualTo(400);
    }

    @Test
    void handleIllegalStateReturns409WithGenericMessage() {
        var response = handler.handleIllegalState(
                new IllegalStateException("Internal state detail that must not leak"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("Request cannot be completed");
        assertThat(response.getBody().status()).isEqualTo(409);
    }

    @Test
    void handleUnsupportedMediaTypeReturns415WithGenericMessage() {
        // The exception message contains internal MIME detection details (e.g. "Unsupported media type: application/pdf")
        // The handler must NOT leak this to the client — use a static, user-helpful message instead
        var response = handler.handleUnsupportedMediaType(
                new UnsupportedMediaTypeException("Unsupported media type: application/pdf"));
        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).doesNotContain("application/pdf");
        assertThat(response.getBody().error()).contains("Accepted:");
        assertThat(response.getBody().status()).isEqualTo(415);
    }

    @Test
    void handleUnexpectedReturns500WithGenericMessage() {
        var response = handler.handleUnexpected(new RuntimeException("internal db path or secret"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("An internal error occurred");
        assertThat(response.getBody().status()).isEqualTo(500);
    }
}
