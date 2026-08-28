# Security Audit Report — Phase 4: React Frontend

**Audited document:** `docs/plans/2026-02-25-saas-conversion-phase-4.md` (v8.0)
**Audit date:** 2026-03-12
**Audit number:** SA4-6
**Scope:** Full implementation of Phase 4 (Tasks 4.0–4.9) — backend additions, React frontend, API client, authentication flow, metadata display, upload, and infrastructure.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points

| Endpoint | Method | Auth | CSRF | Rate Limit |
|----------|--------|------|------|------------|
| `/auth/register` | POST | Public | Yes | Auth (20/hr/IP) |
| `/auth/login` | POST | Public | Yes | Auth (20/hr/IP) |
| `/auth/refresh` | POST | Public | **Exempt** | Auth (20/hr/IP) |
| `/auth/logout` | POST | Authenticated | Yes | General (1000/hr) |
| `/api/users/me` | GET | Authenticated | N/A (safe method) | General |
| `/api/users/me` | PATCH | Authenticated | Yes | General |
| `/photos/upload` | POST | Authenticated | Yes | **General (see Finding 1)** |
| `/photos` | GET | Authenticated | N/A | General |
| `/photos/{id}` | GET | Authenticated | N/A | General |
| `/photos/{id}/status` | GET | Authenticated | N/A | General |
| `/photos/{id}` | DELETE | Authenticated | Yes | General |
| `/photos/trash` | GET | Authenticated | N/A | General |
| `/photos/{id}/restore` | POST | Authenticated | Yes | General |
| `/photos/{id}/keywords/*` | GET/POST/DELETE | Authenticated | Yes (mutating) | General |
| `/photos/{id}/metadata` | GET | Authenticated | N/A | General |
| OAuth2 (`/login/oauth2/code/*`) | GET | Public | **Exempt** | N/A |

### Trust Boundaries

```
User → nginx (TLS termination, security headers, burst limiting)
     → Spring Boot API (JWT auth, CSRF, RLS, rate limiting)
       → PostgreSQL (RLS policies, parameterized queries)
       → Redis (refresh tokens, rate limit buckets, job queue)
       → MinIO (pre-signed URLs, no direct user access)

Frontend (React SPA) → apiFetch (CSRF header, key transforms, 401 handler)
                      → Vite proxy (dev) / nginx reverse proxy (prod)
```

### Authentication Architecture

- **JWT**: 15-min httpOnly cookie, HS256 with validated 256+ bit secret
- **Refresh token**: 30-day httpOnly cookie, SHA-256 hashed in Redis, family-based rotation with replay detection
- **CSRF**: Non-httpOnly `XSRF-TOKEN` cookie (SameSite=Strict) + `X-XSRF-TOKEN` header via XorCsrfTokenRequestAttributeHandler
- **OAuth2**: Google OIDC with server-side redirect flow; no auto-merge with password accounts
- **RLS**: PostgreSQL row-level security via AOP aspect setting `app.current_user_id` session variable
- **Rate limiting**: Bucket4j + Redis with three tiers (upload, general, auth)

### Sensitive Data Flows

- **Credentials**: Password → bcrypt(cost=12) → PostgreSQL (via jpt_auth role with BYPASSRLS)
- **GPS coordinates**: EXIF extraction → `photo_metadata.exif_data` JSONB → server-side filtering by `user.showGps` → API response → frontend DOM suppression (defence-in-depth)
- **Pre-signed URLs**: MinIO presign (15 min thumbnails, 1 hour originals) → included in photo list responses → rendered as `<img src>`
- **Refresh tokens**: SecureRandom(32 bytes) → SHA-256 hash → Redis with TTL

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: Upload Rate Limit Not Applied — Regex Mismatch

**Vulnerability:** Rate Limit Bypass — A08 (Security Misconfiguration)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/security/RateLimitFilter.java`, Lines 141–145

**Risk & Exploit Path:**
The upload rate limit (100 uploads/hour/user) is intended to constrain resource consumption from photo uploads. However, the regex used to identify upload requests does not match the actual upload endpoint, so ALL uploads fall through to the general rate limit (1000 requests/hour), effectively granting users 10x the intended upload capacity.

An attacker with valid credentials could upload 1000 photos per hour instead of 100, consuming storage quota rapidly and generating excessive worker processing load.

**Evidence / Trace:**

```java
// RateLimitFilter.java:141-145
private boolean isUploadRequest(HttpServletRequest request) {
    String method = request.getMethod();
    String path = request.getRequestURI();
    return "POST".equals(method) && path.matches(".*/photos/?$");  // ← VULNERABLE
}
```

The regex `.*/photos/?$` matches paths ending in `/photos` or `/photos/`. The actual upload endpoint is `POST /photos/upload` (from `PhotoController.java:47-48`):

```java
@PostMapping("/upload")  // Full path: /photos/upload
public ResponseEntity<PhotoResponse> uploadPhoto(...) { ... }
```

After nginx strips the `/api/` prefix (`proxy_pass http://api:8080/;`), the request URI is `/photos/upload`, which does NOT match `.*/photos/?$`.

**Remediation:**
- Primary fix: Change the regex to match the actual upload path:
  ```java
  return "POST".equals(method) && path.matches(".*/photos/upload/?$");
  ```
- Defence-in-depth: The quota check in `insertPhotoWithQuotaCheck` still enforces storage limits, and the general rate limit (1000/hr) still applies. The upload-specific limit is an additional constraint for resource protection.

**References:**
- CWE-799: Improper Control of Interaction Frequency

---

### Finding #2: GPS Data Leakage via Unfiltered IPTC and XMP Metadata

**Vulnerability:** Sensitive Data Exposure — A02 (Cryptographic Failures / Data Exposure)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java`, Lines 38–46
- Related: `api/src/main/java/org/jphototagger/api/service/PhotoMetadataService.java:48-49`

**Risk & Exploit Path:**
When `showGps=false`, the `withoutGps()` method correctly strips GPS fields from `exifData` and nullifies `gpsLatitude`/`gpsLongitude`. However, GPS location data can also be present in IPTC metadata (e.g., `IPTC:City`, `IPTC:Sub-location`, `IPTC:Province-State`, `IPTC:Country-Primary Location Code`) and XMP metadata (e.g., `XMP:GPSLatitude`, `exif:GPSLatitude`, `photoshop:City`). These maps are passed through to the response unfiltered.

A user who has opted out of GPS display may still have their photo's location data visible through the IPTC or XMP metadata sections. An attacker (or the user themselves) viewing the API response via browser DevTools or the MetadataPanel's IPTC/XMP sections would see location data despite the user's preference.

**Evidence / Trace:**

```java
// PhotoMetadataResponse.java:38-46
public PhotoMetadataResponse withoutGps() {
    Map<String, Object> filteredExif = exifData == null ? null :
            exifData.entrySet().stream()
                    .filter(e -> {
                        String lower = e.getKey().toLowerCase();
                        return !lower.startsWith("gps:") && !lower.startsWith("gps ");
                    })
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    return new PhotoMetadataResponse(photoId, null, null, filteredExif, iptcData, xmpData, extractedAt);
    //                                                                  ^^^^^^^^  ^^^^^^^
    //                                                                  ← NOT FILTERED
}
```

The `iptcData` and `xmpData` maps are returned as-is. Common GPS-related keys in these formats include:
- XMP: `exif:GPSLatitude`, `exif:GPSLongitude`, `exif:GPSAltitude`, `XMP:GPSLatitude`
- IPTC: `IPTC:Sub-location`, `IPTC:City`, `IPTC:Province-State`, `IPTC:Country-Primary Location Code`

**Remediation:**
- Primary fix: Extend `withoutGps()` to also filter GPS-related keys from `iptcData` and `xmpData`:
  ```java
  public PhotoMetadataResponse withoutGps() {
      Map<String, Object> filteredExif = filterGpsKeys(exifData);
      Map<String, Object> filteredIptc = filterLocationKeys(iptcData);
      Map<String, Object> filteredXmp = filterGpsKeys(xmpData);
      return new PhotoMetadataResponse(photoId, null, null, filteredExif, filteredIptc, filteredXmp, extractedAt);
  }
  ```
  Where `filterGpsKeys` strips keys containing `gps` (case-insensitive) and `filterLocationKeys` strips IPTC location fields (`Sub-location`, `City`, `Province-State`, `Country-Primary Location Code`, `Country-Primary Location Name`).
- Add tests verifying XMP/IPTC GPS data is stripped when `showGps=false`.

**References:**
- SA4-F1 (plan requirement): "GPS data must be filtered server-side"
- CWE-200: Exposure of Sensitive Information to an Unauthorized Actor

---

### Finding #3: CSP Allows `style-src 'unsafe-inline'`

**Vulnerability:** Weakened XSS Mitigation — A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `nginx.prod.conf`, Line 78

**Risk & Exploit Path:**
The Content-Security-Policy includes `style-src 'self' 'unsafe-inline'`, which allows inline `<style>` tags and `style` attributes. If an attacker achieves a stored XSS injection (currently mitigated by React text nodes, ESLint `no-danger` rule, and server-side Jsoup sanitization), they could use CSS injection to exfiltrate data (e.g., via `background-image: url(...)` with attribute selectors) without being blocked by CSP.

This is explicitly noted in the nginx config as required for CSS-in-JS. Exploitation requires bypassing multiple existing XSS defences first, making practical risk low.

**Evidence / Trace:**

```nginx
# nginx.prod.conf:78
add_header Content-Security-Policy "default-src 'self'; img-src 'self' data: blob: https://minio.yourdomain.com; style-src 'self' 'unsafe-inline'; connect-src 'self'; font-src 'self'; frame-ancestors 'none';" always;
```

**Remediation:**
- Primary fix: Migrate to CSS-in-JS solution that supports nonces or hashes, then replace `'unsafe-inline'` with `'nonce-{random}'` per response.
- Interim: Accepted risk — documented in nginx config. Review after frontend CSS architecture stabilizes.

**References:**
- CWE-79: Improper Neutralization of Input During Web Page Generation

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack Analysis

No critical chains identified. The three findings do not compose into a higher-severity path:
- Finding 1 (rate limit bypass) could amplify resource exhaustion but is bounded by storage quota.
- Finding 2 (GPS leakage) is limited to users who have GPS data in IPTC/XMP formats specifically.
- Finding 3 (CSP) requires a preceding XSS, which is well-defended against.

### Implicit Trust Assumptions

- **Frontend trusts API key transforms:** `camelizeKeys` correctly skips `exifData` (SA4-F6), preventing EXIF key corruption.
- **RLS as defence-in-depth:** Even if application-layer ownership checks fail, PostgreSQL RLS policies prevent cross-user data access. The `assert_user_context()` function guards against nil-UUID connections.
- **nginx as sole TLS terminator:** Spring Boot disables HSTS (`headers.httpStrictTransportSecurity.disable()`) and defers all security headers to nginx. This is correct for the architecture but means a misconfigured nginx would remove all header-based protections.

### Defence-in-Depth Assessment

Strong layering observed:
1. **GPS filtering:** Server-side stripping (primary) + frontend DOM suppression (secondary) + ESLint enforcement (tertiary)
2. **XSS prevention:** React text nodes (primary) + ESLint `no-danger` rule (secondary) + Jsoup sanitization at ingestion (tertiary) + CSP (quaternary, weakened by unsafe-inline)
3. **Authentication:** JWT validation (primary) + CSRF (secondary) + RLS (tertiary) + rate limiting (quaternary)
4. **Upload safety:** MIME type validation via Tika (primary) + filename sanitization via Jsoup (secondary) + content hash dedup (tertiary)

---

## 1. Executive Summary

The Phase 4 implementation demonstrates strong security engineering across the full stack. The authentication system is particularly well-designed with JWT + refresh token rotation, replay detection via token families, timing side-channel mitigations, and account lockout. Row-Level Security provides a robust defence-in-depth layer. The frontend correctly avoids all common XSS patterns and enforces this via ESLint rules.

Two medium-severity issues were identified: (1) the upload-specific rate limit never activates due to a regex mismatch with the actual endpoint path, and (2) GPS location data can leak through IPTC and XMP metadata even when the user has opted out of GPS display. Both are straightforward fixes. One low-severity CSP weakness (`style-src 'unsafe-inline'`) is acknowledged tech debt.

The codebase is ready for deployment with a commitment to address Findings 1 and 2 before or shortly after launch. Neither finding enables authentication bypass or cross-user data access.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | Upload rate limit regex mismatch | A05/A08 | Medium | Confirmed | 1 | FIX |
| 2 | GPS data leakage via IPTC/XMP | A02 | Medium | High | 1 | FIX |
| 3 | CSP `style-src 'unsafe-inline'` | A05 | Low | Confirmed | 1 | ACCEPT |

---

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical | 0 | 0 |
| High | 0 | 0 |
| Medium | 2 | -16 |
| Low | 1 | -2 |

**Final SQS:** 82/100
**Hard gates triggered:** No
**Posture:** Acceptable — deploy with remediation commitment and timeline for Findings 1 and 2.

---

## 4. Positive Security Observations

1. **Refresh token family rotation with atomic replay detection.** The Lua-scripted GETDEL prevents TOCTOU races, and family-based revocation ensures stolen tokens invalidate the entire token chain. This is a textbook implementation of the refresh token rotation pattern.

2. **Timing side-channel mitigation in authentication.** Both the registration (dummy bcrypt on duplicate) and login (bcrypt check for non-existent users, generic error messages) paths are equalized to prevent user enumeration.

3. **Multi-layer GPS data protection.** Server-side filtering in `PhotoMetadataService`, frontend DOM suppression in `MetadataPanel`, and ESLint enforcement — three independent layers, each sufficient on its own (with the caveat in Finding 2).

4. **CSRF token non-overridable in `apiFetch`.** The `X-XSRF-TOKEN` header is placed after the caller-provided headers spread, preventing accidental or malicious override. This was identified and fixed as a dedicated commit.

5. **Comprehensive error handling with zero information leakage.** `GlobalExceptionHandler` returns generic messages for all exception types, `application.yml` sets `include-stacktrace: never` and `include-message: never`, and the frontend truncates error bodies to 200 characters.

---

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Why Prioritized | Effort | Owner |
|----------|---------|-----------------|--------|-------|
| 1 | #1 — Upload rate limit regex | Upload-specific rate limit is completely non-functional; 10x more permissive than intended. Single-line regex fix. | Quick Win | Backend |
| 2 | #2 — GPS leakage in IPTC/XMP | Violates SA4-F1 privacy requirement. Users who opted out of GPS may still have location exposed via alternative metadata formats. | Moderate | Backend |
| 3 | #3 — CSP unsafe-inline | Requires frontend CSS architecture change. Low urgency due to multiple XSS defences. | Significant Refactor | Frontend |
