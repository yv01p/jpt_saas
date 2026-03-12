# Security Audit — Phase 4: Post-Remediation Review (Audit #5)

> **Audit date:** 2026-03-12
> **Auditor:** LCSA (Claude Opus 4.6)
> **Scope:** Implementation of `docs/plans/2026-02-25-saas-conversion-phase-4.md` — full codebase review including all working tree modifications. This audit verifies remediation of findings from audit-4 and identifies any new or regressed issues.
> **Methodology:** Three-pass white-box analysis (Reconnaissance → Systematic Hunting → Compositional Analysis)

---

## Audit-4 Remediation Verification

Before hunting new findings, verify the status of all 8 findings from audit-4 (`2026-03-11-saas-conversion-phase-4-security-audit-4.md`):

| Audit-4 # | Title | Status | Evidence |
|---|---|---|---|
| 1 | CSP `img-src` blocks MinIO URLs | **FIXED** | `nginx.prod.conf:78` now includes `https://minio.yourdomain.com` in `img-src` |
| 2 | Missing `forward-headers-strategy` | **FIXED** | `application.yml:60` includes `server.forward-headers-strategy: framework` |
| 3 | Registration timing side-channel | **FIXED** | `AuthService.java:74` — `sendVerificationEmailAsync()` is `@Async`; `JptSaasApplication.java` has `@EnableAsync` |
| 4 | Duplicate CSP headers (Spring + nginx) | **FIXED** | `SecurityConfig.java:66-69` removes Spring CSP; comment says "managed exclusively by nginx" |
| 5 | Spring CSP `img-src` overly permissive | **FIXED** | Spring CSP removed entirely |
| 6 | GPS filter misses non-prefixed keys | **FIXED** | `PhotoMetadataResponse.java:43` now filters both `gps:` and `gps ` prefixes |
| 7 | `PATCH /users/me` missing `@Valid` | **FIXED** | `UserController.java:39` now has `@Valid @RequestBody` |
| 8 | Dev cookie `Secure` flag breaks HTTP | **FIXED** | `SecurityConfig.java:43,60`, `AuthController.java:44,50`, `OAuth2SuccessHandler.java:40` all use configurable `cookieSecure` from `${COOKIE_SECURE:true}` |

**All 8 findings from audit-4 have been remediated.**

---

## Pass 1: Reconnaissance & Attack Surface Mapping

Attack surface is unchanged from audit-4. See that report for the full mapping. Key additions verified in this audit:

- **Entry points:** 35+ REST endpoints across 7 controllers
- **Auth architecture:** JWT (15min) + refresh token (30d, family-based rotation) + OAuth2 (Google/GitHub)
- **Data protection:** RLS via PostgreSQL `set_config()` + `assert_user_context()`, server-side GPS filtering (SA4-F1)
- **Cookie config:** JWT (`HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`), Refresh (`HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/auth`), CSRF (`SameSite=Strict`, `Secure`, non-HttpOnly)
- **Rate limiting:** nginx burst (10r/m auth) + Bucket4j (20/hr auth per IP, 100/hr upload per user, 1000/hr general per user)
- **Error handling:** `include-stacktrace: never`, `include-message: never`, client-side body truncation to 200 chars

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: `Permissions-Policy` Header Removed from Spring, Never Added to nginx

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/.../security/SecurityConfig.java`, Lines 66–69 (removed)
- File: `nginx.prod.conf` (missing)

**Risk & Exploit Path:**
The committed version of `SecurityConfig.java` included a `Permissions-Policy` header restricting browser features:
```java
.permissionsPolicy(pp -> pp.policy("camera=(), microphone=(), geolocation=()"))
```

The working tree changes removed this header from Spring Security with the comment "CSP, HSTS, and Permissions-Policy are managed exclusively by nginx." However, `Permissions-Policy` was **never added** to `nginx.prod.conf`. The result is a complete loss of the header.

Without `Permissions-Policy`, an XSS attacker (or malicious embedded content) could:
1. Access the user's camera, microphone, or GPS location via browser APIs
2. This is particularly concerning for a photo management app that stores GPS metadata — a compromised page could silently harvest the user's live geolocation

**Evidence / Trace:**

```java
// SecurityConfig.java:66-69 — working tree (REMOVED):
// CSP, HSTS, and Permissions-Policy are managed exclusively by nginx
// to avoid duplicate/conflicting headers. See nginx.prod.conf.
.headers(headers -> headers
    .httpStrictTransportSecurity(hsts -> hsts.disable()))
// ← permissionsPolicy() call deleted
```

```java
// SecurityConfig.java — committed version (HAD):
.permissionsPolicy(pp -> pp.policy(
        "camera=(), microphone=(), geolocation=()"))
// ← this existed but was removed
```

```nginx
# nginx.prod.conf — MISSING:
# No Permissions-Policy header anywhere in the file
# grep "Permissions-Policy" nginx.prod.conf → no results
# grep "permissions-policy" nginx.prod.conf → no results
```

**Remediation:**
- **Primary fix:** Add to `nginx.prod.conf` inside the `# Security headers` block (after the CSP line):
  ```nginx
  add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()" always;
  ```
- **Alternative:** Restore the Spring Security `permissionsPolicy()` call until the nginx config is verified deployed.

---

### Finding #2: Missing `client_max_body_size` in Production nginx Config

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** N/A (functional breakage)

**Location:**
- File: `nginx.prod.conf` (entire file — missing directive)
- Related: `nginx/default.conf:27` (dev config has `client_max_body_size 200M`)

**Risk & Exploit Path:**
The development nginx config (`nginx/default.conf:27`) correctly sets `client_max_body_size 200M` to allow photo uploads up to 200MB. The production nginx config (`nginx.prod.conf`) has **no** `client_max_body_size` directive anywhere, meaning nginx's default of 1MB applies. This creates two issues:

1. **Functional breakage:** All photo uploads exceeding 1MB will fail with nginx `413 Request Entity Too Large` before reaching Spring Boot. Since the app manages RAW photos (CR2, NEF, ARW — typically 20–50MB), the upload feature is completely broken in production.
2. **Security consideration:** When adding `client_max_body_size`, it should be scoped to the upload endpoint only, not globally. Non-upload endpoints should retain a small limit (e.g., 1MB) to protect against request body DoS.

**Evidence / Trace:**

```nginx
# nginx/default.conf:27 (dev) — PRESENT:
client_max_body_size 200M;
```

```nginx
# nginx.prod.conf — ABSENT:
# grep "client_max_body_size" nginx.prod.conf → no results
# Default: 1m (1MB)
```

```yaml
# application.yml:9-10 — Spring expects 200MB:
spring:
  servlet:
    multipart:
      max-file-size: 200MB
      max-request-size: 200MB
```

**Remediation:**
- **Primary fix:** Add a scoped directive to the upload-relevant location block. Create a dedicated upload location or add to the general API block:
  ```nginx
  # Inside the /api/ location block — or ideally a dedicated /api/photos/upload location:
  location /api/ {
      client_max_body_size 200M;  # Match Spring multipart limit
      proxy_pass http://api:8080/;
      # ... existing headers ...
  }
  ```
- **Defense-in-depth:** If separating upload from general API routes, keep general routes at `client_max_body_size 1m` (the default) and only allow 200MB for the upload path.

---

### Finding #3: CSRF-Exempt `/auth/logout` Enables Cross-Site Forced Logout

**Vulnerability:** Cross-Site Request Forgery — OWASP A01
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/.../security/SecurityConfig.java`, Line 63
- File: `api/src/main/java/.../controller/AuthController.java`, Lines 113–129

**Risk & Exploit Path:**
The CSRF configuration exempts `/auth/logout`:
```java
.ignoringRequestMatchers("/auth/refresh", "/auth/logout", "/login/oauth2/code/*");
```

The JWT and refresh cookies use `SameSite=Lax`, which allows them to be sent on top-level cross-site navigations (form submissions). An attacker can embed a hidden auto-submitting form on a malicious page:

```html
<form action="https://yourdomain.com/api/auth/logout" method="POST">
  <input type="submit" value="Click here for free photos">
</form>
<script>document.forms[0].submit();</script>
```

When the victim visits this page, the browser sends the JWT and refresh cookies (due to `SameSite=Lax` on a top-level form POST), the logout endpoint accepts the request (no CSRF check), revokes the refresh token, and clears cookies. The victim is forcibly logged out.

**Impact:** Availability only — no data loss, no account compromise. An attacker can annoy users but cannot access their data.

**Remediation:**
- **Primary fix:** Remove `/auth/logout` from the CSRF ignore list. The frontend already sends the `X-XSRF-TOKEN` header via `apiFetch`, so CSRF validation will work:
  ```java
  .ignoringRequestMatchers("/auth/refresh", "/login/oauth2/code/*");
  // ← /auth/logout removed
  ```
- **Alternative:** Accept this as a known low-risk trade-off and document it. Forced logout is a minor annoyance with no security impact.

---

### Finding #4: `@Async` Without Bounded Thread Pool May Exhaust Threads Under Load

**Vulnerability:** Resource Exhaustion — OWASP A05 (Misconfiguration)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: `api/src/main/java/.../JptSaasApplication.java`, Line 11 (`@EnableAsync`)
- File: `api/src/main/java/.../service/AuthService.java`, Lines 74–77

**Risk & Exploit Path:**
The `@EnableAsync` annotation enables Spring's async execution. `AuthService.sendVerificationEmailAsync()` is the only `@Async` method. Without a custom `TaskExecutor` bean, Spring defaults to `SimpleAsyncTaskExecutor`, which creates a **new thread for every invocation** with no pool limit, no queue, and no rejection policy.

If an attacker triggers rapid registrations (within rate limits: 20/hour per IP, but from multiple IPs via a botnet), each registration spawns a thread for email sending. If the SMTP server is slow or unresponsive, threads accumulate without bound, eventually exhausting system memory.

The Docker container memory limit (512MB) and the rate limiter mitigate this significantly, but the unbounded executor is a defense-in-depth gap.

**Evidence / Trace:**

```java
// JptSaasApplication.java:11
@EnableAsync  // ← uses SimpleAsyncTaskExecutor (unbounded)
```

```java
// AuthService.java:74-77
@Async
void sendVerificationEmailAsync(String email, String token) {
    emailService.sendVerificationEmail(email, token);  // ← one thread per call
}
```

**Remediation:**
- **Primary fix:** Define a bounded `TaskExecutor` bean:
  ```java
  @Bean
  public TaskExecutor taskExecutor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.setCorePoolSize(2);
      executor.setMaxPoolSize(5);
      executor.setQueueCapacity(50);
      executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
      executor.setThreadNamePrefix("async-email-");
      return executor;
  }
  ```
- The `CallerRunsPolicy` ensures that if the pool is saturated, the calling thread handles the email synchronously rather than dropping it.

---

### Finding #5: OAuth2 Error Redirects Use String Concatenation for URL Construction

**Vulnerability:** Open Redirect Risk — OWASP A01 (Broken Access Control)
**Severity:** Low
**Confidence:** Low (Requires Verification)
**Attack Complexity:** High

**Location:**
- File: `api/src/main/java/.../security/OAuth2SuccessHandler.java`, Lines 63, 90, 97

**Risk & Exploit Path:**
The OAuth2 success handler constructs redirect URLs via string concatenation:

```java
response.sendRedirect(redirectUri + "login?error=no_email");
```

The `redirectUri` comes from `${app.oauth2.redirect-uri:/}` (default `/`). With the default value, this produces `/login?error=no_email` — safe. However:

1. If an operator configures `redirectUri` as `https://myapp.com` (without trailing slash), the redirect becomes `https://myapp.comlogin?error=no_email` — a request to `myapp.comlogin` domain, which could be attacker-registered.
2. If configured as empty string, the redirect is relative `login?error=no_email`.

This is a configuration-dependent risk, not directly exploitable. The default value (`/`) is safe.

**Evidence / Trace:**

```java
// OAuth2SuccessHandler.java:63
response.sendRedirect(redirectUri + "login?error=no_email");     // ← string concatenation
// OAuth2SuccessHandler.java:90
response.sendRedirect(redirectUri + "login?error=email_conflict"); // ← same pattern
// OAuth2SuccessHandler.java:97
response.sendRedirect(redirectUri + "login?error=provider_mismatch");
```

**Remediation:**
- **Primary fix:** Use `URI` class for safe URL construction, or validate the `redirectUri` at startup:
  ```java
  @PostConstruct
  void validateRedirectUri() {
      if (!redirectUri.endsWith("/")) {
          throw new IllegalStateException("app.oauth2.redirect-uri must end with '/'");
      }
  }
  ```

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack Analysis

**No critical chains identified.** The findings above do not compose into a higher-severity attack path. Finding #1 (missing Permissions-Policy) + a hypothetical XSS would be the most impactful chain, but no XSS vectors were identified (React's default escaping, ESLint `no-danger` rule, no `dangerouslySetInnerHTML` usage).

### Implicit Trust Assumptions

1. **nginx always fronts the API in production:** Security headers (HSTS, CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy) are nginx-only. Spring Security HSTS is explicitly disabled. If the API is accessed directly (port 8080 exposed), these headers are absent. **Mitigation:** Docker Compose network isolation — the API container is on the `backend` internal network, not directly reachable. **Risk: Low.**

2. **SMTP availability for async email:** The `@Async` email sending (Finding #4) assumes the SMTP server is responsive. If SMTP hangs, threads accumulate. **Mitigation:** Docker memory limits (512MB) provide a hard ceiling. Rate limiter constrains registration throughput. **Risk: Low.**

### Defense-in-Depth Assessment

**Improved since audit-4:**
- Cookie `Secure` flag is now configurable, enabling correct behavior in both dev (HTTP) and prod (HTTPS)
- Spring CSP removed, eliminating the confusing dual-header situation
- GPS filtering now covers both `GPS:` and `GPS ` key formats
- Email sending is async, closing the registration timing side-channel

**Remaining gaps:**
- Permissions-Policy header lost during Spring→nginx migration (Finding #1)
- Production nginx missing upload body size limit (Finding #2)
- No bounded thread pool for async operations (Finding #4)

### Deployment Context

- **Docker Compose:** API, PostgreSQL, Redis, MinIO on internal `backend` network. Only nginx (port 80/443) on the `frontend` network. This prevents direct API access from the internet.
- **Production readiness blocker:** Finding #2 (missing `client_max_body_size`) will break photo uploads entirely. This must be fixed before production deployment.

---

## 1. Executive Summary

This post-remediation audit confirms that **all 8 findings from audit-4 have been successfully addressed**. The cookie security, CSP management, GPS filtering, registration timing, and input validation issues are all resolved. The security posture has materially improved.

Two new medium-severity findings were identified during this review:

1. **The `Permissions-Policy` header was removed from Spring Security to migrate management to nginx, but was never added to nginx** (Finding #1). This is a regression introduced during the remediation of audit-4 findings #4/#5 (duplicate CSP headers). The fix requires adding one line to `nginx.prod.conf`.

2. **The production nginx config is missing `client_max_body_size`** (Finding #2), which will cause all photo uploads >1MB to fail with 413. This is a deployment-blocking functional issue. The dev config has this directive but it was never ported to production.

No critical or high-severity vulnerabilities were found. The codebase demonstrates strong security engineering with proper authentication timing equalization, family-based token rotation with replay detection, row-level security at the database layer, and defense-in-depth across all sensitive data flows.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | `Permissions-Policy` header lost in Spring→nginx migration | A05 | Medium | Confirmed | 1 | BLOCK (deploy) |
| 2 | Missing `client_max_body_size` in production nginx | A05 | Medium | Confirmed | 1 | BLOCK (deploy) |
| 3 | CSRF-exempt `/auth/logout` enables forced logout | A01 | Low | Confirmed | 1 | ACCEPT or FIX |
| 4 | `@Async` without bounded thread pool | A05 | Low | Medium | 1 | FIX |
| 5 | OAuth2 redirect URL string concatenation | A01 | Low | Low | 1 | VERIFY |

---

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|---|---|---|
| Critical | 0 | 0 |
| High | 0 | 0 |
| Medium | 2 | −16 |
| Low | 3 | −6 |

**Final SQS:** **78**/100
**Hard gates triggered:** No
**Posture:** **Acceptable** — deploy with remediation commitment. Fix Findings #1 and #2 before production deployment (both are quick wins). Remaining findings can be addressed in the next sprint.

---

## 4. Positive Security Observations

1. **Complete remediation of audit-4 findings.** All 8 findings addressed correctly, with no regressions in the fix implementations themselves. The `@Async` annotation, GPS filter expansion, `@Valid` addition, configurable `cookieSecure`, and CSP consolidation were all implemented cleanly.

2. **CSRF implementation remains exemplary.** Non-overridable `X-XSRF-TOKEN` header in `apiFetch`, XOR BREACH protection via `XorCsrfTokenRequestAttributeHandler`, eager cookie loading via `CsrfCookieFilter`, and `SameSite=Strict` on the CSRF cookie. CSRF is now required on `/auth/login` and `/auth/register` (per recent commit `acf7116cf`).

3. **Refresh token rotation with atomic replay detection.** The Lua-based `GETDEL` script prevents TOCTOU races. Family-based revocation, reverse index for O(1) replay detection, and comprehensive security logging (`log.warn("SECURITY: Refresh token replay detected")`) provide robust token lifecycle management.

4. **Row-Level Security is defense-in-depth at its best.** `RlsAspect` sets PostgreSQL session variable → `assert_user_context()` validates it → RLS policies enforce tenant isolation. The `RlsContextCleanupFilter` at `HIGHEST_PRECEDENCE` guarantees ThreadLocal cleanup in a `finally` block. Even if the application layer has a bug, the database rejects unauthorized queries.

5. **Error handling prevents information leakage.** `server.error.include-stacktrace: never`, `server.error.include-message: never`, `GlobalExceptionHandler` returns generic messages for all exception types, client-side response body truncation to 200 chars, and `management.endpoint.health.show-details: never`.

---

## 5. Prioritized Remediation Roadmap

### 1. Finding #2 — Missing `client_max_body_size` in production nginx
- **Why prioritized:** Breaks core photo upload functionality in production. Zero uploads >1MB will succeed.
- **Effort:** Quick Win — add one directive to `nginx.prod.conf`.
- **Owner:** DevOps

### 2. Finding #1 — `Permissions-Policy` header lost in migration
- **Why prioritized:** Regression from a working state. Header was present in committed Spring Security config, removed during nginx migration, never added to nginx.
- **Effort:** Quick Win — add one `add_header` line to `nginx.prod.conf`.
- **Owner:** DevOps

### 3. Finding #4 — `@Async` without bounded thread pool
- **Why prioritized:** Unbounded thread creation under load, though mitigated by rate limiting and Docker memory limits.
- **Effort:** Quick Win — define a `ThreadPoolTaskExecutor` bean.
- **Owner:** Backend

### 4. Finding #3 — CSRF-exempt logout
- **Why prioritized:** Low impact (availability only), but easy to fix.
- **Effort:** Quick Win — remove `/auth/logout` from CSRF ignore list.
- **Owner:** Backend

### 5. Finding #5 — OAuth2 redirect URL construction
- **Why prioritized:** Low confidence, configuration-dependent. Default is safe.
- **Effort:** Quick Win — add startup validation for trailing slash.
- **Owner:** Backend
