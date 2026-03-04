# Critical Implementation Review v2: Phase 2 — Backend API (Auth, Security, REST Endpoints)

**Reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-2.md` (v2.0)
**Baseline:** `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0)
**Previous review:** `docs/plans/2026-02-25-saas-conversion-phase-2-critical-review-1.md`
**Date:** 2026-03-04
**Reviewer:** Senior Staff Engineer (automated)

---

## 1. Overall Assessment

v2.0 thoroughly addresses all eight critical and ten minor issues from the v1 review. The FTS query now references the stored GIN-indexed column, all list endpoints return `Page<T>`, the RLS mechanism was redesigned as a `HandlerInterceptor` + `StatementInspector`, CSRF is exempted on pre-auth endpoints, and the refresh token flow is fully specified. The test stubs now include setup/assertion comments, and missing repositories, services, and validation annotations have been added.

**However, three new critical issues emerge from closer analysis of the v2.0 revisions and cross-referencing against the Flyway migrations and existing configuration:**

1. **RLS on the `users` and `email_tokens` tables blocks all authentication operations** — login, registration, and email verification cannot function because the RLS policy filters by `app.current_user_id`, which is the nil UUID for unauthenticated requests.
2. **The `StatementInspector` prepend approach is incompatible with PostgreSQL's JDBC extended query protocol** — multi-statement SQL strings cannot be used with `PreparedStatement`, which is what Hibernate uses.
3. **Phase 2 has no Gradle dependency management step** — `spring-boot-starter-security`, jjwt, OAuth2, and Bucket4j are all absent from `api/build.gradle.kts`.

---

## 2. Critical Issues

### C1. RLS Blocks Authentication Operations (Tasks 2.4, 2.5, 2.6)

**Description:** The V2 Flyway migration enables RLS with `FORCE ROW LEVEL SECURITY` on the `users` table. The policy is:

```sql
CREATE POLICY tenant_users ON users
    USING (id = current_setting('app.current_user_id')::uuid);
```

The `connection-init-sql` in `application.yml` sets this to the nil UUID (`00000000-...`). The migration itself warns:

> *"NOTE: users and email_tokens RLS means login, registration, and email verification flows (Phase 2) must use a privileged role that bypasses RLS."*

The plan ignores this note entirely. All database access uses the `jpt_app` role (from `application.yml`), which is subject to RLS. This means:

- **Login:** `UserRepository.findByEmail("user@example.com")` returns `Optional.empty()` because the RLS policy filters users where `id = nil-UUID` — no rows match. Login always fails with "Invalid credentials."
- **Registration:** `em.persist(newUser)` fails the RLS `WITH CHECK` clause because the new user's `id` (a fresh UUID) does not equal the nil UUID.
- **Email verification:** `EmailTokenRepository.findByTokenHash(...)` returns empty for the same reason.
- **OAuth2 login:** Same — user lookup by `oauth_provider`/`oauth_id` returns empty.

**Impact:** Authentication is completely non-functional. This is a showstopper — no user can register or log in.

**Fix:** Auth operations require a code path that bypasses RLS. Two approaches:

1. **Dedicated privileged DataSource (recommended):** Create a secondary `DataSource` bean connected as the `jpt` superuser role (or a new dedicated `jpt_auth` role that has `BYPASSRLS`). `AuthService`, `RefreshTokenService`, and `OAuth2SuccessHandler` inject this DataSource (via a separate `JdbcTemplate` or `EntityManager`) for user lookups, creation, and token validation. All other services use the standard RLS-governed `jpt_app` DataSource.

   ```java
   @Bean("authJdbcTemplate")
   public JdbcTemplate authJdbcTemplate(@Qualifier("authDataSource") DataSource ds) {
       return new JdbcTemplate(ds);
   }
   ```

2. **RLS policy exemption for SELECT on users by email:** Add a permissive SELECT policy that allows lookups by email without requiring `app.current_user_id`. This is less secure — it means any query can read any user's row — and does not solve the INSERT problem for registration.

   Option 1 is strongly preferred. It keeps RLS enforcement strict for all tenant-scoped data while providing a clean bypass for authentication.

   **Important:** The `assert_user_context()` function (defined in V2 migration) should be called by the `RlsStatementInspector` after executing `SET LOCAL`, as specified by the migration comment. The plan does not call it.

---

### C2. StatementInspector Prepend Fails with JDBC Extended Query Protocol (Task 2.4)

**Description:** The plan's `RlsStatementInspector` prepends `SET LOCAL app.current_user_id = '{userId}';` to the first SQL statement in each transaction:

```java
return "SET LOCAL app.current_user_id = '" + userId + "'; " + originalSql;
```

PostgreSQL's JDBC driver uses the **extended query protocol** by default. In this protocol, each `Parse` message accepts exactly **one SQL statement**. A semicolon-separated multi-statement string causes PostgreSQL to return:

```
ERROR: cannot insert multiple commands into a prepared statement
```

This only works with `preferQueryMode=simple` in the JDBC URL, which disables prepared statement caching and parameterized query planning — a significant performance regression and a re-opened SQL injection surface for any string interpolation in native queries.

**Impact:** Every authenticated request fails with a PostgreSQL protocol error on the first database query.

**Fix:** Execute `SET LOCAL` as a **separate JDBC statement** before the first Hibernate query, not by prepending it to the query string. Correct approaches:

1. **`@Aspect` on `@Transactional` methods (cleanest):**

   ```java
   @Aspect
   @Component
   public class RlsAspect {
       @Autowired private EntityManager em;

       @Before("@annotation(org.springframework.transaction.annotation.Transactional)")
       public void setRlsContext() {
           UUID userId = RlsContext.getCurrentUserId();
           if (userId != null) {
               em.createNativeQuery("SET LOCAL app.current_user_id = :id")
                 .setParameter("id", userId.toString())
                 .executeUpdate();
           }
       }
   }
   ```

2. **Hibernate `EventListener` (alternative):** Register a `PreLoadEventListener` or use `Session.doWork()` at the beginning of each transactional service method.

3. **Custom `PlatformTransactionManager` wrapper:** Decorate the `JpaTransactionManager` to execute `SET LOCAL` immediately after `doBegin()`.

   Option 1 or 3 are preferred. The `StatementInspector` should be removed from this design — it is architecturally wrong for the task of issuing a separate setup command.

---

### C3. Missing Gradle Dependencies (All Tasks)

**Description:** `api/build.gradle.kts` currently includes:
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-data-redis`
- `flywaydb`
- `postgresql`
- `minio`
- `spring-boot-starter-test` + Testcontainers

The plan introduces Spring Security (Task 2.4), JWT (Task 2.3), OAuth2 (Task 2.6), and Bucket4j (Task 2.9) without a step to add their dependencies:

| Missing Dependency | Required By |
|---|---|
| `spring-boot-starter-security` | Task 2.4 (SecurityConfig, CSRF, filters) |
| `spring-boot-starter-oauth2-client` | Task 2.6 (OAuth2 Google/GitHub) |
| `io.jsonwebtoken:jjwt-api` + `jjwt-impl` + `jjwt-jackson` | Task 2.3 (JwtService) |
| `com.bucket4j:bucket4j-redis` (or `bucket4j-spring-boot-starter`) | Task 2.9 (rate limiting) |
| `org.springframework.security:spring-security-test` | Tasks 2.4–2.6 (MockMvc security tests) |
| Testcontainers Redis module (`org.testcontainers:redis` or `com.redis:testcontainers-redis`) | Task 2.5 (RefreshTokenService tests with Redis) |

**Impact:** The very first task that touches security (Task 2.3) will fail to compile.

**Fix:** Add a **Task 2.0: Update Gradle Dependencies** step before Task 2.1, or prepend the dependency additions to Task 2.1. List every new dependency with its group/artifact coordinates and version catalog entries.

---

### C4. CSRF Token Not Available to SPA After Registration/Login (Task 2.4)

**Description:** The plan uses `CookieCsrfTokenRepository.withHttpOnlyFalse()` and exempts `/auth/login` and `/auth/register` from CSRF. After a successful registration or login, the server sets the `jwt` and `refresh` cookies. However, with Spring Security 6's deferred CSRF token resolution, the `XSRF-TOKEN` cookie is **only set when the CSRF token is resolved** — which doesn't happen on CSRF-exempt endpoints.

After login, the SPA needs to send the CSRF token with subsequent mutating requests (`POST /auth/refresh`, `DELETE /photos/{id}`, etc.). But it doesn't have the `XSRF-TOKEN` cookie because the login response didn't include it.

**Impact:** The first mutating request after login (e.g., uploading a photo, creating an album) is rejected with `403 Forbidden`. The SPA has no way to obtain the CSRF token.

**Fix:** Use Spring Security 6.1+'s `SpaCsrfTokenRequestHandler`, which is specifically designed for SPA + cookie-based CSRF:

```java
.csrf(csrf -> csrf
    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
    .ignoringRequestMatchers("/auth/login", "/auth/register")
)
```

`SpaCsrfTokenRequestHandler` ensures the CSRF token cookie is set on every response (including the login response), and it correctly handles the `X-XSRF-TOKEN` header from SPAs. Alternatively, add a filter that explicitly resolves the `CsrfToken` on every response to force cookie creation.

---

### C5. Join Table Entities with Composite Primary Keys Not Specified (Tasks 2.1, 2.8)

**Description:** The plan says to map all entities to Flyway schema tables but does not address the composite-PK join tables:

- `album_photos` — PK: `(album_id, photo_id)`, extra column: `user_id`, composite FKs
- `photo_keywords` — PK: `(photo_id, keyword_id)`, extra column: `user_id`

These are not simple `@ManyToMany` join tables because they carry the `user_id` column (required for RLS). They need explicit `@Entity` classes with `@IdClass` or `@EmbeddedId`:

```java
@Entity
@Table(name = "album_photos")
@IdClass(AlbumPhotoId.class)
public class AlbumPhoto {
    @Id private UUID albumId;
    @Id private UUID photoId;
    private UUID userId;
    // ...
}
```

**Impact:** Without explicit entity mappings, the album add/remove and keyword assignment operations in Task 2.8 have no JPA entity to persist. The `@ManyToOne` references mentioned in the plan are insufficient for tables with composite PKs and extra columns.

**Fix:** Add `AlbumPhoto`, `AlbumPhotoId`, `PhotoKeyword`, and `PhotoKeywordId` to the entity list in Task 2.1. Specify the `@IdClass` pattern and document that `user_id` is set from the authenticated user context (not from request input).

---

## 3. Minor Issues & Improvements

### M1. No `application-test.yml` Profile

The tests use `@ActiveProfiles("test")` throughout, and Task 2.9 explicitly depends on test-specific rate limit properties (`app.rate-limit.upload=3`). No `application-test.yml` file is created in any task. This should include:
- Test-specific rate limits
- Refresh token TTL (short, for expiry tests)
- Testcontainers datasource auto-configuration
- Redis test configuration

### M2. `assert_user_context()` Never Called

The V2 migration defines `assert_user_context()` and comments: *"Phase 2 interceptor should call SELECT assert_user_context() after SET LOCAL."* The plan's `RlsStatementInspector`/`RlsInterceptor` never calls this function. Without it, a nil UUID set by `connection-init-sql` would silently pass through RLS checks (returning no rows) rather than failing fast.

### M3. Share Endpoint Public Path Without Controller

`SecurityConfig` marks `/share/**` as a public path, but no `ShareController` is created in Phase 2. The plan should explicitly note this as deferred (presumably Phase 4 or 5) to avoid confusion during implementation. Currently, `/share/**` is an unauthenticated endpoint that returns 404 — a potential fingerprinting vector.

### M4. Soft-Deleted Photos Block Re-Upload

The `UNIQUE (user_id, content_hash)` constraint (V1 migration, line 58) does not exclude soft-deleted rows. If a user deletes a photo (soft delete sets `deleted_at`), then re-uploads the same file, the INSERT violates the unique constraint. This is likely a Phase 3 concern (upload pipeline), but the constraint should either:
- Be a partial unique index: `CREATE UNIQUE INDEX ... WHERE deleted_at IS NULL`
- Or be handled by the upload service (check for soft-deleted duplicate and restore instead of re-inserting)

### M5. `email_verified` Soft-Gating Not Addressed

The design document (v4.0, [CR#5]) specifies: *"unverified accounts soft-gated (no uploads) with 7-day auto-purge."* The plan creates the `email_verified` field on the `User` entity but no endpoint or filter checks this value. Upload gating may be Phase 3, but the 7-day auto-purge requires a scheduled task that is not mentioned in any phase.

### M6. `updated_at` Columns Not Auto-Managed

Multiple tables (`users`, `photos`, `keywords`, `albums`, `shares`) have `updated_at TIMESTAMPTZ` columns. The plan does not specify how these are maintained. Options: `@PreUpdate` JPA callback, Hibernate `@UpdateTimestamp`, or a PostgreSQL trigger. Without explicit handling, `updated_at` will always be NULL.

### M7. No Error Response DTO

The `GlobalExceptionHandler` (Task 2.4, Step 5) specifies consistent JSON: `{"error": "message", "status": 400}`. But no `ErrorResponse` DTO or record is defined. The handler should use a shared response record to ensure all error paths produce identical JSON shape:

```java
public record ErrorResponse(String error, int status) {}
```

### M8. OAuth2 Test Mocking Strategy Unclear

Task 2.6 tests simulate "OAuth callback" without specifying how. Testing Spring Security OAuth2 requires either:
- `@WithMockOAuth2User` (from `spring-security-test`)
- MockMvc with `SecurityMockMvcRequestPostProcessors.oidcLogin()`
- A mock `OAuth2UserService` bean

The plan should specify the mocking strategy to prevent tests from attempting real OAuth2 provider calls.

---

## 4. Questions for Clarification

1. **Auth DataSource strategy:** Does the team prefer a secondary `DataSource` bean for RLS-bypassed auth operations (Option 1 from C1), or a dedicated `jpt_auth` PostgreSQL role with `BYPASSRLS`? The latter is cleaner but requires a V4 Flyway migration to create the role.

2. **RLS mechanism implementation:** Given that both the v1 review (C3) and this review (C2) have identified problems with the proposed RLS mechanism, should the plan specify the AOP-based approach (`@Aspect` on `@Transactional`) as the canonical implementation, or is there a preferred alternative?

3. **Upload endpoint in Phase 2 scope:** The `PhotoController` defines `GET /photos`, `DELETE /photos/{id}`, etc., but no `POST /photos` (upload). Without an upload path, all photo-related tests require direct database seeding. Is this intentional, with upload deferred to Phase 3? If so, should Phase 2 tests use a test helper that inserts photos directly via `EntityManager`?

4. **CSRF strategy confirmation:** Should the plan adopt `SpaCsrfTokenRequestHandler` (Spring Security 6.1+) as recommended in C4, or is there a reason to use the basic `CookieCsrfTokenRepository` approach?

---

## 5. Final Recommendation

**Major revisions needed.**

v2.0 successfully resolved all v1 findings, but three new critical issues must be addressed before implementation:

| # | Issue | Priority | Effort |
|---|---|---|---|
| C1 | RLS blocks all auth operations — login/register non-functional | **Must fix** | Medium (new DataSource + role) |
| C2 | StatementInspector prepend fails with JDBC extended protocol | **Must fix** | Medium (redesign to AOP or TxManager) |
| C3 | Missing Gradle dependencies — won't compile | **Must fix** | Low (add dependencies) |
| C4 | CSRF token unavailable to SPA post-login | **Should fix** | Low (use SpaCsrfTokenRequestHandler) |
| C5 | Join table entities with composite PKs unspecified | **Should fix** | Low (add entity classes) |

C1 and C2 are architectural issues that affect the foundational security layer. They should be resolved and documented in the plan before any implementation begins. C3 is a prerequisite blocker — implementation literally cannot start without the dependencies.
