# Critical Implementation Review v3: Phase 2 — Backend API (Auth, Security, REST Endpoints)

**Reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-2.md` (v3.0)
**Baseline:** `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0)
**Previous reviews:** v1 (`-critical-review-1.md`), v2 (`-critical-review-2.md`)
**Date:** 2026-03-04
**Reviewer:** Senior Staff Engineer (automated)

---

## 1. Overall Assessment

v3.0 successfully addresses all five critical and eight minor issues from the v2 review. The `jpt_auth` BYPASSRLS role with a dedicated `authDataSource` cleanly solves the auth-vs-RLS conflict. Replacing `RlsStatementInspector` with an AOP `@Aspect` avoids the JDBC extended query protocol incompatibility. The new Task 2.0 adds missing Gradle dependencies. `SpaCsrfTokenRequestHandler` correctly handles CSRF for SPAs, and the join table entities with `@IdClass` are now specified.

**However, the v3.0 RLS implementation has two critical correctness bugs that will cause every authenticated request to fail at runtime:**

1. **PostgreSQL's `SET` command does not accept parameterized query placeholders.** The `RlsAspect` uses `em.createNativeQuery("SET LOCAL app.current_user_id = :id").setParameter(...)`, which Hibernate translates to a PreparedStatement with `$1`. PostgreSQL rejects this during the Parse phase: `ERROR: syntax error at or near "$1"`. The fix is to use the `set_config()` SQL function, which is a regular function call and fully supports parameterized queries.

2. **The `RlsAspect` and Spring's `TransactionInterceptor` have the same default `@Order` priority.** When two aspects share the same order, their relative execution sequence is undefined. If the RLS aspect fires *before* the transaction interceptor opens the transaction, `SET LOCAL` executes outside any transaction and behaves like `SET` (session-scoped) — the exact cross-tenant connection pool leak identified in v1 review C3.

Additionally, the V4 Flyway migration uses a literal password string instead of a Flyway placeholder, making the `authDataSource` unable to connect.

---

## 2. Critical Issues

### C1. `SET LOCAL` Does Not Support Parameterized Queries (Task 2.4 — RlsAspect)

**Description:** The `RlsAspect` executes:

```java
em.createNativeQuery("SET LOCAL app.current_user_id = :id")
  .setParameter("id", userId.toString())
  .executeUpdate();
```

Hibernate translates `:id` into a JDBC positional parameter `$1` and creates a PreparedStatement:

```sql
SET LOCAL app.current_user_id = $1
```

PostgreSQL's `SET` is a utility command, not a regular SQL statement. Its grammar requires a **literal value** — it does not accept bind parameters. The PostgreSQL parser rejects this during the `Parse` phase with:

```
ERROR: syntax error at or near "$1"
```

**Impact:** Every authenticated request that triggers a `@Transactional` method fails with a database error. The entire API is non-functional for authenticated users.

**Fix:** Use PostgreSQL's `set_config()` function, which is a regular SQL function call and fully supports parameterized queries:

```java
@Before("@annotation(org.springframework.transaction.annotation.Transactional)")
public void setRlsContext() {
    UUID userId = RlsContext.getCurrentUserId();
    if (userId != null) {
        // set_config(name, value, is_local) — is_local=true is equivalent to SET LOCAL
        em.createNativeQuery("SELECT set_config('app.current_user_id', :id, true)")
          .setParameter("id", userId.toString())
          .getSingleResult();
        em.createNativeQuery("SELECT assert_user_context()")
          .getSingleResult();
    }
}
```

`set_config('app.current_user_id', value, true)` is functionally identical to `SET LOCAL app.current_user_id = value` — it is transaction-scoped and resets on commit/rollback. It is a regular SQL function that can be invoked via PreparedStatement with bind parameters, eliminating the injection risk of string concatenation.

---

### C2. RlsAspect Ordering vs. TransactionInterceptor Is Undefined (Task 2.4 — RlsAspect)

**Description:** The `RlsAspect` uses `@Before("@annotation(Transactional)")` to execute `SET LOCAL` (via `set_config`) before `@Transactional` method bodies. Spring's `TransactionInterceptor` is an `@Around` advice that opens the transaction, delegates to the method, and commits/rolls back.

Both `RlsAspect` (no `@Order` annotation → defaults to `Ordered.LOWEST_PRECEDENCE`) and `TransactionInterceptor` (defaults to `Ordered.LOWEST_PRECEDENCE`) share the same order priority. Per the [Spring AOP documentation](https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/advice-ordering.html):

> *"When two pieces of advice defined in different aspects both need to run at the same join point... The ordering is undefined... Consider collapsing such advice methods into one or refactoring into separate @Order-ed aspects."*

If the `RlsAspect` `@Before` fires **before** `TransactionInterceptor` opens the transaction:
1. `set_config('app.current_user_id', userId, true)` executes outside any transaction.
2. With `is_local = true` but no active transaction, PostgreSQL treats this as session-scoped (same behavior as `SET`).
3. The value persists on the connection after the transaction commits.
4. HikariCP returns the connection to the pool with User A's ID still active.
5. **User B's next request on that connection inherits User A's RLS context — cross-tenant data leak.**

**Impact:** Non-deterministic cross-tenant data access. This may work in development (where ordering happens to be correct) and fail unpredictably in production under load or after a Spring version upgrade.

**Fix:** Explicitly order the aspects. Set `TransactionInterceptor` to a higher precedence (lower order number) so it always wraps first:

```java
// SecurityConfig.java or any @Configuration class
@EnableTransactionManagement(order = 0)  // Transaction opens first (higher precedence)
```

```java
@Aspect
@Component
@Order(1)  // Fires AFTER transaction is open (lower precedence = runs inside the @Around)
public class RlsAspect { ... }
```

With this ordering: `TransactionInterceptor` (order=0) wraps the call, opens the transaction, then `RlsAspect` `@Before` (order=1) fires inside the open transaction. `set_config` with `is_local=true` is now guaranteed to be transaction-scoped.

**Verification:** Add an integration test that explicitly confirms cross-request isolation:

```java
@Test
void rlsContextDoesNotLeakAcrossRequests() {
    // Request 1: authenticate as userA, GET /photos → returns userA's photos
    // Request 2: authenticate as userB, GET /photos → must return only userB's photos
    // If SET LOCAL leaked, request 2 would see userA's photos
}
```

---

### C3. V4 Flyway Migration Uses Literal Password, Not Placeholder (Task 2.4)

**Description:** The V4 migration creates the `jpt_auth` role with:

```sql
CREATE ROLE jpt_auth WITH LOGIN PASSWORD 'SET_VIA_SECRETS' BYPASSRLS;
```

The string `SET_VIA_SECRETS` is a **literal password value**, not a Flyway placeholder. Compare with V2 and V3 migrations which correctly use `'${jpt_app_password}'` and `'${worker_db_user_password}'` respectively — Flyway substitutes these at migration time from `spring.flyway.placeholders.*` in `application.yml`.

The `authDataSource` is configured to connect as `jpt_auth` with password `${JPT_AUTH_PASSWORD}` — but the database role's actual password is the literal string `SET_VIA_SECRETS`. Unless `JPT_AUTH_PASSWORD` is also set to `SET_VIA_SECRETS` (defeating the purpose), authentication will fail with `FATAL: password authentication failed for user "jpt_auth"`.

**Impact:** The `authDataSource` cannot connect. All auth operations (login, registration, email verification, OAuth2) fail at startup or on first use. The test suite also fails unless it separately configures the auth DataSource.

**Fix:**

1. Use Flyway placeholder syntax in V4:

   ```sql
   CREATE ROLE jpt_auth WITH LOGIN PASSWORD '${jpt_auth_password}' BYPASSRLS;
   ```

2. Add the placeholder to `application.yml`:

   ```yaml
   spring:
     flyway:
       placeholders:
         jpt_app_password: ${DB_PASS}
         worker_db_user_password: ${WORKER_DB_PASS}
         jpt_auth_password: ${JPT_AUTH_PASSWORD}  # <-- add this
   ```

3. Add the auth DataSource configuration to `application.yml`:

   ```yaml
   spring:
     auth-datasource:
       url: ${DB_URL:jdbc:postgresql://localhost:5432/jpt}
       username: jpt_auth
       password: ${JPT_AUTH_PASSWORD}
   ```

4. Add the auth DataSource to `application-test.yml` so auth integration tests work with Testcontainers:

   ```yaml
   spring:
     auth-datasource:
       url: jdbc:tc:postgresql:16:///testdb
       username: jpt_auth
       password: test_auth_password
     flyway:
       placeholders:
         jpt_auth_password: test_auth_password
   ```

---

### C4. Missing Testcontainers Redis Module (Task 2.0)

**Description:** Task 2.0 lists all Phase 2 dependencies, but the only test dependency added is `spring-security-test`. The refresh token tests (Task 2.5) require a running Redis instance for `RefreshTokenService`, and the rate limiting tests (Task 2.9) require Redis for Bucket4j. The `application-test.yml` comments `# Testcontainers manages this` for Redis but no Testcontainers Redis dependency exists.

The existing `build.gradle.kts` has `spring-boot-starter-data-redis` for production but no Testcontainers Redis module for tests. Without it, `@ServiceConnection` or manual container configuration cannot provide Redis to the test context.

**Impact:** All tests involving Redis (refresh token CRUD, rate limiting, token revocation) fail to start — no Redis connection available.

**Fix:** Add the Redis Testcontainers module to Task 2.0:

```kotlin
// In Task 2.0, Step 1
testImplementation("com.redis:testcontainers-redis:2.2.4")
// OR the community module:
testImplementation("org.testcontainers:redis:1.20.6")  // if using testcontainers BOM
```

And create a shared test configuration class (or `@TestConfiguration`) that starts a Redis container with `@ServiceConnection`:

```java
@TestConfiguration
public class TestRedisConfig {
    @Bean
    @ServiceConnection
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
    }
}
```

---

## 3. Minor Issues & Improvements

### M1. `application-test.yml` Missing Auth DataSource Config

The test profile configures `spring.datasource` but not `spring.auth-datasource`. Since `AuthDataSourceConfig` creates a `@Bean("authDataSource")` from `spring.auth-datasource` properties, the bean will fail to initialize in tests. All auth integration tests (Tasks 2.5, 2.6) will fail at context startup. See C3 fix above for the full resolution.

### M2. `RlsAspect` EntityManager May Not Be Transaction-Bound

The aspect injects `EntityManager` via `@Autowired`. In Spring, this gives a shared proxy that delegates to the current persistence context. If the `@Before` advice fires and no EntityManager has been bound to the current thread yet (because the transaction just opened but Hibernate hasn't been asked for a connection yet), `em.createNativeQuery(...)` may obtain a separate connection from the pool — different from the one used by subsequent Hibernate queries.

To guarantee the same connection, use `EntityManager`'s `unwrap(Session.class).doWork()`:

```java
em.unwrap(Session.class).doWork(connection -> {
    try (var stmt = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
        stmt.setString(1, userId.toString());
        stmt.execute();
    }
    try (var stmt = connection.prepareStatement("SELECT assert_user_context()")) {
        stmt.execute();
    }
});
```

This explicitly uses the Hibernate Session's JDBC connection, guaranteeing `set_config` and subsequent queries share the same connection and transaction.

### M3. `@Aspect` Pointcut Matches Auth Service Methods Unnecessarily

The `RlsAspect` pointcut `@annotation(Transactional)` matches ALL `@Transactional` methods, including those in `AuthService` and `RefreshTokenService`. For unauthenticated requests, `RlsContext.getCurrentUserId()` returns `null` and the aspect skips the `set_config` call — this is correct. However, the aspect still fires (and performs the null check) on every auth method invocation. Consider narrowing the pointcut to exclude the auth package:

```java
@Before("@annotation(org.springframework.transaction.annotation.Transactional) " +
        "&& !within(org.jphototagger.api.service.AuthService) " +
        "&& !within(org.jphototagger.api.service.RefreshTokenService)")
```

This is a minor performance optimization and reduces cognitive overhead, but is not strictly necessary since the null check already prevents execution.

### M4. `GRANT USAGE ON ALL SEQUENCES` May Not Cover Future Migrations

The V4 migration grants `USAGE ON ALL SEQUENCES IN SCHEMA public` to `jpt_auth`. This covers sequences that exist at migration time. If a future migration (V5+) adds a new sequence, `jpt_auth` won't have access. Add `ALTER DEFAULT PRIVILEGES` for sequences, as done for `jpt_app` in V2:

```sql
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE ON SEQUENCES TO jpt_auth;
```

### M5. Bucket4j Redis Artifact May Need Lettuce-Specific Module

The plan adds `com.bucket4j:bucket4j-redis:8.14.0`. Bucket4j's Redis integration is split by client library: `bucket4j-redis` (Jedis), `bucket4j-lettuce` (Lettuce), or `bucket4j-redisson`. Spring Boot's `spring-boot-starter-data-redis` ships Lettuce by default. Verify that the correct module for Lettuce is used — if it's `bucket4j-lettuce`, update the dependency coordinates.

### M6. No `@Transactional` on Controller-Level Multi-Step Operations

Some controller actions involve multiple database operations that must be atomic — e.g., `DELETE /photos/{id}` (soft delete + quota decrement) and `POST /photos/{id}/restore` (restore + quota increment). The plan says these use `SELECT FOR UPDATE` "in same transaction," but neither the controller nor the service method is explicitly annotated `@Transactional`. Without it, each repository call opens its own transaction, breaking atomicity.

Ensure `PhotoService.softDelete()` and `PhotoService.restore()` are explicitly `@Transactional`. This also ensures the `RlsAspect` fires once and sets the RLS context for the entire operation.

---

## 4. Questions for Clarification

1. **`set_config` return value handling:** `SELECT set_config(...)` returns the value that was set (a `text`). `getSingleResult()` will return this string. Is there a preference for using `getSingleResult()` (which returns the value) vs. `executeUpdate()` (which doesn't work for SELECT)? The `Session.doWork()` approach in M2 avoids this question entirely.

2. **Test isolation for RLS:** With the `RlsAspect` approach, how are repository-level tests (Task 2.2) expected to handle RLS? The test methods use `@Transactional` (for rollback), which means the `RlsAspect` fires. But `RlsContext` is empty (no HTTP request → no `RlsInterceptor`). The tests will either need to manually call `RlsContext.setCurrentUserId(...)` in setup, or use the `authDataSource` path. The plan should specify the test setup pattern for RLS-governed repository tests.

3. **Spring AOP proxy type:** The `RlsAspect` matches `@annotation(Transactional)`. Spring Data JPA repositories are JDK dynamic proxies by default. Does the annotation-based pointcut reliably match the `@Transactional` annotation on `SimpleJpaRepository` methods through the JDK proxy? This should be verified with an integration test — if it doesn't match, the RLS context would never be set for direct repository calls (without a wrapping service method).

---

## 5. Final Recommendation

**Approve with changes.**

v3.0 is architecturally sound — the auth DataSource, AOP-based RLS, and CSRF handling are the correct approaches. The remaining issues are implementation-level bugs (not design flaws) that can be fixed without restructuring:

| # | Issue | Priority | Effort |
|---|---|---|---|
| C1 | `SET LOCAL` doesn't support parameters → use `set_config()` | **Must fix** | Low (one-line change) |
| C2 | RlsAspect vs TransactionInterceptor ordering undefined → add `@Order` | **Must fix** | Low (two annotations) |
| C3 | V4 migration literal password → use Flyway placeholder | **Must fix** | Low (placeholder + config) |
| C4 | Missing Testcontainers Redis module → add dependency | **Must fix** | Low (one dependency line) |
| M1 | Test profile missing auth DataSource config | **Should fix** | Low |
| M2 | EntityManager connection binding → use `Session.doWork()` | **Should fix** | Medium |
| M6 | Missing `@Transactional` on multi-step service methods | **Should fix** | Low |

C1 and C2 are correctness bugs that cause runtime failures. C3 prevents auth from connecting. C4 prevents tests from running. All four are straightforward fixes. Once addressed, the plan is ready for implementation.
