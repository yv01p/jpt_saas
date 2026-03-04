package org.jphototagger.api.security;

import java.util.UUID;

/**
 * ThreadLocal holder for the current authenticated user's ID.
 * Used by RlsAspect to set the PostgreSQL session variable for Row-Level Security.
 */
public final class RlsContext {

    private static final ThreadLocal<UUID> CURRENT_USER_ID = new ThreadLocal<>();

    private RlsContext() {}

    public static UUID getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }

    public static void setCurrentUserId(UUID userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
