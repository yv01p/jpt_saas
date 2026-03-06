# Security Audit — Phase 2: Backend API (Auth, Security, REST Endpoints)

**Audited file:** `docs/plans/2026-02-25-saas-conversion-phase-2.md` (v4.0)
**Audit date:** 2026-03-05
**Auditor:** Lead Cyber-Security Auditor (LCSA)
**Audit number:** 2 (SA-2; SA-1 was `docs/plans/2026-03-04-saas-conversion-phase-2-security-audit-1.md`)
**Scope:** Implementation as of 2026-03-05 — actual code, not the plan

---

## Scope & Materials Received

This audit reviewed the **implemented code** for Phase 2, not the plan document itself. The following
files were analyzed:

**Security layer:** `SecurityConfig.java`, `JwtService.java`, `JwtAuthenticationFilter.java`,
`RlsAspect.java`, `RlsContext.java`, `RlsContextCleanupFilter.java`, `RlsInterceptor.java`,
`OAuth2SuccessHandler.java`, `RateLimitFilter.java`

**Services:** `AuthService.java`, `RefreshTokenService.java`, `PhotoService.java`,
`AlbumService.java`, `KeywordService.java`, `SearchService.java`, `SavedSearchService.java`

**Controllers:** `AuthController.java`, `PhotoController.java`, `AlbumController.java`,
`KeywordController.java`, `SearchController.java`, `SavedSearchController.java`,
`GlobalExceptionHandler.java`

**Configuration:** `AuthDataSourceConfig.java`, `RateLimitConfig.java`, `application.yml`,
`application-dev.yml`, `application-test.yml`

**Database:** `V1__core_schema.sql`, `V2__rls_policies.sql`, `V3__worker_db_user.sql`,
`V4__create_jpt_auth_role.sql`

**Infrastructure:** `docker-compose.yml`, `nginx.conf`, `api/build.gradle.kts`

**Entities/DTOs:** `User.java`, `Photo.java`, `LoginRequest.java`, `RegisterRequest.java` (and
remaining entities)

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points

| Endpoint | Auth Required | CSRF | Notes |
|---|---|---|---|
| `POST /auth/register` | No | Exempt | User registration |
| `POST /auth/login` | No | Exempt | Credential login |
| `POST /auth/refresh` | No (cookie) | Exempt | Token rotation |
| `POST /auth/logout` | No (cookie) | Exempt | Cookie clearing |
| `/login/oauth2/code/*` | No | Exempt | OAuth2 callback |
| `GET /actuator/health` | No | Yes | Health probe |
| `GET /photos/**` | JWT cookie | Yes | Photo CRUD |
| `GET /albums/**` | JWT cookie | Yes | Album CRUD |
| `GET /keywords/**` | JWT cookie | Yes | Keyword CRUD |
| `GET /search/**` | JWT cookie | Yes | Search |
| `GET /saved-searches/**` | JWT cookie | Yes | Saved searches |

### Trust Boundaries

- Internet → Nginx → API (Spring Boot) → PostgreSQL / Redis / MinIO
- Nginx: `frontend` network; API, DB, Redis, MinIO: `backend` (internal, not internet-reachable)
- Worker: `backend` network only
- Two PostgreSQL roles: `jpt_app` (RLS-enforced), `jpt_auth` (BYPASSRLS for auth operations)

### Authentication Architecture

- JWT (HMAC-SHA256) in `httpOnly; Secure; SameSite=Lax` cookie — 15-minute expiry
- Refresh tokens: 256-bit random, SHA-256 hashed in Redis, 30-day TTL
- Token family tracking for replay detection
- Row-Level Security (PostgreSQL) enforced via `set_config('app.current_user_id', ?, true)` in
  `RlsAspect` (AOP, `@Order(1)`, fires after `TransactionInterceptor` at `@Order(0)`)
- `RlsContextCleanupFilter` (`@Order(HIGHEST_PRECEDENCE)`) guarantees ThreadLocal cleanup in `finally`

### Sensitive Data Flows

- Passwords → `BCryptPasswordEncoder(12)` → `password_hash` in DB
- Refresh tokens → `SHA-256` → Redis, TTL-bound
- Email verification tokens → `SHA-256` → `email_tokens` table
- EXIF/IPTC/XMP → JSONB columns (not mapped to entity, native queries only)
- JWT signing key → validated at startup, must be 43+ chars in non-dev/test profiles

---

## Pass 2: Vulnerability Findings

---

### Finding #1: No Application-Layer Rate Limiting on Authentication Endpoints

**Vulnerability:** Missing Rate Limiting / Brute Force Protection — OWASP A07 (Authentication Failures)
**Severity:** High
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/security/RateLimitFilter.java`, Lines: 70–76
- Related: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`, Lines: 57–58

**Risk & Exploit Path:**

`RateLimitFilter` is registered after `JwtAuthenticationFilter` and only applies rate limits to
**authenticated** requests. All unauthenticated requests — including `POST /auth/login`,
`POST /auth/register`, and `POST /auth/refresh` — bypass the filter entirely.

The only brute-force control on the login endpoint is the per-account lockout in `AuthService`
(5 failed attempts → 15-minute lockout). This protects individual accounts but does not prevent:

1. **Credential stuffing:** An attacker with a breached credential list can attempt 5 passwords
   against 10,000 accounts (50,000 requests) with zero application-level throttling. The lockout
   only fires per account; cross-account attacks are undetected.
2. **Registration spam:** `POST /auth/register` is entirely unprotected — an attacker can create
   unlimited accounts, exhausting the email sending quota and polluting the user table.
3. **Refresh token probing:** `POST /auth/refresh` is unprotected; while refresh tokens are
   cryptographically strong (256-bit), the endpoint itself could be targeted for other attack
   patterns.

**Evidence / Trace:**

```java
// RateLimitFilter.java:70–76
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UUID userId)) {
    filterChain.doFilter(request, response);  // ← ALL UNAUTHENTICATED REQUESTS BYPASS RATE LIMITING
    return;
}
```

```java
// SecurityConfig.java:57–58 — auth endpoints are CSRF-exempt and permit all
.ignoringRequestMatchers("/auth/login", "/auth/register", "/auth/refresh", "/auth/logout", ...)
.requestMatchers("/auth/**", "/actuator/health").permitAll()
```

The `JwtAuthenticationFilter` correctly does not set authentication for unauthenticated requests,
so the `auth == null` branch is always taken for all auth endpoints.

**Remediation:**

- **Primary fix:** Apply IP-based rate limiting to unauthenticated auth endpoints. This can be
  implemented either in Nginx (recommended — before the request reaches Spring) or in a modified
  `RateLimitFilter` that falls back to IP-keyed buckets when `auth == null`:

  ```java
  // Nginx: limit_req_zone for /auth/login, /auth/register
  limit_req_zone $binary_remote_addr zone=auth:10m rate=5r/m;
  location /api/auth/ {
      limit_req zone=auth burst=10 nodelay;
      ...
  }
  ```

  Or in `RateLimitFilter`, add an IP-based bucket for unauthenticated paths:
  ```java
  if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UUID)) {
      if (isAuthEndpoint(request)) {
          String ip = request.getRemoteAddr();
          // apply IP-keyed bucket (low limit: e.g. 10/min)
          applyRateLimit("rate:auth:" + ip, authBucketConfig());
      }
      filterChain.doFilter(request, response);
      return;
  }
  ```

- **Architectural improvement:** Nginx rate limiting is preferred because it rejects before the
  JVM is involved. Add `limit_req` directives to the production nginx config targeting `/api/auth/`.
- **Defense-in-depth:** Add CAPTCHA on register after N IP-level attempts. Monitor failed login
  counts across all accounts from the same IP (log aggregation / alerting).

**References:**
- OWASP Testing Guide: Testing for Weak Lock Out Mechanism (OTG-AUTHN-003)
- NIST SP 800-63B §5.2.2 (rate limiting on authentication attempts)

---

### Finding #2: Raw Exception Messages Exposed via Global Exception Handler

**Vulnerability:** Information Leakage — OWASP A09 (Security Logging and Monitoring Failures /
Information Disclosure)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java`,
  Lines: 54–64

**Risk & Exploit Path:**

The `GlobalExceptionHandler` passes raw `ex.getMessage()` directly to the API response for two
broad exception types: `IllegalArgumentException` and `IllegalStateException`. These types are
thrown by both application code and third-party libraries. As the codebase grows, any library
that throws an `IllegalArgumentException` with an implementation-revealing message (e.g., internal
class names, stack frame context, database paths) will automatically expose that message to clients.

Currently, all thrown `IllegalArgumentException`/`IllegalStateException` messages are
developer-authored and benign. However, a specific current instance already leaks input data back
to the caller: in `KeywordController`, if `parentId` is a non-UUID string,
`UUID.fromString("bad-value")` throws `IllegalArgumentException("Invalid UUID string: bad-value")`,
which is returned verbatim in the 409 response body.

A second concern: `IllegalArgumentException` is incorrectly mapped to **409 Conflict** instead of
**400 Bad Request**. Clients receiving 409 for malformed input cannot distinguish between "resource
already exists" (true conflict) and "your input was invalid" (client error).

**Evidence / Trace:**

```java
// GlobalExceptionHandler.java:60–64
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)  // ← wrong status for validation errors
            .body(new ErrorResponse(ex.getMessage(), HttpStatus.CONFLICT.value())); // ← raw message
}
```

```java
// KeywordController.java:52
UUID parentId = body.get("parentId") != null ? UUID.fromString((String) body.get("parentId")) : null;
//                                             ↑ throws IAE("Invalid UUID string: <attacker-value>")
//                                               → exposed to client verbatim as 409 body
```

**Remediation:**

- **Primary fix:** Use explicit safe messages for client-facing errors. Do not pass `ex.getMessage()`
  for broad exception types. Instead, catch specific exceptions with known-safe messages or use a
  generic fallback:

  ```java
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
      return ResponseEntity.badRequest()  // 400, not 409
              .body(new ErrorResponse("Invalid request parameters", 400));
  }
  ```

- **Architectural improvement:** Replace the `Map<String, Object>` / `Map<String, String>` request
  body pattern in `AlbumController`, `KeywordController`, and `SavedSearchController` with typed
  DTOs annotated with `@Valid`. This moves validation to Bean Validation (which has controlled
  messages) and prevents `UUID.fromString()` from ever being called on unvalidated user input.
- **Defense-in-depth:** Add a catch-all `@ExceptionHandler(Exception.class)` at the lowest
  priority that returns a generic "Internal server error" (500) with a logged request ID, preventing
  any unhandled exception from leaking details.

---

### Finding #3: TOCTOU Race Condition in Refresh Token Rotation

**Vulnerability:** Race Condition — OWASP A08 (Software and Data Integrity Failures)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** High

**Location:**
- File: `api/src/main/java/org/jphototagger/api/service/RefreshTokenService.java`, Lines: 65–83

**Risk & Exploit Path:**

The `rotate()` method performs a non-atomic read-then-delete on Redis. Two concurrent requests
presenting the same refresh token would both succeed the `get()` before either executes `delete()`.
Both requests would then issue separate new tokens in the same family, and the replay detection
would not trigger (because both requests found the token in the active set, not as a consumed token).

Practical exploitability is **very low**: the attack requires the refresh token (stored in an
`httpOnly; Secure` cookie — not accessible to JavaScript) to be presented simultaneously from two
independent clients. This scenario is primarily a concern if a refresh token is stolen via another
vector (e.g., network interception, compromised client) and the attacker races the legitimate user.

The consequence is double-issuance of tokens (two valid tokens instead of one), not credential
bypass.

**Evidence / Trace:**

```java
// RefreshTokenService.java:65–82
String data = redis.opsForValue().get(key);   // ← Read #1: both concurrent requests see the token
if (data == null) {
    detectReplay(hash);
    throw new InvalidRefreshTokenException("Invalid refresh token");
}
// ... (no distributed lock held between get and delete)
redis.delete(key);                             // ← Delete: non-atomic with the get above
redis.opsForSet().remove(USER_REFRESH_PREFIX + userId, hash);
String newRawToken = createTokenInFamily(userId, familyId);  // ← Both issue new tokens
```

Redis `GETDEL` (available since Redis 6.2) would make this atomic. `StringRedisTemplate` doesn't
expose `GETDEL` directly but it can be executed via `execute(RedisCallback)`.

**Remediation:**

- **Primary fix:** Replace the `get` + `delete` with an atomic `GETDEL` command:

  ```java
  String data = redis.execute(
      (RedisCallback<String>) conn ->
          conn.keyCommands().getDel(key.getBytes(StandardCharsets.UTF_8)) != null
              ? new String(conn.keyCommands().getDel(key.getBytes(StandardCharsets.UTF_8)))
              : null
  );
  // Better: use StringRedisTemplate.execute with the GETDEL RedisCommand
  ```

  Or use a Lua script (atomic in Redis):
  ```lua
  local val = redis.call('GET', KEYS[1])
  if val then redis.call('DEL', KEYS[1]) end
  return val
  ```

- **Defense-in-depth:** A per-user Redis lock (with short TTL) around the rotate operation would
  also prevent concurrent rotation from the same user.

---

### Finding #4: No Allowlist Validation on EXIF Field Parameter

**Vulnerability:** Insufficient Input Validation — OWASP A03 (Injection — indirect)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/service/SearchService.java`, Lines: 41–52
- Related: `api/src/main/java/org/jphototagger/api/controller/SearchController.java`, Lines: 35–41

**Risk & Exploit Path:**

`GET /search/exif?field=<user_input>&value=<user_input>` accepts arbitrary strings for both `field`
and `value`. The `field` is used as a JSON object key via Jackson's `ObjectNode.put()` and passed
to a parameterized JSONB `@>` containment query. SQL injection is not possible (parameterized).
However:

1. An attacker can probe any EXIF key name across the entire `photo_metadata.exif_data` JSONB
   structure with no restriction. While RLS limits results to the calling user's own data, this is
   more liberal than intended.
2. Extremely long `field` or `value` strings could cause excessive memory allocation during JSON
   construction and query processing. No length validation exists at the controller or service layer.

**Evidence / Trace:**

```java
// SearchController.java:35–41
@GetMapping("/exif")
public ResponseEntity<Page<Photo>> searchByExif(
        @AuthenticationPrincipal UUID userId,
        @RequestParam String field,   // ← any string accepted, no length or content validation
        @RequestParam String value,
        ...) {
    return ResponseEntity.ok(searchService.searchByExif(userId, field, value, page, size));
}

// SearchService.java:41–52
ObjectNode node = objectMapper.createObjectNode();
node.put(field, value);  // ← field becomes a JSON key; no SQL injection but no allowlist
```

**Remediation:**

- **Primary fix:** Add an allowlist of permitted EXIF field names. Known EXIF fields are a finite
  set (make, model, exposure, iso, etc.). Validate `field` against the set before constructing
  the JSON filter:

  ```java
  private static final Set<String> ALLOWED_EXIF_FIELDS = Set.of(
      "Make", "Model", "ExposureTime", "FNumber", "ISOSpeedRatings",
      "DateTimeOriginal", "Flash", "FocalLength", "GPSLatitude", "GPSLongitude"
  );

  if (!ALLOWED_EXIF_FIELDS.contains(field)) {
      throw new IllegalArgumentException("Unknown EXIF field");
  }
  ```

- **Defense-in-depth:** Add `@Size(max = 100)` on `field` and `@Size(max = 500)` on `value`
  request parameters.

---

### Finding #5: Missing HTTP Security Headers in Production Nginx Configuration

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `nginx.conf` (all lines — the file is explicitly marked as dev-only)

**Risk & Exploit Path:**

The deployed `nginx.conf` explicitly states: `# WARNING: NOT FOR PRODUCTION — NO TLS, NO SECURITY
HEADERS`. The file is missing the following production-required headers:

- `Strict-Transport-Security` (HSTS) — without this, browsers can be downgraded from HTTPS to HTTP
  via active MITM, bypassing the `Secure` cookie attribute and exposing JWT/refresh cookies
- `Content-Security-Policy` (CSP) — without this, any stored XSS (if introduced in future phases)
  has unrestricted execution scope
- `X-Content-Type-Options: nosniff` — prevents MIME-type sniffing attacks
- `X-Frame-Options: DENY` — prevents clickjacking
- `Referrer-Policy: strict-origin-when-cross-origin` — limits credential/token leakage via Referer

Note: Spring Security applies `X-Content-Type-Options` and `X-Frame-Options` at the API layer by
default, but the React frontend served by Nginx has no such protection.

This finding is flagged as **Low** (not Medium/High) because the gap is explicitly acknowledged and
documented in the source file, and is a pre-production concern. However, it must be resolved before
any public deployment.

**Remediation:**

- **Primary fix:** Create a production nginx config with TLS termination and the required headers.
  Minimum addition to the existing `server` block:

  ```nginx
  add_header Strict-Transport-Security "max-age=63072000; includeSubDomains; preload" always;
  add_header X-Content-Type-Options "nosniff" always;
  add_header X-Frame-Options "DENY" always;
  add_header Referrer-Policy "strict-origin-when-cross-origin" always;
  add_header Content-Security-Policy "default-src 'self'; img-src 'self' data: blob:; ..." always;
  ```

- **Architectural improvement:** Use `listen 443 ssl; ssl_certificate ...; ssl_certificate_key ...;`
  with the existing Certbot/Let's Encrypt integration already in docker-compose. Redirect port 80
  to 443 with a 301.

---

### Finding #6: Email Enumeration via Registration Timing Difference

**Vulnerability:** User Enumeration — OWASP A07 (Authentication Failures)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: `api/src/main/java/org/jphototagger/api/service/AuthService.java`, Lines: 39–43

**Risk & Exploit Path:**

When registering with an existing email, `AuthService.register()` returns immediately after a
database lookup with no additional work. When registering with a new email, it performs bcrypt
hashing (cost factor 12, typically ~250ms). This timing difference allows an attacker to determine
whether an email address is registered:

- Existing email: response in ~5ms (DB query only)
- New email: response in ~250ms (DB query + bcrypt + DB insert + email send)

The 409 Conflict response body ("Email already registered") also confirms enumeration directly,
though the timing oracle operates even if that message were removed.

**Evidence / Trace:**

```java
// AuthService.java:39–43
var existing = authJdbc.queryForList("SELECT id FROM users WHERE email = ?", email);
if (!existing.isEmpty()) {
    throw new IllegalArgumentException("Email already registered");  // ← immediate return, ~5ms
}
// New user path: bcrypt(12) + 2 DB inserts + email send, ~250ms
String hash = passwordEncoder.encode(password);
```

Note: The login endpoint correctly performs a dummy bcrypt check for unknown emails (Line 83) to
prevent timing-based enumeration. The same mitigation is absent from registration.

**Remediation:**

- **Primary fix:** Add a dummy `passwordEncoder.encode()` call in the "email exists" branch to
  equalize response time:

  ```java
  if (!existing.isEmpty()) {
      passwordEncoder.encode(password);  // timing equalization
      // Return a generic success message regardless
      return null;  // or throw with a generic message
  }
  ```

- **Architectural improvement:** Return the same response (202 Accepted: "If this email is not
  registered, a verification email has been sent") for both the conflict and success cases, per NIST
  SP 800-63B guidance. This prevents enumeration via both timing and response body.

---

### Finding #7: Untyped Request Bodies with Missing Bean Validation

**Vulnerability:** Insufficient Input Validation — OWASP A03
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- `api/.../controller/AlbumController.java`, Lines: 47–58 (`createAlbum`, `updateAlbum`)
- `api/.../controller/KeywordController.java`, Lines: 47–63 (`createKeyword`, `updateKeyword`)
- `api/.../controller/SavedSearchController.java`, Lines: 46–60 (`createSavedSearch`,
  `updateSavedSearch`)

**Risk & Exploit Path:**

Three controllers accept `@RequestBody Map<String, Object>` or `@RequestBody Map<String, String>`
without `@Valid` or any DTO-level validation. This creates several problems:

1. **No length limits:** Album names, keyword names, and saved search `queryJson` strings have no
   maximum length enforced at the application layer (only the DB column type limits apply). An
   attacker can submit multi-megabyte strings, causing excessive memory allocation per request.

2. **ClassCastException on wrong types:** `KeywordController` does `(String) body.get("parentId")`.
   If the client sends `"parentId": 12345` (a JSON number), Jackson maps it as `Integer`, and the
   cast throws `ClassCastException` (uncaught → 500 Internal Server Error).

3. **Wrong HTTP status codes:** `IllegalArgumentException` ("Name is required", "Query JSON is
   required") returns 409 Conflict instead of 400 Bad Request. UUID parse failures return 409
   instead of 422 Unprocessable Entity.

4. **Opaque validation errors:** Without Bean Validation, the client cannot determine which field
   failed or why, degrading the API contract.

**Evidence / Trace:**

```java
// KeywordController.java:51–52
String name = (String) body.get("name");
UUID parentId = body.get("parentId") != null ? UUID.fromString((String) body.get("parentId")) : null;
//                                             ↑ ClassCastException if parentId is not a String
//                                             ↑ IllegalArgumentException if invalid UUID format → 409
```

```java
// SavedSearchController.java:51
savedSearchService.createSavedSearch(userId, body.get("name"), body.get("queryJson"));
// body.get("queryJson") could be a multi-MB string — no length limit
```

**Remediation:**

- **Primary fix:** Replace all `Map<String, ...>` request bodies with typed DTO records annotated
  with `@Valid`:

  ```java
  public record CreateAlbumRequest(
      @NotBlank @Size(max = 255) String name
  ) {}

  public record CreateKeywordRequest(
      @NotBlank @Size(max = 255) String name,
      UUID parentId  // nullable, Jackson handles UUID deserialization type-safely
  ) {}
  ```

  The existing `MethodArgumentNotValidException` handler in `GlobalExceptionHandler` (which already
  returns 400) will then handle validation failures correctly.

---

### Finding #8: Photo Entity Serializes Internal Storage Fields in API Responses

**Vulnerability:** Information Disclosure — OWASP A02 (Cryptographic Failures / Sensitive Data
Exposure)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/entity/Photo.java`, Lines: 38–45
- Related: All endpoints returning `Photo` objects (`PhotoController`, `SearchController`)

**Risk & Exploit Path:**

The `Photo` JPA entity is serialized directly into API responses without a dedicated response DTO.
This exposes internal fields to clients:

- `storageKey` (Line 39): The MinIO object key (e.g., `users/{userId}/photos/{uuid}.jpg`). While
  MinIO is in the `backend` network and not directly internet-accessible in the current deployment,
  exposing the storage key:
  - Reveals the internal object storage naming scheme
  - Would become exploitable if MinIO is ever misconfigured as public or a presigned URL system is
    added in a future phase
- `contentHash` (Line 44): The SHA-256 of the file content. Exposes deduplication fingerprinting
  strategy and enables cross-user photo comparison if a user obtains another user's hash somehow.
- `userId` (Line 23): Exposes the authenticated user's UUID in every photo response. While the user
  already knows their own ID (from the JWT subject), it is redundant in API responses.

**Evidence / Trace:**

```java
// Photo.java:38–44 — these fields have no @JsonIgnore
@Column(name = "storage_key", length = 512)
private String storageKey;         // ← internal MinIO path exposed

@Column(name = "content_hash", length = 64)
private String contentHash;        // ← file fingerprint exposed
```

```java
// PhotoController.java:30–35 — returns Photo entity directly
@GetMapping
public ResponseEntity<Page<Photo>> listPhotos(...) {
    return ResponseEntity.ok(photoService.listPhotos(userId, page, size));  // ← entity → JSON
}
```

**Remediation:**

- **Primary fix:** Introduce a `PhotoResponse` DTO that exposes only client-relevant fields:

  ```java
  public record PhotoResponse(
      UUID id, String filename, String caption, String title, String description,
      Long sizeBytes, Instant takenAt, Instant uploadedAt, String processingStatus
  ) {}
  ```

  Map from `Photo` entity in the service/controller layer. Exclude `storageKey`, `contentHash`,
  and `userId`.

---

### Finding #9: Missing `testcontainers:redis` Dependency in `build.gradle.kts`

**Vulnerability:** Test Coverage Gap (Informational)
**Severity:** Informational
**Confidence:** Confirmed
**Attack Complexity:** N/A

**Location:**
- File: `api/build.gradle.kts`, Lines: 45–51

**Risk & Exploit Path:**

The Phase 2 plan (v4, item CR3-C4) specifies adding `org.testcontainers:redis` to support
`TestRedisConfig` for refresh token and rate limiting integration tests. The dependency is absent
from the implemented `build.gradle.kts`. Tests that depend on a real Redis container (refresh token
rotation, rate limiting, token family replay detection) cannot compile or run without this
dependency, leaving Phase 2's most complex security feature (token family tracking) without
integration test coverage.

**Evidence / Trace:**

```kotlin
// api/build.gradle.kts:45–51
testImplementation("org.testcontainers:postgresql")
testImplementation("org.testcontainers:junit-jupiter")
// ← org.testcontainers:redis is missing
```

**Remediation:**

- Add to `build.gradle.kts`:
  ```kotlin
  testImplementation("org.testcontainers:redis:1.20.6")
  ```
  Or, if using the BOM:
  ```kotlin
  testImplementation("org.testcontainers:redis")
  ```

---

### Finding #10: Refresh Token Family Replay Detection Lost After Full Revocation

**Vulnerability:** Design Gap — Session Management (Informational)
**Severity:** Informational
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `api/src/main/java/org/jphototagger/api/service/RefreshTokenService.java`, Lines: 102–114

**Risk & Exploit Path:**

`revokeAllForUser()` (called on password change) deletes `REFRESH_PREFIX + hash` and
`TOKEN_FAMILY_PREFIX + hash` for all active token hashes. However, the `FAMILY_PREFIX + familyId`
set (the list of all hashes ever issued in a family) is left intact until TTL expiry, while the
`TOKEN_FAMILY_PREFIX` reverse-index entries are deleted.

If an attacker possesses a refresh token that was active at the time of `revokeAllForUser()`:
1. `REFRESH_PREFIX + hash` is deleted → the token is correctly rejected as invalid
2. `TOKEN_FAMILY_PREFIX + hash` is deleted → `detectReplay()` finds **no** family entry → no
   family revocation occurs, and the incident is **not logged** as a replay attack

Consequence: a stolen, pre-revocation refresh token, replayed after a password change, is silently
rejected with a generic 401 rather than triggering the security alert and family revocation path.
Since all tokens were already revoked, there is no practical security impact — but the security
event is missed and not logged.

**Remediation:**

- **Design option:** After `revokeAllForUser()`, retain the `TOKEN_FAMILY_PREFIX + hash` entries
  (but remove `REFRESH_PREFIX + hash`). The `detectReplay()` check will then fire on any subsequent
  replay attempt, triggering the security log event even post-revocation.
- **Effort:** Low — remove the `redis.delete(TOKEN_FAMILY_PREFIX + hash)` line from
  `revokeAllForUser()`. The TTL on `TOKEN_FAMILY_PREFIX` entries (set in `createTokenInFamily`)
  will expire them naturally.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack: Finding #1 + Account Lockout Side-Channel

The missing rate limiting (Finding #1) combined with the per-account lockout mechanism creates a
potential **denial-of-service vector against specific users**: an attacker who knows a target's
email can intentionally trigger 5 failed login attempts (triggering the 15-minute lockout), repeat
every 15 minutes, and effectively lock the target out of their account indefinitely. While the
lockout *is* the designed protection, without IP-rate-limiting there is no mechanism to detect or
block this targeted DoS. The existing timing mitigation (dummy bcrypt for unknown emails) does not
help here because the attacker uses the known email.

**Recommendation:** Distinguish between "lockout triggered by attacker" and "lockout triggered by
legitimate user." Progressive delays (rather than hard lockouts) or lockout notification emails
help mitigate this without enabling a DoS on the account itself.

### RLS Integrity: Belt-and-Suspenders Evaluation

The RLS system was the primary focus of SA-1. The current implementation is sound:
- `RlsContextCleanupFilter` (`HIGHEST_PRECEDENCE`) guarantees ThreadLocal cleanup in `finally`
- `RlsInterceptor.afterCompletion()` provides secondary cleanup
- `set_config('app.current_user_id', ?, true)` is transaction-scoped (reverts on commit/rollback)
- `connection-init-sql` initializes all pool connections with nil UUID
- `assert_user_context()` verifies context immediately after setting it
- `jpt_auth` (BYPASSRLS) role is strictly column-grant-limited per V4 migration

No new RLS integrity concerns were found.

### Defense-in-Depth: If JWT Cookie Is Stolen

The JWT is `httpOnly; Secure; SameSite=Lax`. If stolen (requires server compromise, MITM, or
client-side malware):
- JWT TTL is 15 minutes (short)
- No refresh is possible without the refresh cookie (different `path=/auth` constraint)
- CSRF is required for all state-changing requests (prevents cross-origin abuse)
- Per-user rate limiting would apply to attacker API requests

The defense-in-depth is adequate for a 15-minute JWT TTL.

### Deployment Context Review

| Control | Status | Notes |
|---|---|---|
| API in backend network | Confirmed | Not directly internet-reachable |
| MinIO in backend network | Confirmed | No public exposure |
| Redis password-protected | Confirmed | `requirepass` in command |
| PostgreSQL with FORCE RLS | Confirmed | V2 migration |
| `cap_drop: [ALL]` on containers | Confirmed | docker-compose |
| `no-new-privileges:true` | Confirmed | docker-compose |
| Worker read-only filesystem | Confirmed | `read_only: true` in compose |
| Nginx blocking actuator externally | Confirmed | `location /api/actuator/ { deny all; }` |

---

## 1. Executive Summary

The Phase 2 implementation demonstrates strong foundational security engineering. The Row-Level
Security architecture (dual datasource, aspect-based `set_config`, family-based refresh token replay
detection, bcrypt-12, httpOnly/Secure/SameSite cookies) reflects careful attention to the most
critical multi-tenant isolation and authentication risks. The previous security audit (SA-1) findings
have been correctly remediated: `SET LOCAL` is now replaced with a parameterized `set_config()`,
`RlsAspect` ordering is enforced, Flyway placeholder handling is correct, and column-level grants
are properly scoped.

One **High** severity gap remains: the `RateLimitFilter` explicitly bypasses all unauthenticated
requests, leaving `/auth/login`, `/auth/register`, and `/auth/refresh` with **zero application-level
rate limiting**. The only brute-force protection on login is the per-account lockout (5 attempts,
15 minutes), which is insufficient against credential stuffing across many accounts. This finding is
production-blocking and straightforward to fix (Nginx `limit_req` or an IP-keyed bucket fallback
in `RateLimitFilter`).

One **Medium** severity finding relates to the exception handling pattern: `GlobalExceptionHandler`
passes raw `ex.getMessage()` for `IllegalArgumentException` and `IllegalStateException` to clients.
Currently, all messages are developer-authored and safe, but the pattern creates a security-sensitive
coupling that will become exploitable as the codebase grows. Three controllers (`AlbumController`,
`KeywordController`, `SavedSearchController`) use unvalidated `Map<String, Object>` bodies that
compound this risk by exposing malformed-input messages (e.g., UUID parse errors) verbatim in
responses.

The remaining findings are Low severity or Informational and represent defense-in-depth improvements
(EXIF field allowlist, email enumeration timing, Photo entity response DTO, nginx security headers).
The codebase is not ready for production deployment until Finding #1 is resolved.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|---|---|---|---|---|---|
| 1 | No rate limiting on auth endpoints | A07 | High | Confirmed | 3 endpoints | BLOCK |
| 2 | Raw exception messages in responses | A09 | Medium | Confirmed | 2 exception types | FIX BEFORE DEPLOY |
| 3 | TOCTOU race in token rotation | A08 | Low | High | 1 | FIX |
| 4 | No allowlist on EXIF field param | A03 | Low | Confirmed | 1 | FIX |
| 5 | Missing nginx security headers | A05 | Low | Confirmed | All response paths | FIX BEFORE DEPLOY |
| 6 | Email enumeration via timing | A07 | Low | Medium | 1 endpoint | FIX |
| 7 | Untyped request bodies / no validation | A03 | Low | Confirmed | 3 controllers | FIX |
| 8 | Photo entity leaks internal fields | A02 | Low | Confirmed | 2 endpoints | FIX |
| 9 | Missing testcontainers:redis dep | — | Info | Confirmed | 1 | FIX |
| 10 | Family replay state lost after revocation | — | Info | Confirmed | 1 method | CONSIDER |

---

## 3. Security Quality Score (SQS)

**Deductions:**

| Finding | Severity | Deduction |
|---|---|---|
| #1 — No auth endpoint rate limiting | High | −20 |
| #2 — Raw exception messages | Medium | −8 |
| #3 — TOCTOU token rotation | Low | −2 |
| #4 — No EXIF field allowlist | Low | −2 |
| #5 — Missing nginx security headers | Low | −2 |
| #6 — Email registration timing | Low | −2 |
| #7 — Untyped request bodies | Low | −2 |
| #8 — Photo entity internal fields | Low | −2 |
| #9 — Missing testcontainers:redis | Info | −1 |
| #10 — Family replay state after revoke | Info | −1 |

**Final SQS: 58/100**
**Hard gates triggered:** No (no Critical findings, no hardcoded secrets in source code)
**Posture: Unacceptable — block deployment**

The score is below 70 primarily due to the High finding (#1) and the cluster of Low findings.
Resolving Finding #1 alone raises the score to 78 (Acceptable). Addressing all findings brings the
score to 100.

---

## 4. Positive Security Observations

1. **Excellent RLS implementation:** The dual-datasource architecture (`jpt_app` for regular
   operations, `jpt_auth` with BYPASSRLS for auth), combined with `RlsAspect` ordering, atomic
   `set_config()`, transaction scoping, and `assert_user_context()` verification is a textbook-correct
   multi-tenant isolation pattern. The nil UUID constraint on users further prevents any accidental
   data leakage from the fallback `connection-init-sql` default.

2. **Refresh token security is sophisticated:** Family-based replay detection with O(1) reverse
   index lookup, token rotation on every use, SHA-256 hashing in Redis, and `revokeAllForUser()` on
   password change represents significantly better-than-baseline session security.

3. **Timing side-channel mitigation on login is correct:** The dummy `passwordEncoder.matches()`
   call for unknown emails, combined with always performing the bcrypt check before evaluating lockout
   status, correctly prevents both user-enumeration and lockout timing leaks on the login endpoint.

4. **Cryptographic choices are sound:** BCrypt cost factor 12, HMAC-SHA256 with startup validation
   (rejects short secrets and dev defaults in production), 256-bit refresh tokens, and SHA-256 for
   all token storage are all appropriate for 2026 threat models.

5. **Container security posture is strong:** `cap_drop: [ALL]`, `no-new-privileges: true`,
   `read_only: true` (worker), backend network isolation, and principle of least privilege in both
   PostgreSQL roles and MinIO service accounts reflect sound operational security. The Nginx actuator
   block prevents Spring Boot management endpoints from being internet-reachable.

---

## 5. Prioritized Remediation Roadmap

**1. Finding #1 — No Rate Limiting on Auth Endpoints**
- *Why prioritized:* Only High severity finding; enables credential stuffing, registration spam, and
  targeted account DoS. Unacceptable for any public deployment.
- *Effort:* Quick Win (Nginx `limit_req` configuration or a 20-line addition to `RateLimitFilter`)
- *Owner:* Backend / DevOps

**2. Finding #5 — Missing Nginx Security Headers**
- *Why prioritized:* No TLS, no HSTS, no CSP — the application cannot be safely deployed over the
  internet without these. The `Secure` cookie attribute is ineffective without TLS.
- *Effort:* Quick Win (Nginx config addition + Certbot SSL activation already in docker-compose)
- *Owner:* DevOps

**3. Finding #2 — Raw Exception Messages in API Responses**
- *Why prioritized:* Medium severity with a bad-practice pattern that will worsen over time. The
  fix requires changing `GlobalExceptionHandler` and introducing typed DTOs for three controllers.
- *Effort:* Moderate (DTO creation for Album/Keyword/SavedSearch, exception handler cleanup)
- *Owner:* Backend

**4. Finding #7 — Untyped Request Bodies / Missing Bean Validation**
- *Why prioritized:* Directly coupled to Finding #2; ClassCastException risk creates hidden 500
  error paths; no length limits enables resource exhaustion per request.
- *Effort:* Moderate (new DTOs, `@Valid` annotations, controller updates)
- *Owner:* Backend

**5. Finding #8 — Photo Entity Leaks Internal Storage Fields**
- *Why prioritized:* `storageKey` exposure will become a real security risk if MinIO access control
  is relaxed in a future phase (presigned URLs, public CDN). Address before Phase 3 adds upload.
- *Effort:* Moderate (introduce `PhotoResponse` DTO, update `PhotoController` and `SearchController`)
- *Owner:* Backend
