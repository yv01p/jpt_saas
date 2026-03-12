# Security Audit — Phase 4: React Frontend (Audit #3)

**Auditor:** LCSA (automated)
**Date:** 2026-03-11
**Scope:** All Phase 4 implemented code — frontend (`frontend/src/`) and backend additions (`UserController`, `PhotoController` keyword endpoints, `PhotoMetadataService`, `SecurityConfig`, `GlobalExceptionHandler`, `application.yml`).
**Focus:** Implementation review only (not plan review).

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Entry Points:**
- `PhotoController` — 9 endpoints: upload, list, get, status, delete, trash, restore, keyword list/add/remove
- `UserController` — 2 endpoints: GET/PATCH `/api/users/me`
- `PhotoMetadataService` — called via metadata endpoint (controller not in scope but service is)
- Frontend — `LoginPage`, `RegisterPage`, 7 authenticated pages, `apiFetch` API client

**Trust Boundaries:**
- Browser → frontend (user input via forms, URL params, drag-and-drop files)
- Frontend → backend (JSON via `apiFetch` with CSRF token + credentials)
- Backend → PostgreSQL (JPA/Hibernate with RLS interceptor)
- Backend → MinIO (pre-signed URL generation)
- Backend → Redis (session/rate-limiting)

**Authentication/Authorization Architecture:**
- JWT authentication filter (`JwtAuthenticationFilter`) + OAuth2 login
- CSRF via `CookieCsrfTokenRepository` (HttpOnly=false for SPA)
- Rate limiting filter (`RateLimitFilter`)
- Row-Level Security interceptor (`RlsInterceptor`)
- `@AuthenticationPrincipal UUID userId` on all controller methods

**Sensitive Data Flows:**
- Credentials: email/password on login/register (frontend → backend)
- GPS coordinates: server-side filtering in `PhotoMetadataService` (SA4-F1), client-side DOM suppression in `MetadataPanel`
- Pre-signed URLs: generated server-side, returned in `PhotoResponse`
- EXIF metadata: preserved without key transformation (SA4-F6)
- Secrets: all in env vars, no defaults for critical ones

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: `ResponseStatusException` Swallowed by GlobalExceptionHandler — Returns 500 Instead of Intended Status

**Vulnerability:** Incorrect Error Handling — OWASP A09 (Security Logging and Monitoring Failures)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/controller/UserController.java`, Lines: 32, 40
- File: `api/src/main/java/org/jphototagger/api/service/PhotoMetadataService.java`, Lines: 38, 43
- Related: `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java`, Lines: 124–129 (catch-all)

**Risk & Exploit Path:**
`UserController.getCurrentUser()` and `updateCurrentUser()` throw `ResponseStatusException(HttpStatus.NOT_FOUND)` when the user is not found. `PhotoMetadataService.getMetadata()` throws `ResponseStatusException(HttpStatus.NOT_FOUND)` for missing user and missing metadata. Spring's `ExceptionHandlerExceptionResolver` evaluates `@ExceptionHandler` methods before default resolvers. The `@ExceptionHandler(Exception.class)` catch-all in `GlobalExceptionHandler` catches `ResponseStatusException` (which extends `RuntimeException` → `Exception`) and returns HTTP 500 with `"An internal error occurred"` — suppressing the intended 404.

**Impact:**
1. **Information differential:** An attacker probing `/api/users/me` without auth gets 401; with auth but deleted user gets 500 — the 500 distinguishes "user existed but was deleted" from "endpoint doesn't exist."
2. **Error log pollution:** Every `ResponseStatusException` is logged at ERROR level as "Unhandled exception," obscuring real errors in monitoring.
3. **Functional bug:** Frontend receives 500 instead of 404 for missing metadata (e.g., photo still processing), triggering the global 401-or-bust error handler rather than a clean error state.

**Evidence / Trace:**

```java
// UserController.java:32 — Source
User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));  // ← VULNERABLE

// GlobalExceptionHandler.java:124-129 — Sink
@ExceptionHandler(Exception.class)  // ← catches ResponseStatusException
public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);  // ← logged as ERROR
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("An internal error occurred", 500));  // ← 500 not 404
}
```

**Remediation:**
- **Primary fix:** Replace all `ResponseStatusException` with `EntityNotFoundException` in `UserController` and `PhotoMetadataService`, matching the established codebase pattern:
  ```java
  // Before:
  .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  // After:
  .orElseThrow(() -> new EntityNotFoundException("User not found"));
  ```
- **Architectural improvement:** Add `@ExceptionHandler(ResponseStatusException.class)` to `GlobalExceptionHandler` that extracts and returns the intended status code, as a safety net for future usage.

**References:**
- CWE-755: Improper Handling of Exceptional Conditions
- Handoff §3 Dead Ends documents this exact pattern for `PhotoController` keyword endpoints (which were correctly fixed to use `EntityNotFoundException`)

---

### Finding #2: CSRF Cookie Missing `SameSite` and `Secure` Attributes (SA4-F4 Not Implemented)

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`, Line: 57

**Risk & Exploit Path:**
The CSRF cookie is created via `CookieCsrfTokenRepository.withHttpOnlyFalse()` without configuring `SameSite=Strict` or `Secure`. The plan (SA4-F4) specifies `repo.setCookieSameSite("Strict")` and reliance on `ForwardedHeaderFilter` for `Secure`. Without `SameSite`, the CSRF cookie is sent on cross-origin requests in some browsers, weakening CSRF protection. Without `Secure`, the cookie can be transmitted over HTTP if TLS terminates unexpectedly.

The completion review marks this as a deployment-time task (nginx/proxy), but defense-in-depth requires setting these at the application level.

**Evidence / Trace:**

```java
// SecurityConfig.java:57
.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
// ← No .setCookieSameSite("Strict") or Secure flag configuration
```

**Remediation:**
- **Primary fix:**
  ```java
  var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
  csrfRepo.setCookieCustomizer(c -> c.sameSite("Strict").secure(true));
  // Then use csrfRepo in .csrfTokenRepository(csrfRepo)
  ```
- **Defense-in-depth:** Also set `server.forward-headers-strategy: framework` in `application.yml` so `Secure` is auto-detected behind TLS-terminating proxy.

**References:**
- CWE-1275: Sensitive Cookie with Improper SameSite Attribute

---

### Finding #3: CSRF Token Overridable via Header Spread Order in `apiFetch`

**Vulnerability:** Improper CSRF Protection — OWASP A01 (Broken Access Control)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** High

**Location:**
- File: `frontend/src/api/client.ts`, Lines: 57–63

**Risk & Exploit Path:**
`apiFetch` sets the `X-XSRF-TOKEN` header first, then spreads caller-provided headers after it. If any call site passes `headers: { 'X-XSRF-TOKEN': 'attacker-value' }`, it would override the legitimate CSRF token. No current call site does this, making exploitation theoretical. However, this is a defense-in-depth violation — the CSRF token should be non-overridable by design.

**Evidence / Trace:**

```typescript
// client.ts:57-63
headers: {
  'X-XSRF-TOKEN': csrfToken,                    // ← Set first
  ...(processedOptions?.headers instanceof Headers
    ? Object.fromEntries(processedOptions.headers.entries())
    : processedOptions?.headers),                // ← Spread AFTER, can override
},
```

**Remediation:**
- **Primary fix:** Spread caller headers first, then set CSRF token last:
  ```typescript
  headers: {
    ...(processedOptions?.headers instanceof Headers
      ? Object.fromEntries(processedOptions.headers.entries())
      : processedOptions?.headers),
    'X-XSRF-TOKEN': csrfToken,  // Always wins
  },
  ```

---

### Finding #4: Login CSRF — `/auth/login` Exempt from CSRF Protection

**Vulnerability:** Cross-Site Request Forgery on Login — OWASP A01
**Severity:** Low
**Confidence:** High
**Attack Complexity:** High

**Location:**
- File: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java`, Line: 59

**Risk & Exploit Path:**
`/auth/login` is in the CSRF-exempt list. An attacker can craft a page that auto-submits a login form with the attacker's credentials, forcing the victim's browser to authenticate as the attacker. The victim then uses the application believing they are logged in as themselves, but any data they upload goes to the attacker's account. This is a "login CSRF" attack.

Exploitation requires social engineering (victim must visit attacker's page) and the attack window is narrow (victim must not notice they're in the wrong account). Impact is limited to data the victim creates during the hijacked session.

**Evidence / Trace:**

```java
// SecurityConfig.java:59
.ignoringRequestMatchers("/auth/login", "/auth/register", "/auth/refresh", "/auth/logout",
        "/login/oauth2/code/*")
// ← /auth/login exempt from CSRF
```

**Remediation:**
- **Primary fix:** Remove `/auth/login` from the CSRF exemption list. The frontend already sends the CSRF token via `apiFetch`, so login requests will include it naturally.
- **Note:** `/auth/register` should also be reconsidered — registration CSRF could create spam accounts.

---

### Finding #5: No Application-Level Security Response Headers

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java` — entire file (headers not configured)

**Risk & Exploit Path:**
Spring Security's default header configuration is not explicitly set. While Spring Security adds some headers by default (`X-Content-Type-Options`, `X-Frame-Options`, `Cache-Control`), `Content-Security-Policy`, `Strict-Transport-Security`, and `Permissions-Policy` are not configured. The plan defers these to nginx (SA4-F7), but if nginx is misconfigured, bypassed, or the app is accessed directly, there is no defense-in-depth.

**Remediation:**
- **Primary fix:** Add explicit header configuration in `SecurityConfig`:
  ```java
  .headers(headers -> headers
      .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; img-src 'self' blob: data: https://*.minio.*; style-src 'self' 'unsafe-inline'"))
      .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
      .permissionsPolicy(pp -> pp.policy("camera=(), microphone=(), geolocation=()")))
  ```
- **Defense-in-depth:** Keep nginx headers as the primary layer; Spring Security headers as fallback.

---

## Pass 3: Cross-Cutting & Compositional Analysis

**Chained attacks:** No critical chain identified. Finding #3 (CSRF override) + Finding #4 (login CSRF exempt) don't combine because login doesn't go through `apiFetch` in an attack scenario (attacker uses their own form).

**Implicit trust assumptions:**
- Frontend trusts that `PhotoResponse.thumbnailUrl` and `originalUrl` are safe to render in `<img src>`. Since these are pre-signed MinIO URLs generated server-side, this is correct.
- `MetadataPanel` trusts that `exifData` values are safe for React text node rendering. React auto-escapes text nodes, so this is safe. The `react/no-danger: 'error'` ESLint rule prevents future regressions.

**Defense-in-depth gaps:**
- GPS filtering: Server-side (`PhotoMetadataService.withoutGps()`) + client-side (`MetadataPanel` conditional render) — dual-layer, good.
- Error truncation: Server-side (`include-stacktrace/message: never`) + client-side (200-char truncation) — dual-layer, good.
- CSRF: Cookie + header token — standard double-submit pattern, weakened slightly by Findings #2–4.

**Deployment context:**
- Vite dev proxy only active in development — no production risk.
- `application.yml` uses env vars with no dangerous defaults for secrets. `DB_URL` has a localhost default which is safe.

---

## 1. Executive Summary

The Phase 4 implementation demonstrates strong security fundamentals: consistent use of `@AuthenticationPrincipal` for tenant isolation, Row-Level Security at the database layer, dual-layer GPS filtering (SA4-F1), error body truncation (SA4-F3), environment-variable-only secrets (SA4-F5), EXIF key preservation (SA4-F6), and `react/no-danger: 'error'` ESLint enforcement (SA2-F1). The attack surface is well-constrained.

The most significant finding is a **medium-severity error handling inconsistency** where `UserController` and `PhotoMetadataService` use `ResponseStatusException` — which the `GlobalExceptionHandler` catch-all silently converts to HTTP 500. This is both a functional bug (frontend receives unexpected error codes) and a minor information leak (500 vs 404 distinguishes internal states). The fix is straightforward: replace with `EntityNotFoundException`, matching the pattern already used correctly in `PhotoController`.

The remaining findings are low-severity defense-in-depth improvements: CSRF cookie attributes (SA4-F4), CSRF header spread order, login CSRF exemption, and application-level security headers (SA4-F7). None represent exploitable vulnerabilities in the current deployment, but each strengthens the security posture for edge cases and future changes.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | `ResponseStatusException` swallowed → 500 | A09 | Medium | Confirmed | 4 (2 in UserController, 2 in PhotoMetadataService) | FIX |
| 2 | CSRF cookie missing `SameSite`/`Secure` | A05 | Low | High | 1 | FIX |
| 3 | CSRF header overridable via spread order | A01 | Low | High | 1 | FIX |
| 4 | Login CSRF — `/auth/login` CSRF-exempt | A01 | Low | High | 1 | REVIEW |
| 5 | No application-level security headers | A05 | Low | High | 1 | REVIEW |

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical        | 0     | 0         |
| High            | 0     | 0         |
| Medium          | 1     | −8 (grouped: 4 instances) |
| Low             | 4     | −8 (4 × −2) |

**Final SQS:** 84/100
**Hard gates triggered:** No
**Posture:** Acceptable — deploy with remediation commitment and timeline

## 4. Positive Security Observations

1. **Consistent tenant isolation:** Every controller endpoint uses `@AuthenticationPrincipal UUID userId` and validates ownership before returning data. The RLS interceptor provides a database-layer safety net.
2. **Dual-layer GPS filtering (SA4-F1):** `PhotoMetadataService.withoutGps()` strips GPS server-side; `MetadataPanel` conditionally renders GPS sections client-side. Even if one layer fails, the other protects.
3. **XSS prevention by design:** `react/no-danger: 'error'` ESLint rule prevents `dangerouslySetInnerHTML`. All user data renders as React text nodes (auto-escaped). EXIF values are rendered via `<dd>{value}</dd>` — safe.
4. **Secrets management:** All sensitive values (`JWT_SECRET`, `DB_PASS`, `REDIS_PASSWORD`, `MINIO_SECRET_KEY`) sourced from environment variables with no fallback defaults. Missing vars cause startup failure.
5. **Error information minimization:** Server-side `include-stacktrace: never` + `include-message: never`, client-side 200-character truncation, and generic catch-all error messages prevent internal detail leakage.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Why Prioritized | Effort | Owner |
|----------|---------|-----------------|--------|-------|
| 1 | #1 — `ResponseStatusException` → 500 | Confirmed functional bug + info leak; 4 call sites affected | Quick Win | Backend |
| 2 | #3 — CSRF header spread order | One-line fix, eliminates entire class of potential CSRF bypass | Quick Win | Frontend |
| 3 | #2 — CSRF cookie `SameSite`/`Secure` | Implements SA4-F4 requirement from plan; small config change | Quick Win | Backend |
| 4 | #4 — Login CSRF exemption | Requires testing that login works with CSRF enabled; low risk if deferred | Moderate | Backend |
| 5 | #5 — Application-level security headers | CSP policy requires careful tuning to avoid breaking functionality | Moderate | Backend + DevOps |
