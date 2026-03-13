# Security Findings Fixes — Design Spec

**Date:** 2026-03-13
**Source:** security-scan-report-2026-03-13.md
**Scope:** 8 validated findings + 4 discarded-but-worth-fixing findings (12 total)

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

### Finding #2 — HTTP status oracle enables lockout-free brute-force on unverified accounts

**Root cause:** When a user submits the correct password but their email is unverified, `authenticate()` throws `EmailVerificationRequiredException` → HTTP 403, while wrong passwords produce HTTP 401. The `failed_login_attempts` counter is only incremented on the wrong-password path. Correct-password probes against unverified accounts never hit the counter — lockout is bypassed entirely during the 24-hour verification window.

**Fix (two parts):**

1. In `AuthService.authenticate()`: call `incrementFailedAttempts(userId)` (see Finding #3) **before** throwing `EmailVerificationRequiredException`. The unverified-email path now counts against lockout identically to wrong passwords.
2. In `AuthController.login()`: map `EmailVerificationRequiredException` → **HTTP 401** with body `{"error":"Invalid credentials","status":401}` — identical to the wrong-password response. The oracle is removed: an attacker cannot distinguish the two cases by status code.

> **UX note:** Legitimate unverified users see "Invalid credentials." The frontend must display a secondary hint: *"Registered recently? Check your inbox or request a new verification link."* The existing `/auth/verify` endpoint handles resending.

### Finding #3 — Non-atomic lockout counter race condition

**Root cause:** Failed login counter uses a read-modify-write sequence (SELECT then UPDATE) with no row locking. Concurrent requests all read the same counter value and all write the same incremented value, under-counting the actual attempts. Five simultaneous wrong-password requests may leave the counter at 1 or 2 instead of 5.

**Fix:** Extract a private `incrementFailedAttempts(UUID userId)` helper using a single atomic SQL statement:

```sql
UPDATE users
SET failed_login_attempts = failed_login_attempts + 1,
    locked_until = CASE
        WHEN failed_login_attempts + 1 >= 5
            THEN NOW() + INTERVAL '15 minutes'
        ELSE locked_until
    END
WHERE id = ?
```

This helper replaces both branches of the existing wrong-password UPDATE, and is also called by the unverified-email path (Finding #2). No separate SELECT is needed; the increment and conditional lockout are one atomic operation.

**Lockout-after-expiry behaviour (intentional):** Once `failed_login_attempts >= MAX_FAILED_ATTEMPTS`, the `CASE` condition is true on every subsequent wrong-password attempt, immediately issuing a fresh 15-minute lockout. The counter resets only on a successful login. This is intentional: it prevents "exhaust lockout window, pause, try again" patterns. A user whose lockout has expired can try again; if they fail, they are immediately re-locked.

**`authenticate()` method flow after all three fixes:**

1. Query user by email. If not found → `passwordEncoder.matches(pw, getDummyHash())` → throw `BadCredentialsException`.
2. Always call `passwordEncoder.matches(pw, storedHash)` (timing preservation; result stored in `passwordCorrect`).
3. Evaluate `isLocked` from the initial SELECT result (fields `failedLoginAttempts`, `lockedUntil` read at query time). **No re-fetch occurs within this call.**
4. If `!passwordCorrect` → `incrementFailedAttempts(userId)` → throw `BadCredentialsException`.
5. If correct password but `isLocked` (lockout still active per step 3 evaluation) → throw `BadCredentialsException`.
6. If correct password, not locked, `!emailVerified` → `incrementFailedAttempts(userId)` → throw `EmailVerificationRequiredException`.
7. Success → `UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?` → return `{userId, email}`.

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

**Key sets — `PhotoMetadataResponse` is authoritative.** The stripper uses the exact same sets defined there, making `PhotoMetadataResponse` the single source of truth:

```java
// IPTC — 10 elements, matches PhotoMetadataResponse.IPTC_LOCATION_KEYS exactly:
private static final Set<String> IPTC_LOCATION_KEYS = Set.of(
    "iptc:sub-location", "iptc:city", "iptc:province-state",
    "iptc:country-primary location code", "iptc:country-primary location name",
    "sub-location", "city", "province-state",
    "country-primary location code", "country-primary location name"
);

// XMP — 5 elements, matches PhotoMetadataResponse.XMP_LOCATION_KEYS exactly:
private static final Set<String> XMP_LOCATION_KEYS = Set.of(
    "photoshop:city", "photoshop:state", "photoshop:country",
    "iptc4xmpcore:location", "xmp:location"
);
```

All key comparisons use `entry.getKey().toLowerCase()` (case-insensitive), matching the existing `PhotoMetadataResponse` behaviour.

`filterGpsFromExif` removes keys where `lower.contains("gps")`.
`filterLocationFromIptc` removes keys where `IPTC_LOCATION_KEYS.contains(lower)`.
`filterLocationFromXmp` removes keys where `lower.contains("gps") || XMP_LOCATION_KEYS.contains(lower)`.

> **Predicate note:** The existing `ShareService.stripGpsFromExif()` uses `GPS_KEY_PATTERN = Pattern.compile("(?i)gps.*")` with `matches()` (full-string anchor), which only strips keys whose *entire name* starts with `"gps"` — it misses composite keys like `"EXIF:GPSLatitude"`. The authenticated path (`PhotoMetadataResponse.filterGpsKeys()`) uses `lower.contains("gps")`, which correctly strips all GPS-related keys regardless of prefix. The stripper adopts the authenticated path's `contains("gps")` predicate — this is an intentional broadening to fix the coverage gap. The old `GPS_KEY_PATTERN` in `ShareService` is removed when `stripGpsFromExif()` delegates to the stripper.

**`PhotoMetadataResponse.withoutGps()`** is updated to delegate to `MetadataLocationStripper`'s static methods directly — e.g., `MetadataLocationStripper.filterGpsFromExif(this.exif())`. The three private methods (`filterGpsKeys`, `filterLocationKeys`, `filterGpsAndLocationKeys`) are removed. No constructor/factory signature changes are needed: static calls are valid from inside a record's instance method.

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

**Fix — `ShareController.getShare()` (photo share):** After URL generation, before `response.put("photo", photo)`:
```java
photo.remove("storage_key");
```

**Fix — `ShareController.getSharedAlbumPhotos()` (album share):** Replace the bare `return shareLookupRepository.findAlbumPhotos(albumId, capped)` with a mapped page. The album owner UUID is available from `shareData.get("user_id")` — all photos in an album are guaranteed to belong to the album's creator (enforced by `validateResourceExists()`). For each photo in the page:

1. Generate presigned URLs using the same key-parsing logic as `getShare()` (split `storage_key` on `/`, parse `photoId` from `parts[2]`). Use `shareData.get("user_id")` as `photoOwnerId`.
2. Remove `storage_key` from the photo map.

> **No GPS/location stripping needed:** `findAlbumPhotos()` does not join `photo_metadata` — the result maps contain no `exif_data`, `iptc_data`, or `xmp_data` columns. Location data cannot leak from album photo responses. Only `findPhotoById()` (single-photo shares) joins `photo_metadata` and requires stripping.

```java
UUID albumOwnerId = (UUID) shareData.get("user_id");
return shareLookupRepository.findAlbumPhotos(albumId, capped).map(rawPhoto -> {
    Map<String, Object> photo = new HashMap<>(rawPhoto);
    Object storageKey = photo.get("storage_key");
    if (storageKey != null) {
        String key = storageKey.toString();
        try {
            String[] parts = key.split("/");
            if (parts.length >= 3) {
                UUID photoId = UUID.fromString(parts[2].replaceAll("\\.[^.]+$", ""));
                photo.put("thumbnailUrl", storageService.generateThumbnailPresignedUrl(
                        storageService.thumbnailSmKey(albumOwnerId, photoId)));
                photo.put("originalUrl", storageService.generateOriginalPresignedUrl(key));
            }
        } catch (IllegalArgumentException e) {
            log.warn("Failed to parse storage key for album photo URL generation: {}", key, e);
        }
        photo.remove("storage_key");
    }
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

---

## Section 3: Scheduler RLS Bypass (Finding #6)

### Files changed
- `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java`
- `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`
- `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`
- `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java`
- `api/src/main/resources/db/migration/V14__grant_photos_select_to_jpt_auth.sql` *(new, if needed)*

### Finding #6 — Background schedulers silently no-op due to missing RLS context

**Root cause:** Scheduler threads have no HTTP request context, so `RlsContext.getCurrentUserId()` returns `null`. `RlsAspect` no-ops, leaving `app.current_user_id = '00000000-...'` (from `connection-init-sql`) as the active RLS context. PostgreSQL evaluates `user_id = null::uuid` → NULL for every row, returning 0 results. All primary-datasource (`jpt_app` role) repository calls in schedulers silently return empty.

**Fix pattern:** Replace all primary-datasource `photoRepository`/`userRepository` calls in schedulers with `authJdbcTemplate` (BYPASSRLS, `jpt_auth` role) SQL queries.

**DB permissions prerequisite:** `jpt_auth` currently holds `SELECT, INSERT` on `users` (column-level UPDATE on specific columns) and `SELECT, INSERT, DELETE` on `email_tokens` (V4 migration). The reviewed migrations (V1–V13) do not include grants for `photos`, `album_photos`, `albums`, `saved_searches`, `shares`, or `keywords` to `jpt_auth`. However, `UnverifiedAccountPurgeScheduler` already runs DML against all of these tables via `authJdbcTemplate` in production (`DELETE FROM album_photos`, `DELETE FROM photos`, `DELETE FROM albums`, `DELETE FROM saved_searches`, `DELETE FROM shares`, `UPDATE/DELETE keywords`, `DELETE FROM users`). This confirms that `jpt_auth` already holds the required permissions — they were granted outside the reviewed Flyway migrations (likely in the initial DB setup or a bootstrap script).

V14 adds only the grants that are **provably missing** from the reviewed migrations AND newly required by the scheduler fixes in this spec. All `GRANT` statements are idempotent — harmless if they already exist:

```sql
-- V14__grant_scheduler_permissions_to_jpt_auth.sql
-- Grants jpt_auth the permissions required by TrashPurgeScheduler and
-- OrphanReconciliationScheduler after migrating from photoRepository (RLS-blocked)
-- to authJdbcTemplate (BYPASSRLS).
--
-- UnverifiedAccountPurgeScheduler already uses authJdbcTemplate for DML against
-- album_photos, photos, albums, saved_searches, shares, keywords, email_tokens,
-- and users — those grants already exist (confirmed by scheduler running in prod).

-- TrashPurgeScheduler needs SELECT to query purgeable batches:
GRANT SELECT ON photos TO jpt_auth;
-- TrashPurgeScheduler.purgeNullStorageKeyPhotos() CTE issues DELETE FROM photos:
GRANT DELETE ON photos TO jpt_auth;
-- TrashPurgeScheduler.purgeNullStorageKeyPhotos() CTE issues UPDATE users SET used_bytes:
GRANT UPDATE (used_bytes) ON users TO jpt_auth;
```

> **Note on existing grants:** If `jpt_auth` already holds `SELECT`/`DELETE` on `photos` (as implied by `UnverifiedAccountPurgeScheduler` working), these `GRANT` statements are no-ops. The only grant that is definitively new is `UPDATE (used_bytes) ON users` — the reviewed V4/V11 migrations only grant column-level UPDATE on `(password_hash, failed_login_attempts, locked_until, email_verified, oauth_provider, oauth_id, updated_at)`, which does not include `used_bytes`.

---

### `PhotoDeleteJobEnqueuer` — two new methods

The current message format (from `enqueue(List<Photo>)` and `enqueueOrphan()`):
```
{ photo_id, original_key, thumbnail_sm, thumbnail_md }
```
Thumbnail keys are always: `{userId}/thumbnails/{photoId}_sm.jpg` and `{userId}/thumbnails/{photoId}_md.jpg`.

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
                operations.opsForStream().add("delete-jobs", Map.of(
                    "photo_id",     photoId.toString(),
                    "original_key", origKey,
                    "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
                    "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
                ));
            }
            return null;
        }
    });
}
```

**Add `enqueueStorageKeys(UUID userId, List<String> storageKeys)`:** Used by `UnverifiedAccountPurgeScheduler`. Storage keys have format `{userId}/originals/{photoId}.{ext}`; `photoId` is parsed using the same extraction logic as `OrphanReconciliationScheduler.extractPhotoId()`. This logic is promoted to a package-private static helper in `PhotoDeleteJobEnqueuer` (or inlined; `OrphanReconciliationScheduler` calls its own local copy):

```java
public void enqueueStorageKeys(UUID userId, List<String> storageKeys) {
    redisTemplate.executePipelined(new SessionCallback<Object>() {
        @Override public Object execute(RedisOperations operations) {
            for (String storageKey : storageKeys) {
                UUID photoId = extractPhotoIdFromKey(storageKey);
                if (photoId == null) {
                    log.warn("Cannot parse photo_id from storage key '{}', skipping", storageKey);
                    continue;
                }
                operations.opsForStream().add("delete-jobs", Map.of(
                    "photo_id",     photoId.toString(),
                    "original_key", storageKey,
                    "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
                    "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
                ));
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

`OrphanReconciliationScheduler.extractPhotoId()` is updated to delegate to `PhotoDeleteJobEnqueuer.extractPhotoIdFromKey()`.

---

### `TrashPurgeScheduler` — full migration to `authJdbc`

Inject `@Qualifier("authJdbcTemplate") JdbcTemplate authJdbc`. Remove `photoRepository` injection and the existing primary `jdbcTemplate` injection.

**Replace `photoRepository.findPurgeableBatch(cutoff)`:**
```java
List<Map<String, Object>> batch = authJdbc.queryForList(
    "SELECT id, user_id, storage_key FROM photos " +
    "WHERE deleted_at < ? " +
    "LIMIT 100",
    Timestamp.from(cutoff));
```

> **No `storage_key IS NOT NULL` filter:** The original `findPurgeableBatch()` returns all soft-deleted photos past the cutoff, including those with null `storage_key` (e.g., incomplete uploads that a user soft-deleted before processing finished). `enqueueByRows()` already logs and skips null-storage-key rows (no MinIO job needed), while `deletePhotosBatch()` deletes all rows by ID regardless of `storage_key`. Adding a `storage_key IS NOT NULL` filter would strand soft-deleted null-storage-key rows permanently — `purgeNullStorageKeyPhotos()` only handles `deleted_at IS NULL` rows.

**Replace `photoRepository.deleteAllById(ids)` with batched delete:**
```java
UUID[] idArray = batch.stream()
    .map(r -> (UUID) r.get("id"))
    .toArray(UUID[]::new);
authJdbc.update("DELETE FROM photos WHERE id = ANY(?)",
    (PreparedStatement ps) -> ps.setArray(1,
        ps.getConnection().createArrayOf("uuid", idArray)));
```

**Replace enqueue call:** `photoDeleteJobEnqueuer.enqueueByRows(batch)` (before delete, same as existing ordering).

**Replace `purgeNullStorageKeyPhotos()` jdbcTemplate → authJdbc:** The SQL CTE (`DELETE FROM photos ... UPDATE users ...`) is unchanged; only the template reference changes. `jpt_auth` currently has column-level UPDATE on `(password_hash, failed_login_attempts, locked_until, email_verified, oauth_provider, oauth_id, updated_at)` — `used_bytes` is **not** in this list. The V14 migration above (which grants `UPDATE (used_bytes) ON users TO jpt_auth`) covers this requirement.

---

### `OrphanReconciliationScheduler` — two repository calls replaced

Inject `@Qualifier("authJdbcTemplate") JdbcTemplate authJdbc`. Remove `userRepository` and `photoRepository` injections. Remove `@Transactional(readOnly = true)` annotation.

**Replace `userRepository.streamAllIds()`:**
```java
List<UUID> userIds = authJdbc.queryForList("SELECT id FROM users", UUID.class);
for (UUID userId : userIds) {
    orphansFound += reconcileUser(userId);
}
```
The stream is replaced with a list. For very large deployments this loads all user IDs into memory; for the expected scale this is acceptable. If scale becomes a concern, use server-side cursor via `jdbcTemplate.query()` with a `RowCallbackHandler`.

**Replace `photoRepository.findAllById(batch)` in `findExistingIds()`:**
```java
// For each batch of up to ID_BATCH_SIZE UUIDs:
UUID[] batchArray = batch.toArray(UUID[]::new);
List<UUID> existing = authJdbc.query(
    con -> {
        PreparedStatement ps = con.prepareStatement(
            "SELECT id FROM photos WHERE id = ANY(?)");
        ps.setArray(1, con.createArrayOf("uuid", batchArray));
        return ps;
    },
    (rs, rowNum) -> UUID.fromString(rs.getString("id")));
```
Use `PreparedStatementCreator` (lambda) to set the PostgreSQL `uuid[]` array parameter — this is the correct `JdbcTemplate.query(PreparedStatementCreator, RowMapper)` overload. Partitioning into `ID_BATCH_SIZE = 1000` chunks is preserved.

---

### `UnverifiedAccountPurgeScheduler` — one call replaced

Remove `photoRepository` injection.

**Replace `photoRepository.findAllByUserIdWithStorageKey(userId)`:**
```java
List<String> storageKeys = authJdbcTemplate.queryForList(
    "SELECT storage_key FROM photos WHERE user_id = ? AND storage_key IS NOT NULL",
    String.class, userId);
```

**Replace `enqueueDeleteJobsBatch(photos)` call:**
```java
photoDeleteJobEnqueuer.enqueueStorageKeys(userId, storageKeys);
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

---

## Section 6: Keyword & Access Control (D1, D3, D4)

### Files changed
- `api/src/main/java/org/jphototagger/api/service/KeywordService.java`
- `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java`
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

**`PhotoController` changes:**
- Remove `@Transactional` from `addKeywordToPhoto` and `removeKeywordFromPhoto`.
- Remove the inline ownership-validation + `photoKeywordRepository` calls from both methods.
- Replace each method body with a single delegate call: `photoService.addKeywordToPhoto(userId, id, keywordId)` / `photoService.removeKeywordFromPhoto(userId, id, keywordId)`.
- Remove `photoKeywordRepository` and `keywordRepository` from `PhotoController` constructor/fields (they are no longer needed by the controller after this refactor; `listKeywordsForPhoto` still uses them — keep those two fields only if `listKeywordsForPhoto` is not also being moved, which it is not in this fix).

> **Note:** `listKeywordsForPhoto` in `PhotoController` also uses `photoKeywordRepository` and `keywordRepository` directly and is not being moved in this fix. Therefore `PhotoController` retains both repository fields; only the two `@Transactional` annotations are removed from the controller.

---

## Implementation Order

Apply in this sequence to minimise risk and make each step independently verifiable:

1. **Auth fixes** (#1, #2, #3) — `AuthService` + `AuthController`
2. **OAuth2 guard** (#8) — `OAuth2SuccessHandler`
3. **Restore reorder** (#7) — `PhotoService`
4. **Share system** (#4, #5, C3) — `MetadataLocationStripper` (new) + `ShareService` + `ShareController` + `ShareLookupRepository` + `PhotoMetadataResponse`
5. **Keyword fixes** (D1, D3, D4) — `KeywordService` + `KeywordRepository` + `PhotoController` + `PhotoService`
6. **Scheduler fixes** (#6) — V14 migration + `PhotoDeleteJobEnqueuer` + all three schedulers

---

## Out of Scope

- Frontend XSS audit
- Nginx/TLS deep audit
- MinIO IAM policy review
- Dependency CVE scan (pom.xml/package.json)
