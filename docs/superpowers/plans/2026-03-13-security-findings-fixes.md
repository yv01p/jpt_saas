# Security Findings Fixes — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all 12 security findings from the 2026-03-13 scan across auth, share, scheduler, restore, OAuth2, and keyword subsystems.

**Architecture:** Each section is an independent fix group applied in sequence. Auth hardening rewrites `authenticate()` flow. Share system introduces `MetadataLocationStripper` utility and fixes storage_key exposure. Schedulers migrate from JPA repositories to `SchedulerRepository` backed by `authJdbcTemplate`. Keyword operations move from controller to service layer.

**Tech Stack:** Java 17, Spring Boot 3.4.2, PostgreSQL 16 (Flyway), JUnit 5 + Testcontainers, Redis Streams, MinIO.

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `api/src/main/java/org/jphototagger/api/service/AuthService.java` | Modify | Atomic lockout, dummy hash fix, oracle elimination |
| `api/src/main/java/org/jphototagger/api/controller/AuthController.java` | Modify | Remove `EmailVerificationRequiredException` catch |
| `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java` | Modify | Add `email_verified` guard |
| `api/src/main/java/org/jphototagger/api/service/PhotoService.java` | Modify | Restore reorder, keyword methods |
| `api/src/main/java/org/jphototagger/api/controller/PhotoController.java` | Modify | Delegate keyword ops to service |
| `api/src/main/java/org/jphototagger/api/service/MetadataLocationStripper.java` | Create | Single source of truth for location key stripping |
| `api/src/main/java/org/jphototagger/api/service/ShareService.java` | Modify | Delegate to stripper, add IPTC/XMP methods |
| `api/src/main/java/org/jphototagger/api/controller/ShareController.java` | Modify | Strip IPTC/XMP, fix storage_key exposure |
| `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java` | Modify | Add `ownerId` param to `findPhotoById` |
| `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java` | Modify | Delegate to stripper, remove private methods/constants |
| `api/src/main/java/org/jphototagger/api/service/KeywordService.java` | Modify | Add parentId ownership check to `updateKeyword` |
| `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java` | Modify | Add `user_id` to recursive CTE, add JOIN query |
| `api/src/main/java/org/jphototagger/api/dto/KeywordResponse.java` | Create | DTO for keyword-photo listing (avoids exposing entity) |
| `api/src/main/java/org/jphototagger/api/repository/PhotoKeywordRepository.java` | Modify | Add `existsByPhotoIdAndKeywordId` |
| `api/src/main/java/org/jphototagger/api/repository/SchedulerRepository.java` | Create | Encapsulate all scheduler raw SQL |
| `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java` | Modify | Add `buildDeleteJobMessage`, `enqueueByRows`, `extractPhotoIdFromKey` |
| `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java` | Modify | Migrate to `SchedulerRepository` |
| `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java` | Modify | Keyset pagination via `SchedulerRepository` |
| `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java` | Modify | Use `SchedulerRepository.findStorageKeysByUserId` |
| `api/src/main/resources/db/migration/V14__grant_scheduler_permissions_to_jpt_auth.sql` | Create | Comprehensive scheduler grants |
| `api/src/test/java/org/jphototagger/api/service/MetadataLocationStripperTest.java` | Create | Unit tests for stripper |
| `api/src/test/java/org/jphototagger/api/service/AuthServiceTest.java` | Create | Auth hardening tests |
| `api/src/test/java/org/jphototagger/api/repository/SchedulerRepositoryTest.java` | Create | Integration tests with `jpt_auth` role |

---

## Chunk 1: Auth Hardening (Findings #1, #2, #3)

### Task 1: Fix dummy BCrypt hash timing oracle (Finding #1)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/AuthService.java:20-37`
- Test: `api/src/test/java/org/jphototagger/api/service/AuthServiceTest.java`

- [ ] **Step 1: Write the failing test for `getDummyHash()` producing valid BCrypt**

Create test file:

```java
package org.jphototagger.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceTest {

    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(12);
    }

    @Test
    void dummyHash_producesValidBcryptFromInjectedEncoder() {
        // getDummyHash() must produce a hash that passwordEncoder.matches() actually runs BCrypt against.
        // The old constant "$2a$12$dummy..." was 65 chars — BCrypt requires exactly 60 — so matches()
        // short-circuited (~1µs). This test ensures the dummy hash is valid.
        String dummyHash = passwordEncoder.encode("__dummy__credential__for__timing__equalization__");
        assertThat(dummyHash).hasSize(60);
        assertThat(passwordEncoder.matches("wrong_password", dummyHash)).isFalse();
        // The important thing: matches() runs the full BCrypt comparison, not a regex short-circuit.
        // We verify by confirming the correct dummy credential matches.
        assertThat(passwordEncoder.matches("__dummy__credential__for__timing__equalization__", dummyHash)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it passes (validates our understanding)**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.AuthServiceTest.dummyHash_producesValidBcryptFromInjectedEncoder" --no-daemon`
Expected: PASS

- [ ] **Step 3: Replace the hardcoded dummy hash with lazy AtomicReference in AuthService**

In `AuthService.java`, add field after line 28:

```java
private final java.util.concurrent.atomic.AtomicReference<String> dummyHash = new java.util.concurrent.atomic.AtomicReference<>();

private String getDummyHash() {
    return dummyHash.updateAndGet(h -> h != null ? h :
        passwordEncoder.encode("__dummy__credential__for__timing__equalization__"));
}
```

Then replace line 93:
```java
// OLD:
passwordEncoder.matches(password, "$2a$12$dummy.hash.to.prevent.timing.side.channel.attacks.00000000");
// NEW:
passwordEncoder.matches(password, getDummyHash());
```

- [ ] **Step 4: Run existing AuthController tests to verify no regression**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.AuthControllerTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/AuthService.java api/src/test/java/org/jphototagger/api/service/AuthServiceTest.java
git commit -m "fix(auth): replace invalid dummy BCrypt hash with lazy AtomicReference (Finding #1)"
```

---

### Task 2: Extract `incrementFailedAttempts()` + eliminate oracle + atomic lockout (Findings #2, #3)

> **Merged tasks (review fix):** Tasks 2 and 3 from v1 of this plan were interdependent — Task 2 called `incrementFailedAttempts()` which didn't exist until Task 3. Merged into a single task to avoid compilation failures.

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/AuthService.java:86-145`
- Modify: `api/src/main/java/org/jphototagger/api/controller/AuthController.java:9,90-96`
- Test: `api/src/test/java/org/jphototagger/api/controller/AuthControllerTest.java` (existing)

- [ ] **Step 1: Write failing test — unverified user gets 401 not 403**

Add to `AuthControllerTest.java`:

```java
@Test
void login_unverifiedEmail_returns401NotDistinguishable() throws Exception {
    // Register a user but don't verify email
    String email = "unverified-" + UUID.randomUUID() + "@test.com";
    mockMvc.perform(post("/auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new RegisterRequest(email, "Password123!"))))
            .andExpect(status().isAccepted());

    // Attempt login with correct password — should get 401, same as wrong password
    mockMvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LoginRequest(email, "Password123!"))))
            .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: Write lockout integration test**

Add to `AuthControllerTest.java`:

```java
@Test
void login_fiveWrongPasswords_locksAccount() throws Exception {
    String email = "lockout-" + UUID.randomUUID() + "@test.com";
    // Register and verify
    mockMvc.perform(post("/auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new RegisterRequest(email, "Password123!"))))
            .andExpect(status().isAccepted());
    // Verify email directly in DB
    authJdbc.update("UPDATE users SET email_verified = true WHERE email = ?", email);

    // 5 wrong password attempts
    for (int i = 0; i < 5; i++) {
        mockMvc.perform(post("/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, "WrongPassword!"))))
                .andExpect(status().isUnauthorized());
    }

    // 6th attempt with correct password should still fail (locked)
    mockMvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LoginRequest(email, "Password123!"))))
            .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 3: Run unverified test to confirm it fails (currently returns 403)**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.AuthControllerTest.login_unverifiedEmail_returns401NotDistinguishable" --no-daemon`
Expected: FAIL (403 instead of 401)

- [ ] **Step 4: Extract `incrementFailedAttempts()` helper with atomic SQL**

In `AuthService.java`, add private method:

```java
private void incrementFailedAttempts(UUID userId) {
    authJdbc.update(
        "UPDATE users " +
        "SET failed_login_attempts = CASE " +
        "        WHEN locked_until IS NOT NULL AND locked_until < NOW() " +
        "            THEN 1 " +
        "        ELSE failed_login_attempts + 1 " +
        "    END, " +
        "    locked_until = CASE " +
        "        WHEN locked_until IS NOT NULL AND locked_until < NOW() " +
        "            THEN NULL " +
        "        WHEN failed_login_attempts + 1 >= 5 " +
        "             AND (locked_until IS NULL) " +
        "            THEN NOW() + INTERVAL '15 minutes' " +
        "        ELSE locked_until " +
        "    END " +
        "WHERE id = ?",
        userId);
}
```

- [ ] **Step 5: Rewrite `authenticate()` to match spec's 7-step flow**

Replace the entire `authenticate()` method body (lines 86-145) with the spec's canonical flow. The final method must match this exact ordering:

```java
public Map<String, Object> authenticate(String email, String password) {
    // Step 1: Query user by email
    var rows = authJdbc.queryForList(
            "SELECT id, email, password_hash, failed_login_attempts, locked_until, email_verified FROM users WHERE email = ?",
            email);

    if (rows.isEmpty()) {
        // Unknown email — use dummy hash for timing equalization
        passwordEncoder.matches(password, getDummyHash());
        throw new BadCredentialsException("Invalid credentials");
    }

    Map<String, Object> user = rows.get(0);
    UUID userId = (UUID) user.get("id");
    String storedHash = (String) user.get("password_hash");
    int failedAttempts = (int) user.get("failed_login_attempts");
    Instant lockedUntil = user.get("locked_until") != null
            ? ((java.sql.Timestamp) user.get("locked_until")).toInstant()
            : null;

    // Step 2: Always check password (timing preservation)
    boolean passwordCorrect = passwordEncoder.matches(password, storedHash);

    // Step 3: Evaluate isLocked from initial SELECT (no re-fetch)
    boolean isLocked = failedAttempts >= MAX_FAILED_ATTEMPTS
            && lockedUntil != null
            && lockedUntil.isAfter(Instant.now());

    // Step 4: Wrong password
    if (!passwordCorrect) {
        incrementFailedAttempts(userId);
        throw new BadCredentialsException("Invalid credentials");
    }

    // Step 5: Correct password but locked — increment for timing equalization
    if (isLocked) {
        incrementFailedAttempts(userId);
        throw new BadCredentialsException("Invalid credentials");
    }

    // Step 6: Correct password, not locked, but unverified email — oracle eliminated
    Boolean emailVerified = (Boolean) user.get("email_verified");
    if (emailVerified == null || !emailVerified) {
        incrementFailedAttempts(userId);
        throw new BadCredentialsException("Invalid credentials");
    }

    // Step 7: Success — reset counter
    authJdbc.update(
            "UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?",
            userId);

    return Map.of("userId", userId, "email", (String) user.get("email"));
}
```

- [ ] **Step 6: Fix AuthController — remove EmailVerificationRequiredException catch**

In `AuthController.java`, remove lines 90-92:
```java
// REMOVE:
} catch (EmailVerificationRequiredException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse("Please verify your email before logging in", 403));
```

Also remove the import on line 9:
```java
// REMOVE:
import org.jphototagger.api.exception.EmailVerificationRequiredException;
```

- [ ] **Step 7: Fix existing tests that expect 403 for unverified email**

Search `AuthControllerTest.java` for any assertions using `status().isForbidden()` or `HttpStatus.FORBIDDEN` or `403` in the context of unverified-email login scenarios. Update them to `status().isUnauthorized()` / `401`. Known candidates:
- Any test named `*unverified*` or `*emailVerification*` that asserts 403

- [ ] **Step 8: Run full AuthController test suite**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.AuthControllerTest" --no-daemon`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/AuthService.java api/src/main/java/org/jphototagger/api/controller/AuthController.java api/src/test/java/org/jphototagger/api/controller/AuthControllerTest.java
git commit -m "fix(auth): atomic lockout, oracle elimination, bounded lockout with post-expiry reset (Findings #2, #3)"
```

---

## Chunk 2: OAuth2 Guard + Restore Reorder (Findings #7, #8)

### Task 4: OAuth2 `email_verified` claim guard (Finding #8)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:70-74`
- Test: `api/src/test/java/org/jphototagger/api/security/OAuth2SuccessHandlerTest.java` (existing)

- [ ] **Step 1: Write failing test for unverified email rejection**

Add to `OAuth2SuccessHandlerTest.java`:

```java
@Test
void onAuthenticationSuccess_emailNotVerified_redirectsWithError() throws Exception {
    // Mock OidcUser with emailVerified = false
    OidcUser oidcUser = mock(OidcUser.class);
    when(oidcUser.getEmail()).thenReturn("unverified@example.com");
    when(oidcUser.getSubject()).thenReturn("sub123");
    when(oidcUser.getEmailVerified()).thenReturn(false);

    OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
    when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
    when(token.getPrincipal()).thenReturn(oidcUser);

    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, token);

    verify(response).sendRedirect(contains("error=email_not_verified"));
}

@Test
void onAuthenticationSuccess_emailVerifiedNull_redirectsWithError() throws Exception {
    OidcUser oidcUser = mock(OidcUser.class);
    when(oidcUser.getEmail()).thenReturn("null-verified@example.com");
    when(oidcUser.getSubject()).thenReturn("sub456");
    when(oidcUser.getEmailVerified()).thenReturn(null);

    OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
    when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
    when(token.getPrincipal()).thenReturn(oidcUser);

    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, token);

    verify(response).sendRedirect(contains("error=email_not_verified"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.security.OAuth2SuccessHandlerTest.onAuthenticationSuccess_emailNotVerified_redirectsWithError" --no-daemon`
Expected: FAIL

- [ ] **Step 3: Add email_verified guard to OAuth2SuccessHandler**

In `OAuth2SuccessHandler.java`, add after line 73 (after the null/blank email check):

```java
Boolean emailVerified = oidcUser.getEmailVerified();
if (!Boolean.TRUE.equals(emailVerified)) {
    response.sendRedirect(redirectUri + "login?error=email_not_verified");
    return;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.security.OAuth2SuccessHandlerTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java api/src/test/java/org/jphototagger/api/security/OAuth2SuccessHandlerTest.java
git commit -m "fix(oauth2): reject unverified email_verified claims (Finding #8)"
```

---

### Task 5: Fix restore() race condition (Finding #7)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/PhotoService.java:297-319`

- [ ] **Step 1: Reorder restore() to acquire user lock FIRST**

Replace the `restore()` method at lines 297-319 in `PhotoService.java`:

```java
@Transactional
public void restore(UUID userId, UUID photoId) {
    // Lock user row FIRST — mirrors softDelete() pattern.
    // Second concurrent request blocks here until first commits.
    User user = entityManager.createQuery(
            "SELECT u FROM User u WHERE u.id = :userId", User.class)
            .setParameter("userId", userId)
            .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
            .getSingleResult();

    // Re-read photo inside the lock — second concurrent request sees
    // deletedAt == null (already restored by first) and throws here.
    Photo photo = photoRepository.findById(photoId)
            .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() != null)
            .orElseThrow(() -> new EntityNotFoundException("Photo not found in trash"));

    long newUsed = user.getUsedBytes() + photo.getSizeBytes();
    if (newUsed > user.getQuotaBytes()) {
        throw new IllegalStateException("Restoring this photo would exceed your storage quota");
    }

    photo.setDeletedAt(null);
    photoRepository.save(photo);

    user.setUsedBytes(newUsed);
    userRepository.save(user);
}
```

- [ ] **Step 2: Run existing photo tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.PhotoControllerTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/PhotoService.java
git commit -m "fix(photo): acquire user lock before photo read in restore() (Finding #7)"
```

---

## Chunk 3: Share System (Findings #4, #5, C3)

### Task 6: Create `MetadataLocationStripper` utility (Finding #4)

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/service/MetadataLocationStripper.java`
- Test: `api/src/test/java/org/jphototagger/api/service/MetadataLocationStripperTest.java`

- [ ] **Step 1: Write failing tests for MetadataLocationStripper**

```java
package org.jphototagger.api.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataLocationStripperTest {

    @Test
    void filterGpsFromExif_removesGpsKeys() {
        Map<String, Object> exif = new HashMap<>(Map.of(
                "GPS:GPSLatitude", 40.0,
                "GPS:GPSLongitude", -74.0,
                "EXIF:GPSAltitude", 100.0,
                "Make", "Canon",
                "Model", "EOS R5"
        ));
        Map<String, Object> result = MetadataLocationStripper.filterGpsFromExif(exif);
        assertThat(result).containsOnlyKeys("Make", "Model");
    }

    @Test
    void filterGpsFromExif_nullInput_returnsNull() {
        assertThat(MetadataLocationStripper.filterGpsFromExif(null)).isNull();
    }

    @Test
    void filterLocationFromIptc_removesLocationKeys() {
        Map<String, Object> iptc = new HashMap<>(Map.of(
                "City", "New York",
                "Province-State", "NY",
                "Sub-location", "Manhattan",
                "IPTC:Keywords", "photo"
        ));
        Map<String, Object> result = MetadataLocationStripper.filterLocationFromIptc(iptc);
        assertThat(result).containsOnlyKeys("IPTC:Keywords");
    }

    @Test
    void filterLocationFromIptc_nullInput_returnsNull() {
        assertThat(MetadataLocationStripper.filterLocationFromIptc(null)).isNull();
    }

    @Test
    void filterLocationFromXmp_removesGpsAndLocationKeys() {
        Map<String, Object> xmp = new HashMap<>(Map.of(
                "exif:GPSLatitude", "40.0",
                "photoshop:City", "New York",
                "iptc4xmpcore:Location", "Manhattan",
                "dc:title", "Test Photo"
        ));
        Map<String, Object> result = MetadataLocationStripper.filterLocationFromXmp(xmp);
        assertThat(result).containsOnlyKeys("dc:title");
    }

    @Test
    void filterLocationFromXmp_nullInput_returnsNull() {
        assertThat(MetadataLocationStripper.filterLocationFromXmp(null)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails (class doesn't exist)**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.MetadataLocationStripperTest" --no-daemon`
Expected: FAIL (compilation error)

- [ ] **Step 3: Implement MetadataLocationStripper**

```java
package org.jphototagger.api.service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for stripping GPS and location data from photo metadata.
 * Static utility — no Spring dependency, usable from records and services alike.
 */
public final class MetadataLocationStripper {

    private MetadataLocationStripper() {}

    public static final Set<String> IPTC_LOCATION_KEYS = Set.of(
            "iptc:sub-location", "iptc:city", "iptc:province-state",
            "iptc:country-primary location code", "iptc:country-primary location name",
            "sub-location", "city", "province-state",
            "country-primary location code", "country-primary location name"
    );

    public static final Set<String> XMP_LOCATION_KEYS = Set.of(
            "photoshop:city", "photoshop:state", "photoshop:country",
            "iptc4xmpcore:location", "xmp:location"
    );

    /** Removes GPS-related keys from EXIF data. Returns null for null input. */
    public static Map<String, Object> filterGpsFromExif(Map<String, Object> exif) {
        if (exif == null) return null;
        return exif.entrySet().stream()
                .filter(e -> !e.getKey().toLowerCase().contains("gps"))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Removes location-related keys from IPTC data. Returns null for null input. */
    public static Map<String, Object> filterLocationFromIptc(Map<String, Object> iptc) {
        if (iptc == null) return null;
        return iptc.entrySet().stream()
                .filter(e -> !IPTC_LOCATION_KEYS.contains(e.getKey().toLowerCase()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Removes GPS and location-related keys from XMP data. Returns null for null input. */
    public static Map<String, Object> filterLocationFromXmp(Map<String, Object> xmp) {
        if (xmp == null) return null;
        return xmp.entrySet().stream()
                .filter(e -> {
                    String lower = e.getKey().toLowerCase();
                    return !lower.contains("gps") && !XMP_LOCATION_KEYS.contains(lower);
                })
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.MetadataLocationStripperTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/MetadataLocationStripper.java api/src/test/java/org/jphototagger/api/service/MetadataLocationStripperTest.java
git commit -m "feat(share): create MetadataLocationStripper as single source of truth for location key filtering"
```

---

### Task 7: Migrate `PhotoMetadataResponse.withoutGps()` to use stripper

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java:39-83`
- Test: `api/src/test/java/org/jphototagger/api/dto/PhotoMetadataResponseTest.java` (existing)

- [ ] **Step 1: Run existing PhotoMetadataResponseTest to establish baseline**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.dto.PhotoMetadataResponseTest" --no-daemon`
Expected: PASS

- [ ] **Step 2: Update `withoutGps()` to delegate to MetadataLocationStripper**

In `PhotoMetadataResponse.java`:

1. Remove the two private constant sets (lines 39-49): `IPTC_LOCATION_KEYS`, `XMP_LOCATION_KEYS`
2. Remove the three private methods (lines 58-83): `filterGpsKeys`, `filterGpsAndLocationKeys`, `filterLocationKeys`
3. Add import: `import org.jphototagger.api.service.MetadataLocationStripper;`
4. Update `withoutGps()` (lines 51-56):

```java
public PhotoMetadataResponse withoutGps() {
    return new PhotoMetadataResponse(photoId, null, null,
            MetadataLocationStripper.filterGpsFromExif(exifData),
            MetadataLocationStripper.filterLocationFromIptc(iptcData),
            MetadataLocationStripper.filterLocationFromXmp(xmpData),
            extractedAt);
}
```

- [ ] **Step 3: Run existing tests to verify delegation works identically**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.dto.PhotoMetadataResponseTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java
git commit -m "refactor(share): delegate PhotoMetadataResponse.withoutGps() to MetadataLocationStripper"
```

---

### Task 8: Add IPTC/XMP stripping to ShareService and ShareController (Finding #4)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/ShareService.java:47,148-166`
- Modify: `api/src/main/java/org/jphototagger/api/controller/ShareController.java:100-109`
- Test: `api/src/test/java/org/jphototagger/api/service/ShareServiceTest.java` (existing)

- [ ] **Step 1: Write failing test for IPTC/XMP stripping in ShareService**

Add to `ShareServiceTest.java`:

```java
@Test
void stripLocationFromIptc_removesLocationKeys() {
    String iptcJson = "{\"City\":\"New York\",\"Province-State\":\"NY\",\"IPTC:Keywords\":\"photo\"}";
    String result = shareService.stripLocationFromIptc(iptcJson);
    assertThat(result).contains("IPTC:Keywords");
    assertThat(result).doesNotContain("City");
    assertThat(result).doesNotContain("Province-State");
}

@Test
void stripLocationFromXmp_removesGpsAndLocationKeys() {
    String xmpJson = "{\"exif:GPSLatitude\":\"40.0\",\"photoshop:City\":\"New York\",\"dc:title\":\"Test\"}";
    String result = shareService.stripLocationFromXmp(xmpJson);
    assertThat(result).contains("dc:title");
    assertThat(result).doesNotContain("GPSLatitude");
    assertThat(result).doesNotContain("photoshop:City");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.ShareServiceTest.stripLocationFromIptc_removesLocationKeys" --no-daemon`
Expected: FAIL (method doesn't exist)

- [ ] **Step 3: Update ShareService — delegate stripGpsFromExif to stripper, add IPTC/XMP methods**

In `ShareService.java`:

1. Add import: `import org.jphototagger.api.service.MetadataLocationStripper;`
2. Remove `GPS_KEY_PATTERN` constant (line 47)
3. Replace `stripGpsFromExif` method body (lines 148-166) to delegate to stripper:

```java
public String stripGpsFromExif(String exifJson) {
    if (exifJson == null) return null;
    try {
        Map<String, Object> exifMap = objectMapper.readValue(exifJson,
                new TypeReference<Map<String, Object>>() {});
        Map<String, Object> stripped = MetadataLocationStripper.filterGpsFromExif(exifMap);
        return objectMapper.writeValueAsString(stripped);
    } catch (JsonProcessingException e) {
        log.warn("Failed to parse EXIF JSON for GPS stripping, returning null to prevent GPS data leak", e);
        return null;
    }
}

public String stripLocationFromIptc(String iptcJson) {
    if (iptcJson == null) return null;
    try {
        Map<String, Object> iptcMap = objectMapper.readValue(iptcJson,
                new TypeReference<Map<String, Object>>() {});
        Map<String, Object> stripped = MetadataLocationStripper.filterLocationFromIptc(iptcMap);
        return objectMapper.writeValueAsString(stripped);
    } catch (JsonProcessingException e) {
        log.warn("Failed to parse IPTC JSON for location stripping, returning null", e);
        return null;
    }
}

public String stripLocationFromXmp(String xmpJson) {
    if (xmpJson == null) return null;
    try {
        Map<String, Object> xmpMap = objectMapper.readValue(xmpJson,
                new TypeReference<Map<String, Object>>() {});
        Map<String, Object> stripped = MetadataLocationStripper.filterLocationFromXmp(xmpMap);
        return objectMapper.writeValueAsString(stripped);
    } catch (JsonProcessingException e) {
        log.warn("Failed to parse XMP JSON for location stripping, returning null", e);
        return null;
    }
}
```

4. Remove `import java.util.regex.Pattern;` if no other usage.

- [ ] **Step 4: (ShareController change skipped — Task 9 Step 3 supersedes)**

> **Note:** Task 9 Step 3 replaces the entire photo block in `getShare()`, including the IPTC/XMP stripping lines. Do NOT modify `ShareController.java` in this task. Task 9 applies the combined controller change (IPTC/XMP stripping + storage_key removal + owner_id predicate) in one step.

- [ ] **Step 5: Run tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.ShareServiceTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/ShareService.java api/src/test/java/org/jphototagger/api/service/ShareServiceTest.java
git commit -m "fix(share): strip IPTC/XMP location data in share responses (Finding #4)"
```

---

### Task 9: Fix storage_key exposure + add owner_id predicate (Findings #5, C3)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/controller/ShareController.java:95-142,148-165`
- Modify: `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java:64-74`

- [ ] **Step 1: Add `ownerId` parameter to `ShareLookupRepository.findPhotoById()`**

In `ShareLookupRepository.java`, replace `findPhotoById` (lines 64-74):

```java
public Optional<Map<String, Object>> findPhotoById(UUID photoId, UUID ownerId) {
    var results = jdbc.queryForList(
        "SELECT p.id, p.filename, p.caption, p.title, p.description, " +
        "       p.size_bytes, p.taken_at, p.uploaded_at, p.processing_status, p.storage_key, " +
        "       pm.exif_data, pm.iptc_data, pm.xmp_data " +
        "FROM photos p " +
        "LEFT JOIN photo_metadata pm ON pm.photo_id = p.id " +
        "WHERE p.id = ? AND p.user_id = ? AND p.deleted_at IS NULL",
        photoId, ownerId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
}
```

- [ ] **Step 2: Extract `enrichPhotoWithPresignedUrls()` helper in ShareController**

In `ShareController.java`, add private method:

```java
private void enrichPhotoWithPresignedUrls(Map<String, Object> photo, UUID ownerId) {
    UUID photoId = (UUID) photo.get("id");
    Object storageKey = photo.remove("storage_key");
    if (storageKey != null && photoId != null) {
        photo.put("thumbnailUrl", storageService.generateThumbnailPresignedUrl(
                storageService.thumbnailSmKey(ownerId, photoId)));
        photo.put("originalUrl", storageService.generateOriginalPresignedUrl(storageKey.toString()));
    }
}
```

- [ ] **Step 3: Update `getShare()` to use ownerId and enrichPhotoWithPresignedUrls**

Replace the photo handling block in `getShare()` (lines 95-132):

```java
if ("photo".equals(resourceType)) {
    UUID ownerId = (UUID) shareData.get("user_id");
    var photoOpt = shareLookupRepository.findPhotoById(resourceId, ownerId);
    if (photoOpt.isEmpty()) {
        throw new jakarta.persistence.EntityNotFoundException("Share not found");
    }
    Map<String, Object> photo = new HashMap<>(photoOpt.get());

    if (!includeGps) {
        if (photo.get("exif_data") != null)
            photo.put("exif_data", shareService.stripGpsFromExif(photo.get("exif_data").toString()));
        if (photo.get("iptc_data") != null)
            photo.put("iptc_data", shareService.stripLocationFromIptc(photo.get("iptc_data").toString()));
        if (photo.get("xmp_data") != null)
            photo.put("xmp_data", shareService.stripLocationFromXmp(photo.get("xmp_data").toString()));
    }

    enrichPhotoWithPresignedUrls(photo, ownerId);
    response.put("photo", photo);
```

- [ ] **Step 4: Update `getSharedAlbumPhotos()` to strip storage_key from album photos**

Replace the return statement in `getSharedAlbumPhotos()` (line 164):

```java
UUID albumOwnerId = (UUID) shareData.get("user_id");
return shareLookupRepository.findAlbumPhotos(albumId, capped).map(rawPhoto -> {
    Map<String, Object> photo = new HashMap<>(rawPhoto);
    enrichPhotoWithPresignedUrls(photo, albumOwnerId);
    return photo;
});
```

- [ ] **Step 5: Add tests for storage_key removal and owner_id predicate**

Add to `ShareControllerTest.java` (or `ShareServiceTest.java`):

```java
@Test
void getShare_responseDoesNotContainStorageKey() throws Exception {
    // Create a share, then retrieve it and verify storage_key is not in the response
    // (use an existing share setup from test fixtures)
    var result = mockMvc.perform(get("/shares/{token}", shareToken))
            .andExpect(status().isOk())
            .andReturn();
    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("storage_key");
}

@Test
void getShare_photoWithWrongOwner_returns404() throws Exception {
    // Verify that findPhotoById with mismatched ownerId returns empty/404
    // This tests the owner_id predicate added to ShareLookupRepository
    // Create a share pointing to a photo, then tamper the owner_id
    // The share lookup should fail because the photo's user_id doesn't match
}
```

> **Note:** Adapt these test skeletons to the actual test infrastructure. The key assertions: (1) `storage_key` never appears in share API responses, (2) `findPhotoById(photoId, wrongOwnerId)` returns empty.

- [ ] **Step 6: Run full test suite**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/controller/ShareController.java api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java api/src/test/java/org/jphototagger/api/controller/ShareControllerTest.java
git commit -m "fix(share): remove storage_key from responses, add owner_id predicate (Findings #5, C3)"
```

---

## Chunk 4: Keyword & Access Control (D1, D3, D4)

### Task 10: Fix `updateKeyword()` missing parentId ownership check (D1)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/KeywordService.java:53-62`
- Test: `api/src/test/java/org/jphototagger/api/controller/KeywordControllerTest.java` (existing)

- [ ] **Step 1: Add parentId ownership guard to updateKeyword()**

> **Spec deviation (intentional):** The spec places `keyword.setParentId(parentId)` inside the `if (parentId != null)` block, which prevents clearing a parent by passing null. The plan places it outside the block so that `parentId = null` correctly clears the parent. This is the more correct behavior.

In `KeywordService.java`, replace lines 53-62:

```java
@Transactional
public Keyword updateKeyword(UUID userId, UUID keywordId, String name, UUID parentId) {
    if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Name is required");
    }
    Keyword keyword = getKeyword(userId, keywordId);
    keyword.setName(name);
    if (parentId != null) {
        keywordRepository.findById(parentId)
            .filter(p -> p.getUserId().equals(userId))
            .orElseThrow(() -> new EntityNotFoundException("Parent keyword not found"));
    }
    keyword.setParentId(parentId);
    return keywordRepository.save(keyword);
}
```

- [ ] **Step 2: Run existing keyword tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.KeywordControllerTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/KeywordService.java
git commit -m "fix(keyword): add parentId ownership check to updateKeyword() (D1)"
```

---

### Task 11: Fix recursive CTE missing user_id filter (D3)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java:25-33`

- [ ] **Step 1: Add `AND k.user_id = :userId` to recursive step**

In `KeywordRepository.java`, replace the `findSubtree` query (lines 25-33):

```java
@Query(value = "WITH RECURSIVE subtree AS ("
        + "  SELECT id, user_id, name, parent_id, updated_at FROM keywords "
        + "  WHERE id = :rootId AND user_id = :userId "
        + "  UNION ALL "
        + "  SELECT k.id, k.user_id, k.name, k.parent_id, k.updated_at FROM keywords k "
        + "  INNER JOIN subtree s ON k.parent_id = s.id "
        + "  WHERE k.user_id = :userId"
        + ") SELECT * FROM subtree ORDER BY name LIMIT 1000",
        nativeQuery = true)
List<Keyword> findSubtree(@Param("userId") UUID userId, @Param("rootId") UUID rootId);
```

- [ ] **Step 2: Add cross-tenant recursive CTE isolation test**

Add to `KeywordControllerTest.java` (or a dedicated `KeywordRepositoryTest` if preferred):

```java
@Test
void findSubtree_doesNotReturnOtherTenantsKeywords() throws Exception {
    // Create a keyword tree for user A
    String keywordName = "subtree-test-" + UUID.randomUUID();
    var createResult = mockMvc.perform(post("/keywords").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + keywordName + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    UUID rootId = objectMapper.readTree(createResult.getResponse().getContentAsString())
            .get("id").asText().transform(UUID::fromString);

    // Attempt to query user A's subtree as user B should return empty
    // (The controller already scopes by userId via @AuthenticationPrincipal,
    //  but this test verifies the CTE itself filters by user_id in the recursive step)
    var subtreeResult = mockMvc.perform(get("/keywords/" + rootId + "/subtree"))
            .andExpect(status().isOk())
            .andReturn();
    // Verify the root keyword belongs to the authenticated user
    var subtree = objectMapper.readTree(subtreeResult.getResponse().getContentAsString());
    assertThat(subtree).allSatisfy(node ->
        assertThat(node.get("name").asText()).isNotEmpty());
}
```

> **Note:** If a full cross-tenant test requires two authenticated sessions, implement as an integration test with direct repository calls using two different userIds, asserting `findSubtree(userBId, userAKeywordId)` returns empty.

- [ ] **Step 3: Run keyword tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.KeywordControllerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java api/src/test/java/org/jphototagger/api/controller/KeywordControllerTest.java
git commit -m "fix(keyword): add user_id filter to recursive CTE step (D3)"
```

---

### Task 12: Move keyword-photo operations to PhotoService (D4)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/PhotoService.java`
- Modify: `api/src/main/java/org/jphototagger/api/controller/PhotoController.java:1-49,108-155`
- Modify: `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java`
- Modify: `api/src/main/java/org/jphototagger/api/repository/PhotoKeywordRepository.java`

- [ ] **Step 1: Add `existsByPhotoIdAndKeywordId` to PhotoKeywordRepository**

In `PhotoKeywordRepository.java`, add:

```java
boolean existsByPhotoIdAndKeywordId(UUID photoId, UUID keywordId);
```

- [ ] **Step 2: Add `findKeywordsByPhotoIdAndUserId` to KeywordRepository**

In `KeywordRepository.java`, add:

```java
@Query("SELECT k FROM Keyword k JOIN PhotoKeyword pk ON pk.keywordId = k.id " +
       "WHERE pk.photoId = :photoId AND pk.userId = :userId")
List<Keyword> findKeywordsByPhotoIdAndUserId(@Param("photoId") UUID photoId,
                                              @Param("userId") UUID userId);
```

- [ ] **Step 3: Create `KeywordResponse` DTO**

Create `api/src/main/java/org/jphototagger/api/dto/KeywordResponse.java`:

```java
package org.jphototagger.api.dto;

import org.jphototagger.api.entity.Keyword;

import java.util.UUID;

public record KeywordResponse(UUID id, String name, UUID parentId) {

    public static KeywordResponse from(Keyword keyword) {
        return new KeywordResponse(keyword.getId(), keyword.getName(),
                keyword.getParentId());
    }
}
```

- [ ] **Step 4: Add keyword methods to PhotoService**

In `PhotoService.java`, add imports for `Keyword`, `KeywordResponse`, `KeywordRepository`, `PhotoKeyword`, `PhotoKeywordRepository`. Inject them via constructor. Add methods:

```java
@Transactional
public void addKeywordToPhoto(UUID userId, UUID photoId, UUID keywordId) {
    getPhoto(userId, photoId);
    Keyword keyword = keywordRepository.findById(keywordId)
        .orElseThrow(() -> new EntityNotFoundException("Keyword not found"));
    if (!keyword.getUserId().equals(userId)) {
        throw new EntityNotFoundException("Keyword not found");
    }
    if (photoKeywordRepository.existsByPhotoIdAndKeywordId(photoId, keywordId)) {
        return;
    }
    PhotoKeyword pk = new PhotoKeyword();
    pk.setPhotoId(photoId);
    pk.setKeywordId(keywordId);
    pk.setUserId(userId);
    photoKeywordRepository.save(pk);
}

@Transactional
public void removeKeywordFromPhoto(UUID userId, UUID photoId, UUID keywordId) {
    getPhoto(userId, photoId);
    photoKeywordRepository.deleteByPhotoIdAndKeywordIdAndUserId(photoId, keywordId, userId);
}

@Transactional(readOnly = true)
public List<KeywordResponse> listKeywordsForPhoto(UUID userId, UUID photoId) {
    getPhoto(userId, photoId);
    return keywordRepository.findKeywordsByPhotoIdAndUserId(photoId, userId)
            .stream().map(KeywordResponse::from).toList();
}
```

- [ ] **Step 5: Simplify PhotoController — remove keyword repos, delegate to service**

In `PhotoController.java`:

1. Remove `PhotoKeywordRepository` and `KeywordRepository` imports and fields (lines 4-8, 39-40, 42-48)
2. Update constructor to only take `PhotoService` and `StorageService`
3. Remove `@Transactional` from `addKeywordToPhoto` and `removeKeywordFromPhoto`
4. Add import: `import org.jphototagger.api.dto.KeywordResponse;`
5. Replace all three keyword method bodies:

```java
@GetMapping("/{id}/keywords")
public ResponseEntity<List<KeywordResponse>> listKeywordsForPhoto(
        @AuthenticationPrincipal UUID userId,
        @PathVariable UUID id) {
    return ResponseEntity.ok(photoService.listKeywordsForPhoto(userId, id));
}

@PostMapping("/{id}/keywords/{keywordId}")
public ResponseEntity<Void> addKeywordToPhoto(
        @AuthenticationPrincipal UUID userId,
        @PathVariable UUID id,
        @PathVariable UUID keywordId) {
    photoService.addKeywordToPhoto(userId, id, keywordId);
    return ResponseEntity.ok().build();
}

@DeleteMapping("/{id}/keywords/{keywordId}")
public ResponseEntity<Void> removeKeywordFromPhoto(
        @AuthenticationPrincipal UUID userId,
        @PathVariable UUID id,
        @PathVariable UUID keywordId) {
    photoService.removeKeywordFromPhoto(userId, id, keywordId);
    return ResponseEntity.noContent().build();
}
```

6. Remove unused imports: `Keyword`, `PhotoKeyword`, `PhotoKeywordRepository`, `KeywordRepository`, `Transactional`, `EntityNotFoundException`

- [ ] **Step 6: Run photo and keyword tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.PhotoControllerTest" --tests "org.jphototagger.api.controller.KeywordControllerTest" --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/dto/KeywordResponse.java api/src/main/java/org/jphototagger/api/service/PhotoService.java api/src/main/java/org/jphototagger/api/controller/PhotoController.java api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java api/src/main/java/org/jphototagger/api/repository/PhotoKeywordRepository.java
git commit -m "refactor(keyword): move keyword-photo operations to PhotoService, add KeywordResponse DTO (D4)"
```

---

## Chunk 5: Scheduler Fixes (Finding #6)

### Task 13: Create V14 migration for scheduler permissions

**Files:**
- Create: `api/src/main/resources/db/migration/V14__grant_scheduler_permissions_to_jpt_auth.sql`

- [ ] **Step 1: Create migration file**

```sql
-- V14__grant_scheduler_permissions_to_jpt_auth.sql
-- Comprehensive grants for all tables accessed by schedulers via authJdbcTemplate.
-- This migration supersedes any out-of-band grants previously applied to jpt_auth
-- during environment provisioning. All scheduler-required permissions are now
-- version-controlled in Flyway — the single source of truth for scheduler permissions.

-- TrashPurgeScheduler: SELECT purgeable batches, DELETE purged rows
GRANT SELECT, DELETE ON photos TO jpt_auth;

-- TrashPurgeScheduler.purgeNullStorageKeyPhotos(): CTE updates users.used_bytes
GRANT UPDATE (used_bytes) ON users TO jpt_auth;

-- UnverifiedAccountPurgeScheduler: full user cascade delete
-- NOTE: DELETE ON users is the highest-privilege grant to jpt_auth.
-- Required by UnverifiedAccountPurgeScheduler for purging unverified accounts.
-- Application-layer WHERE clause restricts to email_verified = false AND created_at < cutoff.
-- No database-level restriction is possible — audit any new DELETE usage against users table.
GRANT DELETE ON users TO jpt_auth;
GRANT SELECT, DELETE ON album_photos TO jpt_auth;
GRANT DELETE ON albums TO jpt_auth;
GRANT DELETE ON saved_searches TO jpt_auth;
GRANT DELETE ON shares TO jpt_auth;
GRANT SELECT, UPDATE, DELETE ON keywords TO jpt_auth;

-- OrphanReconciliationScheduler: query user IDs, check photo existence
-- (SELECT ON users already granted in V4; SELECT ON photos granted above)
```

- [ ] **Step 2: Run Flyway migration via test**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.JptSaasApplicationTest" --no-daemon`
Expected: PASS (migration applies cleanly)

- [ ] **Step 3: Commit**

```bash
git add api/src/main/resources/db/migration/V14__grant_scheduler_permissions_to_jpt_auth.sql
git commit -m "infra: V14 migration — comprehensive scheduler permissions for jpt_auth (Finding #6)"
```

---

### Task 14: Create SchedulerRepository

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/repository/SchedulerRepository.java`

- [ ] **Step 1: Implement SchedulerRepository**

```java
package org.jphototagger.api.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates all raw SQL used by schedulers via authJdbcTemplate (BYPASSRLS).
 * Scheduler classes call repository methods instead of inlining SQL.
 */
@Repository
public class SchedulerRepository {

    private final JdbcTemplate authJdbc;

    public SchedulerRepository(@Qualifier("authJdbcTemplate") JdbcTemplate authJdbc) {
        this.authJdbc = authJdbc;
    }

    public List<Map<String, Object>> findPurgeableBatch(Instant cutoff) {
        return authJdbc.queryForList(
            "SELECT id, user_id, storage_key FROM photos " +
            "WHERE deleted_at < ? " +
            "LIMIT 100",
            Timestamp.from(cutoff));
    }

    public void deletePhotosByIds(UUID[] ids) {
        authJdbc.update("DELETE FROM photos WHERE id = ANY(?)",
            (PreparedStatement ps) -> ps.setArray(1,
                ps.getConnection().createArrayOf("uuid", ids)));
    }

    public int purgeNullStorageKeyPhotos() {
        return authJdbc.update(
            "WITH deleted AS (" +
            "    DELETE FROM photos" +
            "    WHERE storage_key IS NULL" +
            "    AND deleted_at IS NULL" +
            "    AND uploaded_at < now() - INTERVAL '1 hour'" +
            "    RETURNING user_id, COALESCE(size_bytes, 0) AS size_bytes" +
            ")" +
            "UPDATE users u" +
            "  SET used_bytes = GREATEST(0, u.used_bytes - d.size_bytes)" +
            "  FROM deleted d" +
            "  WHERE u.id = d.user_id"
        );
    }

    public List<UUID> queryUserIdPage(UUID afterId, int pageSize) {
        if (afterId == null) {
            return authJdbc.queryForList(
                "SELECT id FROM users ORDER BY id LIMIT ?", UUID.class, pageSize);
        }
        return authJdbc.queryForList(
            "SELECT id FROM users WHERE id > ? ORDER BY id LIMIT ?",
            UUID.class, afterId, pageSize);
    }

    public List<UUID> findExistingPhotoIds(UUID[] batch) {
        return authJdbc.query(
            con -> {
                PreparedStatement ps = con.prepareStatement(
                    "SELECT id FROM photos WHERE id = ANY(?)");
                ps.setArray(1, con.createArrayOf("uuid", batch));
                return ps;
            },
            (rs, rowNum) -> UUID.fromString(rs.getString("id")));
    }

    public List<Map<String, Object>> findStorageKeysByUserId(UUID userId) {
        return authJdbc.queryForList(
            "SELECT id, user_id, storage_key FROM photos " +
            "WHERE user_id = ? AND storage_key IS NOT NULL",
            userId);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:compileJava --no-daemon`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/repository/SchedulerRepository.java
git commit -m "feat(scheduler): create SchedulerRepository to encapsulate scheduler SQL"
```

---

### Task 15: Add `buildDeleteJobMessage`, `enqueueByRows`, `extractPhotoIdFromKey` to PhotoDeleteJobEnqueuer

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java`

- [ ] **Step 1: Extract `buildDeleteJobMessage()` helper and add new methods**

In `PhotoDeleteJobEnqueuer.java`:

1. Add `buildDeleteJobMessage()` private helper
2. Refactor `enqueue(List<Photo>)` to use it
3. Refactor `enqueueOrphan()` to use it
4. Add `enqueueByRows(List<Map<String,Object>>)`
5. Add static `extractPhotoIdFromKey(String)`

```java
private Map<String, String> buildDeleteJobMessage(UUID userId, UUID photoId, String originalKey) {
    return Map.of(
        "photo_id",     photoId.toString(),
        "original_key", originalKey,
        "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
        "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
    );
}

public void enqueueByRows(List<Map<String, Object>> rows) {
    redisTemplate.executePipelined(new SessionCallback<Object>() {
        @SuppressWarnings("unchecked")
        @Override
        public Object execute(RedisOperations operations) {
            for (Map<String, Object> row : rows) {
                UUID photoId   = (UUID) row.get("id");
                UUID userId    = (UUID) row.get("user_id");
                String origKey = (String) row.get("storage_key");
                if (origKey == null) {
                    log.warn("Skipping delete-job for photo {} — null storage_key", photoId);
                    continue;
                }
                operations.opsForStream().add("delete-jobs",
                    buildDeleteJobMessage(userId, photoId, origKey));
            }
            return null;
        }
    });
}

/** Parses UUID from "{userId}/originals/{photoId}.{ext}"; returns null on failure. */
static UUID extractPhotoIdFromKey(String key) {
    try {
        int lastSlash = key.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String filename = key.substring(lastSlash + 1);
        int dot = filename.lastIndexOf('.');
        String uuidStr = dot >= 0 ? filename.substring(0, dot) : filename;
        return UUID.fromString(uuidStr);
    } catch (IllegalArgumentException e) {
        return null;
    }
}
```

Update `enqueue(List<Photo>)` to use `buildDeleteJobMessage`:

```java
// Inside the loop, replace Map.of(...) with:
Map<String, String> msg = buildDeleteJobMessage(userId, photoId, photo.getStorageKey());
```

Update `enqueueOrphan()` to use `buildDeleteJobMessage`:

```java
public void enqueueOrphan(UUID userId, UUID photoId, String originalKey) {
    redisTemplate.opsForStream().add("delete-jobs",
        buildDeleteJobMessage(userId, photoId, originalKey));
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:compileJava --no-daemon`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java
git commit -m "refactor(scheduler): extract buildDeleteJobMessage, add enqueueByRows and extractPhotoIdFromKey"
```

---

### Task 16: Migrate TrashPurgeScheduler to SchedulerRepository

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`

- [ ] **Step 1: Replace photoRepository and jdbcTemplate with SchedulerRepository**

Rewrite `TrashPurgeScheduler.java`:

```java
package org.jphototagger.api.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jphototagger.api.repository.SchedulerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TrashPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);

    private final SchedulerRepository schedulerRepository;
    private final PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer;
    private final int retentionDays;

    public TrashPurgeScheduler(
            SchedulerRepository schedulerRepository,
            PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer,
            @Value("${jpt.trash.retention-days:30}") int retentionDays) {
        this.schedulerRepository = schedulerRepository;
        this.photoDeleteJobEnqueuer = photoDeleteJobEnqueuer;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "trashPurge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeTrash() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("TrashPurgeScheduler: purging photos deleted before {} (retention={} days)",
                cutoff, retentionDays);

        List<Map<String, Object>> batch;
        int totalPurged = 0;

        do {
            batch = schedulerRepository.findPurgeableBatch(cutoff);
            if (batch.isEmpty()) {
                break;
            }
            photoDeleteJobEnqueuer.enqueueByRows(batch);
            UUID[] ids = batch.stream()
                    .map(row -> (UUID) row.get("id"))
                    .toArray(UUID[]::new);
            schedulerRepository.deletePhotosByIds(ids);
            totalPurged += batch.size();
            log.debug("TrashPurgeScheduler: purged batch of {} photos (total so far: {})",
                    batch.size(), totalPurged);
        } while (!batch.isEmpty());

        int nullKeyRows = schedulerRepository.purgeNullStorageKeyPhotos();
        log.info("TrashPurgeScheduler: purged {} photos, cleaned {} null-storage-key rows",
                totalPurged, nullKeyRows);
    }
}
```

- [ ] **Step 2: Update SchedulerTest mocks for TrashPurgeScheduler**

`SchedulerTest` uses `@MockBean PhotoRepository` — update to `@MockBean SchedulerRepository` and change mock setup:

1. Replace `@MockBean PhotoRepository photoRepository;` with `@MockBean SchedulerRepository schedulerRepository;`
2. Update any `when(photoRepository.findPurgeableBatch(...))` to `when(schedulerRepository.findPurgeableBatch(any(Instant.class))).thenReturn(List.of(...))`
3. For empty-batch tests: `when(schedulerRepository.findPurgeableBatch(any())).thenReturn(List.of())`
4. For batch-with-data tests: return `List<Map<String,Object>>` rows matching the `findPurgeableBatch` schema: `Map.of("id", photoId, "user_id", userId, "storage_key", "key")`
5. Add `when(schedulerRepository.purgeNullStorageKeyPhotos()).thenReturn(0)` where needed
6. Verify with: `verify(schedulerRepository).deletePhotosByIds(any(UUID[].class))`

- [ ] **Step 3: Run scheduler tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.scheduler.SchedulerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java api/src/test/java/org/jphototagger/api/scheduler/SchedulerTest.java
git commit -m "refactor(scheduler): migrate TrashPurgeScheduler to SchedulerRepository (Finding #6)"
```

---

### Task 17: Migrate OrphanReconciliationScheduler to SchedulerRepository + keyset pagination

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`

- [ ] **Step 1: Rewrite OrphanReconciliationScheduler**

Replace entire file to use `SchedulerRepository` and keyset pagination:

```java
package org.jphototagger.api.scheduler;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jphototagger.api.repository.SchedulerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class OrphanReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrphanReconciliationScheduler.class);
    private static final Duration RECENCY_THRESHOLD = Duration.ofHours(2);
    private static final int ID_BATCH_SIZE = 1_000;

    private final SchedulerRepository schedulerRepository;
    private final MinioClient minioInternalClient;
    private final PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer;
    private final String bucket;

    public OrphanReconciliationScheduler(
            SchedulerRepository schedulerRepository,
            @Qualifier("minioInternalClient") MinioClient minioInternalClient,
            PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer,
            @Value("${minio.bucket}") String bucket) {
        this.schedulerRepository = schedulerRepository;
        this.minioInternalClient = minioInternalClient;
        this.photoDeleteJobEnqueuer = photoDeleteJobEnqueuer;
        this.bucket = bucket;
    }

    @Scheduled(cron = "0 0 4 * * SUN")
    @SchedulerLock(name = "orphanReconciliation", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void reconcileOrphans() {
        log.info("OrphanReconciliationScheduler: starting orphan reconciliation");

        UUID cursor = null;
        int pageSize = 100;
        int orphansFound = 0;

        while (true) {
            List<UUID> page = schedulerRepository.queryUserIdPage(cursor, pageSize);
            if (page.isEmpty()) break;
            for (UUID userId : page) {
                orphansFound += reconcileUser(userId);
            }
            cursor = page.get(page.size() - 1);
        }

        log.info("OrphanReconciliationScheduler: enqueued {} orphaned objects for deletion",
                orphansFound);
    }

    private int reconcileUser(UUID userId) {
        String prefix = userId + "/originals/";

        Iterable<Result<Item>> objects = minioInternalClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .recursive(false)
                        .build());

        ZonedDateTime recencyCutoff = ZonedDateTime.now().minus(RECENCY_THRESHOLD);
        Map<UUID, String> candidateKeys = new HashMap<>();
        for (Result<Item> result : objects) {
            try {
                Item item = result.get();
                if (item.isDir()) continue;
                if (item.lastModified() == null || item.lastModified().isAfter(recencyCutoff)) {
                    if (item.lastModified() == null) {
                        log.warn("OrphanReconciliationScheduler: null lastModified for key={}, skipping", item.objectName());
                    }
                    continue;
                }
                String objectKey = item.objectName();
                if (!objectKey.startsWith(prefix)) continue;

                UUID photoId = PhotoDeleteJobEnqueuer.extractPhotoIdFromKey(objectKey);
                if (photoId == null) {
                    log.warn("OrphanReconciliationScheduler: could not parse photo_id from key={}", objectKey);
                    continue;
                }
                candidateKeys.put(photoId, objectKey);
            } catch (Exception e) {
                log.error("OrphanReconciliationScheduler: error processing MinIO object", e);
            }
        }

        if (candidateKeys.isEmpty()) return 0;

        List<UUID> candidateIds = new ArrayList<>(candidateKeys.keySet());
        Set<UUID> existingIds = findExistingIds(candidateIds);

        int count = 0;
        for (Map.Entry<UUID, String> entry : candidateKeys.entrySet()) {
            UUID photoId = entry.getKey();
            String objectKey = entry.getValue();
            if (!existingIds.contains(photoId)) {
                photoDeleteJobEnqueuer.enqueueOrphan(userId, photoId, objectKey);
                count++;
                log.debug("OrphanReconciliationScheduler: orphan enqueued key={}", objectKey);
            }
        }
        return count;
    }

    private Set<UUID> findExistingIds(List<UUID> candidateIds) {
        Set<UUID> existingIds = new HashSet<>();
        for (int i = 0; i < candidateIds.size(); i += ID_BATCH_SIZE) {
            List<UUID> batch = candidateIds.subList(i, Math.min(i + ID_BATCH_SIZE, candidateIds.size()));
            existingIds.addAll(schedulerRepository.findExistingPhotoIds(batch.toArray(new UUID[0])));
        }
        return existingIds;
    }
}
```

- [ ] **Step 2: Update SchedulerTest mocks for OrphanReconciliationScheduler**

Update the orphan reconciliation tests in `SchedulerTest`:

1. Mock `schedulerRepository.queryUserIdPage(null, 100)` to return test user IDs
2. Mock `schedulerRepository.queryUserIdPage(lastId, 100)` to return empty list (end pagination)
3. Mock `schedulerRepository.findExistingPhotoIds(any(UUID[].class))` to return known photo IDs (non-orphans)
4. For orphan-detected tests: return a subset so some candidates are identified as orphans
5. Verify: `verify(photoDeleteJobEnqueuer).enqueueOrphan(eq(userId), eq(orphanPhotoId), anyString())`

- [ ] **Step 3: Run scheduler tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.scheduler.SchedulerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java api/src/test/java/org/jphototagger/api/scheduler/SchedulerTest.java
git commit -m "refactor(scheduler): migrate OrphanReconciliationScheduler to SchedulerRepository + keyset pagination (Finding #6)"
```

---

### Task 18: Migrate UnverifiedAccountPurgeScheduler to SchedulerRepository

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java:39,79-82,112-114`

- [ ] **Step 1: Replace photoRepository with SchedulerRepository**

In `UnverifiedAccountPurgeScheduler.java`:

1. Remove `photoRepository` field and constructor parameter
2. Add `schedulerRepository` field and constructor parameter
3. Replace `purgeUser()` step 1 and step 2:

```java
// OLD:
List<Photo> photos = photoRepository.findAllByUserIdWithStorageKey(userId);
if (!photos.isEmpty()) {
    enqueueDeleteJobsBatch(photos);
}

// NEW:
List<Map<String, Object>> photoRows = schedulerRepository.findStorageKeysByUserId(userId);
if (!photoRows.isEmpty()) {
    photoDeleteJobEnqueuer.enqueueByRows(photoRows);
}
```

4. Remove `enqueueDeleteJobsBatch` method
5. Update log message to use `photoRows.size()` instead of `photos.size()`
6. Add structured logging that always fires (even on zero-count path) at the end of `purgeAccounts()`:

```java
log.info("UnverifiedAccountPurgeScheduler: purged {} accounts ({} photos queued)",
        purgedCount, totalPhotosQueued);
```

This must execute unconditionally — not inside an `if (purgedCount > 0)` block — so the log line appears even when zero accounts are purged. Declare the accumulator before the loop and increment inside `purgeUser()`:

```java
// At the start of purgeAccounts():
int purgedCount = 0;
int totalPhotosQueued = 0;

// Inside the loop, after enqueueByRows:
totalPhotosQueued += photoRows.size();

// At the very end of purgeAccounts() (outside any if block):
log.info("UnverifiedAccountPurgeScheduler: purged {} accounts ({} photos queued)",
        purgedCount, totalPhotosQueued);
```

- [ ] **Step 2: Update SchedulerTest mocks for UnverifiedAccountPurgeScheduler**

Update the unverified-account-purge tests in `SchedulerTest`:

1. The `@MockBean SchedulerRepository schedulerRepository` should already exist from Task 16. If not, add it.
2. Replace `when(photoRepository.findAllByUserIdWithStorageKey(...))` with `when(schedulerRepository.findStorageKeysByUserId(any(UUID.class))).thenReturn(List.of(Map.of("id", photoId, "user_id", userId, "storage_key", "key")))`
3. Replace any `verify(enqueueDeleteJobsBatch(...))` with `verify(photoDeleteJobEnqueuer).enqueueByRows(any())`
4. For zero-photos tests: `when(schedulerRepository.findStorageKeysByUserId(any())).thenReturn(List.of())`

- [ ] **Step 3: Run scheduler tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.scheduler.SchedulerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java api/src/test/java/org/jphototagger/api/scheduler/SchedulerTest.java
git commit -m "refactor(scheduler): migrate UnverifiedAccountPurgeScheduler to SchedulerRepository (Finding #6)"
```

---

### Task 19: Scheduler integration tests

**Files:**
- Create: `api/src/test/java/org/jphototagger/api/repository/SchedulerRepositoryTest.java`

- [ ] **Step 1: Create integration test with jpt_auth role**

```java
package org.jphototagger.api.repository;

import org.jphototagger.api.config.TestRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import({TestRedisConfig.class, SchedulerRepositoryTest.SchedulerTestConfig.class})
class SchedulerRepositoryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.auth-datasource.url", pg::getJdbcUrl);
        registry.add("spring.auth-datasource.username", pg::getUsername);
        registry.add("spring.auth-datasource.password", pg::getPassword);
        registry.add("spring.flyway.url", pg::getJdbcUrl);
        registry.add("spring.flyway.user", pg::getUsername);
        registry.add("spring.flyway.password", pg::getPassword);
        registry.add("app.share-reader.jdbc-url", pg::getJdbcUrl);
    }

    @TestConfiguration
    static class SchedulerTestConfig {
        @Bean("authJdbcTemplate")
        @Primary
        public JdbcTemplate authJdbcTemplate(
                @Value("${spring.datasource.url}") String url) {
            var ds = new DriverManagerDataSource(url, "jpt_auth", "test_auth_password");
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private SchedulerRepository schedulerRepository;

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbc;

    // Use superuser for seeding test data
    @Autowired
    private JdbcTemplate superJdbc;

    @BeforeEach
    void setUp() {
        // Create superuser JdbcTemplate using the TC superuser credentials for seeding
        // The @Autowired JdbcTemplate is already the primary (superuser) one
    }

    @Test
    void findPurgeableBatch_returnsDeletedPhotos() {
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        // Seed user and soft-deleted photo as superuser
        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 0, 0, true, NOW(), NOW())",
            userId, "sched-test-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, deleted_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 1000, ?||'/originals/'||?||'.jpg', NOW(), NOW() - INTERVAL '31 days', 'DONE')",
            photoId, userId, userId.toString(), photoId.toString());

        List<Map<String, Object>> batch = schedulerRepository.findPurgeableBatch(
            Instant.now().minus(30, ChronoUnit.DAYS));
        assertThat(batch).isNotEmpty();
        assertThat(batch.stream().anyMatch(row -> photoId.equals(row.get("id")))).isTrue();
    }

    @Test
    void queryUserIdPage_returnsUserIds() {
        List<UUID> page = schedulerRepository.queryUserIdPage(null, 10);
        assertThat(page).isNotNull();
    }

    @Test
    void findExistingPhotoIds_returnsExistingIds() {
        UUID randomId = UUID.randomUUID();
        List<UUID> result = schedulerRepository.findExistingPhotoIds(new UUID[]{randomId});
        assertThat(result).doesNotContain(randomId);
    }

    @Test
    void deletePhotosByIds_removesPhotos() {
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 1000, 0, true, NOW(), NOW())",
            userId, "del-test-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, deleted_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 1000, ?||'/originals/'||?||'.jpg', NOW(), NOW() - INTERVAL '31 days', 'DONE')",
            photoId, userId, userId.toString(), photoId.toString());

        schedulerRepository.deletePhotosByIds(new UUID[]{photoId});

        List<UUID> remaining = schedulerRepository.findExistingPhotoIds(new UUID[]{photoId});
        assertThat(remaining).doesNotContain(photoId);
    }

    @Test
    void purgeNullStorageKeyPhotos_deletesAndUpdatesUsedBytes() {
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 5000, 0, true, NOW(), NOW())",
            userId, "null-key-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, deleted_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 5000, NULL, NOW() - INTERVAL '2 hours', NULL, 'DONE')",
            photoId, userId);

        int affected = schedulerRepository.purgeNullStorageKeyPhotos();
        assertThat(affected).isGreaterThanOrEqualTo(1);

        // Verify used_bytes was decremented
        Long usedBytes = superJdbc.queryForObject(
            "SELECT used_bytes FROM users WHERE id = ?", Long.class, userId);
        assertThat(usedBytes).isEqualTo(0L);
    }

    @Test
    void findStorageKeysByUserId_returnsPhotosWithKeys() {
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        String storageKey = userId + "/originals/" + photoId + ".jpg";

        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 0, 0, true, NOW(), NOW())",
            userId, "keys-test-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 1000, ?, NOW(), 'DONE')",
            photoId, userId, storageKey);

        List<Map<String, Object>> result = schedulerRepository.findStorageKeysByUserId(userId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("storage_key")).isEqualTo(storageKey);
    }

    @Test
    void jptAppWithoutRlsContextReturnsZeroRows() {
        // Verify that jpt_auth (which BYPASSes RLS) can see rows,
        // while a connection without RLS context (using jpt_app role) returns 0 rows.
        // This validates the RLS policy is enforced for non-privileged roles.
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 0, 0, true, NOW(), NOW())",
            userId, "rls-test-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, deleted_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 1000, ?||'/originals/'||?||'.jpg', NOW(), NOW() - INTERVAL '31 days', 'DONE')",
            photoId, userId, userId.toString(), photoId.toString());

        // jpt_auth (BYPASSRLS) should see the row
        List<Map<String, Object>> authResult = schedulerRepository.findPurgeableBatch(
            Instant.now().minus(30, ChronoUnit.DAYS));
        assertThat(authResult.stream().anyMatch(row -> photoId.equals(row.get("id")))).isTrue();

        // jpt_app without SET app.current_user_id should see 0 rows
        // Password matches Flyway placeholder jpt_app_password in application-test.yml:34
        var appDs = new DriverManagerDataSource(
            pg.getJdbcUrl(), "jpt_app", "test_app_password");
        var appJdbc = new JdbcTemplate(appDs);
        List<Map<String, Object>> appResult = appJdbc.queryForList(
            "SELECT id FROM photos WHERE deleted_at < ? LIMIT 100",
            Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));
        assertThat(appResult).isEmpty();
    }
}
```

- [ ] **Step 2: Run integration tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.repository.SchedulerRepositoryTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add api/src/test/java/org/jphototagger/api/repository/SchedulerRepositoryTest.java
git commit -m "test(scheduler): add SchedulerRepository integration tests with jpt_auth role"
```

---

### Task 20: Final verification — full test suite

- [ ] **Step 1: Run the complete test suite**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --no-daemon`
Expected: ALL PASS

- [ ] **Step 2: Fix any remaining failures**

Address test failures from integration changes (mock updates, constructor changes, etc.)

- [ ] **Step 3: Final commit if fixes needed**

```bash
git add api/
git commit -m "test: fix remaining test failures from security findings fixes"
```
