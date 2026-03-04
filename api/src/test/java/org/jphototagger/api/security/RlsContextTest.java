package org.jphototagger.api.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RlsContext ThreadLocal holder.
 */
class RlsContextTest {

    @AfterEach
    void cleanup() {
        RlsContext.clear();
    }

    @Test
    void setAndGetCurrentUserId() {
        UUID userId = UUID.randomUUID();
        RlsContext.setCurrentUserId(userId);
        assertThat(RlsContext.getCurrentUserId()).isEqualTo(userId);
    }

    @Test
    void clearRemovesUserId() {
        RlsContext.setCurrentUserId(UUID.randomUUID());
        RlsContext.clear();
        assertThat(RlsContext.getCurrentUserId()).isNull();
    }

    @Test
    void defaultValueIsNull() {
        assertThat(RlsContext.getCurrentUserId()).isNull();
    }
}
