# Security Audit Report — Phase 4: Post-Remediation Review (Audit #7)

**Audited document:** `docs/plans/2026-02-25-saas-conversion-phase-4.md` (v8.0)
**Audit date:** 2026-03-12
**Audit number:** SA4-7
**Scope:** Full implementation of Phase 4 — backend additions, React frontend, API client, authentication flow, metadata display, upload, infrastructure, and nginx production config. Focus on current working tree state including all uncommitted changes.
**Methodology:** Three-pass white-box analysis (Reconnaissance → Systematic Hunting → Compositional Analysis)

---

## Audit-5 & Audit-6 Remediation Verification

### Audit-6 Findings

| Audit-6 # | Title | Status | Evidence |
|---|---|---|---|
| 1 | Upload rate limit regex mismatch | **FIXED** | `RateLimitFilter.java:144` — regex now `.*/photos/upload/?$` matches actual endpoint |
| 2 | GPS data leakage via IPTC/XMP | **PARTIALLY FIXED** | `PhotoMetadataResponse.java:46-50` — EXIF GPS keys and IPTC location keys now filtered; however XMP-specific location keys without "gps" in the name still pass through (see Finding #2) |
| 3 | CSP `style-src 'unsafe-inline'` | **FIXED** | `nginx.prod.conf:76` — now uses `style-src 'self'` without `'unsafe-inline'` |

### Audit-5 Findings

| Audit-5 # | Title | Status | Evidence |
|---|---|---|---|
| 1 | `Permissions-Policy` header lost in migration | **FIXED** | `nginx.prod.conf:78` — `Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()"` present |
| 2 | Missing `client_max_body_size` in production nginx | **FIXED** | `nginx.prod.conf:105` — `client_max_body_size 200M` in `/api/` location block |
| 3 | CSRF-exempt `/auth/logout` enables forced logout | **FIXED** | `SecurityConfig.java:63-64` — CSRF ignore list is now only `"/auth/refresh", "/login/oauth2/code/*"` |
| 4 | `@Async` without bounded thread pool | **FIXED** | `JptSaasApplication.java` — `TaskExecutor` bean with core=2, max=5, queue=50, `CallerRunsPolicy` |
| 5 | OAuth2 redirect URL string concatenation | **FIXED** | `OAuth2SuccessHandler.java:53-57` — `@PostConstruct validateRedirectUri()` enforces trailing slash |

**Summary: 7 of 8 findings across audits 5 and 6 are fully remediated. One (audit-6 #2) is partially fixed with residual leakage documented in Finding #2 below.**

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points

| Endpoint | Method | Auth | CSRF | Rate Limit | Controller Prefix |
|----------|--------|------|------|------------|-------------------|
| `/auth/register` | POST | Public | Yes | Auth (20/hr/IP) | `/auth` |
| `/auth/login` | POST | Public | Yes | Auth (20/hr/IP) | `/auth` |
| `/auth/refresh` | POST | Public | **Exempt** | Auth (20/hr/IP) | `/auth` |
| `/auth/logout` | POST | Authenticated | Yes | General (1000/hr) | `/auth` |
| `/api/users/me` | GET | Authenticated | N/A | General | **`/api/users`** |
| `/api/users/me` | PATCH | Authenticated | Yes | General | **`/api/users`** |
| `/photos/upload` | POST | Authenticated | Yes | Upload (100/hr) | `/photos` |
| `/photos` | GET | Authenticated | N/A | General | `/photos` |
| `/photos/{id}` | GET/DELETE | Authenticated | Yes (mutating) | General | `/photos` |
| `/photos/{id}/metadata` | GET | Authenticated | N/A | General | `/photos` |
| `/photos/{id}/keywords/*` | GET/POST/DELETE | Authenticated | Yes (mutating) | General | `/photos` |
| `/albums/*` | CRUD | Authenticated | Yes (mutating) | General | `/albums` |
| `/keywords/*` | CRUD | Authenticated | Yes (mutating) | General | `/keywords` |
| `/search/*` | GET | Authenticated | N/A | General | `/search` |
| `/saved-searches/*` | CRUD | Authenticated | Yes (mutating) | General | `/saved-searches` |
| OAuth2 (`/login/oauth2/code/*`) | GET | Public | **Exempt** | N/A | — |

### Trust Boundaries

```
User → nginx (TLS termination, /api/ prefix stripping, security headers, burst limiting)
     → Spring Boot API (JWT auth, CSRF, RLS, application-layer rate limiting)
       → PostgreSQL (RLS policies, parameterized queries)
       → Redis (refresh tokens, rate limit buckets, job queue)
       → MinIO (pre-signed URLs, no direct user access)

Frontend (React SPA) → apiFetch (CSRF header, key transforms, 401 handler)
                      → Vite proxy (dev) / nginx reverse proxy (prod)
```

### Critical Architecture Detail — nginx Path Stripping

nginx production config (`nginx.prod.conf`) uses trailing-slash `proxy_pass` which strips the location prefix:

```nginx
location /api/auth/ { proxy_pass http://api:8080/auth/; }     # /api/auth/login → /auth/login
location /api/      { proxy_pass http://api:8080/; }           # /api/photos     → /photos
```

All controllers MUST use root-relative `@RequestMapping` paths (e.g., `/photos`, `/auth`, `/users`) for nginx path stripping to work. Any controller using a `/api/` prefix in its mapping will be unreachable in production.

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: UserController Route Prefix Mismatch — Endpoints Unreachable in Production

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** N/A (functional breakage)

**Location:**
- File: `api/src/main/java/org/jphototagger/api/controller/UserController.java`, Line 20
- Related: `nginx.prod.conf`, Lines 104–111 (path stripping)
- Related: `frontend/src/api/client.ts`, Line 83

**Risk & Exploit Path:**
`UserController` uses `@RequestMapping("/api/users")` while **every other controller** uses root-relative paths (`/auth`, `/photos`, `/albums`, `/keywords`, `/search`, `/saved-searches`). In production, nginx strips the `/api/` prefix before forwarding to Spring Boot:

1. Frontend calls `GET /api/users/me` (via `fetchCurrentUser()` in `client.ts:83`)
2. nginx matches `location /api/` and strips prefix: `proxy_pass http://api:8080/` → request becomes `GET /users/me`
3. Spring Boot receives `GET /users/me` but `UserController` is mapped to `/api/users/me` → **404 Not Found**

This breaks two critical functions:
1. **Session hydration fails:** `hydrateSession()` cannot fetch the current user. The catch-all in `client.ts:94` silently swallows the error, leaving `isAuthenticated = false`. Users appear logged out despite having valid JWT cookies.
2. **GPS preference toggle unreachable:** `PATCH /api/users/me` is also broken, so users cannot change their `showGps` setting. The default is `false` (privacy-preserving), so the failure mode is safe from a data-protection perspective.

In development mode (Vite proxy), the prefix is NOT stripped — Vite proxies `/api/*` directly to `http://localhost:8080/api/*` — so this bug only manifests in production behind nginx.

**Evidence / Trace:**

```java
// UserController.java:20 — INCONSISTENT PREFIX
@RequestMapping("/api/users")           // ← VULNERABLE: uses /api/ prefix
public class UserController { ... }

// All other controllers use root-relative paths:
@RequestMapping("/photos")              // PhotoController.java:30
@RequestMapping("/auth")                // AuthController.java:28
@RequestMapping("/albums")              // AlbumController.java:23
@RequestMapping("/keywords")            // KeywordController.java:24
@RequestMapping("/search")              // SearchController.java:19
@RequestMapping("/saved-searches")      // SavedSearchController.java:23
```

```nginx
# nginx.prod.conf:104-106 — strips /api/ prefix
location /api/ {
    proxy_pass http://api:8080/;       # /api/users/me → /users/me (no match)
}
```

```typescript
// client.ts:83 — frontend expects /api/users/me to work
return await apiFetch<User>('/api/users/me');
```

**Remediation:**
- **Primary fix:** Change `UserController` to use `@RequestMapping("/users")` to match the convention of all other controllers:
  ```java
  @RequestMapping("/users")  // Consistent with other controllers
  public class UserController { ... }
  ```
- **Verification:** After fix, confirm `GET /api/users/me` → nginx strips to `/users/me` → Spring matches `/users/me` → 200.

---

### Finding #2: Residual XMP Location Data Leakage Through Non-GPS-Named Keys

**Vulnerability:** Sensitive Data Exposure — OWASP A02
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java`, Lines 53–61

**Risk & Exploit Path:**
Audit-6 Finding #2 identified that IPTC and XMP metadata were passed through unfiltered when `showGps=false`. The remediation added `filterGpsKeys()` (for EXIF and XMP, matching keys containing "gps") and `filterLocationKeys()` (for IPTC, matching specific location field names). However, XMP metadata can contain location information in keys that do NOT contain the substring "gps":

- `photoshop:City` — city name from Adobe Photoshop metadata
- `photoshop:State` — state/province
- `photoshop:Country` — country name
- `Iptc4xmpCore:Location` — IPTC-in-XMP location field
- `xmp:Location` — generic location

These keys pass through `filterGpsKeys()` undetected because they don't contain "gps".

A user who has opted out of GPS display may still have their photo's city, state, and country visible through the XMP metadata section. The severity is lower than the original finding because these fields contain coarse location data (city-level), not precise GPS coordinates.

**Evidence / Trace:**

```java
// PhotoMetadataResponse.java:53-61 — filterGpsKeys only matches "gps" substring
private static Map<String, Object> filterGpsKeys(Map<String, Object> data) {
    if (data == null) return null;
    return data.entrySet().stream()
            .filter(e -> {
                String lower = e.getKey().toLowerCase();
                return !lower.contains("gps") && !lower.startsWith("gps:");  // ← misses photoshop:City etc.
            })
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
}
// filterLocationKeys is only applied to iptcData, NOT xmpData
// PhotoMetadataResponse.java:49
Map<String, Object> filteredXmp = filterGpsKeys(xmpData);    // ← only GPS filter, no location filter
```

**Remediation:**
- **Primary fix:** Apply location key filtering to XMP data as well, and extend the filter to cover XMP-specific location keys:
  ```java
  private static final Set<String> XMP_LOCATION_KEYS = Set.of(
          "photoshop:city", "photoshop:state", "photoshop:country",
          "iptc4xmpcore:location", "xmp:location"
  );

  public PhotoMetadataResponse withoutGps() {
      Map<String, Object> filteredExif = filterGpsKeys(exifData);
      Map<String, Object> filteredIptc = filterLocationKeys(iptcData, IPTC_LOCATION_KEYS);
      Map<String, Object> filteredXmp = filterGpsAndLocationKeys(xmpData);
      return new PhotoMetadataResponse(photoId, null, null, filteredExif, filteredIptc, filteredXmp, extractedAt);
  }

  private static Map<String, Object> filterGpsAndLocationKeys(Map<String, Object> data) {
      if (data == null) return null;
      return data.entrySet().stream()
              .filter(e -> {
                  String lower = e.getKey().toLowerCase();
                  return !lower.contains("gps") && !XMP_LOCATION_KEYS.contains(lower);
              })
              .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }
  ```
- **Test:** Add a test with XMP data containing `photoshop:City` and verify it's stripped when `showGps=false`.

**References:**
- SA4-F1 (plan requirement): "GPS data must be filtered server-side"
- CWE-200: Exposure of Sensitive Information to an Unauthorized Actor

---

### Finding #3: Email Verification Endpoint Missing — Password Users Cannot Upload

**Vulnerability:** Missing Functionality / Security Gate Bypass Prevention — OWASP A07
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** N/A (functional gap)

**Location:**
- File: `api/src/main/java/org/jphototagger/api/service/AuthService.java`, Lines 60–71 (token creation)
- File: `api/src/main/java/org/jphototagger/api/controller/AuthController.java` (no verify endpoint)

**Risk & Exploit Path:**
The registration flow creates an email verification token, stores its SHA-256 hash in the `email_tokens` table, and sends the plain token to the user's email via `sendVerificationEmailAsync()`. However, **no controller endpoint exists to consume the verification token** and flip `email_verified` to `true`.

This means:
1. Password-registered users can never verify their email
2. The email verification gate in `PhotoService.insertPhotoWithQuotaCheck()` (line 200) permanently blocks uploads for password users
3. OAuth2 users are unaffected — `OAuth2SuccessHandler.java:86` sets `email_verified=true` directly

This is not a vulnerability (the failure mode is overly restrictive, not permissive), but it makes the entire password-based registration flow non-functional for the core feature (photo upload).

**Evidence / Trace:**

```java
// AuthService.java:60-71 — creates token but no endpoint consumes it
authJdbc.update(
        "INSERT INTO email_tokens (id, user_id, token_hash, purpose, expires_at, created_at) " +
                "VALUES (?, ?, ?, 'verify', NOW() + INTERVAL '24 hours', NOW())",
        UUID.randomUUID(), userId, tokenHash);
sendVerificationEmailAsync(email, plainToken);
```

```java
// AuthController.java — only register, login, refresh, logout. No verify endpoint.
@PostMapping("/register")  // Creates token
@PostMapping("/login")     // Doesn't check email_verified
@PostMapping("/refresh")   // Token rotation
@PostMapping("/logout")    // Revokes tokens
// Missing: @GetMapping("/verify") or @PostMapping("/verify")
```

```java
// PhotoService.java:200 — blocks unverified users permanently
if (!user.isEmailVerified()) {
    throw new EmailVerificationRequiredException("Email verification required before uploading");
}
```

**Remediation:**
- **Primary fix:** Implement `POST /auth/verify` endpoint that accepts `{ "token": "..." }`, validates the token hash against `email_tokens`, sets `email_verified = true` on the user, and deletes the consumed token.
- **Security constraints for the verify endpoint:**
  - Rate limit with the auth tier (already applies to `/auth/**`)
  - Use constant-time comparison for token hash lookup
  - Delete all verification tokens for the user after successful verification
  - Return a generic response regardless of token validity (prevent token enumeration)

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack Analysis

**Finding #1 + Frontend auth → Complete authentication breakage in production:**
The UserController route mismatch (Finding #1) doesn't just break the user preferences endpoint — it breaks session hydration entirely. The `fetchCurrentUser()` → `hydrateSession()` chain silently fails, leaving all users appearing as unauthenticated. Combined with the JWT cookie still being sent on subsequent requests, this creates a confusing state where the API returns authenticated responses but the frontend treats the user as logged out.

No other finding chains produce elevated severity.

### Implicit Trust Assumptions

1. **All controllers use root-relative `@RequestMapping` paths** — Violated by UserController (Finding #1). This assumption is implicit in the nginx configuration and undocumented.

2. **nginx always fronts the API in production** — Security headers (HSTS, CSP, X-Frame-Options, Permissions-Policy) are nginx-only. Spring Security HSTS is explicitly disabled. If the API is accessed directly (misconfigured Docker network), these headers are absent. **Mitigation:** Docker Compose backend network is `internal: true`. **Risk: Low.**

3. **SMTP service is available for email delivery** — `@Async` email sending with `CallerRunsPolicy` means SMTP failures now block the calling thread (the request handler thread) rather than silently creating unbounded threads. This is correct behavior but means registration latency spikes if SMTP is down. **Risk: Low (functional, not security).**

### Defense-in-Depth Assessment

**Strong areas:**
1. **Authentication:** JWT validation + CSRF (BREACH-protected via XOR handler) + RLS + rate limiting (nginx burst + Bucket4j) — four independent layers
2. **GPS filtering:** Server-side stripping (primary, with partial XMP gap) + frontend DOM suppression (secondary) + ESLint enforcement (tertiary)
3. **XSS prevention:** React text nodes (primary) + no `dangerouslySetInnerHTML` usage confirmed + Jsoup sanitization at ingestion + CSP without `unsafe-inline`
4. **Upload safety:** Tika MIME detection + Jsoup filename sanitization + content hash dedup + quota enforcement with pessimistic locking
5. **Token security:** Refresh token family rotation with atomic GETDEL + replay detection with family revocation + SHA-256 hashed storage

**Remaining gaps:**
- XMP location metadata leakage through non-GPS-named keys (Finding #2)
- Email verification flow incomplete for password users (Finding #3)

### Deployment Context

- **BLOCKER:** Finding #1 breaks the entire authenticated user experience in production. User profile, GPS preference, and session hydration all fail behind nginx.
- Docker Compose network isolation is properly configured (`backend` network is `internal: true`)
- Container security hardening is excellent: `cap_drop: ALL`, `no-new-privileges`, `read_only` on worker, memory limits on all containers
- TLS configuration is modern: TLS 1.2/1.3 only, strong cipher suite, OCSP stapling, session tickets disabled

---

## 1. Executive Summary

This audit verifies remediation of findings from audits 5 and 6, and identifies new issues in the current working tree. **Seven of eight prior findings are fully remediated.** Audit-6 Finding #2 (GPS data leakage via IPTC/XMP) is partially fixed — the primary EXIF and IPTC vectors are closed, but XMP-specific location keys without "gps" in their names still leak through.

One new medium-severity issue was identified: **`UserController` uses an inconsistent `/api/users` route prefix** (Finding #1), making both user endpoints (`GET /api/users/me` and `PATCH /api/users/me`) unreachable in production behind nginx. All other controllers correctly use root-relative paths. Because `fetchCurrentUser()` depends on this endpoint, session hydration fails silently, causing the frontend to treat all authenticated users as logged out. This is a deployment-blocking issue requiring a one-line fix.

A missing email verification endpoint (Finding #3) means password-registered users can never verify their email and therefore can never upload photos. This doesn't create a vulnerability (the failure is overly restrictive) but renders the password registration flow functionally incomplete.

The overall security architecture remains strong. The authentication system, RLS implementation, CSRF protection, rate limiting, and container hardening are all well-engineered. The CSP has been tightened by removing `'unsafe-inline'` from `style-src`. No critical or high-severity vulnerabilities were found.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | UserController `/api/users` route prefix mismatch | A05 | Medium | Confirmed | 1 | BLOCK (deploy) |
| 2 | Residual XMP location leakage through non-GPS-named keys | A02 | Low | Medium | 1 | FIX |
| 3 | Email verification endpoint missing | A07 | Low | Confirmed | 1 | FIX |

---

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|---|---|---|
| Critical | 0 | 0 |
| High | 0 | 0 |
| Medium | 1 | −8 |
| Low | 2 | −4 |

**Final SQS:** **88**/100
**Hard gates triggered:** No
**Posture:** **Strong** — deploy with standard monitoring after fixing Finding #1 (one-line change). Findings #2 and #3 can be addressed in the next sprint.

---

## 4. Positive Security Observations

1. **Comprehensive remediation of prior audit findings.** All 8 findings from audits 5 and 6 were addressed (7 fully, 1 partially). The nginx config now includes `Permissions-Policy`, `client_max_body_size`, and tightened CSP. The CSRF ignore list was narrowed. The async thread pool is bounded. The OAuth2 redirect URI is validated at startup.

2. **CSP tightened from `'unsafe-inline'` to `'self'` for `style-src`.** This removes CSS injection as an XSS exfiltration vector. Tailwind CSS and shadcn/ui generate class-based styles in external stylesheets, so `'self'` suffices. This is a significant security improvement over the previous configuration.

3. **Rate limiting architecture is now fully functional.** The upload regex fix means all three rate limit tiers (auth/upload/general) are operating correctly. The test suite (`RateLimitFilterTest.java`) validates all three buckets with realistic scenarios including the upload path match.

4. **Container security hardening is exemplary.** Every service drops all capabilities (`cap_drop: ALL`), enables `no-new-privileges`, has memory limits, and the worker runs with a read-only root filesystem. The internal `backend` network prevents direct internet access to PostgreSQL, Redis, and MinIO.

5. **Refresh token rotation with atomic replay detection remains best-in-class.** The Lua GETDEL script prevents TOCTOU races, family-based revocation ensures stolen tokens invalidate the entire chain, and reverse indexes provide O(1) replay detection. Security events are properly logged.

---

## 5. Prioritized Remediation Roadmap

### 1. Finding #1 — UserController route prefix mismatch
- **Why prioritized:** Completely breaks user profile and session hydration in production. All authenticated users appear logged out in the frontend. Deployment blocker.
- **Effort:** Quick Win — change `@RequestMapping("/api/users")` to `@RequestMapping("/users")`.
- **Owner:** Backend

### 2. Finding #2 — Residual XMP location leakage
- **Why prioritized:** Extends a partially-fixed privacy requirement (SA4-F1). Low severity because it leaks coarse location (city-level) not precise GPS coordinates.
- **Effort:** Quick Win — add XMP location key set, apply to `filterGpsAndLocationKeys()` for XMP data.
- **Owner:** Backend

### 3. Finding #3 — Email verification endpoint missing
- **Why prioritized:** Password registration flow is non-functional for the core feature (upload). OAuth2 users are unaffected.
- **Effort:** Moderate — implement verify endpoint with proper rate limiting, constant-time comparison, and token cleanup. May be deferred if password registration is not yet a launch requirement.
- **Owner:** Backend
