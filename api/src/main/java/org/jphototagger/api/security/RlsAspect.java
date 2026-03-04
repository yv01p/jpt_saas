package org.jphototagger.api.security;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AOP aspect that sets the PostgreSQL session variable {@code app.current_user_id}
 * within the current transaction boundary using {@code set_config()}.
 *
 * <p>Order(1) ensures this fires AFTER TransactionInterceptor (order=0) opens the transaction,
 * guaranteeing the set_config executes on the same JDBC connection.</p>
 */
@Aspect
@Component
@Order(1)
public class RlsAspect {

    private final EntityManager entityManager;

    public RlsAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Sets RLS context before any @Transactional method, excluding auth services
     * that use jpt_auth (BYPASSRLS) role.
     */
    @Before("@annotation(org.springframework.transaction.annotation.Transactional) " +
            "&& !within(org.jphototagger.api.service.AuthService) " +
            "&& !within(org.jphototagger.api.service.RefreshTokenService)")
    public void setRlsContext() {
        UUID userId = RlsContext.getCurrentUserId();
        if (userId != null) {
            entityManager.unwrap(Session.class).doWork(connection -> {
                try (var stmt = connection.prepareStatement(
                        "SELECT set_config('app.current_user_id', ?, true)")) {
                    stmt.setString(1, userId.toString());
                    stmt.execute();
                }
                try (var stmt = connection.prepareStatement("SELECT assert_user_context()")) {
                    stmt.execute();
                }
            });
        }
    }
}
