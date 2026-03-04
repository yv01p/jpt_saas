# JPhotoTagger SaaS Conversion — Phase 2: Backend API — Auth, Security, REST Endpoints

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

**Version:** 4.0
**Date:** 2026-03-04
**Status:** Approved

---

## Changelog

### v4.0 — 2026-03-04

Revisions following Critical Implementation Review v3 (`docs/plans/2026-02-25-saas-conversion-phase-2-critical-review-3.md`) and Security Audit v1 (`docs/plans/2026-03-04-saas-conversion-phase-2-security-audit-1.md`):

- **[CR3-C1 / SA-1] `SET LOCAL` does not support parameterized queries:** `RlsAspect` used `SET LOCAL ... = :id` which PostgreSQL rejects (`ERROR: syntax error at or near "$1"`). Fix: replaced with `set_config('app.current_user_id', :id, true)` — a regular SQL function that accepts bind parameters and is functionally identical to `SET LOCAL` (transaction-scoped). Updated Task 2.4.
- **[CR3-C2 / SA-2] `RlsAspect` `@Order` undefined — cross-tenant data leak:** Both `RlsAspect` and `TransactionInterceptor` defaulted to `Ordered.LOWEST_PRECEDENCE`. If `RlsAspect` fired before the transaction opened, `set_config(..., true)` executed outside a transaction (session-scoped), leaking user context to the connection pool. Fix: added `@EnableTransactionManagement(order = 0)` and `@Order(1)` on `RlsAspect`. Added cross-request tenant isolation integration test. Updated Task 2.4.
- **[CR3-C3 / SA-3] V4 Flyway migration literal password:** `CREATE ROLE jpt_auth ... PASSWORD 'SET_VIA_SECRETS'` was a literal string, not a Flyway placeholder. Fix: replaced with `'${jpt_auth_password}'` and added `spring.flyway.placeholders.jpt_auth_password` config. Updated Tasks 2.0 and 2.4.
- **[CR3-C4] Missing Testcontainers Redis module:** Refresh token and rate limiting tests require Redis but no Testcontainers Redis dependency existed. Fix: added `org.testcontainers:redis` dependency and shared `TestRedisConfig` with `@ServiceConnection`. Updated Task 2.0.
- **[SA-4] `jpt_auth` role grants overly broad:** `GRANT UPDATE ON users` allowed modifying any column (quota, role). Fix: replaced with column-level `GRANT UPDATE` restricted to auth-relevant columns only. Added missing `DELETE ON email_tokens`. Added `ALTER DEFAULT PRIVILEGES` for sequences. Updated Task 2.4.
- **[SA-7] No login counter reset on successful login:** Failed login counter was never reset, creating a persistent near-lockout state. Fix: on successful authentication, reset `failed_login_attempts = 0` and `locked_until = NULL`. Updated Task 2.5.
- **[SA-8] Refresh token family tracking unused:** The `family` field was stored in Redis but never checked during rotation. Fix: implemented family-based replay detection — if a consumed token's family is replayed, all tokens in that family are revoked and a security event is logged. Updated Task 2.5.
- **[SA-9] `RlsContext` ThreadLocal not cleared in error paths:** If a Servlet container error bypassed `afterCompletion()`, the ThreadLocal leaked to the next request on the same thread. Fix: added `RlsContextCleanupFilter` (Servlet Filter, `@Order(HIGHEST_PRECEDENCE)`) that clears `RlsContext` in a `finally` block. Updated Task 2.4.
- **[SA-6] JWT secret — no startup validation:** Dev profile fallback secret could be active in production if `SPRING_PROFILES_ACTIVE` was unset. Fix: added `@PostConstruct` validation in `JwtService` that fails fast if secret is too short or contains default marker in non-dev/test profiles. Updated Task 2.3.
- **[CR3-M2] `EntityManager` may not be transaction-bound in `RlsAspect`:** `em.createNativeQuery()` could obtain a different connection than subsequent Hibernate queries. Fix: replaced with `em.unwrap(Session.class).doWork(connection -> ...)` to guarantee same JDBC connection. Updated Task 2.4.
- **[CR3-M3] `RlsAspect` pointcut matches auth service methods unnecessarily:** Narrowed pointcut to exclude `AuthService` and `RefreshTokenService`. Updated Task 2.4.
- **[CR3-M4] `GRANT USAGE ON ALL SEQUENCES` won't cover future migrations:** Added `ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE ON SEQUENCES TO jpt_auth`. Updated Task 2.4.
- **[CR3-M5] Bucket4j Redis artifact may need Lettuce module:** Added verification note — Spring Boot ships Lettuce by default; confirm correct Bucket4j module (`bucket4j-lettuce` if needed). Updated Task 2.9.
- **[CR3-M6] Missing `@Transactional` on multi-step service methods:** `PhotoService.softDelete()`, `PhotoService.restore()`, and `AlbumService.addPhoto()` must be explicitly `@Transactional`. Updated Tasks 2.7 and 2.8.
- **[SA-5] Native query + Pageable Sort injection:** Spring Data JPA doesn't validate sort properties for native queries. Fix: controllers calling native queries use `PageRequest.of(page, size)` without Sort; `ORDER BY` is hardcoded in the native query. Updated Tasks 2.2 and 2.8.
- **[SA-12] Missing `Secure` and `SameSite` cookie attributes:** JWT and refresh token cookies lacked `Secure`, `SameSite`, and `Path` attributes. Fix: use `ResponseCookie` builder with `secure(true)`, `sameSite("Lax")`, `path("/")`. Updated Task 2.5.
- **[SA-10] Actuator health exposed without rate limiting or hardening:** Added explicit actuator config (`show-details: never`, `exposure.include: health` only). Added Nginx rate limiting for `/actuator/health`. Updated Tasks 2.0 and 2.4.
- **[SA-11] No password breach database check:** Deferred to future hardening phase. 12-char minimum + bcrypt 12 + lockout provides strong baseline per NIST 800-63B.

### v3.0 — 2026-03-04

Revisions following Critical Implementation Review v2 (`docs/plans/2026-02-25-saas-conversion-phase-2-critical-review-2.md`):

- **[C1] RLS blocks all auth operations:** Login, registration, and email verification fail because RLS on the `users` table filters by `app.current_user_id` (nil UUID for unauthenticated requests). Fix: added V4 Flyway migration creating a dedicated `jpt_auth` role with `BYPASSRLS`, a secondary `authDataSource` bean, and injection into `AuthService`, `RefreshTokenService`, and `OAuth2SuccessHandler`. Added to Task 2.4.
- **[C2] StatementInspector prepend fails with JDBC extended query protocol:** PostgreSQL's JDBC driver uses the extended query protocol, which only allows one statement per `Parse` message. Multi-statement SQL string causes `ERROR: cannot insert multiple commands into a prepared statement`. Fix: replaced `RlsStatementInspector` with `RlsAspect` — an AOP `@Aspect` that intercepts `@Transactional` methods and executes `SET LOCAL` as a separate native query. Also calls `assert_user_context()` after `SET LOCAL` (addresses M2).
- **[C3] Missing Gradle dependencies:** `spring-boot-starter-security`, jjwt, OAuth2, Bucket4j, `spring-security-test`, and Testcontainers Redis module were all absent. Fix: added new Task 2.0 with all dependency additions and compile verification.
- **[C4] CSRF token unavailable to SPA post-login:** Spring Security 6's deferred CSRF resolution doesn't set `XSRF-TOKEN` cookie on CSRF-exempt endpoints (login/register). Fix: adopted `SpaCsrfTokenRequestHandler` in SecurityConfig (Task 2.4).
- **[C5] Join table entities with composite PKs unspecified:** `album_photos` and `photo_keywords` have composite PKs + `user_id` column, cannot use `@ManyToMany`. Fix: added `AlbumPhoto`, `AlbumPhotoId`, `PhotoKeyword`, `PhotoKeywordId` entities with `@IdClass` to Task 2.1.
- **[M1] No `application-test.yml`:** Tests use `@ActiveProfiles("test")` but no test profile config exists. Fix: added to Task 2.0.
- **[M2] `assert_user_context()` never called:** Fix: `RlsAspect` now calls `SELECT assert_user_context()` after `SET LOCAL` (see C2).
- **[M3] `/share/**` public path without controller:** Removed from SecurityConfig public paths. Re-added in Phase 5, Task 5.1 when `ShareController` is implemented.
- **[M4] Soft-deleted photos block re-upload:** Partial unique index fix deferred to Phase 3, Task 3.2 (V4 Flyway migration).
- **[M5] `email_verified` soft-gating:** Upload gating deferred to Phase 3, Task 3.2. 7-day auto-purge added to Phase 3, Task 3.6.
- **[M6] `updated_at` columns not auto-managed:** Added `@UpdateTimestamp` annotation requirement to Task 2.1 entity notes.
- **[M7] No error response DTO:** Added `ErrorResponse` record to Task 2.4.
- **[M8] OAuth2 test mocking strategy unclear:** Specified `oidcLogin()` from `spring-security-test` as the mocking strategy in Task 2.6.
- **[Q3] Phase 2 tests seed photos via `EntityManager`:** No upload endpoint until Phase 3. Tests insert data directly.

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

### Task 2.0: Gradle Dependencies & Test Configuration

**Files:**
- Modify: `api/build.gradle.kts`
- Create: `api/src/test/resources/application-test.yml`
- Create: `api/src/test/java/org/jphototagger/api/config/TestRedisConfig.java` **[v4 CR3-C4]**

**Step 1: Add all Phase 2 dependencies to `api/build.gradle.kts`**

```kotlin
// Security
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

// JWT
implementation("io.jsonwebtoken:jjwt-api:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

// Rate limiting — verify correct module for Lettuce (Spring Boot default) [v4 CR3-M5]
// If bucket4j-redis is Jedis-only, use bucket4j-lettuce instead
implementation("com.bucket4j:bucket4j-redis:8.14.0")

// Test
testImplementation("org.springframework.security:spring-security-test")
testImplementation("org.testcontainers:redis:1.20.6")  // [v4 CR3-C4]
```

**Step 2: Create `application-test.yml`**

```yaml
# api/src/test/resources/application-test.yml
app:
  jwt-secret: "test-secret-key-minimum-256-bits-for-hs256-signing"
  jwt-expiry-minutes: 15
  refresh-token-expiry-days: 1  # short TTL for expiry tests
  rate-limit:
    upload: 3       # low limits for fast test execution
    general: 5

spring:
  datasource:
    url: jdbc:tc:postgresql:16:///testdb
  auth-datasource:                          # [v4 CR3-C3]
    url: jdbc:tc:postgresql:16:///testdb
    username: jpt_auth
    password: test_auth_password
  flyway:
    placeholders:
      jpt_auth_password: test_auth_password  # [v4 CR3-C3]
  data:
    redis:
      host: localhost  # Testcontainers manages this

# [v4 SA-10] Restrict actuator exposure
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
```

**Step 3: Create shared `TestRedisConfig` [v4 CR3-C4]**

```java
// api/src/test/java/org/jphototagger/api/config/TestRedisConfig.java
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

**Step 4: Verify compilation**

Run: `./gradlew :api:compileJava :api:compileTestJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add api/build.gradle.kts api/src/test/resources/application-test.yml api/src/test/java/org/jphototagger/api/config/TestRedisConfig.java
git commit -m "build: add Phase 2 dependencies — security, JWT, OAuth2, Bucket4j, Testcontainers Redis, test config"
```

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
- Create: `api/src/main/java/org/jphototagger/api/entity/AlbumPhoto.java` **[v3 C5]**
- Create: `api/src/main/java/org/jphototagger/api/entity/AlbumPhotoId.java` **[v3 C5]**
- Create: `api/src/main/java/org/jphototagger/api/entity/PhotoKeyword.java` **[v3 C5]**
- Create: `api/src/main/java/org/jphototagger/api/entity/PhotoKeywordId.java` **[v3 C5]**

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
- **AlbumPhoto / PhotoKeyword (join tables) [v3 C5]:** These tables have composite PKs and an extra `user_id` column (required for RLS), so they cannot use `@ManyToMany`. Define explicit `@Entity` classes with `@IdClass`:
  ```java
  @Entity @Table(name = "album_photos") @IdClass(AlbumPhotoId.class)
  public class AlbumPhoto {
      @Id private UUID albumId;
      @Id private UUID photoId;
      private UUID userId;  // set from authenticated user context, never from request input
  }
  public class AlbumPhotoId implements Serializable { UUID albumId; UUID photoId; }
  ```
  Same pattern for `PhotoKeyword` / `PhotoKeywordId`. The composite FK constraint (`album_photos(album_id, user_id) → albums(id, user_id)`) is enforced at the Flyway/DB level only, not in JPA. **[M6]**
- **`updated_at` columns [v3 M6]:** All entities with `updated_at` fields (`User`, `Photo`, `Keyword`, `Album`, `Share`) must annotate the field with `@UpdateTimestamp` (Hibernate, already available via `spring-boot-starter-data-jpa`). This ensures `updated_at` is set automatically on every flush.

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

**IMPORTANT [v4 SA-5]:** All native `@Query` methods that accept `Pageable` must hardcode `ORDER BY` in the SQL and receive `Pageable` without `Sort`. Controllers must construct `PageRequest.of(page, Math.min(size, 100))` — never pass user-supplied sort properties to native queries. Spring Data JPA does not validate sort properties for native queries, creating a SQL injection vector.

```java
public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    Page<Photo> findByUserIdAndDeletedAtIsNullOrderByUploadedAtDesc(
        UUID userId, Pageable pageable);  // [C2]

    Page<Photo> findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
        UUID userId, Pageable pageable);  // [C2]

    @Query(value = "SELECT * FROM photos WHERE user_id = :userId AND deleted_at IS NULL " +
           "AND search_vector @@ plainto_tsquery('english', :query) " +
           "ORDER BY uploaded_at DESC",  // [v4 SA-5] — hardcoded ORDER BY, no Sort from Pageable
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

@Test
void startupFailsWithDefaultSecretInProdProfile() {
    // [v4 SA-6] Verify @PostConstruct validation rejects weak/default secrets
}
```

**Step 2: Implement JwtService**

Use `io.jsonwebtoken:jjwt`. HS256 signing with configurable secret from `app.jwt-secret`. 15-minute expiry from `app.jwt-expiry-minutes`.

**[v4 SA-6] Startup validation:** Add `@PostConstruct` method that fails fast if the JWT secret is inadequate in non-dev/test profiles:

```java
@PostConstruct
void validateSecret() {
    if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
        if (jwtSecret.length() < 43) {
            throw new IllegalStateException("JWT_SECRET must be >= 256 bits (43+ base64 chars)");
        }
        if (jwtSecret.contains("change-me")) {
            throw new IllegalStateException("Default JWT_SECRET detected in non-dev profile");
        }
    }
}
```

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/ api/src/test/
git commit -m "feat: JWT service — token generation, validation, and startup secret check"
```

### Task 2.4: Spring Security Configuration + Global Exception Handler

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`
- Create: `api/src/main/java/org/jphototagger/api/security/JwtAuthenticationFilter.java`
- Create: `api/src/main/java/org/jphototagger/api/security/RlsInterceptor.java` — stores userId in `ThreadLocal` **[C3]**
- Create: `api/src/main/java/org/jphototagger/api/security/RlsAspect.java` — AOP-based RLS context **[v3 C2]**
- Create: `api/src/main/java/org/jphototagger/api/security/RlsContext.java` — `ThreadLocal` holder **[v3 C2]**
- Create: `api/src/main/java/org/jphototagger/api/security/RlsContextCleanupFilter.java` — Servlet filter for ThreadLocal cleanup **[v4 SA-9]**
- Create: `api/src/main/java/org/jphototagger/api/config/AuthDataSourceConfig.java` — privileged auth DataSource **[v3 C1]**
- Create: `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java` **[C6]**
- Create: `api/src/main/java/org/jphototagger/api/dto/ErrorResponse.java` **[v3 M7]**
- Create: `api/src/main/resources/db/migration/V4__create_jpt_auth_role.sql` **[v3 C1]**
- Modify: Nginx config — add rate limiting for `/actuator/health` **[v4 SA-10]**

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

    @Test
    void rlsContextDoesNotLeakAcrossRequests() throws Exception {
        // [v4 CR3-C2] Cross-request tenant isolation test
        // Request 1: authenticate as userA, GET /photos → returns userA's photos
        // Request 2: authenticate as userB, GET /photos → must return only userB's photos
        // If set_config leaked, request 2 would see userA's photos
    }
}
```

**Step 2: Implement SecurityConfig**

- **`@EnableTransactionManagement(order = 0)`** on `SecurityConfig` — ensures `TransactionInterceptor` wraps first **[v4 CR3-C2]**
- JWT filter reads `jwt` httpOnly cookie
- CSRF enabled with `CookieCsrfTokenRepository.withHttpOnlyFalse()` and `SpaCsrfTokenRequestHandler` **[v3 C4]** — ensures CSRF token cookie is set on every response (including login), and correctly handles `X-XSRF-TOKEN` header from SPAs:
  ```java
  .csrf(csrf -> csrf
      .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
      .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
      .ignoringRequestMatchers("/auth/login", "/auth/register")
  )
  ```
- Public paths: `/auth/**`, `/actuator/health` — **Note:** `/share/**` intentionally removed until Phase 5 when `ShareController` is implemented **[v3 M3]**

**[v4 SA-10] Actuator hardening:** Add to `application.yml`:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
```

**Step 3: Implement JwtAuthenticationFilter**

Extract JWT from cookie, validate, set SecurityContext.

**Step 4: Implement RlsContextCleanupFilter [v4 SA-9]**

Servlet `Filter` registered at `@Order(Ordered.HIGHEST_PRECEDENCE)` that clears `RlsContext` in a `finally` block, guaranteeing cleanup regardless of where in the chain an error occurs:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RlsContextCleanupFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(req, res);
        } finally {
            RlsContext.clear();
        }
    }
}
```

The existing `afterCompletion()` cleanup in `RlsInterceptor` remains as belt-and-suspenders.

**Step 5: Flyway migration — `jpt_auth` role [v3 C1]**

Create `V4__create_jpt_auth_role.sql`:
```sql
CREATE ROLE jpt_auth WITH LOGIN PASSWORD '${jpt_auth_password}' BYPASSRLS;  -- [v4 CR3-C3] Flyway placeholder
GRANT CONNECT ON DATABASE jpt TO jpt_auth;
GRANT USAGE ON SCHEMA public TO jpt_auth;

-- [v4 SA-4] Column-level grants — principle of least privilege
GRANT SELECT, INSERT ON users TO jpt_auth;
GRANT UPDATE (password_hash, failed_login_attempts, locked_until, email_verified,
              oauth_provider, oauth_id) ON users TO jpt_auth;
GRANT SELECT, INSERT, DELETE ON email_tokens TO jpt_auth;

GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO jpt_auth;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE ON SEQUENCES TO jpt_auth;  -- [v4 CR3-M4]
```

This role bypasses RLS for authentication operations (login, registration, email verification, OAuth2) while `jpt_app` remains RLS-governed for all tenant-scoped data.

**Step 6: Implement Auth DataSource [v3 C1]**

```java
// AuthDataSourceConfig.java
@Configuration
public class AuthDataSourceConfig {
    @Bean("authDataSource")
    @ConfigurationProperties("spring.auth-datasource")
    public DataSource authDataSource() { return DataSourceBuilder.create().build(); }

    @Bean("authJdbcTemplate")
    public JdbcTemplate authJdbcTemplate(@Qualifier("authDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
```

Configure in `application.yml`:
```yaml
spring:
  auth-datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: jpt_auth
    password: ${JPT_AUTH_PASSWORD}
  flyway:
    placeholders:
      jpt_auth_password: ${JPT_AUTH_PASSWORD}  # [v4 CR3-C3]
```

**Step 7: Implement RLS — HandlerInterceptor + AOP Aspect [v3 C2, v4 CR3-C1/C2/M2/M3]**

The RLS mechanism uses two components:

1. **`RlsInterceptor`** (Spring `HandlerInterceptor`): After authentication, extracts the authenticated user ID from `SecurityContext` and stores it in `RlsContext` (a `ThreadLocal<UUID>` holder). Clears the `ThreadLocal` in `afterCompletion()`.

2. **`RlsAspect`** (Spring AOP `@Aspect`): Intercepts `@Transactional` methods and uses `set_config()` via `Session.doWork()` to guarantee the same JDBC connection:

   ```java
   @Aspect
   @Component
   @Order(1)  // [v4 CR3-C2] Fires AFTER TransactionInterceptor (order=0) opens the transaction
   public class RlsAspect {
       @Autowired private EntityManager em;

       // [v4 CR3-M3] Exclude auth services — they use jpt_auth (BYPASSRLS), not RLS
       @Before("@annotation(org.springframework.transaction.annotation.Transactional) " +
               "&& !within(org.jphototagger.api.service.AuthService) " +
               "&& !within(org.jphototagger.api.service.RefreshTokenService)")
       public void setRlsContext() {
           UUID userId = RlsContext.getCurrentUserId();
           if (userId != null) {
               // [v4 CR3-C1] Use set_config() — SET LOCAL doesn't accept bind parameters
               // [v4 CR3-M2] Use Session.doWork() — guarantees same JDBC connection
               em.unwrap(Session.class).doWork(connection -> {
                   try (var stmt = connection.prepareStatement(
                           "SELECT set_config('app.current_user_id', ?, true)")) {
                       stmt.setString(1, userId.toString());
                       stmt.execute();
                   }
                   // [v3 M2] Fail fast if nil UUID
                   try (var stmt = connection.prepareStatement("SELECT assert_user_context()")) {
                       stmt.execute();
                   }
               });
           }
       }
   }
   ```

   **WARNING:** Never use string concatenation for `set_config` values. Always use parameterized queries. `UUID.toString()` is safe, but the pattern must not be adapted for non-UUID inputs.

This guarantees `set_config` always executes within the transaction boundary on the same JDBC connection as subsequent queries. Transaction-scoped (auto-reset on commit/rollback). No risk of cross-tenant connection pool leakage.

**Step 8: Implement GlobalExceptionHandler + ErrorResponse [C6, v3 M7]**

```java
// ErrorResponse.java [v3 M7]
public record ErrorResponse(String error, int status) {}

@ControllerAdvice
public class GlobalExceptionHandler {
    // All responses use ErrorResponse record for consistent JSON: {"error": "message", "status": 400}
    // 400 — MethodArgumentNotValidException, ConstraintViolationException
    // 401 — AuthenticationException (generic "Invalid credentials")
    // 404 — EntityNotFoundException
    // 409 — DataIntegrityViolationException (content_hash duplicate)
    // 413 — QuotaExceededException (custom)
    // 423 — AccountLockedException (custom) — NOT used by login (see C7)
    // 429 — RateLimitExceededException (custom)
}
```

**Step 9: Nginx rate limiting for `/actuator/health` [v4 SA-10]**

Add to existing Nginx configuration:

```nginx
# Rate limit zone for health endpoint
limit_req_zone $binary_remote_addr zone=health:1m rate=30r/m;

# In server block:
location /actuator/health {
    limit_req zone=health burst=5 nodelay;
    proxy_pass http://api:8080;
}
```

**Step 10: Run tests, verify pass**

**Step 11: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/ api/src/main/java/org/jphototagger/api/config/AuthDataSourceConfig.java api/src/main/java/org/jphototagger/api/controller/ api/src/main/java/org/jphototagger/api/dto/ErrorResponse.java api/src/main/resources/db/migration/V4__create_jpt_auth_role.sql nginx/ api/src/test/
git commit -m "feat: Spring Security config — JWT, CSRF (SPA handler), RLS aspect (set_config, ordered), auth DataSource, exception handler, RLS cleanup filter, actuator hardening"
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
        .andExpect(cookie().httpOnly("jwt", true))
        .andExpect(cookie().secure("jwt", true));  // [v4 SA-12]
}

@Test
void loginReturnsGeneric401ForLockedAccount() throws Exception {
    // Register user, fail login 5 times with wrong password
    // 6th attempt (even with correct password): returns 401 "Invalid credentials"
    // NOT 423 — no distinction between wrong-password and locked [C7]
}

@Test
void successfulLoginResetsFailedAttemptCounter() throws Exception {
    // [v4 SA-7] Register user, fail login 3 times, then succeed
    // Assert: failed_login_attempts == 0 after successful login
    // Next wrong attempt should NOT lock the account (counter was reset)
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
void replayOfConsumedTokenRevokesEntireFamily() throws Exception {
    // [v4 SA-8] Login (family A created), capture refresh token T1
    // Refresh with T1 → get T2 (T1 consumed, T2 in family A)
    // Replay T1 (consumed token) → 401 AND all tokens in family A revoked
    // Attempt refresh with T2 → 401 (revoked by family kill)
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

- **Uses `@Qualifier("authJdbcTemplate")` or `@Qualifier("authDataSource")` for all user lookups and creation** — bypasses RLS on the `users` table **[v3 C1]**
- Password validation: >= 12 chars (enforced by `@Size` on DTO + `@Valid` on controller)
- bcrypt cost factor 12
- Account lockout: 5 failures → 15 min lock
- **Timing side-channel mitigation [C7]:** Always perform `BCrypt.checkpw()` regardless of lockout status. After comparison, check `failedLoginAttempts >= 5 && lockedUntil > now()` — if locked, return generic `401 "Invalid credentials"` (same as wrong password). Never return a distinct status code or message for locked accounts.
- **[v4 SA-7] Login counter reset:** On successful authentication (correct password AND not locked), reset `failed_login_attempts = 0` and `locked_until = NULL`.
- Email verification token generation (SHA-256 stored, plaintext emailed via `EmailService`)

**Step 4: Implement RefreshTokenService [C5, v4 SA-8]**

- **Token format:** 256-bit cryptographically random value, base64url-encoded
- **Redis key schema:** `refresh:{SHA-256(token)}` → JSON `{"userId": "...", "issuedAt": "...", "family": "..."}`
- **User token set:** `user_refresh:{userId}` → Redis Set of all active token hashes for the user
- **TTL:** 30 days (configurable via `app.refresh-token-expiry-days`)
- **Rotation:** On `POST /auth/refresh`:
  1. Validate incoming token (lookup `refresh:{SHA-256(token)}` in Redis)
  2. **If token hash is not found but was previously part of a known family** → replay detected: revoke ALL tokens in that family (read `user_refresh:{userId}`, filter by family, delete each `refresh:{hash}` key, log security event). Return 401. **[v4 SA-8]**
  3. Delete old token key from Redis
  4. Remove old hash from `user_refresh:{userId}` set
  5. Generate new refresh token + new JWT, **preserving the same `family` ID** **[v4 SA-8]**
  6. Store new token key and add hash to user set
  7. Return both as httpOnly cookies
- **Family tracking implementation [v4 SA-8]:** Maintain a Redis Set `refresh_family:{familyId}` containing all token hashes ever issued in the family (including consumed ones). On rotation, add the new hash and keep the old hash in the family set. On replay detection (token not in `refresh:*` but found in `refresh_family:*`), revoke the entire family.
- **Revocation on logout:** Delete token key from Redis, remove from user set
- **Revocation on password change:** Read all hashes from `user_refresh:{userId}`, delete each `refresh:{hash}` key, then delete the set itself

**Step 5: Implement EmailService [M3]**

- `EmailService` interface with `sendVerificationEmail(String to, String token)` and `sendPasswordResetEmail(String to, String token)`
- `StubEmailService` implementation (annotated `@Profile("dev | test")`) that logs the email content to the application log. Real SMTP implementation deferred to a later phase.

**Step 6: Implement AuthController**

- `POST /auth/register` — validates with `@Valid`, creates user, sends verification email
- `POST /auth/login` — validates credentials (always bcrypt), issues JWT + refresh token in httpOnly cookies
- `POST /auth/refresh` — refresh token rotation flow via `RefreshTokenService` (with family-based replay detection)
- `POST /auth/logout` — clears cookies, revokes refresh token in Redis

**[v4 SA-12] Cookie attributes:** All cookies must use `ResponseCookie` builder with full security attributes:

```java
ResponseCookie jwt = ResponseCookie.from("jwt", token)
    .httpOnly(true)
    .secure(true)        // HTTPS only
    .sameSite("Lax")     // prevent cross-origin cookie sending
    .path("/")
    .maxAge(Duration.ofMinutes(15))
    .build();

ResponseCookie refresh = ResponseCookie.from("refresh", refreshToken)
    .httpOnly(true)
    .secure(true)
    .sameSite("Lax")
    .path("/auth/refresh")  // scoped to refresh endpoint only
    .maxAge(Duration.ofDays(30))
    .build();
```

Add test assertions for `Secure` and `SameSite` attributes on both cookies.

**Step 7: Run tests, verify pass**

**Step 8: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/controller/ api/src/main/java/org/jphototagger/api/service/ api/src/main/java/org/jphototagger/api/dto/ api/src/test/
git commit -m "feat: auth endpoints — register, login, refresh, logout with token rotation and family replay detection"
```

### Task 2.6: OAuth2 Integration (Google/GitHub)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`
- Create: `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java`

**Step 1: Write failing tests [M7 — expanded coverage]**

All OAuth2 tests use `SecurityMockMvcRequestPostProcessors.oidcLogin()` from `spring-security-test` to simulate OAuth2 callbacks without hitting real providers. **[v3 M8]**

```java
@Test
void oauthLoginCreatesNewUser() {
    // Simulate OAuth callback with oidcLogin().oidcUser(mockUser) — new email
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

- **Uses `@Qualifier("authJdbcTemplate")` for user lookups and creation** — bypasses RLS **[v3 C1]**
- On success: create user if new (set `oauthProvider`, `oauthId`), issue JWT + refresh token
- If email already exists with password account: block login, show linking message
- Never auto-merge by email
- Handle missing email from provider gracefully
- **[v4 SA-12]** Use `ResponseCookie` builder with `secure(true)`, `sameSite("Lax")`, `path("/")` for JWT cookie, same as `AuthController`.

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

All photo tests seed data directly via `EntityManager` — no upload endpoint exists until Phase 3. **[v3 Q3]**

```java
@Test
void listPhotos_returnsOnlyUsersPhotos() {
    // Setup: create 2 users, each with 2 photos (via EntityManager)
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

**[v4 CR3-M6] `@Transactional` requirement:** `PhotoService.softDelete()` and `PhotoService.restore()` must be explicitly annotated `@Transactional` to ensure atomicity of the soft delete/restore + quota update operations and to trigger the `RlsAspect` for the full operation.

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

**[v4 SA-5] Sort injection prevention:** All controllers calling native queries must construct `PageRequest.of(page, Math.min(size, 100))` without Sort. Native queries must hardcode `ORDER BY`.

- **Keywords:** CRUD + hierarchical subtree query. Subtree query uses `@Query(nativeQuery = true)` with `WITH RECURSIVE` CTE — JPQL does not support recursive CTEs. **[M5]**
- **Albums:** CRUD + add/remove photos. Cross-tenant isolation enforced by the Flyway composite FK constraint (`album_photos(album_id, user_id) → albums(id, user_id)` and `(photo_id, user_id) → photos(id, user_id)`). JPA entities use simple `@ManyToOne`; the composite FK is DB-level only. **[M6]**
- **Search:** Full-text search (via `PhotoRepository.searchByText` native query) + EXIF field queries (native queries with JSONB operators) + keyword search (join through `photo_keywords`)
- **Saved searches:** CRUD

**[v4 CR3-M6] `@Transactional` requirement:** `AlbumService.addPhoto()` and any other multi-step service methods must be explicitly annotated `@Transactional`.

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

**[v4 CR3-M5] Bucket4j module verification:** Spring Boot ships Lettuce as the default Redis client. Verify that `com.bucket4j:bucket4j-redis:8.14.0` works with Lettuce. If it requires Jedis, switch to `com.bucket4j:bucket4j-lettuce:8.14.0` or the appropriate Lettuce-compatible module.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/ api/src/main/java/org/jphototagger/api/config/ api/src/test/
git commit -m "feat: Bucket4j rate limiting with Redis backend"
```

---

**Next Phase:** [Phase 3: Storage & Media — MinIO, Upload Pipeline, Worker](2026-02-25-saas-conversion-phase-3.md)
