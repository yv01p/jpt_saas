# JPhotoTagger SaaS Conversion — Phase 2: Backend API — Auth, Security, REST Endpoints

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

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

Map each entity to the Flyway schema tables. Use `@Entity`, `@Table`, `@Id`, `@GeneratedValue(strategy = GenerationType.UUID)`. Include all columns from V1 migration. Mark `search_vector` as `@Column(insertable = false, updatable = false)` (generated column).

**Step 4: Run test to verify it passes**

Run: `./gradlew :api:test --tests UserEntityTest`
Expected: PASS

**Step 5: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/entity/
git commit -m "feat: JPA entities for all tables"
```

### Task 2.2: Spring Repositories (Spring Data JPA)

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/repository/UserRepository.java`
- Create: `api/src/main/java/org/jphototagger/api/repository/PhotoRepository.java`
- Create: `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java`
- Create: `api/src/main/java/org/jphototagger/api/repository/AlbumRepository.java`
- Create: `api/src/main/java/org/jphototagger/api/repository/ShareRepository.java`

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
        // setup user, insert active + deleted photos
        // verify only active photos returned
    }

    @Test
    void fullTextSearch_matchesCaption() {
        // setup user, insert photo with caption "sunset over mountains"
        // search for "sunset" — should find it
    }
}
```

**Step 2: Implement repositories with custom queries**

```java
public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    List<Photo> findByUserIdAndDeletedAtIsNullOrderByUploadedAtDesc(UUID userId);

    List<Photo> findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(UUID userId);

    @Query("SELECT p FROM Photo p WHERE p.userId = :userId AND p.deletedAt IS NULL " +
           "AND FUNCTION('to_tsvector', 'english', " +
           "COALESCE(p.filename,'') || ' ' || COALESCE(p.title,'') || ' ' || " +
           "COALESCE(p.caption,'') || ' ' || COALESCE(p.description,'')) " +
           "@@ FUNCTION('plainto_tsquery', 'english', :query)")
    List<Photo> searchByText(@Param("userId") UUID userId, @Param("query") String query);

    Optional<Photo> findByUserIdAndContentHash(UUID userId, String contentHash);
}
```

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/repository/ api/src/test/
git commit -m "feat: Spring Data JPA repositories with search queries"
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

### Task 2.4: Spring Security Configuration

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`
- Create: `api/src/main/java/org/jphototagger/api/security/JwtAuthenticationFilter.java`
- Create: `api/src/main/java/org/jphototagger/api/security/RlsFilter.java`

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
- `RlsFilter` sets `SET LOCAL app.current_user_id = ?` on each request transaction
- Public paths: `/auth/**`, `/share/**`, `/actuator/health`

**Step 3: Implement JwtAuthenticationFilter**

Extract JWT from cookie, validate, set SecurityContext.

**Step 4: Implement RlsFilter**

After authentication, execute `SET LOCAL app.current_user_id = ?` within the transaction.

**Step 5: Run tests, verify pass**

**Step 6: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/ api/src/test/
git commit -m "feat: Spring Security config — JWT, CSRF, RLS filter"
```

### Task 2.5: Auth Controller — Registration & Login

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/controller/AuthController.java`
- Create: `api/src/main/java/org/jphototagger/api/service/AuthService.java`
- Create: `api/src/main/java/org/jphototagger/api/dto/RegisterRequest.java`
- Create: `api/src/main/java/org/jphototagger/api/dto/LoginRequest.java`

**Step 1: Write failing test — registration**

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
void loginLocksAccountAfter5Failures() throws Exception {
    // Register user, then fail login 5 times
    // 6th attempt should return 423 Locked
}
```

**Step 2: Implement AuthService**

- Password validation: >= 12 chars
- bcrypt cost factor 12
- Account lockout: 5 failures → 15 min lock
- Email verification token generation (SHA-256 stored, plaintext emailed)

**Step 3: Implement AuthController**

- `POST /auth/register` — creates user, sends verification email
- `POST /auth/login` — validates credentials, issues JWT in httpOnly cookie
- `POST /auth/refresh` — refresh token flow via Redis
- `POST /auth/logout` — clears cookie, revokes refresh token

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/controller/ api/src/main/java/org/jphototagger/api/service/ api/src/main/java/org/jphototagger/api/dto/ api/src/test/
git commit -m "feat: auth endpoints — register, login, refresh, logout"
```

### Task 2.6: OAuth2 Integration (Google/GitHub)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`
- Create: `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java`

**Step 1: Write failing test — OAuth2 does not auto-merge**

```java
@Test
void oauthLoginBlocksIfEmailExists() {
    // Pre-create user with email
    // Simulate OAuth callback with same email
    // Verify login is blocked with appropriate message
}
```

**Step 2: Implement OAuth2SuccessHandler**

- On success: create user if new, issue JWT
- If email already exists with password account: block login, show linking message
- Never auto-merge by email

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

**Step 1: Write failing tests**

```java
@Test
void listPhotos_returnsOnlyUsersPhotos() { }

@Test
void getPhoto_returns404ForOtherUsersPhoto() { }

@Test
void deletePhoto_softDeletes() { }

@Test
void getPhotoStatus_returnsProcessingStatus() { }

@Test
void trashView_returnsDeletedPhotos() { }

@Test
void restorePhoto_clearsDeletedAt() { }
```

**Step 2: Implement PhotoController**

- `GET /photos` — list active photos (deleted_at IS NULL)
- `GET /photos/{id}` — single photo with ownership check
- `GET /photos/{id}/status` — processing status polling
- `DELETE /photos/{id}` — soft delete + quota decrement
- `GET /photos/trash` — trash view
- `POST /photos/{id}/restore` — restore from trash + quota re-increment

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/controller/ api/src/main/java/org/jphototagger/api/service/ api/src/test/
git commit -m "feat: photo CRUD endpoints with soft delete and trash"
```

### Task 2.8: Keyword, Album, Search, SavedSearch Endpoints

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/controller/KeywordController.java`
- Create: `api/src/main/java/org/jphototagger/api/controller/AlbumController.java`
- Create: `api/src/main/java/org/jphototagger/api/controller/SearchController.java`
- Create corresponding services

**Step 1: Write failing tests for each controller**

**Step 2: Implement controllers**

- Keywords: CRUD + hierarchical tree (recursive CTE)
- Albums: CRUD + add/remove photos (composite FK enforcement)
- Search: full-text search + EXIF field queries + keyword search
- Saved searches: CRUD

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: keyword, album, search, saved search endpoints"
```

### Task 2.9: Rate Limiting — Bucket4j + Redis

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/security/RateLimitFilter.java`
- Create: `api/src/main/java/org/jphototagger/api/config/RateLimitConfig.java`

**Step 1: Write failing test — rate limit enforced**

```java
@Test
void uploadRateLimitRejects101stUploadInHour() { }

@Test
void generalRateLimitRejects1001stRequestInHour() { }
```

**Step 2: Implement Bucket4j filter**

Per-user token buckets stored in Redis. 100 uploads/hour, 1000 general requests/hour.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: Bucket4j rate limiting with Redis backend"
```

---

**Next Phase:** [Phase 3: Storage & Media — MinIO, Upload Pipeline, Worker](2026-02-25-saas-conversion-phase-3.md)
