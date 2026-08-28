# Security Audit — Phase 4: React Frontend (SA10)

> **Audit date:** 2026-03-12
> **Auditor:** LCSA (Claude Opus 4.6)
> **Scope:** Implementation of `docs/plans/2026-02-25-saas-conversion-phase-4.md` — all backend prerequisites (Task 4.0) and frontend code (Tasks 4.1–4.9), plus supporting backend services, Dockerfiles, nginx configuration, and deployment configuration. This audit reviews the **current state** of the codebase after SA1–SA9 remediations.
> **Prior audits:** SA1–SA9 for this phase. This audit verifies SA9 remediations and performs a fresh three-pass review.
> **Materials reviewed:** 40+ source files across `api/`, `worker/`, `frontend/`, `pgbackup/`, Docker/nginx configuration, and CI/CD.

---

## SA9 Remediation Verification

| SA9 # | Title | Status | Evidence |
|---|---|---|---|
| 1 | api/Dockerfile ENTRYPOINT Shell Injection | **FIXED** | `api/Dockerfile:13` — exec-form with tini: `ENTRYPOINT ["/sbin/tini", "--", "java", ...]` |
| 2 | Rate Limit Bypass via X-Forwarded-For Spoofing | **FIXED** | `nginx.prod.conf:100,110` uses `$remote_addr` (not `$proxy_add_x_forwarded_for`); `application.yml:60` uses `native` strategy with `internal-proxies` CIDR allowlist |
| 3 | api/Dockerfile Base Image Not Digest-Pinned | **FIXED** | `api/Dockerfile:1` — `@sha256:6ad8ed080d9be96b61438ec3ce99388e294af216ed57356000c06070e85c5d5d` |
| 4 | UnsupportedMediaTypeException Leaks MIME Type | **FIXED** | `GlobalExceptionHandler.java:100` — generic message: "Unsupported file type. Accepted: JPEG, PNG, TIFF, HEIC, WebP, CR2, NEF, ARW, DNG" |
| 5 | CSRF Bootstrap Relies on 401 Side-Effect | **FIXED** | `SecurityConfig.java:77` — `/csrf` added to `permitAll()` list |

**All 5 findings from SA9 have been remediated.**

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points

| Entry Point | Auth Required | CSRF Required | Rate Limited |
|---|---|---|---|
| `POST /auth/register` | No | Yes | 20/hr per IP |
| `POST /auth/verify` | No | Yes | 20/hr per IP |
| `POST /auth/login` | No | Yes | 20/hr per IP |
| `POST /auth/refresh` | No (cookie) | No (exempt) | 20/hr per IP |
| `POST /auth/logout` | No (cookie) | Yes | 20/hr per IP |
| `GET /csrf` | No | No (GET) | 1000/hr general |
| `GET /actuator/health` | No | No (GET) | Blocked by nginx in prod |
| `GET /users/me` | Yes (JWT) | No (GET) | 1000/hr per user |
| `PATCH /users/me` | Yes (JWT) | Yes | 1000/hr per user |
| `POST /photos/upload` | Yes (JWT) | Yes | 100/hr per user |
| `GET /photos` | Yes (JWT) | No (GET) | 1000/hr per user |
| `GET /photos/{id}` | Yes (JWT) | No (GET) | 1000/hr per user |
| `GET /photos/{id}/status` | Yes (JWT) | No (GET) | 1000/hr per user |
| `DELETE /photos/{id}` | Yes (JWT) | Yes | 1000/hr per user |
| `GET /photos/trash` | Yes (JWT) | No (GET) | 1000/hr per user |
| `POST /photos/{id}/restore` | Yes (JWT) | Yes | 1000/hr per user |
| `GET /photos/{id}/keywords` | Yes (JWT) | No (GET) | 1000/hr per user |
| `POST /photos/{id}/keywords/{kwId}` | Yes (JWT) | Yes | 1000/hr per user |
| `DELETE /photos/{id}/keywords/{kwId}` | Yes (JWT) | Yes | 1000/hr per user |
| `GET /photos/{id}/metadata` | Yes (JWT) | No (GET) | 1000/hr per user |
| `GET /search` | Yes (JWT) | No (GET) | 1000/hr per user |
| `GET /keywords` | Yes (JWT) | No (GET) | 1000/hr per user |
| `POST /keywords` | Yes (JWT) | Yes | 1000/hr per user |
| `PUT /keywords/{id}` | Yes (JWT) | Yes | 1000/hr per user |
| `DELETE /keywords/{id}` | Yes (JWT) | Yes | 1000/hr per user |
| `GET /albums` | Yes (JWT) | No (GET) | 1000/hr per user |
| OAuth2 login flow | No | No (exempt) | 20/hr per IP |

### Trust Boundaries

1. **Browser → nginx (TLS)** → **Spring Boot API** → **PostgreSQL (RLS)** → response
2. **Browser → MinIO (pre-signed URLs)** — direct GET, no API intermediary, time-limited
3. **Frontend auth store** → cookie-based JWT (15 min) + refresh token rotation (30 days)
4. **Redis** — rate limit buckets, refresh token families, token-to-family reverse index

### Authentication Architecture

- JWT in httpOnly cookie (15 min expiry, `Secure`, `SameSite=Strict`)
- Refresh token in httpOnly cookie (30 days, `Secure`, `SameSite=Lax`, `Path=/auth`)
- CSRF via `CookieCsrfTokenRepository.withHttpOnlyFalse()` with XOR BREACH protection
- OAuth2 (Google/GitHub) with server-side redirect + JWT issuance
- PostgreSQL RLS via `app.current_user_id` session variable + `assert_user_context()`
- Account lockout: 5 failed attempts → 15 min lockout

### Sensitive Data Flows

- **Credentials:** email/password in POST body → bcrypt(12) hash → DB
- **GPS coordinates:** server-side filtering in `PhotoMetadataResponse.withoutGps()` when `showGps=false`; frontend DOM suppression as defence-in-depth
- **Photo content:** stored in MinIO with user-scoped paths (`{userId}/originals/...`), accessed via time-limited pre-signed URLs
- **Refresh tokens:** SHA-256 hashed in Redis, atomic rotation via Lua script, family-based replay detection
- **EXIF metadata:** Jsoup-sanitized at write time (Phase 3), rendered as React text nodes (never `dangerouslySetInnerHTML`)

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: `EmailVerificationRequiredException` Handler Passes `ex.getMessage()` to Client

**Vulnerability:** Information Disclosure — OWASP A09 (Security Logging and Monitoring Failures)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java`, Lines 84–88
- Related: `api/src/main/java/org/jphototagger/api/service/AuthService.java`, Line 136
- Related: `api/src/main/java/org/jphototagger/api/service/PhotoService.java`, Line 202

**Risk & Exploit Path:**
The `EmailVerificationRequiredException` handler passes `ex.getMessage()` directly to the HTTP response. While the current exception messages are benign ("Please verify your email before logging in", "Email verification required before uploading"), this pattern contradicts the codebase's otherwise consistent use of generic error messages. A future developer could add internal details to the exception message without realizing it reaches clients.

The same pattern exists for `QuotaExceededException` (line 93), though that message ("Storage quota exceeded") is also currently benign. Both patterns are inconsistent with `application.yml`'s `include-message: never` intent.

**Evidence / Trace:**

```java
// GlobalExceptionHandler.java:84-88
@ExceptionHandler(EmailVerificationRequiredException.class)
public ResponseEntity<ErrorResponse> handleEmailVerificationRequired(EmailVerificationRequiredException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse(ex.getMessage(), HttpStatus.FORBIDDEN.value()));
    // ← ex.getMessage() passed through to client
}
```

```java
// AuthService.java:136
throw new EmailVerificationRequiredException("Please verify your email before logging in");
```

Every other exception handler in the file uses hardcoded generic messages (e.g., "Invalid credentials", "Not Found", "An internal error occurred"). These two handlers are the exceptions.

**Remediation:**

- **Primary fix:** Replace `ex.getMessage()` with static messages in both handlers:
  ```java
  @ExceptionHandler(EmailVerificationRequiredException.class)
  public ResponseEntity<ErrorResponse> handleEmailVerificationRequired(EmailVerificationRequiredException ex) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
              .body(new ErrorResponse("Email verification required", HttpStatus.FORBIDDEN.value()));
  }

  @ExceptionHandler(QuotaExceededException.class)
  public ResponseEntity<ErrorResponse> handleQuotaExceeded(QuotaExceededException ex) {
      return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
              .body(new ErrorResponse("Storage quota exceeded", HttpStatus.PAYMENT_REQUIRED.value()));
  }
  ```

**References:**
- CWE-209: Generation of Error Message Containing Sensitive Information

---

### Finding #2: `pgbackup/Dockerfile` Base Image Not Digest-Pinned

**Vulnerability:** Supply Chain Risk — OWASP A06 (Vulnerable and Outdated Components)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `pgbackup/Dockerfile`, Line 1

**Risk & Exploit Path:**
The pgbackup Dockerfile uses `FROM postgres:16` without a SHA256 digest pin. This is the only remaining Dockerfile without digest pinning — `api/Dockerfile` and `worker/Dockerfile` are both pinned. The `postgres:16` tag is mutable; a compromised Docker Hub account or tag mutation could inject malicious code into the backup container.

The pgbackup container has access to the PostgreSQL database (for pg_dump) and to the B2 backup credentials, making it a high-value target if compromised.

**Evidence / Trace:**

```dockerfile
# pgbackup/Dockerfile:1
FROM postgres:16                 # ← No digest pin — mutable tag

# api/Dockerfile:1 (correct — fixed in SA9)
FROM eclipse-temurin:21-jre-alpine@sha256:6ad8ed...

# worker/Dockerfile:1 (correct)
FROM eclipse-temurin:21-jre-alpine@sha256:6ad8ed...
```

**Remediation:**

- **Primary fix:** Pin the base image to a specific digest:
  ```dockerfile
  FROM postgres:16@sha256:<current-digest>
  ```
  Run: `docker pull postgres:16 && docker inspect --format='{{index .RepoDigests 0}}' postgres:16`

**References:**
- CWE-829: Inclusion of Functionality from Untrusted Control Sphere

---

### Finding #3: Dev nginx Config Uses `$proxy_add_x_forwarded_for` (Inconsistent with Prod Fix)

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Medium

**Location:**
- File: `nginx.conf`, Lines 34 and 43

**Risk & Exploit Path:**
SA9 Finding #2 identified the `X-Forwarded-For` spoofing issue and it was remediated in `nginx.prod.conf` by switching to `$remote_addr`. However, the dev nginx config (`nginx.conf`) still uses `$proxy_add_x_forwarded_for` on both proxy locations.

While the dev config is labeled "DEV ONLY — NO TLS, NO SECURITY HEADERS" and is not deployed to production, developers running the full Docker Compose stack locally may discover inconsistent rate limiting behavior between dev and prod. More importantly, if the dev config is accidentally used in staging or production (e.g., config mount error), the rate limit bypass would be reintroduced.

**Evidence / Trace:**

```nginx
# nginx.conf:34 (dev — VULNERABLE)
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

# nginx.prod.conf:100 (prod — FIXED)
proxy_set_header X-Forwarded-For   $remote_addr;
```

**Remediation:**

- **Primary fix:** Align the dev config with the prod config:
  ```nginx
  proxy_set_header X-Forwarded-For $remote_addr;
  ```

---

### Finding #4: `MethodArgumentNotValidException` Handler Exposes Field Names and Constraint Messages

**Vulnerability:** Information Disclosure — OWASP A09
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java`, Lines 32–40

**Risk & Exploit Path:**
The validation error handler concatenates field names and their default constraint messages into the response body. While this is useful for API consumers during development, it reveals internal DTO field names and validation rules to potential attackers. For example, a malformed registration request reveals: `"email: must not be blank; password: size must be between 12 and 128"`.

This discloses:
1. Internal field names (though these are fairly obvious)
2. Exact validation constraints (password length range)
3. That the backend uses Bean Validation (technology fingerprint)

This is standard practice in many APIs and only a minor concern, but it is inconsistent with the strict `include-message: never` policy applied elsewhere. The pattern of exposing field-specific errors is a conscious usability choice, not a security oversight — but it should be documented as an intentional exception.

**Evidence / Trace:**

```java
// GlobalExceptionHandler.java:32-40
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())  // ← Exposes field names + constraints
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
    return ResponseEntity.badRequest()
            .body(new ErrorResponse(message, HttpStatus.BAD_REQUEST.value()));
}
```

**Remediation:**

- **Primary fix (if strict):** Replace with generic message:
  ```java
  .body(new ErrorResponse("Validation failed", HttpStatus.BAD_REQUEST.value()));
  ```
- **Alternative (recommended):** Keep the current behavior but add a code comment documenting the intentional trade-off between usability and information disclosure. This is standard practice in REST APIs and the disclosed information (field names and basic constraints) is generally not sensitive.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attacks

No exploitable chains identified. Previous chains (SA9 #2 + account lockout) have been remediated by the `$remote_addr` fix in nginx and the `native` forwarded-headers strategy.

### Implicit Trust Assumptions

1. **Frontend → CSRF cookie:** The `apiFetch` wrapper reads `XSRF-TOKEN` from `document.cookie` via regex `(/XSRF-TOKEN=([^;]+)/)?.[1]`. If the cookie value contains special characters, the regex still captures correctly because `[^;]+` matches everything up to the next semicolon. The XOR BREACH protection layer transforms the token, but the client sends the raw cookie value — Spring's `XorCsrfTokenRequestAttributeHandler` handles the decode. Verified: the client does not need to decode the XOR token itself. **No issue.**

2. **`camelizeKeys` skips `exifData`:** The function checks `newKey === 'exifData'` after transformation, meaning it correctly identifies the camelized key name. Original wire key `exif_data` is transformed to `exifData`, and the value is preserved without recursion. **No issue.**

3. **Pre-signed URL generation:** The `minioPublicClient` is configured with presign-only IAM credentials (`s3:GetObject` only). Even if the presign secret key were compromised, the attacker could only generate GET URLs — not upload, delete, or list objects. The public client is never used for I/O operations. **Strong separation.**

4. **OAuth2 redirect URI validation:** `OAuth2SuccessHandler.validateRedirectUri()` (line 54) enforces that the redirect URI ends with `/`. The redirect URI is server-side configured via `app.oauth2.redirect-uri` (default `/`), not user-controllable. The `sendRedirect()` calls concatenate this with hardcoded path suffixes (e.g., `redirectUri + "login?error=no_email"`). **No open redirect.**

5. **Frontend post-login redirect:** `LoginPage.tsx:17` reads `from` from `location.state`, which is set by `ProtectedRoute` when redirecting unauthenticated users. React Router's `navigate()` treats this as a relative path — it does not perform cross-origin redirects. An attacker cannot set `location.state` via URL manipulation. **No open redirect.**

### Defense-in-Depth Assessment

- **GPS privacy:** Three layers — (1) server-side `PhotoMetadataResponse.withoutGps()` removes GPS, IPTC location, and XMP location keys; (2) frontend `MetadataPanel` conditionally renders GPS section based on `showGps`; (3) database default `show_gps = FALSE`. **Robust.**
- **XSS prevention:** Four layers — (1) React JSX auto-escaping; (2) ESLint `react/no-danger: error` rule; (3) backend Jsoup sanitization on EXIF strings at write time; (4) TypeScript strict mode. No `dangerouslySetInnerHTML` usage found (only `innerHTML` is in hardcoded error message in `main.tsx`). **Strong.**
- **CSRF:** Three layers — (1) `CookieCsrfTokenRepository` with XOR BREACH protection; (2) `SameSite=Strict` on CSRF cookie; (3) nginx rate limiting on auth endpoints. **Well-implemented.**
- **Authentication:** JWT (15 min) + refresh token rotation with family-based replay detection + bcrypt(12) + timing equalization + account lockout. **Production-grade.**
- **Error handling:** `include-stacktrace: never` + `include-message: never` + `GlobalExceptionHandler` with generic messages + frontend 200-char body truncation. Two minor `ex.getMessage()` leaks identified (Finding #1) but current messages are benign. **Strong with minor inconsistency.**
- **Container security:** All containers drop all capabilities, set `no-new-privileges: true`, run as non-root users, and the backend network is internal. Worker filesystem is read-only. Resource limits enforced. **Strong.**
- **Rate limiting:** Dual-layer (nginx burst protection + application-layer Bucket4j via Redis). Auth endpoints: 10 req/min (nginx) + 20 req/hr (app). **Effective.**

### Deployment Context

- Docker Compose with isolated backend network (`internal: true`) — PostgreSQL, Redis, MinIO not directly accessible
- TLS 1.2/1.3 via nginx with strong cipher suite, OCSP stapling, session tickets disabled
- All security headers present in nginx.prod.conf (HSTS 2yr, CSP, X-Frame-Options DENY, X-Content-Type-Options nosniff, Permissions-Policy, Referrer-Policy)
- CI/CD includes Trivy vulnerability scanning with SARIF upload and pipeline-gating on HIGH/CRITICAL findings
- GitHub Actions all pinned to commit SHA (not branch tags)

---

## 1. Executive Summary

The Phase 4 implementation is in strong security posture following the SA9 remediation cycle. All five SA9 findings have been correctly addressed: the API Dockerfile now uses exec-form ENTRYPOINT with tini, the X-Forwarded-For spoofing is mitigated by both nginx `$remote_addr` replacement and Tomcat's `RemoteIpValve` with trusted proxy allowlist, the API base image is digest-pinned, the MIME type leak is replaced with a generic error, and the CSRF endpoint is explicitly permitted.

The four new findings in this audit are all Low severity — two minor information disclosure inconsistencies in error handlers, one dev-environment nginx config inconsistency, and one unpinned Dockerfile base image. None represent exploitable vulnerabilities in the current deployment. The codebase demonstrates mature security engineering with defense-in-depth across authentication, authorization, GPS privacy, XSS prevention, CSRF protection, and container hardening.

The application is ready for production deployment with the understanding that the four Low-severity findings represent best-practice improvements rather than blocking issues.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | `ex.getMessage()` Passed to Client in Two Exception Handlers | A09 — Info Disclosure | Low | Confirmed | 2 (EmailVerification + Quota) | FIX |
| 2 | pgbackup/Dockerfile Base Image Not Digest-Pinned | A06 — Supply Chain | Low | Confirmed | 1 | FIX |
| 3 | Dev nginx Config Still Uses `$proxy_add_x_forwarded_for` | A05 — Misconfig | Low | Confirmed | 2 (both proxy locations) | FIX |
| 4 | Validation Error Handler Exposes Field Names and Constraints | A09 — Info Disclosure | Low | Confirmed | 1 | ACCEPT |

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical        | 0     | 0         |
| High            | 0     | 0         |
| Medium          | 0     | 0         |
| Low             | 4     | −8        |

**Raw Score:** 100 − 8 = **92/100**

**Hard gates triggered:** No

**Final SQS:** 92/100
**Posture:** Strong — deploy with standard monitoring

## 4. Positive Security Observations

1. **Complete SA9 remediation:** All five findings from the previous audit were correctly fixed. The Dockerfile ENTRYPOINT is now exec-form with tini, the forwarded-headers configuration uses Tomcat's native `RemoteIpValve` with a CIDR allowlist, and the CSRF endpoint is explicitly permitted. No regressions introduced.

2. **Exemplary refresh token security:** Atomic `GETDEL` via Lua script prevents TOCTOU races during rotation. Family-based replay detection revokes the entire token tree on replay. Token-to-family reverse index enables O(1) lookup. This is beyond what most production systems implement.

3. **Multi-layer GPS privacy:** `PhotoMetadataResponse.withoutGps()` strips GPS coordinates, all GPS-prefixed EXIF keys (case-insensitive), IPTC location fields (sub-location, city, province-state, country codes), and XMP location fields (photoshop city/state/country, IPTC4XMP location). Frontend DOM suppression provides a second layer. Database defaults `show_gps` to `FALSE`. Comprehensive.

4. **Consistent authorization enforcement:** Every controller endpoint uses `@AuthenticationPrincipal UUID userId`. Photo/keyword operations validate ownership before mutation. RLS policies at the PostgreSQL level provide a third enforcement layer. The `assert_user_context()` function prevents accidental queries without a user context.

5. **Principled MinIO credential separation:** Five distinct IAM users (root, API, presign, worker, backup) with purpose-scoped policies. The presign user is read-only (`s3:GetObject`), the worker is path-restricted, and the backup user is read-only. The public MinIO client is never used for I/O operations — only URL generation.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #1 — Replace `ex.getMessage()` in two handlers | Consistency with `include-message: never` policy. Currently benign messages, but pattern is fragile. | Quick Win | Backend |
| 2 | #2 — Pin pgbackup base image digest | Supply chain hardening. Last remaining unpinned Dockerfile. One command + one-line edit. | Quick Win | DevOps |
| 3 | #3 — Align dev nginx X-Forwarded-For | Eliminates config drift. Prevents accidental production use of dev config. | Quick Win | DevOps |
| 4 | #4 — Document validation error trade-off | Add a code comment explaining the intentional usability-vs-disclosure trade-off. No code change required unless strict policy is preferred. | Quick Win | Backend |
