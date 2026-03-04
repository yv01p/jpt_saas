# Critical Implementation Review: Phase 2 — Backend API (Auth, Security, REST Endpoints)

**Reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-2.md`
**Baseline:** `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0)
**Date:** 2026-03-04
**Reviewer:** Senior Staff Engineer (automated)

---

## 1. Overall Assessment

The plan follows a sound TDD cadence (write failing test, implement, verify, commit) and covers the core backend surface area: JPA entities, repositories, JWT auth, Spring Security, auth endpoints, OAuth2, photo CRUD, and rate limiting. The decomposition into incremental tasks is logical.

**However, the plan has several critical gaps and correctness issues that would produce bugs, security holes, or severe performance problems in production.** The most serious are: (1) the full-text search query bypasses the pre-computed GIN-indexed `search_vector` column, (2) no pagination on list endpoints, (3) the `RlsFilter` transaction-scope guarantee is underspecified, (4) CSRF configuration conflicts with public auth endpoints, and (5) refresh token implementation is mentioned but never detailed.

---

## 2. Critical Issues

### C1. Full-Text Search Query Bypasses GIN Index (Task 2.2 — PhotoRepository)

**Description:** The `searchByText` JPQL query rebuilds the tsvector on every invocation:

```java
FUNCTION('to_tsvector', 'english', COALESCE(p.filename,'') || ' ' || ...)
```

The design document (v4.0) specifies a **generated stored column** `search_vector` with a **GIN index** (`photos_search_idx`). The JPQL query ignores this column entirely. PostgreSQL cannot use the GIN index on a dynamically computed expression — every search triggers a full table scan per user.

**Impact:** O(n) sequential scan on every search for every user. Under load with even modest photo counts (10k+), this will saturate the database.

**Fix:** Use a `@Query(nativeQuery = true)` that references the stored column directly:

```java
@Query(value = "SELECT * FROM photos WHERE user_id = :userId AND deleted_at IS NULL " +
       "AND search_vector @@ plainto_tsquery('english', :query)",
       nativeQuery = true)
List<Photo> searchByText(@Param("userId") UUID userId, @Param("query") String query);
```

This is the exact query pattern specified in the design document.

---

### C2. No Pagination on Any List Endpoint (Tasks 2.2, 2.7, 2.8)

**Description:** All repository methods and controller endpoints return `List<Photo>`, `List<Keyword>`, etc. with no pagination. `GET /photos` returns every active photo for the user in a single response.

**Impact:** A user with 50,000 photos triggers a full table load into JVM memory, serialization of a massive JSON array, and transfer over the wire. This is a denial-of-service vector against the application's own heap. It also creates N+1 query risks if any `@ManyToOne` associations are lazily fetched during serialization.

**Fix:** All list endpoints must use `Pageable` / `Page<T>`:

```java
Page<Photo> findByUserIdAndDeletedAtIsNullOrderByUploadedAtDesc(UUID userId, Pageable pageable);
```

Controllers accept `?page=0&size=50` (with a sane max page size, e.g. 100). The design document's `GET /photos/trash` similarly needs pagination.

---

### C3. RlsFilter Transaction Scope Is Underspecified (Task 2.4)

**Description:** The plan says `RlsFilter` executes `SET LOCAL app.current_user_id = ?` "within the transaction." However, a Servlet filter executes *before* Spring's transactional proxy opens a transaction. `SET LOCAL` outside a transaction block has **no effect** — it behaves like `SET` (session-scoped), which means the value persists on the connection after the request completes. With HikariCP connection pooling, the next request on that connection inherits the previous user's ID — **a cross-tenant data leak**.

**Impact:** Complete RLS bypass. User A's requests could execute under User B's identity if they share a pooled connection.

**Fix:** The RLS variable must be set *inside* the transaction. Two correct approaches:

1. **Preferred:** Use a Hibernate `StatementInspector` or `@EventListener` on `TransactionSynchronization.afterBegin` to execute `SET LOCAL` after the transaction opens.
2. **Alternative:** Use a Spring `HandlerInterceptor` + `TransactionTemplate` that wraps the `SET LOCAL` call in the request's existing `@Transactional` context. However, this requires careful ordering with Spring's `OpenEntityManagerInViewInterceptor`.

The design document correctly specifies `SET LOCAL` (transaction-scoped), but the plan's filter-based implementation cannot guarantee this. Explicitly document which mechanism ensures the `SET LOCAL` runs after `BEGIN`.

---

### C4. CSRF Conflicts with Public Auth Endpoints (Task 2.4 / 2.5)

**Description:** The plan enables CSRF globally with `CookieCsrfTokenRepository.withHttpOnlyFalse()` and marks `/auth/**` as a public path (no authentication required). However, `POST /auth/register` and `POST /auth/login` are mutating requests. A brand-new user visiting the site has no `XSRF-TOKEN` cookie — their first POST will be rejected by Spring Security's CSRF filter.

**Impact:** Registration and login are broken for new users. The SPA has no way to obtain the CSRF token before the first authenticated request.

**Fix:** Either:
1. Exempt `/auth/login` and `/auth/register` from CSRF (they are not vulnerable to CSRF since they don't act on an existing session), OR
2. Add a `GET /auth/csrf` endpoint that returns the CSRF token cookie before the SPA sends any POST, OR
3. Use Spring Security 6's `CsrfTokenRequestAttributeHandler` with deferred loading, which sets the `XSRF-TOKEN` cookie on the first response (including unauthenticated GETs to the SPA).

Option 3 is the cleanest and aligns with Spring Security 6 defaults.

---

### C5. Refresh Token Flow Has No Implementation Detail (Task 2.5)

**Description:** The plan lists `POST /auth/refresh` as an endpoint but provides no implementation guidance: no Redis key schema, no token format, no rotation strategy, no test. The design document specifies refresh tokens stored in Redis with 30-day expiry, revocation on password change, and immediate revocation on logout.

**Impact:** Without explicit detail, the implementer may:
- Store refresh tokens in plaintext (should be hashed with SHA-256, same as email tokens)
- Skip token rotation on refresh (allows stolen tokens to be used indefinitely)
- Miss the "revoke on password change" requirement
- Not implement family-based rotation detection

**Fix:** Add explicit implementation steps:
- Redis key: `refresh:{SHA-256(token)}` → `{userId, issuedAt, family}`
- On refresh: issue new refresh + access token, delete old refresh token key (rotation)
- On password change: delete all `refresh:*` keys for the user (or use a Redis set of token hashes per user for efficient bulk revocation)
- Test: verify refresh returns new JWT cookie, old refresh token is invalid after rotation, password change invalidates all refresh tokens

---

### C6. Missing `@ControllerAdvice` Global Exception Handler

**Description:** No task creates a global exception handler. The plan produces controllers that will throw `EntityNotFoundException`, `DataIntegrityViolationException` (for duplicate content hash), and validation errors. Without a `@ControllerAdvice`, Spring Boot returns default whitelabel error pages with stack traces, or inconsistent error shapes.

**Impact:** Information leakage (stack traces in production), inconsistent error response format for the SPA, and the design document's `409 Conflict` on duplicate `content_hash` won't be properly mapped.

**Fix:** Add a task (before or alongside Task 2.5) to create:
```java
@ControllerAdvice
public class GlobalExceptionHandler {
    // 400 — MethodArgumentNotValidException, ConstraintViolationException
    // 401 — AuthenticationException
    // 404 — EntityNotFoundException
    // 409 — DataIntegrityViolationException (content_hash duplicate)
    // 413 — QuotaExceededException
    // 423 — AccountLockedException
    // 429 — RateLimitExceededException
}
```

---

### C7. Account Lockout Timing Side-Channel (Task 2.5)

**Description:** The lockout check (`failed_login_attempts >= 5 AND locked_until > now()`) is evaluated before password comparison. If the endpoint returns early with `423 Locked` for locked accounts but performs bcrypt verification for unlocked accounts, the response time difference reveals whether an account exists and is locked.

**Impact:** Attacker can enumerate locked accounts (and therefore valid emails) by measuring response times.

**Fix:** Always perform the bcrypt comparison regardless of lockout status. Return a generic `401 Unauthorized` with body `"Invalid credentials"` for both wrong-password and locked scenarios. Optionally include a `Retry-After` header for locked accounts, but do not change the status code or body in a way that distinguishes the two cases.

---

### C8. `search_vector` Column Mapping Missing from Entity (Task 2.1)

**Description:** The plan says to mark `search_vector` as `@Column(insertable = false, updatable = false)` (correct for a generated column), but `tsvector` is a PostgreSQL-specific type with no JPA/Hibernate type mapping. Without a custom `@Type` annotation or Hibernate 6 `@JdbcTypeCode`, Hibernate will fail to map this column during entity scanning.

**Impact:** Application startup fails or the column is silently ignored (depending on `hibernate.ddl-auto` setting).

**Fix:** Either:
1. Map it with a custom Hibernate type (e.g., `@Column(columnDefinition = "tsvector", insertable = false, updatable = false)` + a registered `UserType`), OR
2. **Simpler:** Exclude it from the entity entirely (don't map it as a field). Since all FTS queries use native SQL (`@Query(nativeQuery = true)`), the entity never needs to read this column. This is the cleanest approach.

---

## 3. Minor Issues & Improvements

### M1. Missing Repositories (Task 2.2)

`SavedSearchRepository` and `EmailTokenRepository` are not listed in Task 2.2 but are needed by Tasks 2.5 (email verification) and 2.8 (saved searches). `PhotoMetadataRepository` is also absent (needed by the worker, but should exist in the shared entity/repo layer).

### M2. Test Stubs Too Vague (Tasks 2.7, 2.8)

Several test methods are empty stubs (`void listPhotos_returnsOnlyUsersPhotos() { }`). The plan should at minimum describe the test setup and assertions in comments, as done for Task 2.1. Empty stubs risk being implemented as trivially-passing tests.

### M3. No Email Service (Task 2.5)

`AuthService` is described as "sends verification email" but no `EmailService` is created. This needs at minimum an interface (for testing with a mock) and a placeholder SMTP implementation or a note deferring it to a later phase.

### M4. Missing `@Valid` on Request DTOs (Task 2.5)

`RegisterRequest` and `LoginRequest` are listed but no validation annotations (`@Email`, `@Size(min=12)`) or `@Valid` on controller parameters are mentioned. Without these, the "rejects short password" test will need manual validation logic.

### M5. Keyword Hierarchical Queries (Task 2.8)

Recursive CTEs (`WITH RECURSIVE`) cannot be expressed in JPQL. The plan should explicitly note that `KeywordRepository` will need `@Query(nativeQuery = true)` for subtree queries. The recursive CTE is non-trivial:

```sql
WITH RECURSIVE subtree AS (
    SELECT id FROM keywords WHERE id = :rootId AND user_id = :userId
    UNION ALL
    SELECT k.id FROM keywords k JOIN subtree s ON k.parent_id = s.id
)
SELECT * FROM keywords WHERE id IN (SELECT id FROM subtree);
```

### M6. Album Composite FK in JPA (Task 2.1 / 2.8)

The design specifies `album_photos` with composite foreign keys: `(album_id, user_id) → albums(id, user_id)` and `(photo_id, user_id) → photos(id, user_id)`. This requires composite keys on `albums` and `photos` (or at least composite unique constraints). Mapping this in JPA with `@IdClass` or `@EmbeddedId` is non-trivial and not addressed. The plan should detail the entity mapping or acknowledge it as a Flyway-only constraint (not enforced at the JPA level).

### M7. OAuth2 Integration Test Coverage (Task 2.6)

The single test ("OAuth login blocks if email exists") is insufficient. Missing tests:
- New OAuth user is created successfully
- OAuth user gets JWT cookie
- OAuth user with no email from provider is handled
- Account linking flow works after password authentication

### M8. Rate Limit Test Feasibility (Task 2.9)

Testing "101st upload in an hour is rejected" requires either sending 101 requests in the test (slow) or injecting a pre-loaded Bucket4j bucket. The plan should specify using a test-specific rate limit configuration (e.g., 3 requests/hour) or mocking the Redis bucket state.

### M9. Missing `PhotoMetadata` Relationship (Task 2.1)

The `Photo` entity should have a `@OneToOne` mapping to `PhotoMetadata` (or not — if they're always queried separately). The plan doesn't specify the relationship strategy. Given that metadata is written by the worker and read by search queries, a lazy `@OneToOne` or separate repository access is the right call, but it should be explicit.

### M10. Commit Hygiene (Task 2.1)

Step 5 commits only the entity source files (`api/src/main/java/.../entity/`), omitting the test file created in Step 1. Tests should be committed alongside their subjects.

---

## 4. Questions for Clarification

1. **Upload endpoint scope:** Photo upload (multipart, content hashing, quota check, MinIO write, Redis Streams enqueue) is not in Phase 2. Is it intentionally in Phase 3? If so, the `PhotoController` in Task 2.7 is incomplete — `GET /photos` won't have data to return without an upload path. Consider at minimum a note about test data setup.

2. **Share API endpoint:** The plan lists `/share/**` as a public path in `SecurityConfig`, and there's a `ShareRepository` in Task 2.2, but no `ShareController` is created in Phase 2. Is share management deferred to a later phase?

3. **User entity fields:** The design includes `oauth_provider` and `oauth_id` on the `users` table. Are these mapped in the `User` entity in Task 2.1? They're needed for Task 2.6 (OAuth2).

4. **Nginx ↔ Spring Boot path rewriting:** The design specifies that Nginx's `proxy_pass` strips `/api/` so Spring Boot sees `/auth/login` not `/api/auth/login`. The tests in Task 2.4/2.5 use `/photos` and `/auth/register` (post-rewrite paths). Is there a risk of confusion if developers test against the API directly (port 8080) vs. through Nginx? Consider documenting the path mapping in a comment or test helper.

---

## 5. Final Recommendation

**Major revisions needed.**

The plan requires fixes for at least **C1–C5** before implementation:

| # | Issue | Priority |
|---|---|---|
| C1 | FTS query bypasses GIN index → full table scan | **Must fix** |
| C2 | No pagination → OOM on large datasets | **Must fix** |
| C3 | RlsFilter outside transaction → cross-tenant leak | **Must fix** |
| C4 | CSRF blocks registration/login for new users | **Must fix** |
| C5 | Refresh token flow undocumented | **Must fix** |
| C6 | No global exception handler | **Should fix** |
| C7 | Lockout timing side-channel | **Should fix** |
| C8 | `tsvector` column mapping | **Should fix** |

All critical items (C1–C5) are correctness or security bugs that will manifest immediately in testing or production. The remaining items (C6–C8, M1–M10) are quality and completeness gaps that should be addressed before implementation begins.
