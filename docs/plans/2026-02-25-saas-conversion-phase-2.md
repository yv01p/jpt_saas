# JPhotoTagger SaaS Conversion — Phase 2: Backend API — Auth, Security, REST Endpoints

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

**Version:** 2.0
**Date:** 2026-03-04
**Status:** Approved

---

## Changelog

### v2.0 — 2026-03-04

Revisions following Critical Implementation Review v1 (`docs/plans/2026-02-25-saas-conversion-phase-2-critical-review-1.md`):

- **[C1] FTS query bypassed GIN index:** Replaced JPQL `FUNCTION('to_tsvector', ...)` with `@Query(nativeQuery = true)` referencing the stored `search_vector` generated column. Ensures the `photos_search_idx` GIN index is used.
- **[C2] No pagination on list endpoints:** All list queries now return `Page<T>` with `Pageable`. Controllers accept `?page=0&size=50` with max page size of 100.
- **[C3] RlsFilter ran outside transaction:** Replaced Servlet `RlsFilter` with a `HandlerInterceptor` (stores userId in `ThreadLocal`) + Hibernate `StatementInspector` (issues `SET LOCAL` on first SQL statement within the transaction). Guarantees transaction-scoped RLS.
- **[C4] CSRF blocked auth endpoints:** Added `.ignoringRequestMatchers("/auth/login", "/auth/register")` to CSRF config. Pre-authentication endpoints are not vulnerable to CSRF.
- **[C5] Refresh token flow undocumented:** Added full implementation detail: Redis key schema (`refresh:{SHA-256(token)}`), token rotation on refresh, bulk revocation via `user_refresh:{userId}` set, and 4 explicit tests.
- **[C6] Missing GlobalExceptionHandler:** Added as sub-step of Task 2.4 with consistent JSON error shape and mappings for 400/401/404/409/413/423/429.
- **[C7] Account lockout timing side-channel:** Login always performs bcrypt comparison regardless of lockout status. Returns generic `401 "Invalid credentials"` for both wrong-password and locked accounts.
- **[C8] `tsvector` column unmappable in JPA:** Removed `search_vector` from `Photo` entity entirely. Column exists in DB via Flyway; native queries reference it directly.
- **[M1] Missing repositories:** Added `SavedSearchRepository`, `EmailTokenRepository`, `PhotoMetadataRepository` to Task 2.2.
- **[M2] Empty test stubs:** Added setup/assertion comments to all test stubs in Tasks 2.7, 2.8.
- **[M3] Missing EmailService:** Added `EmailService` interface + stub implementation to Task 2.5.
- **[M4] Missing validation annotations:** Added `@Email`, `@Size(min=12)` to DTOs and `@Valid` on controller parameters.
- **[M5] Keyword recursive CTE:** Noted `@Query(nativeQuery = true)` requirement for hierarchical queries in Task 2.8.
- **[M6] Album composite FK:** Documented as Flyway-only constraint, not JPA-enforced.
- **[M7] OAuth2 test coverage:** Added 3 additional test cases to Task 2.6.
- **[M8] Rate limit test feasibility:** Tests use low-limit test profile (3 requests/hour) instead of sending 101 requests.
- **[M9] PhotoMetadata relationship:** Explicitly uses separate repository access, no `@OneToOne` mapping on `Photo`.
- **[M10] Commit hygiene:** All commits now include corresponding test files.

### v1.0 — 2026-02-25

Initial plan.

---

### Task 2.1: JPA Entities

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/entity/User.java`
- Create: `api/src/main/java/org/jphototagger/api/entity/Photo.java`
- Create: `api/src/main/java/org/jphototagger/api/entity/PhotoMetadata.java`
- Create: `api/src/main/java/org/jphototagger/api/entity/Keyword.java`
- Create: `api/src/main/java/org/jphototagger/api/entity/Album.java`
- Create: `api/src/main/java/org/jphototagger/api/entity/Share.java`
- Create: `api/src/main/java/org/jphototagger/api/entity/EmailToken.java`
- Create: `api/src/main/java/org/jphototagger/api/entity/SavedSearch.java`

**Step 1: Write failing test — User entity persists**

```java
// api/src/test/java/org/jphototagger/api/entity/UserEntityTest.java
package org.jphototagger.api.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserEntityTest {

    @Autowired
    private EntityManager em;

    @Test
    void userPersistsAndLoads() {
        User user = new User();
        user.setEmail("test@example.com");
        user.setPasswordHash("$2a$12$hashedpassword");
        em.persist(user);
        em.flush();
        em.clear();

        User loaded = em.find(User.class, user.getId());
        assertThat(loaded.getEmail()).isEqualTo("test@example.com");
        assertThat(loaded.getQuotaBytes()).isEqualTo(10737418240L);
        assertThat(loaded.getUsedBytes()).isEqualTo(0L);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :api:test --tests UserEntityTest`
Expected: FAIL — User class doesn't exist

**Step 3: Write all JPA entities**

Map each entity to the Flyway schema tables. Use `@Entity`, `@Table`, `@Id`, `@GeneratedValue(strategy = GenerationType.UUID)`. Include all columns from V1 migration.

Entity-specific notes:
- **Photo:** Do NOT map the `search_vector` column. It is a PostgreSQL generated `tsvector` column with no Hibernate type mapping. The column exists in the DB (created by Flyway) and is referenced only by native queries. **[C8]**
- **PhotoMetadata:** Standalone entity with `@Id` on `photoId`. No `@OneToOne` relationship to `Photo` — metadata is written by the worker and queried separately via `PhotoMetadataRepository`. **[M9]**
- **User:** Include `oauthProvider`, `oauthId`, `failedLoginAttempts`, `lockedUntil` fields (needed by Tasks 2.5, 2.6).
- **Album / AlbumPhoto:** The composite FK constraint (`album_photos(album_id, user_id) → albums(id, user_id)`) is enforced at the Flyway/DB level only, not in JPA. `AlbumPhoto` uses a simple `@ManyToOne` to `Album` and `Photo`. **[M6]**

**Step 4: Run test to verify it passes**

Run: `./gradlew :api:test --tests UserEntityTest`
Expected: PASS

**Step 5: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/entity/ api/src/test/java/org/jphototagger/api/entity/
git commit -m "feat: JPA entities for all tables"
```

### Task 2.2: Spring Repositories (Spring Data JPA)

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/repository/UserRepository.java`
- Create: `api/src/main/java/org/jphototagger/api/repository/PhotoRepository.java`
- Create: `api/src/main/java/org/jphototagger/api/repository/PhotoMetadataRepository.java` **[M1]**
- Create: `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java`
- Create: `api/src/main/java/org/jphototagger/api/repository/AlbumRepository.java`
- Create: `api/src/main/java/org/jphototagger/api/repository/ShareRepository.java`
- Create: `api/src/main/java/org/jphototagger/api/repository/EmailTokenRepository.java` **[M1]**
- Create: `api/src/main/java/org/jphototagger/api/repository/SavedSearchRepository.java` **[M1]**

**Step 1: Write failing test — PhotoRepository queries**

```java
// api/src/test/java/org/jphototagger/api/repository/PhotoRepositoryTest.java
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PhotoRepositoryTest {

    @Autowired PhotoRepository photoRepo;
    @Autowired UserRepository userRepo;

    @Test
    void findByUserIdAndDeletedAtIsNull_excludesSoftDeleted() {
        // Setup: create user, insert 2 active photos + 1 soft-deleted photo
        // Assert: page contains exactly the 2 active photos
        // Assert: soft-deleted photo is not in the result
    }

    @Test
    void fullTextSearch_matchesCaption() {
        // Setup: create user, insert photo with caption "sunset over mountains"
        // Act: searchByText(userId, "sunset", PageRequest.of(0, 50))
        // Assert: result contains the photo
        // Assert: searchByText(userId, "nonexistent", ...) returns empty page
    }
}
```

**Step 2: Implement repositories with custom queries**

```java
public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    Page<Photo> findByUserIdAndDeletedAtIsNullOrderByUploadedAtDesc(
        UUID userId, Pageable pageable);  // [C2]

    Page<Photo> findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
        UUID userId, Pageable pageable);  // [C2]

    @Query(value = "SELECT * FROM photos WHERE user_id = :userId AND deleted_at IS NULL " +
           "AND search_vector @@ plainto_tsquery('english', :query)",
           countQuery = "SELECT count(*) FROM photos WHERE user_id = :userId AND deleted_at IS NULL " +
           "AND search_vector @@ plainto_tsquery('english', :query)",
           nativeQuery = true)  // [C1] — uses stored GIN-indexed search_vector column
    Page<Photo> searchByText(@Param("userId") UUID userId,
                             @Param("query") String query,
                             Pageable pageable);

    Optional<Photo> findByUserIdAndContentHash(UUID userId, String contentHash);
}
```

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/repository/ api/src/test/
git commit -m "feat: Spring Data JPA repositories with paginated search queries"
```

### Task 2.3: JWT Authentication — Token Service

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/security/JwtService.java`
- Test: `api/src/test/java/org/jphototagger/api/security/JwtServiceTest.java`

**Step 1: Write failing test**

```java
@Test
void generateAndValidateToken() {
    UUID userId = UUID.randomUUID();
    String token = jwtService.generateToken(userId, "test@example.com");
    assertThat(jwtService.validateToken(token)).isTrue();
    assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
}

@Test
void expiredTokenIsInvalid() {
    // Create token with -1 minute expiry
    // Verify validateToken returns false
}
```

**Step 2: Implement JwtService**

Use `io.jsonwebtoken:jjwt`. HS256 signing with configurable secret from `app.jwt-secret`. 15-minute expiry from `app.jwt-expiry-minutes`.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/ api/src/test/
git commit -m "feat: JWT service — token generation and validation"
```

### Task 2.4: Spring Security Configuration + Global Exception Handler

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`
- Create: `api/src/main/java/org/jphototagger/api/security/JwtAuthenticationFilter.java`
- Create: `api/src/main/java/org/jphototagger/api/security/RlsInterceptor.java` (renamed from `RlsFilter`) **[C3]**
- Create: `api/src/main/java/org/jphototagger/api/security/RlsStatementInspector.java` **[C3]**
- Create: `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java` **[C6]**

**Step 1: Write failing test — unauthenticated requests rejected**

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityTest {

    @Autowired MockMvc mockMvc;

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/photos"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedRequestSucceeds() throws Exception {
        // Create valid JWT, include in cookie
        mockMvc.perform(get("/photos").cookie(new Cookie("jwt", validToken)))
            .andExpect(status().isOk());
    }
}
```

**Step 2: Implement SecurityConfig**

- JWT filter reads `jwt` httpOnly cookie
- CSRF enabled with `CookieCsrfTokenRepository.withHttpOnlyFalse()`
- **CSRF exemption for pre-auth endpoints:** `.ignoringRequestMatchers("/auth/login", "/auth/register")` — these endpoints have no session to exploit **[C4]**
- Public paths: `/auth/**`, `/share/**`, `/actuator/health`

**Step 3: Implement JwtAuthenticationFilter**

Extract JWT from cookie, validate, set SecurityContext.

**Step 4: Implement RLS — HandlerInterceptor + StatementInspector [C3]**

The RLS mechanism uses two components to guarantee `SET LOCAL` runs inside the transaction:

1. **`RlsInterceptor`** (Spring `HandlerInterceptor`): After authentication, extracts the authenticated user ID from `SecurityContext` and stores it in a `ThreadLocal<UUID>`.

2. **`RlsStatementInspector`** (Hibernate `StatementInspector`): On the first SQL statement within each transaction, reads the user ID from the `ThreadLocal` and prepends `SET LOCAL app.current_user_id = '{userId}';` before the statement. Uses a per-thread flag to ensure `SET LOCAL` is issued only once per transaction. The flag is cleared by the `RlsInterceptor.afterCompletion()` callback.

Register the `StatementInspector` in `application.yml`:
```yaml
spring.jpa.properties.hibernate.session_factory.statement_inspector: org.jphototagger.api.security.RlsStatementInspector
```

This guarantees the `SET LOCAL` always executes within the transaction boundary, making it transaction-scoped (auto-reset on commit/rollback). No risk of cross-tenant connection pool leakage.

**Step 5: Implement GlobalExceptionHandler [C6]**

```java
@ControllerAdvice
public class GlobalExceptionHandler {
    // All responses use consistent JSON: {"error": "message", "status": 400}
    // 400 — MethodArgumentNotValidException, ConstraintViolationException
    // 401 — AuthenticationException (generic "Invalid credentials")
    // 404 — EntityNotFoundException
    // 409 — DataIntegrityViolationException (content_hash duplicate)
    // 413 — QuotaExceededException (custom)
    // 423 — AccountLockedException (custom) — NOT used by login (see C7)
    // 429 — RateLimitExceededException (custom)
}
```

**Step 6: Run tests, verify pass**

**Step 7: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/ api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java api/src/test/
git commit -m "feat: Spring Security config — JWT, CSRF, RLS inspector, exception handler"
```

### Task 2.5: Auth Controller — Registration & Login

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/controller/AuthController.java`
- Create: `api/src/main/java/org/jphototagger/api/service/AuthService.java`
- Create: `api/src/main/java/org/jphototagger/api/service/RefreshTokenService.java` **[C5]**
- Create: `api/src/main/java/org/jphototagger/api/service/EmailService.java` (interface) **[M3]**
- Create: `api/src/main/java/org/jphototagger/api/service/StubEmailService.java` (stub impl) **[M3]**
- Create: `api/src/main/java/org/jphototagger/api/dto/RegisterRequest.java`
- Create: `api/src/main/java/org/jphototagger/api/dto/LoginRequest.java`

**Step 1: Write failing test — registration, login, refresh**

```java
@Test
void registerCreatesUser() throws Exception {
    mockMvc.perform(post("/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"email": "new@example.com", "password": "securePassword12"}
            """))
        .andExpect(status().isCreated());
}

@Test
void registerRejectsShortPassword() throws Exception {
    mockMvc.perform(post("/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"email": "new@example.com", "password": "short"}
            """))
        .andExpect(status().isBadRequest());
}

@Test
void loginReturnsJwtCookie() throws Exception {
    // Register user first, then login
    mockMvc.perform(post("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"email": "new@example.com", "password": "securePassword12"}
            """))
        .andExpect(status().isOk())
        .andExpect(cookie().exists("jwt"))
        .andExpect(cookie().httpOnly("jwt", true));
}

@Test
void loginReturnsGeneric401ForLockedAccount() throws Exception {
    // Register user, fail login 5 times with wrong password
    // 6th attempt (even with correct password): returns 401 "Invalid credentials"
    // NOT 423 — no distinction between wrong-password and locked [C7]
}

// --- Refresh token tests [C5] ---

@Test
void refreshReturnsNewJwtAndRefreshCookies() throws Exception {
    // Login to get initial refresh token cookie
    // POST /auth/refresh with refresh token cookie
    // Assert: new jwt cookie, new refresh token cookie
}

@Test
void oldRefreshTokenIsInvalidAfterRotation() throws Exception {
    // Login, capture refresh token, refresh once (get new token)
    // Attempt refresh with the old token → 401
}

@Test
void passwordChangeInvalidatesAllRefreshTokens() throws Exception {
    // Login on two "devices" (two refresh tokens)
    // Change password
    // Both refresh tokens should return 401
}

@Test
void expiredRefreshTokenReturns401() throws Exception {
    // Create refresh token with short TTL (test profile)
    // Wait for expiry, attempt refresh → 401
}
```

**Step 2: Implement Request DTOs with validation [M4]**

```java
public record RegisterRequest(
    @Email @NotBlank String email,
    @Size(min = 12, message = "Password must be at least 12 characters") @NotBlank String password
) {}

public record LoginRequest(
    @Email @NotBlank String email,
    @NotBlank String password
) {}
```

**Step 3: Implement AuthService**

- Password validation: >= 12 chars (enforced by `@Size` on DTO + `@Valid` on controller)
- bcrypt cost factor 12
- Account lockout: 5 failures → 15 min lock
- **Timing side-channel mitigation [C7]:** Always perform `BCrypt.checkpw()` regardless of lockout status. After comparison, check `failedLoginAttempts >= 5 && lockedUntil > now()` — if locked, return generic `401 "Invalid credentials"` (same as wrong password). Never return a distinct status code or message for locked accounts.
- Email verification token generation (SHA-256 stored, plaintext emailed via `EmailService`)

**Step 4: Implement RefreshTokenService [C5]**

- **Token format:** 256-bit cryptographically random value, base64url-encoded
- **Redis key schema:** `refresh:{SHA-256(token)}` → JSON `{"userId": "...", "issuedAt": "...", "family": "..."}`
- **User token set:** `user_refresh:{userId}` → Redis Set of all active token hashes for the user
- **TTL:** 30 days (configurable via `app.refresh-token-expiry-days`)
- **Rotation:** On `POST /auth/refresh`:
  1. Validate incoming token (lookup `refresh:{SHA-256(token)}` in Redis)
  2. Delete old token key from Redis
  3. Remove old hash from `user_refresh:{userId}` set
  4. Generate new refresh token + new JWT
  5. Store new token key and add hash to user set
  6. Return both as httpOnly cookies
- **Revocation on logout:** Delete token key from Redis, remove from user set
- **Revocation on password change:** Read all hashes from `user_refresh:{userId}`, delete each `refresh:{hash}` key, then delete the set itself

**Step 5: Implement EmailService [M3]**

- `EmailService` interface with `sendVerificationEmail(String to, String token)` and `sendPasswordResetEmail(String to, String token)`
- `StubEmailService` implementation (annotated `@Profile("dev | test")`) that logs the email content to the application log. Real SMTP implementation deferred to a later phase.

**Step 6: Implement AuthController**

- `POST /auth/register` — validates with `@Valid`, creates user, sends verification email
- `POST /auth/login` — validates credentials (always bcrypt), issues JWT + refresh token in httpOnly cookies
- `POST /auth/refresh` — refresh token rotation flow via `RefreshTokenService`
- `POST /auth/logout` — clears cookies, revokes refresh token in Redis

**Step 7: Run tests, verify pass**

**Step 8: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/controller/ api/src/main/java/org/jphototagger/api/service/ api/src/main/java/org/jphototagger/api/dto/ api/src/test/
git commit -m "feat: auth endpoints — register, login, refresh, logout with token rotation"
```

### Task 2.6: OAuth2 Integration (Google/GitHub)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`
- Create: `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java`

**Step 1: Write failing tests [M7 — expanded coverage]**

```java
@Test
void oauthLoginCreatesNewUser() {
    // Simulate OAuth callback with new email
    // Assert: user created in DB with oauth_provider and oauth_id set
    // Assert: JWT cookie is issued
}

@Test
void oauthLoginBlocksIfEmailExistsWithPassword() {
    // Pre-create user with email + password_hash
    // Simulate OAuth callback with same email
    // Assert: login is blocked, response contains linking message
}

@Test
void oauthLoginSucceedsForExistingOAuthUser() {
    // Pre-create user with oauth_provider=google, oauth_id=123
    // Simulate OAuth callback with same provider+id
    // Assert: JWT cookie is issued, no new user created
}

@Test
void oauthLoginHandlesMissingEmail() {
    // Simulate OAuth callback where provider returns no email
    // Assert: appropriate error response (not a crash)
}
```

**Step 2: Implement OAuth2SuccessHandler**

- On success: create user if new (set `oauthProvider`, `oauthId`), issue JWT + refresh token
- If email already exists with password account: block login, show linking message
- Never auto-merge by email
- Handle missing email from provider gracefully

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/ api/src/test/
git commit -m "feat: OAuth2 login (Google/GitHub) with no auto-merge"
```

### Task 2.7: Photo CRUD Endpoints

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/controller/PhotoController.java`
- Create: `api/src/main/java/org/jphototagger/api/service/PhotoService.java`

**Step 1: Write failing tests [M2 — with setup/assertions]**

```java
@Test
void listPhotos_returnsOnlyUsersPhotos() {
    // Setup: create 2 users, each with 2 photos
    // Act: GET /photos as user1 with ?page=0&size=50
    // Assert: response contains only user1's 2 photos
    // Assert: response includes pagination metadata (totalElements, totalPages)
}

@Test
void getPhoto_returns404ForOtherUsersPhoto() {
    // Setup: create user1 with photo, create user2
    // Act: GET /photos/{user1PhotoId} as user2
    // Assert: 404 (not 403 — don't leak existence)
}

@Test
void deletePhoto_softDeletesAndDecrementsQuota() {
    // Setup: create user with photo (size_bytes = 5000), used_bytes = 5000
    // Act: DELETE /photos/{id}
    // Assert: photo.deletedAt is not null
    // Assert: user.usedBytes == 0
}

@Test
void getPhotoStatus_returnsProcessingStatus() {
    // Setup: create photo with processing_status = 'processing'
    // Act: GET /photos/{id}/status
    // Assert: response body contains {"id": "...", "processing_status": "processing"}
}

@Test
void trashView_returnsDeletedPhotos() {
    // Setup: create user with 1 active + 1 soft-deleted photo
    // Act: GET /photos/trash?page=0&size=50
    // Assert: response contains only the soft-deleted photo
}

@Test
void restorePhoto_clearsDeletedAtAndIncrementsQuota() {
    // Setup: create user with soft-deleted photo (size_bytes = 5000), used_bytes = 0
    // Act: POST /photos/{id}/restore
    // Assert: photo.deletedAt is null
    // Assert: user.usedBytes == 5000
}
```

**Step 2: Implement PhotoController**

All list endpoints return `Page<T>` with `Pageable` (default size=50, max=100). **[C2]**

- `GET /photos?page=0&size=50` — list active photos (deleted_at IS NULL), paginated
- `GET /photos/{id}` — single photo with ownership check (404 if not owned)
- `GET /photos/{id}/status` — processing status polling
- `DELETE /photos/{id}` — soft delete + quota decrement (in same transaction via `SELECT FOR UPDATE`)
- `GET /photos/trash?page=0&size=50` — trash view, paginated
- `POST /photos/{id}/restore` — restore from trash + quota re-increment (in same transaction)

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/controller/ api/src/main/java/org/jphototagger/api/service/ api/src/test/
git commit -m "feat: photo CRUD endpoints with soft delete, trash, and pagination"
```

### Task 2.8: Keyword, Album, Search, SavedSearch Endpoints

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/controller/KeywordController.java`
- Create: `api/src/main/java/org/jphototagger/api/controller/AlbumController.java`
- Create: `api/src/main/java/org/jphototagger/api/controller/SearchController.java`
- Create: `api/src/main/java/org/jphototagger/api/controller/SavedSearchController.java`
- Create corresponding services

**Step 1: Write failing tests for each controller [M2 — with setup/assertions]**

```java
// --- KeywordControllerTest ---
@Test
void listKeywords_returnsHierarchicalTree() {
    // Setup: create user with parent keyword "Nature" and child "Sunset"
    // Act: GET /keywords?page=0&size=50
    // Assert: both keywords returned with correct parent_id relationship
}

@Test
void getKeywordSubtree_usesRecursiveCTE() {
    // Setup: create keyword tree: Animals → Dogs → Labrador
    // Act: GET /keywords/{animalsId}/subtree
    // Assert: returns all 3 keywords in the subtree
}

// --- AlbumControllerTest ---
@Test
void addPhotoToAlbum_enforcesOwnership() {
    // Setup: create user1 with album and photo, create user2 with photo
    // Act: POST /albums/{user1Album}/photos/{user2Photo} as user1
    // Assert: 404 (composite FK prevents cross-tenant assignment)
}

// --- SearchControllerTest ---
@Test
void fullTextSearch_returnsPaginatedResults() {
    // Setup: create user with photos having various captions
    // Act: GET /search?q=sunset&page=0&size=50
    // Assert: paginated results matching "sunset"
}

// --- SavedSearchControllerTest ---
@Test
void savedSearchCRUD() {
    // Setup: create user
    // Act: POST /saved-searches, GET /saved-searches, PUT, DELETE
    // Assert: full CRUD lifecycle works
}
```

**Step 2: Implement controllers**

All list endpoints return `Page<T>` with `Pageable`. **[C2]**

- **Keywords:** CRUD + hierarchical subtree query. Subtree query uses `@Query(nativeQuery = true)` with `WITH RECURSIVE` CTE — JPQL does not support recursive CTEs. **[M5]**
- **Albums:** CRUD + add/remove photos. Cross-tenant isolation enforced by the Flyway composite FK constraint (`album_photos(album_id, user_id) → albums(id, user_id)` and `(photo_id, user_id) → photos(id, user_id)`). JPA entities use simple `@ManyToOne`; the composite FK is DB-level only. **[M6]**
- **Search:** Full-text search (via `PhotoRepository.searchByText` native query) + EXIF field queries (native queries with JSONB operators) + keyword search (join through `photo_keywords`)
- **Saved searches:** CRUD

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/controller/ api/src/main/java/org/jphototagger/api/service/ api/src/test/
git commit -m "feat: keyword, album, search, saved search endpoints with pagination"
```

### Task 2.9: Rate Limiting — Bucket4j + Redis

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/security/RateLimitFilter.java`
- Create: `api/src/main/java/org/jphototagger/api/config/RateLimitConfig.java`

**Step 1: Write failing test — rate limit enforced [M8]**

Tests use a test-specific rate limit configuration with low limits to avoid sending hundreds of requests:

```java
// application-test.yml: app.rate-limit.upload=3, app.rate-limit.general=5

@Test
void uploadRateLimitRejects4thUploadInTestProfile() {
    // Setup: authenticated user
    // Act: send 3 upload requests (all succeed), send 4th
    // Assert: 4th returns 429 Too Many Requests
}

@Test
void generalRateLimitRejects6thRequestInTestProfile() {
    // Setup: authenticated user
    // Act: send 5 GET /photos requests (all succeed), send 6th
    // Assert: 6th returns 429 Too Many Requests
}
```

**Step 2: Implement Bucket4j filter**

Per-user token buckets stored in Redis. Production limits: 100 uploads/hour, 1000 general requests/hour. Test limits configured via properties for fast test execution.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/ api/src/main/java/org/jphototagger/api/config/ api/src/test/
git commit -m "feat: Bucket4j rate limiting with Redis backend"
```

---

**Next Phase:** [Phase 3: Storage & Media — MinIO, Upload Pipeline, Worker](2026-02-25-saas-conversion-phase-3.md)
