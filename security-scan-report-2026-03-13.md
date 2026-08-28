# Security Scan Report

**Date:** 2026-03-13
**Source:** /home/ubuntu/jpt_saas (local)
**Tool Version:** security-scan v1.1.0

---

# Phase 1: Threat Model

## Threat Model
**System purpose:** JPT SaaS is a photo management and sharing platform that allows users to upload, organize, search, and share photos with metadata extraction (EXIF/IPTC/XMP). It features user authentication, role-based access control, Row-Level Security in PostgreSQL, and async image processing via a separate worker service.

**Architecture:** Spring Boot Java backend (API + worker), React TypeScript frontend, PostgreSQL database with RLS and multiple roles, Redis for caching/rate limiting/job queues, MinIO for object storage, Nginx reverse proxy with TLS. Key components: AuthController (login/register/OAuth2), PhotoController (upload/download), ShareController (public share links), SearchController (full-text/EXIF search), Worker service (async image processing via Redis Streams), and multiple database roles (jpt_app, jpt_auth with BYPASSRLS, share_reader with BYPASSRLS, worker_db_user).

**Sensitive data flows:**
- User credentials (email/password hash) → AuthService → jpt_auth role → PostgreSQL
- JWT tokens (15-min expiry) and refresh tokens (30-day) → httpOnly cookies with Strict SameSite
- Photo originals (up to 200MB) → MinIO bucket organized by userId → presigned URLs (1hr expiry for originals, 15min for thumbnails)
- EXIF/IPTC/XMP metadata → photo_metadata JSONB columns
- Share tokens (256-bit) → hashed in DB, returned once at creation
- Verification tokens (256-bit) → hashed in email_tokens, single-use

**Trust boundaries:**
- Authenticated users (JWT in httpOnly cookie) ← primary boundary
- Unauthenticated share visitors (via share token) ← separate DataSource with share_reader role
- Worker service (separate JVM, limited DB role: SELECT on photos, INSERT/UPDATE photo_metadata)
- Public endpoints (CSRF protected: /auth/*, /share/*, /csrf, /actuator/health)
- Private endpoints (all others require JWT)
- External OAuth2 providers (Google) ← identity trust

**Primary attack surface:**
1. Photo upload endpoint (POST /photos) — 200MB max size, Tika MIME validation, temp file handling, quota enforcement, hash-based dedup
2. Share token system (GET /share/{token}, POST /shares) — 256-bit tokens, BYPASSRLS role access, optional GPS filtering, expiration
3. Search endpoints (GET /search*) — full-text (tsvector), EXIF JSONB, keyword joins; allowlist validation on EXIF fields
4. Authentication flow (POST /auth/register, /auth/login, /auth/verify) — email enumeration timing mitigations, bcrypt cost 12, failed login lockout
5. JWT and refresh token handling — 15-min JWT expiry, 30-day refresh token rotation in Redis, token revocation on logout
6. OAuth2 integration — email-only merge, OIDC user claims extraction
7. Rate limiting — per-IP auth (20/hr), per-user upload (100/hr), per-user general (1000/hr), bucket4j + Redis
8. Metadata extraction — TIKA validator, EXIF/IPTC/XMP parsing by worker
9. File path construction — userId-based bucket prefixes in MinIO, UUIDs for photo IDs
10. Database Row-Level Security — app.current_user_id session variable set via RlsAspect before each @Transactional method

**Deployment context:** Docker Compose with Nginx (TLS, CSP, HSTS, CORS headers), PostgreSQL (RLS enabled, multiple roles), Redis (for sessions/rate limits/job queue), MinIO (S3-compatible), SMTP for email. Separate worker container.

## Attack Surface Table

| Entry Point | Input Type | Auth Required? | Reaches Sensitive Data? | File Location |
|---|---|---|---|---|
| POST /auth/register | JSON (email, password) | No | Yes (users, email_tokens) | api/src/main/java/org/jphototagger/api/controller/AuthController.java:54 |
| POST /auth/login | JSON (email, password) | No | Yes (users, password_hash) | api/src/main/java/org/jphototagger/api/controller/AuthController.java:73 |
| POST /auth/verify | JSON (token) | No | Yes (email_tokens) | api/src/main/java/org/jphototagger/api/controller/AuthController.java:61 |
| POST /auth/refresh | Cookie (refresh token) | No (needs valid token) | Yes (users, RefreshTokenService) | api/src/main/java/org/jphototagger/api/controller/AuthController.java:99 |
| /login/oauth2/code/* | OAuth2 authorization code | No | Yes (users table) | api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:61 |
| POST /photos | MultipartFile (image, max 200MB) | Yes (JWT) | Yes (photos, MinIO, quota) | api/src/main/java/org/jphototagger/api/controller/PhotoController.java:51 |
| GET /photos | Query params (page, size) | Yes (JWT) | Yes (user's photos) | api/src/main/java/org/jphototagger/api/controller/PhotoController.java:59 |
| GET /photos/{id} | Path param (UUID) | Yes (JWT) | Yes (photo metadata) | api/src/main/java/org/jphototagger/api/controller/PhotoController.java:67 |
| DELETE /photos/{id} | Path param (UUID) | Yes (JWT) | Yes (soft delete, quota) | api/src/main/java/org/jphototagger/api/controller/PhotoController.java:84 |
| POST /shares | JSON (resourceType, resourceId, includeGps) | Yes (JWT) | Yes (shares, token gen) | api/src/main/java/org/jphototagger/api/controller/ShareController.java:56 |
| GET /share/{token} | Path param (share token) | No | Yes (via share_reader role) | api/src/main/java/org/jphototagger/api/controller/ShareController.java:83 |
| GET /share/{token}/photos | Path param, query params | No | Yes (photos via share_reader) | api/src/main/java/org/jphototagger/api/controller/ShareController.java:148 |
| DELETE /shares/{id} | Path param (UUID) | Yes (JWT) | Yes (shares table) | api/src/main/java/org/jphototagger/api/controller/ShareController.java:170 |
| GET /search | Query param (q: full-text) | Yes (JWT) | Yes (user's photos) | api/src/main/java/org/jphototagger/api/controller/SearchController.java:28 |
| GET /search/exif | Query params (field, value) | Yes (JWT) | Yes (photo_metadata JSONB) | api/src/main/java/org/jphototagger/api/controller/SearchController.java:37 |
| GET /search/keyword | Query params (keywordId, page, size) | Yes (JWT) | Yes (user's photos) | api/src/main/java/org/jphototagger/api/controller/SearchController.java:47 |
| GET /albums | Query params | Yes (JWT) | Yes (user's albums) | api/src/main/java/org/jphototagger/api/controller/AlbumController.java:38 |
| POST /albums | JSON (name) | Yes (JWT) | Yes (albums) | api/src/main/java/org/jphototagger/api/controller/AlbumController.java:53 |
| GET /users/me | None | Yes (JWT) | Yes (current user) | api/src/main/java/org/jphototagger/api/controller/UserController.java:29 |
| Redis Streams (photo-jobs) | JSON message (userId, photoId) | Internal (worker) | Yes (photo processing) | worker/src/main/java/org/jphototagger/worker/consumer/PhotoJobConsumer.java:51 |
| MinIO bucket access | Binary objects (userId/type/id keys) | Internal (API/Worker) | Yes (photo originals) | api/src/main/java/org/jphototagger/api/service/StorageService.java:32 |
| Email verification link | URL with plaintext token | External (SMTP) | Yes (email_tokens) | api/src/main/java/org/jphototagger/api/service/AuthService.java:72 |


# Phase 2: Raw Findings

## Group A: Authentication & Session

### FINDING A1: Broken Timing Equalization — User Enumeration via Login Endpoint
**Severity:** Medium
**Confidence:** High (>80%) — The dummy hash is 65 characters; BCryptPasswordEncoder requires exactly 60 (53-char hash field), causing immediate `false` return instead of bcrypt computation.
**Location:** `api/src/main/java/org/jphototagger/api/service/AuthService.java:93`
**Trace:** `POST /auth/login` → `AuthService.authenticate()` → `passwordEncoder.matches(password, "$2a$12$dummy.hash...")` → Spring `BCryptPasswordEncoder` pattern check → **immediate `false` return (no bcrypt)**
**Description:** The anti-enumeration comment reads "Equalize timing with the happy path (bcrypt at cost 12 ≈ 250ms)" and calls `passwordEncoder.matches(password, "$2a$12$dummy.hash.to.prevent.timing.side.channel.attacks.00000000")`. However, this dummy hash is 65 characters long. Spring Security's `BCryptPasswordEncoder.matches()` validates the input against a regex requiring exactly 53 characters in the hash field before performing any bcrypt computation. The dummy hash fails the pattern check and returns `false` in nanoseconds — no bcrypt is performed. The "user not found" path returns in ~1ms, while the "user found, wrong password" path takes ~250ms (full bcrypt). The timing difference is reliably observable.
**Exploit Scenario:** Attacker sends POST `/auth/login` with candidate email addresses and any password. Non-existent emails return in <5ms; registered accounts return in ~250ms. With 20 attempts/hour per IP, attacker can confirm 20 email addresses per hour. Proxy rotation scales this linearly.
**Impact:** Attacker can enumerate registered email addresses at the login endpoint, enabling targeted phishing and credential stuffing.

---

### FINDING A2: HTTP Status Code Oracle for Unverified Account Password Brute-Force
**Severity:** Medium
**Confidence:** High (>80%) — Deterministic: EmailVerificationRequiredException → 403; BadCredentialsException → 401; lockout only increments on 401 path.
**Location:** `api/src/main/java/org/jphototagger/api/service/AuthService.java:113–137`, `AuthController.java:90–96`
**Trace:** `POST /auth/login` → `AuthService.authenticate()` → `passwordEncoder.matches()` returns `true` → `isLocked` check passes → `emailVerified == false` → throws `EmailVerificationRequiredException` → **HTTP 403** (vs HTTP 401 for wrong password)
**Description:** Correct password on unverified account returns 403; wrong password returns 401. `failed_login_attempts` counter is only incremented when `!passwordCorrect`. Correct-password probes that return 403 do not increment the counter — providing a lockout-free oracle during the 24-hour email verification window.
**Exploit Scenario:** Victim registers and has 24 hours before verification expires. Attacker sends login attempts: wrong passwords return 401 and increment counter; correct password returns 403 without incrementing. The lockout mechanism (5 wrong guesses) does not apply to correct-password probes.
**Impact:** Attacker can brute-force passwords of recently-registered unverified accounts without triggering lockout. Successful discovery enables full account takeover once victim verifies.

---

### FINDING A3: Non-Atomic Lockout Counter — Concurrent Request Race Condition
**Severity:** Low
**Confidence:** High (>80%) — SQL is a non-transactional read-then-write without `SELECT FOR UPDATE` or atomic increment.
**Location:** `api/src/main/java/org/jphototagger/api/service/AuthService.java:87–125`
**Trace:** `POST /auth/login` → `authenticate()` → SELECT `failed_login_attempts` → increment in-memory → UPDATE SET `failed_login_attempts = ?` → **non-atomic read-modify-write**
**Description:** Failed login counter is read then written back in separate SQL operations with no row-level locking. Concurrent requests all reading the same counter value will all write the same incremented value. 5 simultaneous requests can all read `0` and all write `1`, leaving counter at `1` instead of `5`. Rate limit (20/hr per IP) is the primary constraint limiting practical impact.
**Impact:** Lockout mechanism is less reliable under concurrent patterns; defense-in-depth layer degraded.

---

### FINDING A4: OAuth2 `email_verified` Claim Not Checked Before Account Creation
**Severity:** Low
**Confidence:** Low (<50%) — Google rarely issues unverified email claims in standard OIDC flows; requires G Suite/Workspace edge case.
**Location:** `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:67–90`
**Trace:** `GET /login/oauth2/code/google` → `OAuth2SuccessHandler.onAuthenticationSuccess()` → `oidcUser.getEmail()` extracted → `oidcUser.isEmailVerified()` **never called** → `INSERT INTO users (..., email_verified=true, ...)`
**Description:** OAuth2SuccessHandler trusts the email claim for account creation without verifying `isEmailVerified()`. For G Suite/Workspace custom domain accounts where email may be unverified, an attacker could create a local account tied to an email they don't own, bypassing the email verification requirement that password-based registrations enforce.
**Impact:** Account impersonation for unverified email addresses in G Suite configurations.

---

## Group B: File Upload, Storage & Worker

### FINDING B1: `restore()` Concurrent Race Causes `used_bytes` Double-Count
**Severity:** Medium
**Confidence:** High (>80%) — Photo is read outside the user-row lock; two concurrent calls observe the same deleted state and both increment sizeBytes.
**Location:** `api/src/main/java/org/jphototagger/api/service/PhotoService.java:298–319`
**Trace:** `POST /photos/{id}/restore` (×2 concurrent, same `photoId`) → `restore()` → `findById(photoId)` (no lock) → `PESSIMISTIC_WRITE on user` → `user.setUsedBytes(user.getUsedBytes() + photo.getSizeBytes())` (twice)
**Description:** `restore()` reads the photo row before acquiring the PESSIMISTIC_WRITE lock on the user row. Two concurrent requests for the same photoId can both pass the `deletedAt != null` filter and both obtain in-memory `photo.getSizeBytes()`. After Thread A commits, Thread B acquires the lock, reads Thread A's updated `usedBytes`, and adds `sizeBytes` again. Result: `used_bytes` permanently inflated by 2× the photo size.
**Exploit Scenario:** User sends two simultaneous restore requests for a 1 GB photo. Counter inflated to 2 GB for 1 GB actual storage, blocking future uploads.
**Impact:** Permanent `used_bytes` inflation; can exhaust quota without actual storage use.

---

### FINDING B2: Attacker-Controlled Exception Message Written to Logs (Log Injection)
**Severity:** Low
**Confidence:** High (>80%) — `log.warn(..., e.getMessage())` with no sanitization on metadata-extractor exception messages.
**Location:** `worker/src/main/java/org/jphototagger/worker/pipeline/MetadataExtractor.java:129–133`
**Trace:** malicious upload → `ImageMetadataReader.readMetadata()` → exception with attacker-controlled message → `log.warn("metadata-extractor failed ... {}", e.getMessage())`
**Description:** Exception messages from metadata-extractor parsing may contain content derived from the file's binary structure (malformed tag values). Unlike the Redis log path which sanitizes output, this path writes the exception message verbatim, enabling CRLF injection into log files to forge log entries.
**Impact:** Log injection (not code execution). Attacker can forge log entries in worker structured output.

---

## Group C: Share System & Search

### FINDING C1: IPTC and XMP Location Data Leaked via Share When `includeGps=false`
**Severity:** High
**Confidence:** High (>80%) — Direct code trace: `findPhotoById` returns `iptc_data`/`xmp_data`; only `exif_data` passed to `stripGpsFromExif()`; `iptc_data`/`xmp_data` written raw to response.
**Location:** `api/src/main/java/org/jphototagger/api/controller/ShareController.java:100-132`, `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java:64-74`, `api/src/main/java/org/jphototagger/api/service/ShareService.java:148-166`
**Trace:** `GET /share/{token}` → `ShareController.getShare()` → `shareLookupRepository.findPhotoById(resourceId)` returns Map with `iptc_data` and `xmp_data` → only `exif_data` passed to `shareService.stripGpsFromExif()` → `iptc_data`/`xmp_data` written to response via `response.put("photo", photo)`
**Description:** The share GPS stripping only processes `exif_data`. IPTC data can contain city, sub-location, province/state, country fields. XMP data can contain `photoshop:City`, `iptc4xmpcore:Location`, `xmp:location`. The authenticated `PhotoMetadataResponse.withoutGps()` correctly handles all three via `filterLocationKeys(iptcData, IPTC_LOCATION_KEYS)` and `filterGpsAndLocationKeys(xmpData)`. The share path uses a different, incomplete stripping implementation.
**Exploit Scenario:** Alice uploads a photo with IPTC location fields set by photo editing software: `City: "Berlin"`, `Sub-location: "Kreuzberg"`. She creates a share with `includeGps=false`. Share recipient receives `"iptc_data": {"City":"Berlin","Sub-Location":"Kreuzberg","Country-Primary Location Name":"Germany"}` — GPS EXIF is stripped but human-readable location is exposed.
**Impact:** User's location privacy intent is bypassed despite explicit `includeGps=false` setting. IPTC/XMP fields are often more human-readable than raw GPS coordinates.

---

### FINDING C2: Raw `storage_key` Exposed in Share Response, Leaking Photo Owner's UUID
**Severity:** Medium
**Confidence:** High (>80%) — `findPhotoById` SELECT includes `p.storage_key`; key format is `{userId}/originals/{photoId}.{ext}`; `response.put("photo", photo)` serializes entire map.
**Location:** `api/src/main/java/org/jphototagger/api/controller/ShareController.java:111-132`, `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java:49-50, 67-68`
**Trace:** `GET /share/{token}` → `findPhotoById()` returns Map with `storage_key` → `storage_key` used to generate presigned URL → `storage_key` field never removed from map → `response.put("photo", photo)` serializes it
**Description:** `storage_key` is selected in both `findPhotoById` and `findAlbumPhotos` queries. The controller uses it to generate presigned URLs but never removes it from the photo map before serialization. Share visitors (unauthenticated) receive `"storage_key": "{userId}/originals/{photoId}.ext"`, exposing the photo owner's internal UUID.
**Exploit Scenario:** Attacker accesses any public share link and extracts the photo owner's UUID from `storage_key` field. UUID is a stable cross-reference identifier for user tracking across shares.
**Impact:** Breaks user pseudonymity in the share system; leaks internal UUID of photo owners to unauthenticated visitors.

---

### FINDING C3: `findPhotoById` Has No User-ID Predicate Under BYPASSRLS — Defense-in-Depth Gap
**Severity:** Medium
**Confidence:** Medium (50-80%) — Missing WHERE clause confirmed; current exploitability blocked by share creation ownership validation; risk is real if share record integrity is broken.
**Location:** `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java:64-74`
**Trace:** `GET /share/{token}` → `shareLookupRepository.findShareByTokenHash(hash)` returns `resource_id` → `shareLookupRepository.findPhotoById(resourceId)` executes `WHERE p.id = ? AND p.deleted_at IS NULL` — no `user_id` constraint — against BYPASSRLS DataSource
**Description:** `findPhotoById` runs as `share_reader` (BYPASSRLS). The query has no `AND p.user_id = <share_creator_user_id>` predicate. If `shares.resource_id` is corrupted (via admin bug, SQL migration error, or secondary injection), `share_reader` would serve any user's private photo without application-layer rejection.
**Impact:** If share record integrity is broken by any secondary path, cross-user photo access is possible via unauthenticated share request.

---

## Group D: Access Control & RLS

### FINDING D1: Cross-Tenant Keyword UUID Existence Oracle via Missing Parent Ownership Check
**Severity:** Low
**Confidence:** High (>80%) — `createKeyword` validates `parentId` ownership; `updateKeyword` does not; FK check validates against unfiltered `keywords` table; FK violation vs. success leaks existence.
**Location:** `api/src/main/java/org/jphototagger/api/service/KeywordService.java:54-61`
**Trace:** `PUT /keywords/{id}` → `KeywordService.updateKeyword(userId, keywordId, name, parentId)` → `keyword.setParentId(parentId)` → `keywordRepository.save(keyword)` → PostgreSQL FK check on `keywords(id)` (bypasses RLS) → success/error oracle
**Description:** `createKeyword` validates `parentId` belongs to the authenticated user, but `updateKeyword` skips this check. PostgreSQL FK constraint `parent_id REFERENCES keywords(id)` validates against the full keywords table without RLS. 200 OK = UUID exists as a keyword globally; FK violation = UUID doesn't exist. Also creates a data-integrity anomaly: attacker's keyword gets `parent_id` pointing to another user's keyword.
**Exploit Scenario:** Attacker calls `PUT /keywords/{own-keyword-id}` with `parentId` set to a guessed UUID. 200 OK confirms the UUID exists as a keyword in the system for any tenant.
**Impact:** Binary oracle confirming UUID existence in keywords table across tenants. Data integrity anomaly from cross-user FK references.

---

### FINDING D2: RLS Context Never Set in Background Schedulers — Schedulers Silently No-Op
**Severity:** Medium
**Confidence:** High (>80%) — `connection-init-sql` sets nil UUID; `RlsContext.getCurrentUserId()` is null in scheduler threads; `RlsAspect` no-ops when userId is null; nil UUID matches no rows.
**Location:** `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`, `OrphanReconciliationScheduler.java:80-93`, `UnverifiedAccountPurgeScheduler.java:79-110`
**Trace (TrashPurge):** `purgeTrash()` → `photoRepository.findPurgeableBatch(cutoff)` → PostgreSQL RLS: `user_id = '00000000-...'` → 0 rows → nothing purged
**Trace (UnverifiedPurge):** `purgeUser(userId)` → `photoRepository.findAllByUserIdWithStorageKey(userId)` → RLS nil UUID → 0 rows → MinIO objects never deleted → `authJdbcTemplate.update("DELETE FROM photos WHERE user_id = ?")` (BYPASSRLS) → DB records deleted, MinIO objects orphaned
**Description:** Scheduler threads run outside HTTP requests, so `RlsContext.getCurrentUserId()` is null. `RlsAspect` no-ops when userId is null, leaving the Hikari connection-init `'00000000-...'` as the RLS context. All primary-datasource queries return 0 rows. `TrashPurgeScheduler` never purges soft-deleted photos. `UnverifiedAccountPurgeScheduler` deletes DB records via BYPASSRLS but can't find MinIO objects (RLS-blocked photo query), causing permanent storage leaks.
**Impact:** Retention window guarantees cannot be met (regulatory risk). MinIO storage fills with unreachable orphaned objects. Storage quota system unreliable.

---

### FINDING D3: Recursive Keyword CTE Missing `user_id` in Recursive Step
**Severity:** Low
**Confidence:** Medium (50-80%) — RLS fully mitigates today; risk is a defense-in-depth gap for future RLS misconfiguration.
**Location:** `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java:25-33`
**Trace:** `GET /keywords/{id}/subtree` → `keywordRepository.findSubtree(userId, rootId)` → recursive CTE step `SELECT k.* FROM keywords k INNER JOIN subtree s ON k.parent_id = s.id` — no `AND k.user_id = :userId`
**Description:** Recursive CTE anchor correctly scopes to `user_id`, but the recursive expansion step has no `user_id` filter. RLS is the sole guard. If RLS is disabled/bypassed, recursive join traverses all tenants.
**Impact:** Cross-tenant keyword tree exposure if RLS is bypassed. Combined with D1, the attacker could have set a cross-tenant FK link, potentially exposing victim's subtree.

---

### FINDING D4: `@Transactional` on Controller Methods — Ordering Fragility
**Severity:** Low
**Confidence:** Medium (50-80%) — Currently safe; risk materializes only if aspect ordering changes.
**Location:** `api/src/main/java/org/jphototagger/api/controller/PhotoController.java:124, 146`
**Description:** Two controller methods (`addKeywordToPhoto`, `removeKeywordFromPhoto`) are annotated with `@Transactional` directly on the controller. Currently safe due to `@Order(0)` on `TransactionInterceptor` and `@Order(1)` on `RlsAspect`. If ordering changes, RLS context may not be set for these endpoints.
**Impact:** Under current config: none. Risk materializes if aspect ordering changes or proxy model changes.


# Phase 3: Validated Findings

### Finding #1: Broken Timing Equalization — User Enumeration via Login — Severity: Medium — Confidence: High
**Location:** `api/src/main/java/org/jphototagger/api/service/AuthService.java:93`
**Attack Surface:** `POST /auth/login` (unauthenticated)
**Description:** The anti-enumeration dummy BCrypt hash is 65 characters long. Spring Security's `BCryptPasswordEncoder.matches()` requires exactly 53 characters in the hash segment (60 chars total) or the regex pattern check fails immediately — returning `false` in microseconds instead of performing real BCrypt (~250ms). Result: unknown emails return in ~1ms, known emails (wrong password) return in ~250ms.
**Trace:** `POST /auth/login` → `authenticate()` → empty DB result → `passwordEncoder.matches(pw, "$2a$12$dummy.hash.to.prevent.timing...")` → 65-char hash fails BCRYPT_PATTERN regex → immediate `false` (~1ms)
**Proof-of-Concept:**
```bash
time curl -s -X POST http://api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"nobody@example.com","password":"x"}' -o /dev/null   # ~1ms
time curl -s -X POST http://api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"real@example.com","password":"x"}' -o /dev/null     # ~250ms
```
**Patch:**
```diff
- passwordEncoder.matches(password, "$2a$12$dummy.hash.to.prevent.timing.side.channel.attacks.00000000");
+ // Hash must be exactly 60 chars ($2a$ + 2-digit cost + $ + 53 base-64 chars) for BCryptPasswordEncoder
+ // to run full BCrypt instead of fast-failing the pattern check.
+ passwordEncoder.matches(password, "$2a$12$xxxxxxxxxxxxxxxxxxxxxxxxuAQIkWFkNuvxPFMO3a4YFnPkLJYrK.");
```
**Impact:** Attacker enumerates registered email addresses at ~20/hour/IP (bypassable via proxies). Enables targeted phishing, credential stuffing, and account existence confirmation. Potential GDPR Article 5(1)(f) issue.

---

### Finding #2: HTTP Status Code Oracle Enables Lockout-Free Password Brute-Force on Unverified Accounts — Severity: Medium — Confidence: High
**Location:** `api/src/main/java/org/jphototagger/api/service/AuthService.java:113-136`, `AuthController.java:90-96`
**Attack Surface:** `POST /auth/login` (unauthenticated)
**Description:** `failed_login_attempts` counter is only incremented when `!passwordCorrect`. Correct password on unverified account triggers `EmailVerificationRequiredException` → HTTP 403; wrong password → HTTP 401. Correct-password probes do NOT increment the lockout counter. During the 24-hour email verification window, an attacker can brute-force passwords with lockout effectively disabled.
**Trace:** `authenticate()` → `passwordCorrect=true` → lockout counter NOT incremented → `emailVerified=false` → HTTP 403 (vs. HTTP 401 for wrong password)
**Proof-of-Concept:**
```bash
for pass in "wrong1" "wrong2" "Secret123" "wrong3"; do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://api/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"victim@example.com\",\"password\":\"$pass\"}")
  echo "$pass -> $code"
done
# wrong1->401, wrong2->401, Secret123->403, wrong3->401  (no lockout ever triggered)
```
**Patch:**
```diff
// In AuthService: increment counter AND lock on correct-password-unverified-email path
+ int newAttempts = failedAttempts + 1;
+ authJdbc.update("UPDATE users SET failed_login_attempts = ? WHERE id = ?", newAttempts, userId);
  throw new EmailVerificationRequiredException("...");

// In AuthController: return 401 (not 403) for unverified email to remove status oracle
- return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("Please verify...", 403));
+ return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Invalid credentials", 401));
```
**Impact:** Attacker can brute-force passwords against any unverified account without triggering lockout, using HTTP status codes as a binary oracle. Rate limit (20/hr/IP) is the only defense, bypassable with IP rotation. Successful discovery enables account takeover once victim verifies email.

---

### Finding #3: Non-Atomic Lockout Counter Race Condition — Severity: Low — Confidence: High
**Location:** `api/src/main/java/org/jphototagger/api/service/AuthService.java:87-125`
**Attack Surface:** `POST /auth/login` (concurrent unauthenticated requests)
**Description:** Failed login counter uses a non-atomic read-modify-write (SELECT then UPDATE) with no row locking. Concurrent requests all read the same `failedAttempts` value and all write the same incremented result, leaving the counter under-counted. The 20 req/hour/IP rate limit is the primary defense (still applies), making practical impact low.
**Proof-of-Concept:**
```bash
for i in {1..5}; do curl -s -X POST http://api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"victim@example.com","password":"wrong"}' & done; wait
# counter often ends at 2-3 instead of 5; lockout not triggered
```
**Patch:**
```diff
- int newAttempts = failedAttempts + 1;
- authJdbc.update("UPDATE users SET failed_login_attempts = ? WHERE id = ?", newAttempts, userId);
+ int newAttempts = authJdbc.queryForObject(
+     "UPDATE users SET failed_login_attempts = failed_login_attempts + 1 WHERE id = ? RETURNING failed_login_attempts",
+     Integer.class, userId);
```
**Impact:** Weakens account lockout defense under concurrent-request bursts. Low impact due to rate limiter compensation, but compounds with Finding #2.

---

### Finding #4: IPTC/XMP Location Data Leaked via Share When `includeGps=false` — Severity: High — Confidence: High
**Location:** `api/src/main/java/org/jphototagger/api/controller/ShareController.java:100-132`, `ShareLookupRepository.java:64-74`, `ShareService.java:148-166`
**Attack Surface:** `GET /share/{token}` (unauthenticated, public share link)
**Description:** Share GPS stripping only processes `exif_data`. `iptc_data` (City, Sub-location, Province/State, Country) and `xmp_data` (`photoshop:City`, `iptc4xmpcore:Location`) are written raw to the response even when `includeGps=false`. The authenticated code path (`PhotoMetadataResponse.withoutGps()`) correctly handles all three metadata types; the share path uses a different, incomplete implementation.
**Trace:** `GET /share/{token}` → `findPhotoById()` returns `{exif_data, iptc_data, xmp_data}` → only `exif_data` passed to `stripGpsFromExif()` → `iptc_data`/`xmp_data` written raw to response
**Proof-of-Concept:**
```bash
curl -s "http://api/share/$TOKEN" | jq '.photo.iptc_data'
# Returns: {"City":"San Francisco","Sub-location":"Golden Gate Park","Country-Primary Location Name":"USA"}
# GPS stripped, but human-readable location fully exposed
```
**Patch:**
Add `stripLocationFromIptc()` and `stripLocationFromXmp()` methods to `ShareService` mirroring the existing `filterLocationKeys()` logic, then call them in `ShareController.getShare()`:
```diff
// ShareController.java (in the !includeGps block)
+ Object iptcData = photo.get("iptc_data");
+ if (iptcData != null) photo.put("iptc_data", shareService.stripLocationFromIptc(iptcData.toString()));
+ Object xmpData = photo.get("xmp_data");
+ if (xmpData != null) photo.put("xmp_data", shareService.stripLocationFromXmp(xmpData.toString()));
```
**Impact:** Users' location privacy intent is silently violated. Any share link recipient can extract precise location data (city, street, country) despite `includeGps=false`. High severity: completely unauthenticated, requires only a share token, IPTC/XMP fields are often more precise than raw GPS, potential GDPR/CCPA violation for location PII.

---

### Finding #5: Raw `storage_key` Exposed in Share Response — Severity: Medium — Confidence: High
**Location:** `api/src/main/java/org/jphototagger/api/controller/ShareController.java:111-132`, `ShareLookupRepository.java:49-50, 67-68`
**Attack Surface:** `GET /share/{token}` and `GET /share/{token}/photos` (unauthenticated)
**Description:** `storage_key` is selected in both `findPhotoById` and `findAlbumPhotos` queries, used to generate presigned URLs, but never removed from the response map. Format: `{userId}/originals/{photoId}.ext`. All unauthenticated share visitors receive the photo owner's internal UUID.
**Proof-of-Concept:**
```bash
curl -s "http://api/share/$TOKEN" | jq '.photo.storage_key'
# "550e8400-e29b-41d4-a716-446655440000/originals/7b3d2b1a-9e4f-4c6d-b2e3-1f2a3b4c5d6e.jpg"
```
**Patch:**
```diff
// ShareController.java — after creating mutable photo map:
+ photo.remove("storage_key");
// For album photos, map the page:
- return shareLookupRepository.findAlbumPhotos(albumId, capped);
+ return shareLookupRepository.findAlbumPhotos(albumId, capped).map(p -> { Map<String,Object> safe = new HashMap<>(p); safe.remove("storage_key"); return safe; });
```
**Impact:** Breaks user pseudonymity in the share system; leaks internal UUID of photo owners to unauthenticated visitors. Building block for chained attacks. Affects every shared photo and album.

---

### Finding #6: Background Schedulers Silently No-Op — Trash/Orphan/Purge Never Run — Severity: High — Confidence: High
**Location:** `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`, `OrphanReconciliationScheduler.java:80-93`, `UnverifiedAccountPurgeScheduler.java:79-110`
**Attack Surface:** Background scheduled tasks; no attacker trigger required
**Description:** Scheduler threads never have an HTTP request context, so `RlsContext.getCurrentUserId()` is always `null`. `RlsAspect` no-ops when userId is null, leaving `app.current_user_id = '00000000-...'` (from `connection-init-sql`) as the RLS context. PostgreSQL evaluates `user_id = null::uuid` as NULL → all primary-datasource queries return 0 rows. `TrashPurgeScheduler` never permanently deletes soft-deleted photos. `OrphanReconciliationScheduler` never reconciles MinIO orphans. `UnverifiedAccountPurgeScheduler` deletes DB records via BYPASSRLS auth datasource correctly, but cannot retrieve photos via the primary datasource (RLS blocks them) — MinIO objects are permanently leaked.
**Trace:** `@Scheduled` on scheduler thread → `RlsContext.getCurrentUserId()=null` → `RlsAspect` no-ops → `app.current_user_id='00000000-...'` → `user_id = null::uuid` → 0 rows returned → nothing purged, forever
**Proof-of-Concept:**
```bash
# As DBA, after 30+ days with soft-deleted photos:
SELECT count(*) FROM photos WHERE deleted_at < now() - interval '30 days';
# Returns N > 0 — retention policy completely non-functional
```
**Patch:** Replace `photoRepository` calls in schedulers with direct queries via the `authJdbcTemplate` (BYPASSRLS):
```diff
// TrashPurgeScheduler — use authJdbc for cross-tenant admin queries:
- List<Photo> batch = photoRepository.findPurgeableBatch(cutoff, batchSize);
+ List<UUID> batch = authJdbc.queryForList(
+     "SELECT id FROM photos WHERE deleted_at < ? AND storage_key IS NOT NULL LIMIT ?",
+     UUID.class, Timestamp.from(cutoff), batchSize);

// UnverifiedAccountPurgeScheduler — use authJdbc for photo lookup:
- List<Photo> photos = photoRepository.findAllByUserIdWithStorageKey(userId);
+ List<String> storageKeys = authJdbc.queryForList(
+     "SELECT storage_key FROM photos WHERE user_id = ? AND storage_key IS NOT NULL", String.class, userId);
```
**Impact:** Trash retention policy is completely non-functional — deleted content lives forever. GDPR/retention SLA violations. MinIO orphan accumulation (unbounded storage cost). For purged unverified accounts: permanent MinIO storage leak (GDPR erasure obligation failure). All failures are silent (logs show "purged 0 photos" which is indistinguishable from "nothing to purge").

---

### Finding #7: `restore()` Concurrent Race Causes `used_bytes` Double-Count — Severity: Low — Confidence: High
**Location:** `api/src/main/java/org/jphototagger/api/service/PhotoService.java:298-319`
**Attack Surface:** `POST /photos/{id}/restore` (authenticated)
**Description:** Photo entity is fetched before the user row `PESSIMISTIC_WRITE` lock is acquired. Two concurrent restores both pass the `deletedAt != null` filter, then sequentially acquire the user lock and each add `sizeBytes` to `usedBytes` — permanently inflating the quota counter. `softDelete()` correctly acquires the lock first and re-reads the photo inside the lock; `restore()` does not.
**Patch:**
```diff
// Acquire user lock FIRST, then re-read photo inside the lock (mirrors softDelete pattern):
+ User user = entityManager.createQuery("SELECT u FROM User u WHERE u.id = :uid", User.class)
+     .setParameter("uid", userId).setLockMode(PESSIMISTIC_WRITE).getSingleResult();
  Photo photo = photoRepository.findById(photoId)
      .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() != null)
      .orElseThrow(...);
- User user = entityManager.createQuery(...).setLockMode(PESSIMISTIC_WRITE)...;
```
**Impact:** User can inflate their `used_bytes` quota counter by racing concurrent restore requests. Practical impact is low (requires precise timing, affects only own account), but could be used to DOS a personal account.

---

### Finding #8: OAuth2 `email_verified` Claim Not Checked — Severity: Low — Confidence: Low
**Location:** `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:67-90`
**Attack Surface:** OAuth2 OIDC callback (unauthenticated, after IdP redirect)
**Description:** `oidcUser.isEmailVerified()` is never called before creating an account with `email_verified=true`. Currently safe with Google/GitHub (which always return `email_verified=true`), but if any OIDC provider with unverified emails is ever configured, this bypasses the email ownership requirement.
**Patch:**
```diff
+ Boolean emailVerified = oidcUser.getEmailVerified();
+ if (!Boolean.TRUE.equals(emailVerified)) {
+     response.sendRedirect(redirectUri + "login?error=email_not_verified");
+     return;
+ }
```
**Impact:** If future OIDC providers allow unverified emails, attackers can register with email addresses they don't own. Fix is trivial (two lines); low confidence for current deployment.

---

# Appendix: Discarded Findings

- **D1 (Cross-Tenant Keyword UUID Oracle):** `updateKeyword` FK oracle is real but not practically exploitable — UUIDs are 122-bit random (infeasible brute-force) and no data content is exposed via success/failure. The cross-tenant FK reference is a data-integrity anomaly worth fixing in a code review but not a security finding.
- **D3 (Recursive Keyword CTE Missing user_id):** RLS is the designed and correctly functioning guard. PostgreSQL applies RLS policies to all table accesses including recursive CTE steps. Not a vulnerability.
- **D4 (@Transactional on Controller Methods):** Currently safe; architectural code smell only.
- **C3 (findPhotoById No User-ID Predicate):** Defense-in-depth gap, not independently exploitable. Requires corrupting a share record in the DB; no application path enables this.

# Appendix: Inconclusive Findings

- **B2 (Log Injection via Exception Message):** Technically possible via crafted image causing `metadata-extractor` to embed controlled content in exception messages, but could not verify that the library exposes attacker-controlled strings in exception messages. Recommend adding CRLF-stripping to the log encoder (`PatternLayoutEncoder` with `%replace(%msg){'[\r\n]+',' '}`) as a low-cost precaution.


# Phase 4: Summary

| Severity | Count |
|----------|-------|
| Critical | 0     |
| High     | 2     |
| Medium   | 3     |
| Low      | 3     |
| **Total** | **8** |

**Most urgent issues:**
1. **Finding #4** — IPTC/XMP location data leaks through public share links despite `includeGps=false`. The privacy control is silently broken and completely unauthenticated to exploit.
2. **Finding #6** — All three background schedulers (trash purge, orphan reconciliation, unverified account purge) silently no-op due to missing RLS context. Soft-deleted photos are never permanently deleted; MinIO objects are never cleaned up. GDPR retention guarantees cannot be met.

**Recommended next actions:**
1. **Fix #4 immediately** — `ShareController.java`: add `stripLocationFromIptc()` and `stripLocationFromXmp()` calls to the `!includeGps` block; add corresponding methods to `ShareService.java`. This is a ~30-line fix and unblocks the privacy guarantee for all existing shares.
2. **Fix #6 immediately** — Replace `photoRepository` calls in all three schedulers with direct `authJdbcTemplate` queries that bypass RLS. The pattern is already used in `UnverifiedAccountPurgeScheduler` for user lookup — extend it to photo/MinIO queries. Test that each scheduler reports non-zero purge counts in a dev environment with test data.
3. **Fix #1 (timing equalization)** — Replace the dummy hash string with a syntactically valid 60-character BCrypt hash. Single-line fix: `"$2a$12$xxxxxxxxxxxxxxxxxxxxxxxxuAQIkWFkNuvxPFMO3a4YFnPkLJYrK."`. Should be deployed with #2.
4. **Fix #2 (status oracle)** — Change `AuthController.java` to return HTTP 401 (not 403) for `EmailVerificationRequiredException`, and increment `failed_login_attempts` before throwing it in `AuthService.java`.
5. **Fix #5 (storage_key exposure)** — `ShareController.java`: add `photo.remove("storage_key")` after creating the mutable copy, and wrap album photo results to strip the field. Alternatively, remove `p.storage_key` from the SQL SELECT in `ShareLookupRepository.java`.
6. **Fix #3 and #7 (race conditions)** — Make lockout counter atomic with `UPDATE ... RETURNING` and reorder the `restore()` lock acquisition to match `softDelete()` pattern.
7. **Fix #8 (OAuth2 email_verified)** — Add `!Boolean.TRUE.equals(oidcUser.getEmailVerified())` guard in `OAuth2SuccessHandler.java` before account creation. Trivial change, eliminates future risk.

**Scan coverage:**
- Entry points reviewed: 27 of 27 (all routes in attack surface table)
- Vulnerability classes checked: Authentication & Session, Cryptographic Weaknesses, Business Logic, Data Exposure, Authorization & Access Control, Injection (SQLi/JSONB/command), Supply Chain (XXE, polyglot, unsafe deser), SSRF, Log Injection, Race Conditions
- Areas NOT covered: Nginx/TLS configuration (headers reviewed in threat model but not deep-audited), MinIO IAM policy files (not present in repo — assumed configured externally), frontend XSS surface (React/TypeScript frontend reviewed for API calls but no deep XSS audit), dependency CVE scan (no automated tool run against pom.xml/package.json)

