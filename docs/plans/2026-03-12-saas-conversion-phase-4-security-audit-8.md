# Security Audit Report — Phase 4: React Frontend & Backend Prerequisites

> **Audit #:** SA4-8
> **Date:** 2026-03-12
> **Audited Plan:** `docs/plans/2026-02-25-saas-conversion-phase-4.md`
> **Scope:** All implemented code for Phase 4 — backend controllers, services, security filters, DTOs, frontend API client, auth flow, route protection, metadata display, upload flow, nginx config, Docker Compose, and Dockerfile.
> **Methodology:** Three-pass manual white-box review (reconnaissance → systematic hunting → compositional analysis)

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Entry Points:**
- `POST /auth/register`, `POST /auth/verify`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`
- `GET /users/me`, `PATCH /users/me`
- `POST /photos/upload`, `GET /photos`, `GET /photos/{id}`, `GET /photos/{id}/status`, `DELETE /photos/{id}`
- `GET /photos/trash`, `POST /photos/{id}/restore`
- `GET /photos/{id}/keywords`, `POST /photos/{id}/keywords/{keywordId}`, `DELETE /photos/{id}/keywords/{keywordId}`
- `GET /photos/{id}/metadata`
- OAuth2 callback: `/login/oauth2/code/*`
- Frontend SPA: all client-side routes

**Trust Boundaries:**
1. Internet → nginx (TLS termination, rate limiting, header injection)
2. nginx → Spring Boot API (X-Forwarded-For/Proto trusted)
3. API → PostgreSQL (RLS via `app.current_user_id` session variable)
4. API → MinIO (pre-signed URLs for object access)
5. API → Redis (rate limiting, job queues)
6. Browser → SPA (CSRF cookie → X-XSRF-TOKEN header)

**Authentication Architecture:**
- JWT in httpOnly cookie (15-min expiry) + refresh token rotation (30-day)
- OAuth2/OIDC (Google) with account takeover prevention
- Spring Security filter chain: JwtAuthenticationFilter → RateLimitFilter → CsrfCookieFilter
- RLS enforced via AOP aspect on `@Transactional` methods

**Sensitive Data Flows:**
- Credentials (email/password) → AuthService → bcrypt → PostgreSQL
- GPS coordinates → PhotoMetadataService → filtered by user preference → response
- Photo files → Tika MIME detection → MinIO storage → pre-signed URLs
- CSRF token → cookie → JavaScript read → X-XSRF-TOKEN header

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: Unverified Email Login Allowed

**Vulnerability:** Missing Email Verification Check on Login — Business Logic Flaw (A07)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/service/AuthService.java`, Lines 85–138
- Related: `api/src/main/java/org/jphototagger/api/service/PhotoService.java:200` (upload gate exists)

**Risk & Exploit Path:**
The `authenticate()` method in `AuthService` does not check `email_verified` before issuing a successful login. A user who registers but never verifies their email can still log in, access the application, and make API calls. While `PhotoService.uploadPhoto()` correctly gates uploads on `email_verified`, all other features (viewing photos, modifying settings, creating keywords/albums) are accessible. This creates an inconsistent security boundary — unverified accounts can interact with the system.

An attacker could register with a throwaway/non-existent email, skip verification, and still access the authenticated API surface. This weakens the email verification requirement and allows resource consumption (e.g., keyword/album creation, API rate limit slots).

**Evidence / Trace:**
```java
// AuthService.java:85-138 — authenticate() never checks email_verified
public Map<String, Object> authenticate(String email, String password) {
    var rows = authJdbc.queryForList(
            "SELECT id, email, password_hash, failed_login_attempts, locked_until FROM users WHERE email = ?",
            email);  // ← VULNERABLE: does not select or check email_verified
    // ... proceeds to validate password and return success
}
```

Contrast with the upload gate:
```java
// PhotoService.java:200 — correctly enforces verification
if (!user.isEmailVerified()) {
    throw new EmailVerificationRequiredException("Email verification required before uploading");
}
```

**Remediation:**
- **Primary fix:** Add `email_verified` to the SELECT in `authenticate()` and reject login if `email_verified = false`:
  ```java
  Boolean verified = (Boolean) user.get("email_verified");
  if (!verified) {
      throw new BadCredentialsException("Invalid credentials");
  }
  ```
- **Defense-in-depth:** Return a distinct (but non-enumerable) response to guide users to check their email, or add a global filter/interceptor that rejects all API requests from unverified users.

---

### Finding #2: X-Forwarded-For IP Spoofing in Rate Limiter

**Vulnerability:** IP Spoofing via X-Forwarded-For Last-Entry Trust — Rate Limit Bypass (A07/A05)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/security/RateLimitFilter.java`, Lines 123–130

**Risk & Exploit Path:**
The `getClientIp()` method extracts the **last** entry from the `X-Forwarded-For` header. In a standard single-proxy architecture (client → nginx → app), the last entry is the proxy's own IP, not the client IP. The client IP is typically the **first** entry. This means:

1. All unauthenticated auth requests are rate-limited under the **nginx proxy IP** rather than the actual client IP. This creates a shared rate limit bucket — one attacker hitting 20 requests exhausts the bucket for ALL clients behind that proxy.
2. Alternatively, if nginx adds the client IP as the last entry (via `$proxy_add_x_forwarded_for`), an attacker can prepend spoofed IPs to the X-Forwarded-For header before nginx appends the real one. However, in this architecture nginx is the only proxy, so `$proxy_add_x_forwarded_for` produces `<client-supplied-value>, <real-client-ip>` — meaning the last entry IS the real client IP. This is actually correct for a single-proxy setup, but fragile.

The real risk is that if any additional proxy/CDN/load balancer is added in front of nginx, this logic breaks silently — rate limiting either collapses to a single bucket or becomes spoofable.

**Evidence / Trace:**
```java
// RateLimitFilter.java:123-130
private String getClientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
        String[] parts = forwarded.split(",");
        return parts[parts.length - 1].trim();  // ← last entry = proxy-appended IP
    }
    return request.getRemoteAddr();
}
```

nginx config:
```nginx
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

**Remediation:**
- **Primary fix:** Since this is a single-proxy architecture, extracting the last entry is technically correct but non-obvious. Add a comment documenting the assumption. Better: use `request.getRemoteAddr()` as the authoritative source when behind a single known proxy (Spring's `ForwardedHeaderFilter` already resolves this, so `getRemoteAddr()` returns the client IP when `server.forward-headers-strategy: framework` is enabled).
- **Architectural improvement:** Use Spring's `ForwardedHeaderFilter` resolution (already enabled) and rely on `request.getRemoteAddr()` exclusively, removing X-Forwarded-For parsing.

---

### Finding #3: CSRF Token Overridable by Caller Headers

**Vulnerability:** CSRF Token Header Ordering Allows Override — CSRF Weakness (A01)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: `frontend/src/api/client.ts`, Lines 59–68

**Risk & Exploit Path:**
The `apiFetch` function spreads caller-provided headers **before** the `X-XSRF-TOKEN` header. Due to JavaScript object spread semantics, the last key wins — so the `X-XSRF-TOKEN` from the cookie always overrides any caller-supplied value. This is actually the **correct** behavior and matches the SA4 requirement ("CSRF header non-overridable").

However, the implementation relies on JavaScript object spread ordering, which while reliable in practice (per ES2015+ spec), is subtle. The plan's commit `241534503` ("fix: make CSRF header non-overridable in apiFetch") suggests this was previously a bug that was fixed.

**Current state:** Secure. The CSRF token is placed last in the spread and cannot be overridden. No action required.

**Note:** Downgraded to informational — no vulnerability exists. Retained for audit trail.

---

### Finding #4: SameSite=Lax on JWT/Refresh Cookies (Not Strict)

**Vulnerability:** Cookie SameSite Policy Inconsistency — Session Security (A07)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `api/src/main/java/org/jphototagger/api/controller/AuthController.java`, Lines 143–152
- File: `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java`, Lines 119–125
- Related: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`, Line 60

**Risk & Exploit Path:**
The CSRF cookie correctly uses `SameSite=Strict` (SecurityConfig line 60), but the JWT and refresh token cookies use `SameSite=Lax`. While `Lax` is acceptable for most scenarios (it prevents cross-site POST), it does allow cookies to be sent on top-level navigations from external sites (e.g., a link from an attacker site to a GET endpoint).

This is mitigated by the fact that:
1. All state-changing operations require POST/PATCH/DELETE (not sent with Lax on cross-site)
2. CSRF protection is enforced on all state-changing endpoints
3. GET endpoints only return data for the authenticated user (RLS enforced)

The risk is minimal — an attacker linking to `https://yourdomain.com/api/photos` from a malicious page would trigger a top-level navigation that sends the JWT cookie, but the response would only be visible in the user's browser (not to the attacker), and the browser's same-origin policy prevents reading the response.

However, `SameSite=Strict` would provide defense-in-depth and is required by SA4-F4 ("session cookie must carry SameSite=Strict"). The JWT cookie effectively serves as the session cookie in this architecture.

**Evidence / Trace:**
```java
// AuthController.java:143-146 — JWT cookie uses Lax
private ResponseCookie buildJwtCookie(String token) {
    return ResponseCookie.from("jwt", token)
            .httpOnly(true).secure(cookieSecure).sameSite("Lax")  // ← SA4-F4 requires Strict
            .path("/").maxAge(Duration.ofMinutes(jwtExpiryMinutes)).build();
}
```

**Remediation:**
- **Primary fix:** Change `.sameSite("Lax")` to `.sameSite("Strict")` on both the JWT and refresh cookies in `AuthController.java` and `OAuth2SuccessHandler.java`.
- **Caveat:** `SameSite=Strict` may break the OAuth2 redirect flow since the callback arrives from Google's domain. The refresh cookie is scoped to `/auth` path so `Lax` may be necessary for `/auth/refresh`. Consider using `Strict` for JWT and `Lax` for refresh only.

---

### Finding #5: Missing Password Maximum Length Validation

**Vulnerability:** Unbounded Password Length — Denial of Service (A07)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/dto/RegisterRequest.java`, Lines 7–10
- File: `api/src/main/java/org/jphototagger/api/service/AuthService.java`, Line 51

**Risk & Exploit Path:**
`RegisterRequest` enforces `@Size(min = 12)` but has no maximum length constraint. `LoginRequest` has no size constraint on the password field at all. bcrypt has a built-in 72-byte limit (it silently truncates), but Spring's `BCryptPasswordEncoder.encode()` still processes the full input string before truncation. An attacker can submit a multi-megabyte password string, forcing the server to:
1. Deserialize the large JSON body
2. Pass it to bcrypt (which internally processes the string)

The 200MB `client_max_body_size` in nginx allows very large request bodies. While bcrypt's CPU cost dominates, the deserialization and memory allocation of a huge string is an amplification vector.

**Evidence / Trace:**
```java
// RegisterRequest.java — no @Size(max = ...)
public record RegisterRequest(
        @Email @NotBlank String email,
        @Size(min = 12) @NotBlank String password  // ← no maximum
) {}

// LoginRequest.java — no size constraint at all
public record LoginRequest(
        @Email @NotBlank String email,
        @NotBlank String password  // ← no constraints
) {}
```

**Remediation:**
- **Primary fix:** Add `@Size(max = 128)` (or similar reasonable limit) to both `RegisterRequest.password` and `LoginRequest.password`. Since bcrypt truncates at 72 bytes, anything beyond ~100 characters is wasted anyway.

---

### Finding #6: Pagination Size Not Validated on Input

**Vulnerability:** Unbounded Page Size Parameter — Resource Consumption (A04)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/controller/PhotoController.java`, Lines 56–60

**Risk & Exploit Path:**
The `listPhotos` endpoint accepts a `size` parameter with default 50 but no input validation. The `PhotoService.listPhotos()` clamps it to `Math.min(size, 100)` at the service layer, which is correct. However, a negative `size` value would pass through to `PageRequest.of(page, size)` which throws `IllegalArgumentException`, resulting in a 500 error rather than a clean 400 validation error. This is a minor robustness issue.

**Evidence / Trace:**
```java
// PhotoController.java:56-60
@GetMapping
public ResponseEntity<Page<PhotoResponse>> listPhotos(
        @AuthenticationPrincipal UUID userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {
    return ResponseEntity.ok(photoService.listPhotos(userId, page, size)...);
}

// PhotoService.java:248-251 — clamps max but not min
public Page<Photo> listPhotos(UUID userId, int page, int size) {
    return photoRepository.findByUserIdAndDeletedAtIsNullOrderByUploadedAtDesc(
            userId, PageRequest.of(page, Math.min(size, 100)));  // negative size = exception
}
```

**Remediation:**
- **Primary fix:** Add `@Min(1) @Max(100)` validation on `size` and `@Min(0)` on `page` parameters, with `@Validated` on the controller class. This produces clean 400 responses for invalid input.

---

### Finding #7: MinIO Presign-Only Policy is Empty

**Vulnerability:** Overly Permissive Presign Policy — Misconfiguration (A05)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docker-compose.yml`, Lines 176–179

**Risk & Exploit Path:**
The `minio-init` entrypoint creates a `presign-only` IAM policy for the presign user with an empty statement array: `{"Version":"2012-10-17","Statement":[]}`. An empty policy in MinIO **denies all actions** by default (deny-by-default). This means the presign user can't actually generate valid pre-signed URLs — any pre-signed URL generated with these credentials would be rejected when the client tries to use it.

However, if the API service uses the main `MINIO_API_ACCESS_KEY` (which has `readwrite` policy) for presigning, this empty policy is simply unused. Let me verify the application.yml:

```yaml
minio:
  presign-access-key: ${MINIO_PRESIGN_ACCESS_KEY}
  presign-secret-key: ${MINIO_PRESIGN_SECRET_KEY}
```

The application uses separate presign credentials. If these credentials have an empty policy, pre-signed URLs will fail at access time. This is either a bug (presigning doesn't work) or the policy was intended to be populated with appropriate read permissions.

**Evidence / Trace:**
```sh
# docker-compose.yml:176
echo '{"Version":"2012-10-17","Statement":[]}' > /tmp/presign-only-policy.json
# ← Empty Statement array = no permissions granted
(mc admin policy create minio presign-only /tmp/presign-only-policy.json || true)
(mc admin user add minio $$MINIO_API_PRESIGN_ACCESS_KEY $$MINIO_API_PRESIGN_SECRET_KEY || true)
(mc admin policy attach minio presign-only --user $$MINIO_API_PRESIGN_ACCESS_KEY || true)
```

**Remediation:**
- **Primary fix:** The presign user needs at minimum `s3:GetObject` permission on the bucket to generate valid pre-signed GET URLs:
  ```json
  {
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::jpt-photos/*"]
    }]
  }
  ```
- **Verification:** Test that pre-signed thumbnail and original URLs are actually accessible in staging.

---

### Finding #8: CSP Missing `script-src` Directive

**Vulnerability:** Incomplete Content Security Policy — Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `nginx.prod.conf`, Line 76

**Risk & Exploit Path:**
The CSP header defines `default-src 'self'` and specific directives for `img-src`, `style-src`, `connect-src`, `font-src`, and `frame-ancestors`. However, `script-src` is not explicitly set, so it falls back to `default-src 'self'`. This is actually correct and secure — scripts can only load from the same origin.

However, the `style-src 'self'` directive may be too strict if any shadcn/ui components inject inline styles. If they do, the browser will block them and the UI will break. This would be caught in testing but is worth noting.

More notably, there is no `object-src` directive. While `default-src 'self'` covers it, explicitly setting `object-src 'none'` is a defense-in-depth best practice to prevent Flash/plugin-based attacks.

**Remediation:**
- **Primary fix:** Add `object-src 'none'; base-uri 'self';` to the CSP header for defense-in-depth.
- **Verify:** Test that `style-src 'self'` doesn't break shadcn/ui components. If inline styles are needed, add `style-src 'self' 'unsafe-inline'` (lower priority since inline styles are a low-risk XSS vector compared to scripts).

---

### Finding #9: EntityNotFoundException May Leak Internal Details

**Vulnerability:** Information Disclosure via Exception Messages — Error Handling (A09)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/controller/UserController.java`, Line 32
- File: `api/src/main/java/org/jphototagger/api/service/PhotoMetadataService.java`, Lines 34, 38, 42

**Risk & Exploit Path:**
Several services throw `EntityNotFoundException` with descriptive messages like "User not found", "Photo not found", "Metadata not available". While `server.error.include-message: never` in `application.yml` should suppress these messages in Spring Boot's default error responses, `EntityNotFoundException` is a JPA exception that may be handled differently depending on the exception handler chain.

If Spring's default error handling catches these and returns 500 (since there's no explicit `@ExceptionHandler` for `EntityNotFoundException`), the response would be a generic 500 rather than a 404, which is confusing but not insecure. If there IS an exception handler that converts it to 404 with the message, the message could leak entity type information.

This is low risk because `server.error.include-message: never` is configured, but the absence of an explicit `@ControllerAdvice` mapping `EntityNotFoundException → 404` creates ambiguity.

**Remediation:**
- **Primary fix:** Add a `@ControllerAdvice` that maps `EntityNotFoundException` to `404 Not Found` with a generic body: `{"error": "Not Found", "status": 404}`.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack Analysis

**Chain 1: Unverified email + API access**
Finding #1 enables an unverified user to log in. While uploads are gated, keyword/album creation and search are not. Combined with Finding #6 (no pagination validation), an unverified user could make many API calls to consume server resources. **Mitigated by:** Rate limiting (1000 req/hr per user) and account purge scheduler (unverified accounts deleted after a configurable period).

**Chain 2: Rate limit IP confusion + brute force**
If Finding #2's IP extraction logic were incorrect (extracting proxy IP instead of client IP), all unauthenticated auth requests would share a single 20-per-hour bucket. This would make the rate limiter a denial-of-service tool against legitimate users rather than a brute-force protection. **Current state:** In the single-proxy architecture, the last X-Forwarded-For entry IS the client IP (nginx appends it). The risk materializes only if a CDN/LB is added later.

### Implicit Trust Assumptions

1. **nginx → API:** The API trusts `X-Forwarded-For` and `X-Forwarded-Proto` from any source. `server.forward-headers-strategy: framework` enables Spring's `ForwardedHeaderFilter` which trusts these headers unconditionally. This is safe when nginx is the only entry point, but dangerous if the API port (8080) is ever exposed directly. **Mitigated by:** Docker network isolation — API is on `backend` internal network, not directly exposed.

2. **RLS context propagation:** The `RlsAspect` only fires on `@Transactional` methods. Any repository call without `@Transactional` bypasses RLS context setting. **Mitigated by:** The `connection-init-sql` sets a null UUID default, and `assert_user_context()` would fail for non-null-UUID contexts. However, read-only queries without `@Transactional` don't trigger the aspect and use the default null UUID context.

### Defense-in-Depth Assessment

**Strong layers:**
- CSRF: Cookie (SameSite=Strict) + X-XSRF-TOKEN header + BREACH protection (XorCsrfTokenRequestAttributeHandler)
- Authentication: JWT in httpOnly cookie + refresh token rotation + account lockout
- Authorization: Spring Security filter chain + RLS at database level
- Rate limiting: nginx (auth) + Bucket4j/Redis (auth + upload + general)
- Container security: cap_drop ALL, no-new-privileges, memory limits, network isolation

**Gaps identified:**
- No WAF or bot detection layer
- No audit logging for security-relevant events (login, failed login, password change)
- No session invalidation mechanism (JWT is stateless — can't be revoked until expiry)

---

## Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | Unverified Email Login Allowed | A07 — Auth | Medium | Confirmed | 1 | FIX |
| 2 | X-Forwarded-For IP Extraction Fragility | A05/A07 — Misconfig | Medium | High | 1 | FIX |
| 3 | CSRF Token Non-Overridable (Informational) | A01 — Access Control | Informational | Confirmed | — | OK |
| 4 | SameSite=Lax on JWT/Refresh Cookies | A07 — Auth | Low | Confirmed | 2 | FIX |
| 5 | Missing Password Maximum Length | A07 — Auth | Low | High | 2 | FIX |
| 6 | Pagination Size Not Validated | A04 — Resource | Low | Confirmed | 2 | FIX |
| 7 | MinIO Presign-Only Policy Empty | A05 — Misconfig | Medium | High | 1 | FIX |
| 8 | CSP Missing object-src/base-uri | A05 — Misconfig | Low | Confirmed | 1 | FIX |
| 9 | EntityNotFoundException Error Handling | A09 — Info Leak | Low | Medium | 4 | FIX |

---

## Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical | 0 | 0 |
| High | 0 | 0 |
| Medium | 3 | −24 |
| Low | 5 | −10 |
| Informational | 1 | −1 |

**Final SQS:** 65/100
**Hard gates triggered:** No
**Posture:** Unacceptable — remediation required before production deployment

*Note:* The SQS is driven by the count of medium findings. Individually, none of these are severe, but collectively they represent gaps that should be closed. Fixing Findings #1, #2, and #7 would bring the score to 89 (Strong).

---

## Positive Security Observations

1. **Comprehensive RLS enforcement:** The multi-layered approach (RlsContext ThreadLocal + RlsAspect + RlsInterceptor + RlsContextCleanupFilter + connection-init-sql default) provides excellent tenant isolation at the database level. The `assert_user_context()` function is a strong safety net.

2. **Timing side-channel mitigation in authentication:** Both the register (dummy bcrypt on duplicate) and login (bcrypt on non-existent user) paths equalize timing to prevent user enumeration. The dummy hash string in authenticate() is appropriately formatted as a valid bcrypt hash.

3. **Server-side GPS filtering (SA4-F1):** GPS stripping in `PhotoMetadataService` is thorough — it removes GPS coordinates, GPS-prefixed EXIF keys, IPTC location keys, and XMP location keys. The `withoutGps()` method returns a new immutable record, preventing accidental leakage.

4. **Container hardening:** All services use `cap_drop: ALL`, `no-new-privileges: true`, memory limits, and CPU limits. The worker container runs with `read_only: true`. Network isolation separates frontend (nginx) from backend services.

5. **CSRF implementation:** The SPA CSRF pattern (CookieCsrfTokenRepository + XorCsrfTokenRequestAttributeHandler + CsrfCookieFilter + bootstrapCsrf()) is correctly implemented with BREACH protection. The frontend's `apiFetch` wrapper ensures the token is always included and non-overridable.

---

## Prioritized Remediation Roadmap

### 1. Finding #1 — Unverified Email Login Allowed
**Why:** Allows unauthorized access to authenticated API surface without email verification. Violates the registration flow's security intent.
**Effort:** Quick Win
**Owner:** Backend

### 2. Finding #7 — MinIO Presign-Only Policy Empty
**Why:** Pre-signed URLs may not work at all (functional bug), or if they do, it means a different (more privileged) credential is being used. Either way, this needs verification and correction.
**Effort:** Quick Win
**Owner:** DevOps

### 3. Finding #2 — X-Forwarded-For IP Extraction Fragility
**Why:** Rate limiting is a critical security control for auth endpoints. The current implementation works but is fragile and would silently break if the infrastructure changes. Switching to `request.getRemoteAddr()` (which Spring's ForwardedHeaderFilter already resolves correctly) is a one-line fix.
**Effort:** Quick Win
**Owner:** Backend

### 4. Finding #4 — SameSite=Lax on JWT/Refresh Cookies
**Why:** SA4-F4 requires SameSite=Strict. Needs careful testing with OAuth2 flow.
**Effort:** Moderate (requires OAuth2 flow testing)
**Owner:** Backend

### 5. Finding #5 — Missing Password Maximum Length
**Why:** Simple validation addition that prevents potential DoS via bcrypt amplification.
**Effort:** Quick Win
**Owner:** Backend
