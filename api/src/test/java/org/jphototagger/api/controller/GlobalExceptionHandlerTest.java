package org.jphototagger.api.controller;

import jakarta.persistence.EntityNotFoundException;
import org.jphototagger.api.dto.ErrorResponse;
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
        assertThat(response.getBody().error()).isEqualTo("Photo not found");
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
}
