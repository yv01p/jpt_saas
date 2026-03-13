# Security Findings Fixes — Design Spec

**Date:** 2026-03-13
**Source:** security-scan-report-2026-03-13.md
**Scope:** 8 validated findings + 4 discarded-but-worth-fixing findings (12 total)

---

## Overview

This spec covers complete fixes for all findings from the 2026-03-13 security scan of JPT SaaS. Fixes are grouped into 6 sections matching the design review. No partial fixes — every finding is addressed to its root cause.

---

## Section 1: Auth Hardening (Findings #1, #2, #3)

### Files changed
- `api/src/main/java/org/jphototagger/api/service/AuthService.java`
- `api/src/main/java/org/jphototagger/api/controller/AuthController.java`

### Finding #1 — Broken timing equalization (dummy BCrypt hash too long)

**Root cause:** The dummy hash string is 65 characters. `BCryptPasswordEncoder.matches()` requires exactly 60 characters (7-char prefix + 53-char hash body) or it fails the pattern check immediately (~1ms) instead of running BCrypt (~250ms). Unknown emails return in ~1ms; known emails with wrong password return in ~250ms — a reliable enumeration oracle.

**Fix:** Replace the broken constant with a valid 60-character BCrypt hash stored as a named private constant:
```java
private static final String DUMMY_HASH =
    "$2a$12$xxxxxxxxxxxxxxxxxxxxxxxxuAQIkWFkNuvxPFMO3a4YFnPkLJYrK.";
```
The constant name documents intent. The value passes the BCrypt pattern check, causing full ~250ms computation for the unknown-email path.

### Finding #2 — HTTP status oracle enables lockout-free brute-force on unverified accounts

**Root cause:** When a user submits the correct password but their email is unverified, `authenticate()` throws `EmailVerificationRequiredException` → HTTP 403, while wrong passwords throw `BadCredentialsException` → HTTP 401. The `failed_login_attempts` counter is only incremented on the wrong-password path. Correct-password probes against unverified accounts never increment the counter, bypassing lockout entirely during the 24-hour verification window.

**Fix (two parts):**

1. In `AuthService.authenticate()`: call `incrementFailedAttempts(userId)` (see Finding #3 below) before throwing `EmailVerificationRequiredException`, applying the same lockout logic as wrong passwords.
2. In `AuthController.login()`: map `EmailVerificationRequiredException` → HTTP 401 with the generic body `{"error": "Invalid credentials", "status": 401}` — identical to the wrong-password response. This removes the status-code oracle entirely.

> UX implication: Legitimate unverified users see "Invalid credentials." The frontend should display a hint such as "Registered recently? Check your inbox or request a new verification link." The `/auth/verify` endpoint handles resending.

### Finding #3 — Non-atomic lockout counter race condition

**Root cause:** Failed login counter uses a read-modify-write sequence (SELECT then UPDATE) with no row locking. Concurrent requests all read the same counter value and all write the same incremented value, leaving the counter under-counted. Five simultaneous wrong-password requests may result in a counter of 1 or 2 instead of 5, preventing lockout.

**Fix:** Extract a private `incrementFailedAttempts(UUID userId)` helper that performs a single atomic SQL statement:

```sql
UPDATE users
SET failed_login_attempts = failed_login_attempts + 1,
    locked_until = CASE
        WHEN failed_login_attempts + 1 >= 5 THEN NOW() + INTERVAL '15 minutes'
        ELSE locked_until
    END
WHERE id = ?
```

This helper replaces both branches of the existing if/else UPDATE in the wrong-password path, and is also called in the unverified-email path (Finding #2). The lockout threshold and duration remain `MAX_FAILED_ATTEMPTS = 5` and `LOCKOUT_DURATION = 15 minutes` as currently defined.

`authenticate()` flow after all three fixes:
1. Query user by email → if not found, run `passwordEncoder.matches(pw, DUMMY_HASH)`, throw `BadCredentialsException`
2. Always call `passwordEncoder.matches(pw, storedHash)` (timing preservation)
3. If wrong password → `incrementFailedAttempts(userId)` → throw `BadCredentialsException`
4. If correct password but account locked → throw `BadCredentialsException`
5. If correct password, not locked, email unverified → `incrementFailedAttempts(userId)` → throw `EmailVerificationRequiredException`
6. Success → reset counter to 0, return `{userId, email}`

---

## Section 2: Share System (Findings #4, #5, C3)

### Files changed
- `api/src/main/java/org/jphototagger/api/service/MetadataLocationStripper.java` *(new)*
- `api/src/main/java/org/jphototagger/api/service/ShareService.java`
- `api/src/main/java/org/jphototagger/api/controller/ShareController.java`
- `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java`
- `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java` *(update to delegate)*

### Finding #4 — IPTC/XMP location data leaked when includeGps=false

**Root cause:** `ShareService.stripGpsFromExif()` only strips EXIF data. IPTC fields (City, Sub-location, Province-State, Country) and XMP fields (photoshop:City, iptc4xmpcore:Location, etc.) are written raw to the share response even when `includeGps=false`. The authenticated path (`PhotoMetadataResponse.withoutGps()`) correctly handles all three; the share path has a separate, incomplete implementation.

**Fix:** Create `MetadataLocationStripper` — a Spring `@Component` utility with three public methods:

- `stripGpsFromExif(String exifJson)` — removes all keys matching `(?i)gps.*` (existing logic moved here)
- `stripLocationFromIptc(String iptcJson)` — removes IPTC location keys:
  `City`, `Sub-location`, `Province-State`, `Country-Primary Location Name`, `Country-Primary Location Code`, `Location`
- `stripLocationFromXmp(String xmpJson)` — removes XMP location keys:
  `photoshop:City`, `photoshop:State`, `photoshop:Country`, `iptc4xmpcore:Location`, `iptc4xmpcore:CountryCode`, `iptc4xmpcore:CountryName`, `iptc4xmpcore:ProvinceState`, `iptc4xmpcore:Sublocation`, `dc:coverage`

All three methods are null-safe and return `null` on JSON parse failure (fail-closed: no location data leaks on error).

`ShareService.stripGpsFromExif()` is updated to delegate to `MetadataLocationStripper.stripGpsFromExif()` (preserving the existing public API). `PhotoMetadataResponse` is updated to also delegate to the same utility, so both paths share identical key sets.

In `ShareController.getShare()`, the `!includeGps` block is expanded to call all three strippers:
```java
if (!includeGps) {
    if (photo.get("exif_data") != null)
        photo.put("exif_data", stripper.stripGpsFromExif(photo.get("exif_data").toString()));
    if (photo.get("iptc_data") != null)
        photo.put("iptc_data", stripper.stripLocationFromIptc(photo.get("iptc_data").toString()));
    if (photo.get("xmp_data") != null)
        photo.put("xmp_data", stripper.stripLocationFromXmp(photo.get("xmp_data").toString()));
}
```

### Finding #5 — Raw storage_key exposed in share response

**Root cause:** `storage_key` is selected in both `findPhotoById()` and `findAlbumPhotos()` SQL queries. The controller uses it to generate presigned URLs but never removes it from the response map. Format `{userId}/originals/{photoId}.ext` exposes the photo owner's internal UUID to unauthenticated share visitors.

**Fix (two call sites):**

1. **`ShareController.getShare()` (photo share):** After URL generation, add `photo.remove("storage_key")` before `response.put("photo", photo)`.

2. **`ShareController.getSharedAlbumPhotos()` (album share):** Currently returns `findAlbumPhotos()` bare with no post-processing. Replace the direct return with a mapped page that for each photo: (a) generates `thumbnailUrl`/`originalUrl` presigned URLs using the same parsing logic as `getShare()`, (b) removes `storage_key`. This also fixes the missing URL generation for album photos.

### Finding C3 — findPhotoById has no user_id predicate under BYPASSRLS

**Root cause:** `ShareLookupRepository.findPhotoById(UUID photoId)` runs as `share_reader` (BYPASSRLS) with only `WHERE p.id = ? AND p.deleted_at IS NULL`. If a share record's `resource_id` were corrupted, it would serve any user's private photo to an unauthenticated visitor.

**Fix:** Add `ownerId` parameter to `findPhotoById(UUID photoId, UUID ownerId)` and add `AND p.user_id = ?` to the WHERE clause. `findShareByTokenHash()` already returns `s.user_id` in its result map, so `ShareController.getShare()` passes `(UUID) shareData.get("user_id")` as `ownerId` — no additional query required.

---

## Section 3: Scheduler RLS Bypass (Finding #6)

### Files changed
- `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`
- `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`
- `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java`
- `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java`

### Finding #6 — Background schedulers silently no-op due to missing RLS context

**Root cause:** Scheduler threads have no HTTP request context, so `RlsContext.getCurrentUserId()` returns null. `RlsAspect` no-ops when userId is null, leaving `app.current_user_id = '00000000-...'` (from `connection-init-sql`) as the active RLS context. PostgreSQL evaluates `user_id = null::uuid` → NULL for every row, returning 0 results. All primary-datasource repository calls in schedulers silently return empty.

**Fix pattern:** Replace all primary-datasource (`photoRepository`, `userRepository`) calls in schedulers with `authJdbcTemplate` (BYPASSRLS) SQL queries.

**`TrashPurgeScheduler`:**
- Inject `@Qualifier("authJdbcTemplate") JdbcTemplate authJdbc` (replace the existing primary `jdbcTemplate` injection)
- Remove `photoRepository` injection
- `photoRepository.findPurgeableBatch(cutoff)` → authJdbc SQL returning `(id, storage_key, user_id, size_bytes)` tuples
- `photoRepository.deleteAllById(ids)` → authJdbc `DELETE FROM photos WHERE id = ANY(?)`
- `purgeNullStorageKeyPhotos()` already uses the injected `jdbcTemplate` — switch to `authJdbc` (the SQL is correct, just wrong datasource)

**`OrphanReconciliationScheduler`:**
- Inject `@Qualifier("authJdbcTemplate") JdbcTemplate authJdbc`
- Remove `userRepository` and `photoRepository` injections
- Remove `@Transactional(readOnly = true)` annotation (no longer using JPA; ShedLock still serializes runs)
- `userRepository.streamAllIds()` → `authJdbc.queryForList("SELECT id FROM users", UUID.class)`
- `photoRepository.findAllById(batch)` in `findExistingIds()` → `authJdbc.queryForList("SELECT id FROM photos WHERE id = ANY(?)", UUID.class, batchArray)` with same batch-size partitioning

**`UnverifiedAccountPurgeScheduler`:**
- Keep `authJdbcTemplate` (already used correctly for user operations)
- Remove `photoRepository` injection
- `photoRepository.findAllByUserIdWithStorageKey(userId)` → `authJdbcTemplate.queryForList("SELECT storage_key FROM photos WHERE user_id = ? AND storage_key IS NOT NULL", String.class, userId)`

**`PhotoDeleteJobEnqueuer`:**
- Add overload `enqueue(UUID userId, List<String> storageKeys)` for use by `UnverifiedAccountPurgeScheduler` (which now has storage keys as strings, not `Photo` entities)
- Add overload `enqueueByRows(List<Map<String,Object>> rows)` or similar for `TrashPurgeScheduler` (which now gets raw SQL result maps)

---

## Section 4: Restore Race Condition (Finding #7)

### Files changed
- `api/src/main/java/org/jphototagger/api/service/PhotoService.java`

### Finding #7 — restore() reads photo before acquiring user lock

**Root cause:** `restore()` fetches the photo row before acquiring `PESSIMISTIC_WRITE` on the user row. Two concurrent requests for the same photoId both pass the `deletedAt != null` filter, then sequentially acquire the user lock and each add `sizeBytes` — permanently inflating `usedBytes` by 2×. `softDelete()` correctly acquires the user lock first.

**Fix:** Reorder `restore()` to mirror `softDelete()` — acquire user lock first, then re-read photo inside the lock. No logic changes required.

```java
@Transactional
public void restore(UUID userId, UUID photoId) {
    // Lock user row FIRST — mirrors softDelete() pattern
    User user = entityManager.createQuery(
            "SELECT u FROM User u WHERE u.id = :userId", User.class)
            .setParameter("userId", userId)
            .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
            .getSingleResult();

    // Re-read photo inside the lock — second concurrent request finds deletedAt==null and throws
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

---

## Section 5: OAuth2 Email Verification Guard (Finding #8)

### Files changed
- `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java`

### Finding #8 — OAuth2 email_verified claim not checked before account creation

**Root cause:** `OAuth2SuccessHandler.onAuthenticationSuccess()` extracts the email from OIDC claims and proceeds to DB lookup and account creation without checking `oidcUser.getEmailVerified()`. Currently safe with Google (always sends `email_verified=true`), but any future OIDC provider that issues unverified email claims would allow account registration with an email the user does not own.

**Fix:** Add a guard immediately after the null/blank email check, before any DB work:

```java
Boolean emailVerified = oidcUser.getEmailVerified();
if (!Boolean.TRUE.equals(emailVerified)) {
    response.sendRedirect(redirectUri + "login?error=email_not_verified");
    return;
}
```

`Boolean.TRUE.equals()` handles null safely — absent claim fails closed (rejected), not open (accepted).

---

## Section 6: Keyword & Access Control (D1, D3, D4)

### Files changed
- `api/src/main/java/org/jphototagger/api/service/KeywordService.java`
- `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java`
- `api/src/main/java/org/jphototagger/api/controller/PhotoController.java`
- `api/src/main/java/org/jphototagger/api/service/PhotoService.java` *(add @Transactional to keyword methods)*

### D1 — updateKeyword() missing parentId ownership check

**Root cause:** `createKeyword()` validates that `parentId` belongs to the authenticated user before saving. `updateKeyword()` skips this check. PostgreSQL FK constraint validates against the full `keywords` table (bypasses RLS), so 200 OK confirms UUID exists as any tenant's keyword — a cross-tenant existence oracle. Also creates data integrity anomaly: cross-user FK references.

**Fix:** Add the identical ownership guard to `updateKeyword()` before save:

```java
if (parentId != null) {
    keywordRepository.findById(parentId)
        .filter(p -> p.getUserId().equals(userId))
        .orElseThrow(() -> new EntityNotFoundException("Parent keyword not found"));
}
```

### D3 — Recursive keyword CTE missing user_id in recursive step

**Root cause:** The recursive CTE anchor filters by `user_id`; the recursive expansion step joins only on `parent_id`. RLS is the sole tenant guard for the recursive step. If RLS is misconfigured or bypassed, the recursive join traverses all tenants.

**Fix:** Add `AND k.user_id = :userId` to the recursive step:

```sql
-- recursive step AFTER
SELECT k.* FROM keywords k
INNER JOIN subtree s ON k.parent_id = s.id
WHERE k.user_id = :userId
```

### D4 — @Transactional on PhotoController methods

**Root cause:** `addKeywordToPhoto` and `removeKeywordFromPhoto` in `PhotoController` carry `@Transactional`. The transaction opens at the controller layer, before `RlsAspect` runs (currently safe due to `@Order` values, but fragile). If aspect ordering changes, the transaction opens without RLS context set.

**Fix:** Remove `@Transactional` from both `PhotoController` methods and add it to the corresponding service methods in `PhotoService`. Transaction boundary moves to the service layer where `RlsAspect` has already executed — the correct Spring layering pattern.

---

## Implementation Order

Apply in this sequence to minimize risk and make each step independently testable:

1. **Auth fixes** (#1, #2, #3) — `AuthService` + `AuthController`
2. **OAuth2 guard** (#8) — `OAuth2SuccessHandler`
3. **restore() reorder** (#7) — `PhotoService`
4. **Share system** (#4, #5, C3) — `MetadataLocationStripper` (new) + `ShareService` + `ShareController` + `ShareLookupRepository`
5. **Keyword fixes** (D1, D3, D4) — `KeywordService` + `KeywordRepository` + `PhotoController` + `PhotoService`
6. **Scheduler fixes** (#6) — `PhotoDeleteJobEnqueuer` + all three schedulers

---

## Out of Scope

- Frontend XSS audit (not covered by the scan)
- Nginx/TLS deep audit (headers reviewed, no findings)
- MinIO IAM policy review (not present in repo)
- Dependency CVE scan (pom.xml/package.json not scanned)
