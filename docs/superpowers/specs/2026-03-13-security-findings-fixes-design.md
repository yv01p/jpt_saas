# Security Findings Fixes — Design Spec

**Date:** 2026-03-13
**Version:** 4
**Source:** security-scan-report-2026-03-13.md
**Scope:** 8 validated findings + 4 discarded-but-worth-fixing findings (12 total)

---

## Changelog

### v4 — 2026-03-13

Incorporates all accepted findings from `2026-03-13-security-findings-fixes-design-critical-review-3.md`.

| ID | Type | Summary | Section |
|----|------|---------|---------|
| C9 | Should fix | `listKeywordsForPhoto` N+1 replaced with single JOIN query in repository layer | §6 D4 |
| C10 | Should fix | `queryUserIds` decoupled from processing — paginated keyset approach replaces long-held read transaction | §3 Finding #6 |
| M9 | Nice to have | Post-expiry lockout resets counter to 0 (fresh 5-attempt window); password reset accessibility documented | §1 Finding #3 |
| M10 | Nice to have | `addKeywordToPhoto` checks for existing association before save — idempotent 200 on duplicate | §6 D4 |
| M11 | Nice to have | `findStorageKeysByUserId` returns `(id, user_id, storage_key)` — eliminates `enqueueStorageKeys` method, reuses `enqueueByRows` | §3 Finding #6 |

### v3 — 2026-03-13

Incorporates all accepted findings from `2026-03-13-security-findings-fixes-design-critical-review-2.md`.

| ID | Type | Summary | Section |
|----|------|---------|---------|
| C6 | Must fix | `RowCallbackHandler` now uses `fetchSize(100)` + `@Transactional(readOnly = true)` for true server-side cursoring — fixes false streaming claim | §3 Finding #6 |
| C8 | Must fix | Atomic SQL `CASE WHEN` only sets `locked_until` on transition into lockout, not on every increment — prevents indefinite lockout extension DoS | §1 Finding #3 |
| C7 | Should fix | Scheduler integration tests specify `@TestConfiguration` connecting as `jpt_auth`, with positive and negative permission tests | §3 Finding #6 |
| M6 | Nice to have | `enrichPhotoWithPresignedUrls` uses `photo.get("id")` directly instead of reverse-parsing storage_key | §2 Finding #5 |
| M7 | Nice to have | V14 migration adds documentation comment on `DELETE ON users` grant blast radius | §3 Finding #6 |
| M8 | Nice to have | `listKeywordsForPhoto` moved to `PhotoService`; `PhotoController` no longer depends on keyword repositories | §6 D4 |

### v2 — 2026-03-13

Incorporates all accepted findings from `2026-03-13-security-findings-fixes-design-critical-review-1.md`.

| ID | Type | Summary | Section |
|----|------|---------|---------|
| C1 | Must fix | `MetadataLocationStripper` owns key sets as `public static final`; `PhotoMetadataResponse` deletes its copies — single source of truth | §2 Finding #4 |
| C2 | Must fix | Step 6 throws `BadCredentialsException` directly, not `EmailVerificationRequiredException` — eliminates oracle at source | §1 Finding #2 |
| C5 | Must fix | Step 5 (correct password + locked) calls `incrementFailedAttempts()` — equalizes timing, prevents lockout-window enumeration | §1 Finding #3 |
| C4 | Should fix | V14 becomes comprehensive scheduler permissions migration — all tables, all schedulers, version-controlled | §3 Finding #6 |
| C3 | Should fix | Add `SchedulerRepository`, integration tests, structured row-count logging for scheduler SQL migration | §3 Finding #6 |
| M1 | Nice to have | Extract `enrichPhotoWithPresignedUrls()` helper in `ShareController` — deduplicate URL generation | §2 Finding #5 |
| M2 | Nice to have | Extract `buildDeleteJobMessage()` helper in `PhotoDeleteJobEnqueuer` — single message format definition | §3 |
| M3 | Nice to have | Use `RowCallbackHandler` instead of `queryForList` for `OrphanReconciliationScheduler` user iteration — replaced with server-side cursoring in v3 (C6) | §3 |
| M4 | Verified | Confirmed `PhotoMetadataResponse` private filter methods are safe to remove (single call site each) | §2 Finding #4 |
| M5 | Follow-up | Frontend login failure hint documented as required follow-up | §7 |
| Q1 | Clarification | `findPhotoById` user_id predicate is defense-in-depth, not response to known attack vector | §2 Finding C3 |
| Q2 | Clarification | V14 supersedes out-of-band grants; Flyway is now single source of truth for scheduler permissions | §3 |
| Q3 | Clarification | Lazy `AtomicReference` cold-start ~250ms is indistinguishable from normal BCrypt — no timing oracle | §1 Finding #1 |
| Q4 | Clarification | Provider onboarding requirement: OIDC providers must supply `email_verified` claim | §5 Finding #8 |

### v1 — 2026-03-13

Initial spec covering 12 findings from security scan.

---

## Overview

This spec covers complete fixes for all findings from the 2026-03-13 security scan of JPT SaaS. Fixes are grouped into 6 sections. No partial fixes — every finding is addressed to its root cause.

---

## Section 1: Auth Hardening (Findings #1, #2, #3)

### Files changed
- `api/src/main/java/org/jphototagger/api/service/AuthService.java`
- `api/src/main/java/org/jphototagger/api/controller/AuthController.java`

### Finding #1 — Broken timing equalization (dummy BCrypt hash)

**Root cause:** The dummy hash string used for unknown-email paths is 65 characters long. `BCryptPasswordEncoder.matches()` requires exactly 60 characters (7-char `$2a$12$` prefix + 53-char hash body) or it fails a regex check immediately (~1 µs) without running BCrypt (~250 ms). Unknown emails return in ~1 ms; known emails with wrong password return in ~250 ms — a reliable enumeration oracle.

**Fix:** Replace the hardcoded invalid constant with a lazy `AtomicReference` backed by the injected `PasswordEncoder` bean:

```java
private final AtomicReference<String> dummyHash = new AtomicReference<>();

private String getDummyHash() {
    return dummyHash.updateAndGet(h -> h != null ? h :
        passwordEncoder.encode("__dummy__credential__for__timing__equalization__"));
}
```

`getDummyHash()` calls `this.passwordEncoder` — the actual injected encoder bean — so the cost factor always matches the application-wide BCrypt configuration. The hash is computed once on first use (first unknown-email login attempt) and then cached in the `AtomicReference`. `authenticate()` calls `passwordEncoder.matches(pw, getDummyHash())` instead of the old constant.

**Why `AtomicReference` over `static final`:** A static field initialiser with `new BCryptPasswordEncoder(12)` hard-codes the cost factor at 12. If the application bean is later reconfigured to a different cost factor (e.g., 13), the dummy hash and the real hashes diverge, recreating the timing gap. Using the injected bean guarantees the cost factor always matches regardless of configuration.

**Cold-start note:** The first unknown-email request triggers a ~250ms BCrypt `encode()` to populate the cache. This latency is indistinguishable from a normal known-email BCrypt `matches()` call, so no timing oracle is introduced. Eager `@PostConstruct` initialization is unnecessary.

### Finding #2 — HTTP status oracle enables lockout-free brute-force on unverified accounts

**Root cause:** When a user submits the correct password but their email is unverified, `authenticate()` throws `EmailVerificationRequiredException` → HTTP 403, while wrong passwords produce HTTP 401. The `failed_login_attempts` counter is only incremented on the wrong-password path. Correct-password probes against unverified accounts never hit the counter — lockout is bypassed entirely during the 24-hour verification window.

**Fix:** Eliminate the oracle at the source — `AuthService.authenticate()` throws `BadCredentialsException` directly from the unverified-email path (step 6). No distinct exception type, no controller-level remapping needed.

1. In `AuthService.authenticate()` step 6: call `incrementFailedAttempts(userId)` (see Finding #3), then throw `BadCredentialsException("Invalid credentials")` — identical to the wrong-password path. The oracle is eliminated at the service layer: no consumer can ever distinguish unverified-email from wrong-password, regardless of how they handle exceptions.
2. `AuthController.login()`: remove the `catch (EmailVerificationRequiredException)` block entirely. The existing `catch (BadCredentialsException)` → HTTP 401 handles all failure cases uniformly.

> **Why not remap at the controller?** The previous design threw `EmailVerificationRequiredException` from the service and relied on `AuthController.login()` to catch and remap to 401. But `GlobalExceptionHandler` (line 80) maps `EmailVerificationRequiredException` → HTTP 403 with body `"Email verification required"`. Any future call site or exception handler that doesn't replicate the controller's catch block re-exposes the oracle. Throwing `BadCredentialsException` directly eliminates this class of regression.

> **UX note:** Legitimate unverified users see "Invalid credentials." The frontend login error page must display a **static** secondary hint on all login failures: *"Registered recently? Check your inbox or request a new verification link."* This hint is not triggered by any server-side signal — it appears for every failure, leaking no information. See §7 Required Follow-ups.

### Finding #3 — Non-atomic lockout counter race condition

**Root cause:** Failed login counter uses a read-modify-write sequence (SELECT then UPDATE) with no row locking. Concurrent requests all read the same counter value and all write the same incremented value, under-counting the actual attempts. Five simultaneous wrong-password requests may leave the counter at 1 or 2 instead of 5.

**Fix:** Extract a private `incrementFailedAttempts(UUID userId)` helper using a single atomic SQL statement:

```sql
UPDATE users
SET failed_login_attempts = CASE
        WHEN locked_until IS NOT NULL AND locked_until < NOW()
            THEN 1  -- expired lockout: reset counter, this is attempt 1
        ELSE failed_login_attempts + 1
    END,
    locked_until = CASE
        WHEN locked_until IS NOT NULL AND locked_until < NOW()
            THEN NULL  -- clear expired lockout, fresh 5-attempt window
        WHEN failed_login_attempts + 1 >= 5
             AND (locked_until IS NULL)
            THEN NOW() + INTERVAL '15 minutes'
        ELSE locked_until
    END
WHERE id = ?
```

This helper replaces both branches of the existing wrong-password UPDATE, and is also called by the unverified-email path (Finding #2). No separate SELECT is needed; the increment and conditional lockout are one atomic operation.

**Post-expiry lockout decay (v4 — M9):** When a lockout has expired (`locked_until IS NOT NULL AND locked_until < NOW()`), the counter resets to 1 (this attempt) and `locked_until` is cleared — giving the user a fresh window of 5 attempts. This replaces the v3 behaviour where an expired lockout immediately re-triggered on the first wrong attempt (1 attempt per 15-minute window), which created a usability trap for users who genuinely forgot their password. The maximum brute-force rate with this design is 20 attempts/hour (5 per 15-minute window), which is acceptable given password complexity requirements. The password reset flow is accessible from the login page without authentication — this is the intended escape hatch for locked-out users.

**Bounded lockout (v3 — C8, updated v4 — M9):** During an active lockout (`locked_until` is in the future), `incrementFailedAttempts()` still increments the counter (preserving the DB round-trip for timing equalization) but does not touch `locked_until` — the lockout expires on schedule. This prevents indefinite lockout extension: an attacker who knows only an email cannot keep an account permanently locked by sending one request every 14 minutes. Post-expiry, the counter resets to give a fresh 5-attempt window before the next lockout triggers.

**`authenticate()` method flow after all three fixes:**

1. Query user by email. If not found → `passwordEncoder.matches(pw, getDummyHash())` → throw `BadCredentialsException`.
2. Always call `passwordEncoder.matches(pw, storedHash)` (timing preservation; result stored in `passwordCorrect`).
3. Evaluate `isLocked` from the initial SELECT result (fields `failedLoginAttempts`, `lockedUntil` read at query time). **No re-fetch occurs within this call.**
4. If `!passwordCorrect` → `incrementFailedAttempts(userId)` → throw `BadCredentialsException`.
5. If correct password but `isLocked` (lockout still active per step 3 evaluation) → `incrementFailedAttempts(userId)` → throw `BadCredentialsException`.
6. If correct password, not locked, `!emailVerified` → `incrementFailedAttempts(userId)` → throw `BadCredentialsException`.
7. Success → `UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?` → return `{userId, email}`.

> **Step 5 timing equalization (v2 — C5, v3 — C8):** The previous design did not call `incrementFailedAttempts()` in step 5, creating a measurable timing difference (~1-3ms for the UPDATE) between wrong-password (step 4, with UPDATE) and correct-password-but-locked (step 5, no UPDATE). An attacker who has triggered lockout could probe passwords during the 15-minute lockout window: faster response = correct password. Calling `incrementFailedAttempts()` in step 5 equalizes the timing and counts probes for audit/monitoring — the counter increment is harmless since the account is already locked, and resets on successful login (step 7).
>
> **Bounded lockout (v3 — C8):** The atomic SQL's `CASE WHEN` includes `AND (locked_until IS NULL OR locked_until < NOW())` — `locked_until` is only set on the *transition* into lockout (when no active lockout exists), not extended on every increment. This prevents indefinite lockout extension: an attacker who knows only an email cannot keep an account permanently locked by sending one request every 14 minutes. During an active lockout, `incrementFailedAttempts()` still increments the counter (preserving the DB round-trip for timing equalization) but does not touch `locked_until` — the lockout expires on schedule. Post-expiry failures re-trigger lockout correctly (counter is still ≥5, but `locked_until` is in the past, so the `CASE WHEN` fires).

> **Step 6 oracle elimination (v2 — C2):** Step 6 now throws `BadCredentialsException` directly, not `EmailVerificationRequiredException`. See Finding #2 fix above for rationale.

> **Ordering note:** `isLocked` is evaluated before any `incrementFailedAttempts()` call (step 3 precedes steps 4–6). When a wrong-password attempt causes the threshold to be crossed, the atomic SQL sets `lockedUntil` in the DB, but `isLocked` in memory remains `false`. The caller correctly receives `BadCredentialsException` (step 4). On the *next* request the user will be locked — `isLocked` will be `true` from the fresh SELECT. This is correct; no re-evaluation within a single call is needed or desirable.

---

## Section 2: Share System (Findings #4, #5, C3)

### Files changed
- `api/src/main/java/org/jphototagger/api/service/MetadataLocationStripper.java` *(new)*
- `api/src/main/java/org/jphototagger/api/service/ShareService.java`
- `api/src/main/java/org/jphototagger/api/controller/ShareController.java`
- `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java`
- `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java` *(delegate to stripper)*

### Finding #4 — IPTC/XMP location data leaked when `includeGps=false`

**Root cause:** `ShareService.stripGpsFromExif()` only strips EXIF. IPTC fields (City, Sub-location, Province-State, Country) and XMP fields (photoshop:City, iptc4xmpcore:Location, etc.) are written raw to the share response even when `includeGps=false`. The authenticated path (`PhotoMetadataResponse.withoutGps()`) correctly handles all three metadata types; the share path has a separate, incomplete implementation — the classic divergent-implementation failure mode.

**Fix:** Create `MetadataLocationStripper` — a **plain utility class** (no `@Component`, no Spring dependency) with three **static Map-based** public methods (operating on `Map<String,Object>`, not JSON strings). Static methods eliminate the Spring injection requirement so `PhotoMetadataResponse` (a `record`) can call them directly without constructor changes. Each method returns `null` for `null` input (fail-closed; consistent with `PhotoMetadataResponse`'s existing private filter methods):

```java
/** Removes GPS-related keys from EXIF data. Returns null for null input. */
public static Map<String, Object> filterGpsFromExif(Map<String, Object> exif)

/** Removes location-related keys from IPTC data. Returns null for null input. */
public static Map<String, Object> filterLocationFromIptc(Map<String, Object> iptc)

/** Removes GPS and location-related keys from XMP data. Returns null for null input. */
public static Map<String, Object> filterLocationFromXmp(Map<String, Object> xmp)
```

**Key sets — `MetadataLocationStripper` is the single source of truth (v2 — C1).** The key sets are defined once as `public static final` constants in `MetadataLocationStripper`. `PhotoMetadataResponse` deletes its private copies and references the stripper's constants via delegation (since `withoutGps()` delegates entirely to the stripper's static methods, no direct constant reference is even needed in `PhotoMetadataResponse`).

```java
// MetadataLocationStripper — canonical definitions, public for any consumer:

// IPTC — 10 elements:
public static final Set<String> IPTC_LOCATION_KEYS = Set.of(
    "iptc:sub-location", "iptc:city", "iptc:province-state",
    "iptc:country-primary location code", "iptc:country-primary location name",
    "sub-location", "city", "province-state",
    "country-primary location code", "country-primary location name"
);

// XMP — 5 elements:
public static final Set<String> XMP_LOCATION_KEYS = Set.of(
    "photoshop:city", "photoshop:state", "photoshop:country",
    "iptc4xmpcore:location", "xmp:location"
);
```

> **Why not the previous design (v2 — C1):** The v1 spec defined identical `private static final` key sets in both `MetadataLocationStripper` and `PhotoMetadataResponse`, claiming the latter was "authoritative." This is duplicated constants — exactly the divergent-implementation failure mode that Finding #4 was written to fix. A future key addition would require updating two classes, re-introducing the data leak. One definition, two consumers.

All key comparisons use `entry.getKey().toLowerCase()` (case-insensitive), matching the existing `PhotoMetadataResponse` behaviour.

`filterGpsFromExif` removes keys where `lower.contains("gps")`.
`filterLocationFromIptc` removes keys where `IPTC_LOCATION_KEYS.contains(lower)`.
`filterLocationFromXmp` removes keys where `lower.contains("gps") || XMP_LOCATION_KEYS.contains(lower)`.

> **Predicate note:** The existing `ShareService.stripGpsFromExif()` uses `GPS_KEY_PATTERN = Pattern.compile("(?i)gps.*")` with `matches()` (full-string anchor), which only strips keys whose *entire name* starts with `"gps"` — it misses composite keys like `"EXIF:GPSLatitude"`. The authenticated path (`PhotoMetadataResponse.filterGpsKeys()`) uses `lower.contains("gps")`, which correctly strips all GPS-related keys regardless of prefix. The stripper adopts the authenticated path's `contains("gps")` predicate — this is an intentional broadening to fix the coverage gap. The old `GPS_KEY_PATTERN` in `ShareService` is removed when `stripGpsFromExif()` delegates to the stripper.

**`PhotoMetadataResponse.withoutGps()`** is updated to delegate to `MetadataLocationStripper`'s static methods directly — e.g., `MetadataLocationStripper.filterGpsFromExif(this.exif())`. The following are removed from `PhotoMetadataResponse`:
- The three private methods: `filterGpsKeys`, `filterLocationKeys`, `filterGpsAndLocationKeys` (confirmed single call site each — `withoutGps()` — safe to remove; v2 — M4).
- The two private key set constants: `IPTC_LOCATION_KEYS`, `XMP_LOCATION_KEYS` (moved to `MetadataLocationStripper` as `public static final`; v2 — C1).

No constructor/factory signature changes are needed: static calls are valid from inside a record's instance method.

**`ShareService`** gains three JSON-wrapper methods (parse JSON → call stripper → serialize back to JSON), which is exactly the pattern already used by `stripGpsFromExif()`:

```java
// ShareService: stripGpsFromExif() now delegates to MetadataLocationStripper.filterGpsFromExif()
// ShareService: add:
public String stripLocationFromIptc(String iptcJson)   // parse → filterLocationFromIptc → serialize
public String stripLocationFromXmp(String xmpJson)     // parse → filterLocationFromXmp → serialize
```

Parse failure in any JSON wrapper → return `null` (fail-closed: no location data leaks on malformed input), consistent with the existing `stripGpsFromExif()` behaviour.

**`ShareController.getShare()`** — the `!includeGps` block is expanded:

```java
if (!includeGps) {
    if (photo.get("exif_data") != null)
        photo.put("exif_data", shareService.stripGpsFromExif(photo.get("exif_data").toString()));
    if (photo.get("iptc_data") != null)
        photo.put("iptc_data", shareService.stripLocationFromIptc(photo.get("iptc_data").toString()));
    if (photo.get("xmp_data") != null)
        photo.put("xmp_data", shareService.stripLocationFromXmp(photo.get("xmp_data").toString()));
}
```

### Finding #5 — Raw `storage_key` exposed in share response

**Root cause:** `storage_key` is selected in both `findPhotoById()` and `findAlbumPhotos()` SQL queries. The controller uses it to generate presigned URLs but never removes it from the response map. Format `{userId}/originals/{photoId}.ext` exposes the photo owner's internal UUID to unauthenticated share visitors.

**Fix — extract `enrichPhotoWithPresignedUrls()` helper (v2 — M1, v3 — M6):** Both `getShare()` and `getSharedAlbumPhotos()` need the same storage_key → presigned URL logic. Extract a private helper in `ShareController` to avoid duplication:

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

> **Why `photo.get("id")` instead of parsing storage_key (v3 — M6):** The v2 spec parsed `photoId` from `storage_key.split("/")[2]` with regex extension stripping and a try-catch. But `photoId` is already selected as `p.id` in both `findPhotoById()` and `findAlbumPhotos()` SQL queries — JdbcTemplate maps PostgreSQL UUID columns to `java.util.UUID`. Using `photo.get("id")` directly eliminates the fragile string parsing, the regex, and the try-catch. `photo.remove("storage_key")` atomically reads and removes the key in one call.

**Fix — `ShareController.getShare()` (photo share):** After metadata stripping, before `response.put("photo", photo)`:
```java
UUID photoOwnerId = (UUID) shareData.get("user_id");
enrichPhotoWithPresignedUrls(photo, photoOwnerId);
```

**Fix — `ShareController.getSharedAlbumPhotos()` (album share):** Replace the bare `return shareLookupRepository.findAlbumPhotos(albumId, capped)` with a mapped page. The album owner UUID is available from `shareData.get("user_id")` — all photos in an album are guaranteed to belong to the album's creator (enforced by `validateResourceExists()`).

> **No GPS/location stripping needed:** `findAlbumPhotos()` does not join `photo_metadata` — the result maps contain no `exif_data`, `iptc_data`, or `xmp_data` columns. Location data cannot leak from album photo responses. Only `findPhotoById()` (single-photo shares) joins `photo_metadata` and requires stripping.

```java
UUID albumOwnerId = (UUID) shareData.get("user_id");
return shareLookupRepository.findAlbumPhotos(albumId, capped).map(rawPhoto -> {
    Map<String, Object> photo = new HashMap<>(rawPhoto);
    enrichPhotoWithPresignedUrls(photo, albumOwnerId);
    return photo;
});
```

### Finding C3 — `findPhotoById` has no `user_id` predicate under BYPASSRLS

**Root cause:** `ShareLookupRepository.findPhotoById(UUID photoId)` runs as `share_reader` (BYPASSRLS) with only `WHERE p.id = ? AND p.deleted_at IS NULL`. If a share record's `resource_id` were corrupted, it would serve any user's private photo to an unauthenticated visitor.

**Fix:** Add `ownerId` parameter: `findPhotoById(UUID photoId, UUID ownerId)`. Add `AND p.user_id = ?` as a third bind parameter in the WHERE clause.

`findShareByTokenHash()` already selects `s.user_id`, so `ShareController.getShare()` updates its call site:

```java
// Before:
var photoOpt = shareLookupRepository.findPhotoById(resourceId);
// After:
UUID ownerId = (UUID) shareData.get("user_id");
var photoOpt = shareLookupRepository.findPhotoById(resourceId, ownerId);
```

No additional query required — `ownerId` is already available in `shareData`.

> **Defense-in-depth note (v2 — Q1):** This fix is general hardening, not a response to a known attack vector. `resource_id` corruption would require SQL injection (not possible — parameterized queries throughout) or UUID collision (probability ~2^-122). The `AND p.user_id = ?` clause is a zero-cost safeguard against future schema or logic changes that might weaken the share→photo lookup path.

---

## Section 3: Scheduler RLS Bypass (Finding #6)

### Files changed
- `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java`
- `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`
- `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`
- `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java`
- `api/src/main/java/org/jphototagger/api/repository/SchedulerRepository.java` *(new — v2 C3)*
- `api/src/main/resources/db/migration/V14__grant_scheduler_permissions_to_jpt_auth.sql` *(new)*

### Finding #6 — Background schedulers silently no-op due to missing RLS context

**Root cause:** Scheduler threads have no HTTP request context, so `RlsContext.getCurrentUserId()` returns `null`. `RlsAspect` no-ops, leaving `app.current_user_id = '00000000-...'` (from `connection-init-sql`) as the active RLS context. PostgreSQL evaluates `user_id = null::uuid` → NULL for every row, returning 0 results. All primary-datasource (`jpt_app` role) repository calls in schedulers silently return empty.

**Fix pattern:** Replace all primary-datasource `photoRepository`/`userRepository` calls in schedulers with `authJdbcTemplate` (BYPASSRLS, `jpt_auth` role) SQL queries.

**DB permissions prerequisite:** `jpt_auth` currently holds `SELECT, INSERT` on `users` (column-level UPDATE on specific columns) and `SELECT, INSERT, DELETE` on `email_tokens` (V4 migration). The reviewed migrations (V1–V13) do not include grants for `photos`, `album_photos`, `albums`, `saved_searches`, `shares`, or `keywords` to `jpt_auth`. However, `UnverifiedAccountPurgeScheduler` already runs DML against all of these tables via `authJdbcTemplate` in production (`DELETE FROM album_photos`, `DELETE FROM photos`, `DELETE FROM albums`, `DELETE FROM saved_searches`, `DELETE FROM shares`, `UPDATE/DELETE keywords`, `DELETE FROM users`). This confirms that `jpt_auth` already holds the required permissions — they were granted outside the reviewed Flyway migrations (likely in the initial DB setup or a bootstrap script).

V14 is a **comprehensive "ensure all scheduler-required grants exist" migration (v2 — C4).** Every table that any scheduler touches via `authJdbcTemplate` is listed explicitly, with comments documenting which scheduler needs which grant. All `GRANT` statements are idempotent — safe even if grants already exist from out-of-band provisioning.

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
-- NOTE (v3 — M7): DELETE ON users is the highest-privilege grant to jpt_auth.
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

> **Note (v2 — Q2):** This migration supersedes any out-of-band grants previously applied to `jpt_auth` during environment provisioning (likely initial DB setup or bootstrap scripts). If the out-of-band mechanism is ever lost (DB rebuild, new environment, DR recovery), V14 ensures all scheduler permissions are present. The only grant that is definitively *new* (not covered by any prior mechanism) is `UPDATE (used_bytes) ON users` — the reviewed V4/V11 migrations only grant column-level UPDATE on `(password_hash, failed_login_attempts, locked_until, email_verified, oauth_provider, oauth_id, updated_at)`, which does not include `used_bytes`.

---

### `PhotoDeleteJobEnqueuer` — two new methods + message helper

The current message format (from `enqueue(List<Photo>)` and `enqueueOrphan()`):
```
{ photo_id, original_key, thumbnail_sm, thumbnail_md }
```
Thumbnail keys are always: `{userId}/thumbnails/{photoId}_sm.jpg` and `{userId}/thumbnails/{photoId}_md.jpg`.

**Extract `buildDeleteJobMessage()` helper (v2 — M2):** All enqueue methods construct the same Redis stream message. Define the format once:

```java
private Map<String, String> buildDeleteJobMessage(UUID userId, UUID photoId, String originalKey) {
    return Map.of(
        "photo_id",     photoId.toString(),
        "original_key", originalKey,
        "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
        "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
    );
}
```

All three methods (`enqueue`, `enqueueOrphan`, `enqueueByRows`) call this helper. (`enqueueStorageKeys` removed in v4 — M11; its caller now uses `enqueueByRows` directly.)

**Add `enqueueByRows(List<Map<String,Object>> rows)`:** Used by `TrashPurgeScheduler`. Each row map contains keys `id` (UUID), `user_id` (UUID), `storage_key` (String). Rows with null `storage_key` are logged and skipped. Uses pipelined XADD (same as `enqueue(List<Photo>)`):

```java
public void enqueueByRows(List<Map<String, Object>> rows) {
    redisTemplate.executePipelined(new SessionCallback<Object>() {
        @Override public Object execute(RedisOperations operations) {
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
```

**~~`enqueueStorageKeys`~~ — removed (v4 — M11).** `UnverifiedAccountPurgeScheduler` now calls `enqueueByRows()` directly with rows from `findStorageKeysByUserId()` (which returns `id, user_id, storage_key`). The string-parsing path is eliminated for DB-sourced data.

**`extractPhotoIdFromKey()` — retained for `OrphanReconciliationScheduler`:** This helper parses photo IDs from MinIO object keys (S3 listing), which is the only remaining path that doesn't have DB-sourced IDs. `OrphanReconciliationScheduler.extractPhotoId()` delegates to this method:

```java
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

---

### `SchedulerRepository` — encapsulate raw SQL (v2 — C3)

Create `api/src/main/java/org/jphototagger/api/repository/SchedulerRepository.java` — a `@Repository` class that wraps `authJdbcTemplate` and encapsulates all raw SQL used by schedulers. Scheduler classes call repository methods instead of inlining SQL. This keeps schedulers focused on orchestration, makes the SQL independently testable, and provides a single place to audit all scheduler DB access.

```java
@Repository
public class SchedulerRepository {
    private final JdbcTemplate authJdbc;

    public SchedulerRepository(@Qualifier("authJdbcTemplate") JdbcTemplate authJdbc) {
        this.authJdbc = authJdbc;
    }

    public List<Map<String, Object>> findPurgeableBatch(Instant cutoff) { ... }
    public void deletePhotosByIds(UUID[] ids) { ... }
    public int purgeNullStorageKeyPhotos() { ... }
    public List<UUID> queryUserIdPage(UUID afterId, int pageSize) { ... }
    public List<UUID> findExistingPhotoIds(UUID[] batch) { ... }
    public List<Map<String, Object>> findStorageKeysByUserId(UUID userId) { ... }
}
```

Each scheduler injects `SchedulerRepository` instead of `authJdbcTemplate` directly. The raw SQL from the subsections below lives in `SchedulerRepository`'s method bodies.

### Scheduler observability — structured logging (v2 — C3)

Each scheduler's `@Scheduled` method must log the final row count at `INFO` level **even when zero**:

- `TrashPurgeScheduler`: `"TrashPurgeScheduler: purged {} photos, cleaned {} null-storage-key rows"` (already partially present; ensure zero-count path logs too).
- `OrphanReconciliationScheduler`: `"OrphanReconciliationScheduler: enqueued {} orphaned objects"` (already present).
- `UnverifiedAccountPurgeScheduler`: `"UnverifiedAccountPurgeScheduler: purged {} accounts ({} photos queued)"` (partially present; ensure zero-count path logs too).

This would have caught Finding #6 immediately — logs would have shown `0` on every run.

### Scheduler integration tests (v2 — C3)

Add one integration test per scheduler class that runs against the `jpt_auth` datasource (via Testcontainers, which the project already uses) and verifies:

1. The SQL executes without permission errors against the `jpt_auth` role.
2. Expected row counts are returned against seeded test data (e.g., insert soft-deleted photos past cutoff, verify `findPurgeableBatch` returns them).
3. The `SchedulerRepository` methods are the test surface — scheduler orchestration logic is tested separately.

**Test environment setup (v3 — C7):** The existing test infrastructure runs Flyway migrations (which create `jpt_auth` via V4) but connects `authJdbcTemplate` as the Testcontainers superuser — every query succeeds regardless of grants. To meaningfully test permissions:

1. **`@TestConfiguration` overrides `authJdbcTemplate` to connect as `jpt_auth`** (inner class of the scheduler integration test — not shared, to avoid overriding `authJdbcTemplate` for non-scheduler tests that may need superuser access):
   ```java
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
   ```
   The `jpt_auth` role already exists (created by V4 migration with `test_auth_password` from Flyway placeholders in `application-test.yml`). No new infrastructure is required.

2. **Positive tests (one per `SchedulerRepository` method):** Seed test data, call the method as `jpt_auth`, verify expected row counts. These catch missing grants immediately — e.g., if V14 omits `SELECT ON photos`, `findPurgeableBatch` throws a permission error instead of silently returning empty.

3. **One negative test:** Verify that `jpt_app` (with RLS, no scheduler context) returns 0 rows for a scheduler query that `jpt_auth` returns N rows. This directly tests the Finding #6 failure mode:
   ```java
   @Test
   void jptAppWithoutRlsContextReturnsZeroRows() {
       // Seed a soft-deleted photo as superuser
       // Query via jpt_app datasource (no RLS context set) → expect 0 rows
       // Query via jpt_auth datasource → expect 1 row
   }
   ```

---

### `TrashPurgeScheduler` — full migration to `SchedulerRepository`

Remove `photoRepository` injection and the existing primary `jdbcTemplate` injection. Inject `SchedulerRepository` instead.

**Replace `photoRepository.findPurgeableBatch(cutoff)` → `schedulerRepository.findPurgeableBatch(cutoff)`:**
```java
// Inside SchedulerRepository.findPurgeableBatch():
List<Map<String, Object>> batch = authJdbc.queryForList(
    "SELECT id, user_id, storage_key FROM photos " +
    "WHERE deleted_at < ? " +
    "LIMIT 100",
    Timestamp.from(cutoff));
```

> **No `storage_key IS NOT NULL` filter:** The original `findPurgeableBatch()` returns all soft-deleted photos past the cutoff, including those with null `storage_key` (e.g., incomplete uploads that a user soft-deleted before processing finished). `enqueueByRows()` already logs and skips null-storage-key rows (no MinIO job needed), while `deletePhotosBatch()` deletes all rows by ID regardless of `storage_key`. Adding a `storage_key IS NOT NULL` filter would strand soft-deleted null-storage-key rows permanently — `purgeNullStorageKeyPhotos()` only handles `deleted_at IS NULL` rows.

**Replace `photoRepository.deleteAllById(ids)` → `schedulerRepository.deletePhotosByIds(idArray)`:**
```java
// Inside SchedulerRepository.deletePhotosByIds():
authJdbc.update("DELETE FROM photos WHERE id = ANY(?)",
    (PreparedStatement ps) -> ps.setArray(1,
        ps.getConnection().createArrayOf("uuid", ids)));
```

**Replace enqueue call:** `photoDeleteJobEnqueuer.enqueueByRows(batch)` (before delete, same as existing ordering).

**Replace `purgeNullStorageKeyPhotos()` → `schedulerRepository.purgeNullStorageKeyPhotos()`:** The SQL CTE (`DELETE FROM photos ... UPDATE users ...`) is unchanged; only the template reference changes. `jpt_auth` currently has column-level UPDATE on `(password_hash, failed_login_attempts, locked_until, email_verified, oauth_provider, oauth_id, updated_at)` — `used_bytes` is **not** in this list. The V14 migration above (which grants `UPDATE (used_bytes) ON users TO jpt_auth`) covers this requirement.

---

### `OrphanReconciliationScheduler` — two repository calls replaced

Remove `userRepository` and `photoRepository` injections. Inject `SchedulerRepository` instead. Remove `@Transactional(readOnly = true)` annotation.

**Replace `userRepository.streamAllIds()` → `schedulerRepository.queryUserIdPage()` with paginated keyset iteration (v2 — M3, v3 — C6, v4 — C10):**

The original code uses `userRepository.streamAllIds()` — a `Stream<UUID>` return type that, combined with `@Transactional(readOnly = true)` on the scheduler, triggers Hibernate's internal fetch-size mechanism for server-side cursoring. The v3 spec replaced this with `RowCallbackHandler` + `@Transactional(readOnly = true)` + `fetchSize(100)`, which achieves true server-side cursoring but holds a read transaction (and its database connection) open for the entire reconciliation loop. For large user sets, this pins a connection for minutes, starving the pool and holding back PostgreSQL's `xmin` horizon (preventing VACUUM from reclaiming dead tuples).

**Paginated keyset approach (v4 — C10):** Replace the long-held cursor transaction with short, independent page queries. Each page runs in autocommit — no transaction needed, connection returned immediately after each page:

```java
// Inside SchedulerRepository.queryUserIdPage():
public List<UUID> queryUserIdPage(UUID afterId, int pageSize) {
    if (afterId == null) {
        return authJdbc.queryForList(
            "SELECT id FROM users ORDER BY id LIMIT ?", UUID.class, pageSize);
    }
    return authJdbc.queryForList(
        "SELECT id FROM users WHERE id > ? ORDER BY id LIMIT ?",
        UUID.class, afterId, pageSize);
}

// Inside OrphanReconciliationScheduler.reconcileOrphans():
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
```

> **Why keyset pagination over server-side cursoring (v4 — C10):** The v3 `RowCallbackHandler` + `@Transactional(readOnly = true)` approach holds a database connection for the entire reconciliation loop (which includes MinIO `listObjects`, `findExistingPhotoIds` queries, and Redis enqueue per user). At 10k+ users this pins a connection for minutes, risking connection pool starvation and delaying VACUUM. Keyset pagination (`WHERE id > ? ORDER BY id LIMIT 100`) uses `users.id` PK index (O(1) seek), runs each page in autocommit (~milliseconds), and releases the connection between pages. The slight overhead of multiple queries is negligible compared to the per-user MinIO/DB/Redis work. This matches the `ID_BATCH_SIZE = 1000` batching pattern already used in `findExistingPhotoIds`.

**Replace `photoRepository.findAllById(batch)` in `findExistingIds()` → `schedulerRepository.findExistingPhotoIds(batchArray)`:**
```java
// Inside SchedulerRepository.findExistingPhotoIds():
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
```
Use `PreparedStatementCreator` (lambda) to set the PostgreSQL `uuid[]` array parameter — this is the correct `JdbcTemplate.query(PreparedStatementCreator, RowMapper)` overload. Partitioning into `ID_BATCH_SIZE = 1000` chunks is preserved in the scheduler.

---

### `UnverifiedAccountPurgeScheduler` — one call replaced

Remove `photoRepository` injection. Inject `SchedulerRepository`.

**Replace `photoRepository.findAllByUserIdWithStorageKey(userId)` → `schedulerRepository.findStorageKeysByUserId(userId)` (v4 — M11):**
```java
// Inside SchedulerRepository.findStorageKeysByUserId():
public List<Map<String, Object>> findStorageKeysByUserId(UUID userId) {
    return authJdbc.queryForList(
        "SELECT id, user_id, storage_key FROM photos " +
        "WHERE user_id = ? AND storage_key IS NOT NULL",
        userId);
}
```

> **Why `(id, user_id, storage_key)` instead of just `storage_key` (v4 — M11):** The v3 spec selected only `storage_key`, then `enqueueStorageKeys()` parsed the photoId back out via `extractPhotoIdFromKey()` — 6 lines of string manipulation to recover data the SQL already has. By selecting all three columns, the result maps match the format expected by `enqueueByRows()` (keys: `id` UUID, `user_id` UUID, `storage_key` String), allowing direct reuse. This eliminates the `enqueueStorageKeys()` method entirely. `extractPhotoIdFromKey()` is retained for `OrphanReconciliationScheduler`, which parses photo IDs from MinIO object keys (not DB results).

**Replace `enqueueDeleteJobsBatch(photos)` call (v4 — M11):**
```java
List<Map<String, Object>> photoRows = schedulerRepository.findStorageKeysByUserId(userId);
photoDeleteJobEnqueuer.enqueueByRows(photoRows);
```

---

## Section 4: Restore Race Condition (Finding #7)

### Files changed
- `api/src/main/java/org/jphototagger/api/service/PhotoService.java`

### Finding #7 — `restore()` reads photo before acquiring user lock

**Root cause:** `restore()` fetches the photo row first, then acquires `PESSIMISTIC_WRITE` on the user row. Two concurrent requests for the same photoId both pass the `deletedAt != null` filter before either holds the lock, then each add `sizeBytes` to `usedBytes` sequentially, permanently inflating the counter by 2×. `softDelete()` correctly acquires the user lock first.

**Fix:** Reorder to mirror `softDelete()` — no logic changes, pure reorder:

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

---

## Section 5: OAuth2 Email Verification Guard (Finding #8)

### Files changed
- `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java`

### Finding #8 — OAuth2 `email_verified` claim not checked

**Root cause:** `OAuth2SuccessHandler` extracts the email from OIDC claims and proceeds to DB lookup and account creation without checking `oidcUser.getEmailVerified()`. Safe with Google today, but any future OIDC provider that issues unverified email claims allows account registration with an email the user does not own.

**Fix:** Add a guard immediately after the null/blank email check, before any DB work:

```java
Boolean emailVerified = oidcUser.getEmailVerified();
if (!Boolean.TRUE.equals(emailVerified)) {
    response.sendRedirect(redirectUri + "login?error=email_not_verified");
    return;
}
```

`Boolean.TRUE.equals()` handles `null` safely — absent claim fails closed (rejected). Guard applies to both the new-account and returning-account code paths since it fires before the `existing.isEmpty()` branch.

> **Provider onboarding requirement (v2 — Q4):** Any OIDC provider added to the application must include the `email_verified` claim in its ID token. Providers that omit or always return `null` for this claim will have all users rejected at login. This is intentional (fail-closed). If a future provider cannot supply the claim, a provider-specific override must be implemented at that time — not pre-built now.

---

## Section 6: Keyword & Access Control (D1, D3, D4)

### Files changed
- `api/src/main/java/org/jphototagger/api/service/KeywordService.java`
- `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java`
- `api/src/main/java/org/jphototagger/api/repository/PhotoKeywordRepository.java` *(v4 — M10: add `existsByPhotoIdAndKeywordId`)*
- `api/src/main/java/org/jphototagger/api/controller/PhotoController.java`
- `api/src/main/java/org/jphototagger/api/service/PhotoService.java`

### D1 — `updateKeyword()` missing parentId ownership check

**Root cause:** `createKeyword()` validates that `parentId` belongs to the authenticated user. `updateKeyword()` skips this check. PostgreSQL FK constraint on `parent_id REFERENCES keywords(id)` validates against the full table (bypasses RLS), so 200 OK confirms UUID exists as any tenant's keyword — a cross-tenant existence oracle. Also creates cross-user FK references (data integrity violation).

**Fix:** Add the identical ownership guard to `updateKeyword()` before save:

```java
if (parentId != null) {
    keywordRepository.findById(parentId)
        .filter(p -> p.getUserId().equals(userId))
        .orElseThrow(() -> new EntityNotFoundException("Parent keyword not found"));
    keyword.setParentId(parentId);
}
```

### D3 — Recursive keyword CTE missing `user_id` in recursive step

**Root cause:** The recursive CTE anchor filters by `user_id`; the recursive expansion step joins only on `parent_id`. RLS is the sole tenant guard for the recursive step. If RLS is misconfigured or bypassed, the recursive join traverses all tenants.

**Fix:** Add `AND k.user_id = :userId` to the recursive step:

```sql
-- Recursive step AFTER:
SELECT k.* FROM keywords k
INNER JOIN subtree s ON k.parent_id = s.id
WHERE k.user_id = :userId
```

### D4 — `@Transactional` on `PhotoController` methods

**Root cause:** `addKeywordToPhoto` and `removeKeywordFromPhoto` in `PhotoController` carry `@Transactional` (lines 124 and 146). The transaction opens at the controller layer — currently safe due to `@Order` values, but fragile if ordering changes. The keyword assignment logic (ownership validation + `photoKeywordRepository` save/delete) lives entirely in the controller; there are no corresponding `PhotoService` methods yet.

**Fix:** Extract the keyword-assignment logic into two new `@Transactional` methods in `PhotoService`, then simplify the controller to delegate:

**New `PhotoService` methods** (inject `PhotoKeywordRepository` and `KeywordRepository` into `PhotoService`):

```java
@Transactional
public void addKeywordToPhoto(UUID userId, UUID photoId, UUID keywordId) {
    // Validate photo ownership (reuses existing getPhoto)
    getPhoto(userId, photoId);
    // Validate keyword ownership
    Keyword keyword = keywordRepository.findById(keywordId)
        .orElseThrow(() -> new EntityNotFoundException("Keyword not found"));
    if (!keyword.getUserId().equals(userId)) {
        throw new EntityNotFoundException("Keyword not found");
    }
    // Idempotent: skip if already associated (v4 — M10)
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
    // Validate photo ownership
    getPhoto(userId, photoId);
    photoKeywordRepository.deleteByPhotoIdAndKeywordIdAndUserId(photoId, keywordId, userId);
}
```

**New `PhotoService.listKeywordsForPhoto()` method (v3 — M8, v4 — C9):** Move the read operation to the service layer for consistency — all three keyword-photo operations now go through `PhotoService`. Uses a single JOIN query instead of the N+1 pattern (v4 — C9):

```java
@Transactional(readOnly = true)
public List<KeywordResponse> listKeywordsForPhoto(UUID userId, UUID photoId) {
    getPhoto(userId, photoId); // validates ownership
    return keywordRepository.findKeywordsByPhotoIdAndUserId(photoId, userId).stream()
        .map(k -> new KeywordResponse(k.getId(), k.getName(), k.getParentId()))
        .toList();
}
```

> **N+1 elimination (v4 — C9):** The v3 spec iterated `photoKeywordRepository.findByPhotoIdAndUserId()` results and called `keywordRepository.findById()` per row — 1+N queries for N keywords. The `.filter(Objects::nonNull)` after `findById().orElse(null)` silently swallowed orphaned `photo_keyword` rows, hiding data integrity issues. The JOIN query eliminates both problems: one query, and orphaned FK references are naturally excluded by the JOIN (no matching keyword = no result row).

**New `KeywordRepository` method (v4 — C9):**

```java
@Query("SELECT k FROM Keyword k JOIN PhotoKeyword pk ON pk.keywordId = k.id " +
       "WHERE pk.photoId = :photoId AND pk.userId = :userId")
List<Keyword> findKeywordsByPhotoIdAndUserId(@Param("photoId") UUID photoId,
                                              @Param("userId") UUID userId);
```

**New `PhotoKeywordRepository` method (v4 — M10):**

```java
boolean existsByPhotoIdAndKeywordId(UUID photoId, UUID keywordId);
```

Spring Data derives this automatically from the method name — no `@Query` needed.

**`PhotoController` changes:**
- Remove `@Transactional` from `addKeywordToPhoto` and `removeKeywordFromPhoto`.
- Remove the inline ownership-validation + `photoKeywordRepository` calls from all three methods (`addKeywordToPhoto`, `removeKeywordFromPhoto`, `listKeywordsForPhoto`).
- Replace each method body with a single delegate call: `photoService.addKeywordToPhoto(userId, id, keywordId)` / `photoService.removeKeywordFromPhoto(userId, id, keywordId)` / `photoService.listKeywordsForPhoto(userId, id)`.
- **Remove `photoKeywordRepository` and `keywordRepository` from `PhotoController` constructor/fields entirely (v3 — M8)** — no remaining usages after all three methods delegate to `PhotoService`.

---

## Implementation Order

Apply in this sequence to minimise risk and make each step independently verifiable:

1. **Auth fixes** (#1, #2, #3) — `AuthService` + `AuthController`
2. **OAuth2 guard** (#8) — `OAuth2SuccessHandler`
3. **Restore reorder** (#7) — `PhotoService`
4. **Share system** (#4, #5, C3) — `MetadataLocationStripper` (new) + `ShareService` + `ShareController` + `ShareLookupRepository` + `PhotoMetadataResponse`
5. **Keyword fixes** (D1, D3, D4) — `KeywordService` + `KeywordRepository` + `PhotoController` + `PhotoService`
6. **Scheduler fixes** (#6) — V14 migration + `SchedulerRepository` (new) + `PhotoDeleteJobEnqueuer` + all three schedulers + integration tests

---

## Required Follow-ups

These items are outside the scope of this spec but are required for correctness after these fixes are applied:

- **Frontend login failure hint (v2 — M5):** After C2 eliminates the `EmailVerificationRequiredException` oracle, the frontend login error page must display a static secondary hint on all login failures: *"Registered recently? Check your inbox or request a new verification link."* This is a UX-only change with no security implications — it helps legitimate users without leaking information (since it appears for every failure, not just unverified accounts).

- **Allowlist-based metadata filtering for shares (v4 — alternative challenge):** The current denylist approach (strip known location keys) is fail-open — unknown keys pass through. The metadata key space is open-ended (raw `metadata-extractor` dump from arbitrary camera firmware via `MetadataExtractor`). A future sprint should evaluate switching share responses to an allowlist of safe EXIF/IPTC/XMP keys, which would be fail-closed by default. This does not affect the authenticated path (full metadata). See `critical-review-3.md` §4 for full trade-off analysis.

---

## Out of Scope

- Frontend XSS audit
- Nginx/TLS deep audit
- MinIO IAM policy review
- Dependency CVE scan (pom.xml/package.json)
