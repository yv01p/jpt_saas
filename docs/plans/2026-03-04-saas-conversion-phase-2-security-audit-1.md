# Security Audit: Phase 2 — Backend API (Auth, Security, REST Endpoints)

**Audited:** `docs/plans/2026-02-25-saas-conversion-phase-2.md` (v3.0)
**Baseline:** `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0)
**Prior reviews:** Critical Implementation Reviews v1, v2, v3
**Date:** 2026-03-04
**Auditor:** Lead Cyber-Security Auditor (automated)

---

## Scope & Assumptions

**In scope:** All code, configuration, SQL migrations, and architecture specified in Phase 2 plan v3.0 — authentication, authorization, JWT, OAuth2, CSRF, RLS, rate limiting, REST endpoints, error handling, and their interaction with existing infrastructure (Docker Compose, Flyway V1–V3, application.yml).

**Out of scope:** Phase 3+ features (upload pipeline, MinIO pre-signed URLs, worker image processing), Nginx configuration (audited separately), frontend SPA code (Phase 4).

**Assumptions:**
- The critical implementation review v3 findings (C1–C4, M1–M6) will be fixed before implementation.
- The plan represents the intended implementation — actual code does not exist yet (plan-level audit).
- Infrastructure security (Docker, network isolation, backup) was audited in prior phases; only Phase 2 additions are re-examined here.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points

| Entry Point | Auth Required | CSRF Required | Rate Limited |
|---|---|---|---|
| `POST /auth/register` | No | No (exempt) | Nginx IP-based (5r/m) |
| `POST /auth/login` | No | No (exempt) | Nginx IP-based (10r/m) |
| `POST /auth/refresh` | Refresh token cookie | Yes | Nginx IP-based |
| `POST /auth/logout` | JWT cookie | Yes | Bucket4j per-user |
| `GET /photos` | JWT cookie | No (GET) | Bucket4j per-user |
| `GET /photos/{id}` | JWT cookie | No (GET) | Bucket4j per-user |
| `GET /photos/{id}/status` | JWT cookie | No (GET) | Bucket4j per-user |
| `DELETE /photos/{id}` | JWT cookie | Yes | Bucket4j per-user |
| `GET /photos/trash` | JWT cookie | No (GET) | Bucket4j per-user |
| `POST /photos/{id}/restore` | JWT cookie | Yes | Bucket4j per-user |
| `GET /keywords` | JWT cookie | No (GET) | Bucket4j per-user |
| `GET /keywords/{id}/subtree` | JWT cookie | No (GET) | Bucket4j per-user |
| `POST /albums/{id}/photos/{photoId}` | JWT cookie | Yes | Bucket4j per-user |
| `GET /search` | JWT cookie | No (GET) | Bucket4j per-user |
| CRUD `/saved-searches` | JWT cookie | Yes (mutating) | Bucket4j per-user |
| `GET /actuator/health` | No | No | None specified |

### Trust Boundaries

1. **Internet → Nginx** (TLS termination, IP rate limiting, security headers)
2. **Nginx → Spring Boot API** (JWT validation, CSRF validation, user context extraction)
3. **Spring Boot → PostgreSQL** (RLS enforcement via `SET LOCAL`, parameterized queries via JPA)
4. **Spring Boot → Redis** (Refresh token storage, rate limit buckets)
5. **OAuth2 Providers → Spring Boot** (Token exchange, user info claims)

### Sensitive Data Flows

- **Passwords:** Client → HTTPS → AuthController → bcrypt hash → PostgreSQL `users.password_hash`
- **JWT secret:** Environment variable → JwtService → HS256 HMAC signing
- **Refresh tokens:** Generated (SecureRandom) → SHA-256 hash → Redis → plaintext in httpOnly cookie
- **Email tokens:** Generated → SHA-256 hash → PostgreSQL `email_tokens.token_hash` → plaintext in email
- **OAuth2 tokens:** Provider → Spring Security → exchanged for JWT (provider token discarded)
- **User IDs:** JWT claim → RlsContext ThreadLocal → PostgreSQL `app.current_user_id` → RLS filter

### Authentication Architecture

- **Primary:** JWT (HS256) in httpOnly cookie, 15-minute expiry
- **Refresh:** 256-bit random token in httpOnly cookie, 30-day TTL, stored in Redis, rotated on use
- **OAuth2:** Google/GitHub via Spring Security OAuth2 Client, no auto-merge by email
- **Lockout:** 5 failures → 15-minute lock, timing-safe (always performs bcrypt)

### Authorization Architecture

- **Application layer:** Service methods check `entity.userId == authenticatedUserId`
- **Database layer (defense-in-depth):** PostgreSQL RLS policies filter all queries by `app.current_user_id`
- **Auth bypass:** Dedicated `jpt_auth` role with `BYPASSRLS` for login/register/email verification
- **Worker bypass:** `worker_db_user` with column-level grants, no RLS (intentional — processes all users' photos)

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: RlsAspect Uses `SET LOCAL` Instead of `set_config()` — SQL Injection via String Interpolation Risk

**Vulnerability:** SQL Injection — OWASP A03 (Injection)
**Severity:** Critical
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: Plan Task 2.4, Step 6 — `RlsAspect.java` code block (lines 438–454)

**Risk & Exploit Path:**

The plan specifies:
```java
em.createNativeQuery("SET LOCAL app.current_user_id = :id")
  .setParameter("id", userId.toString())
  .executeUpdate();
```

Critical Review v3 identified that `SET LOCAL` does not accept parameterized queries (C1) and recommended `set_config()`. **However, the plan document itself (v3.0) was NOT updated to incorporate this fix** — it still shows the `SET LOCAL` syntax in the code block at lines 447–449. If implemented as written, two outcomes are possible:

1. **If the implementer uses parameterized query as shown:** Runtime failure (`ERROR: syntax error at or near "$1"`). Not a security issue — it's a crash.
2. **If the implementer falls back to string concatenation to make it work:** `"SET LOCAL app.current_user_id = '" + userId + "'"` — this is exploitable if `userId` is ever sourced from untrusted input before UUID parsing. While `UUID.toString()` is safe, the pattern establishes a dangerous precedent.

The plan should specify the `set_config()` function call explicitly in the code block to prevent implementers from resorting to string concatenation.

**Evidence / Trace:**

```java
// Plan v3.0, Task 2.4, Step 6:
em.createNativeQuery("SET LOCAL app.current_user_id = :id")    // ← WILL FAIL at runtime
  .setParameter("id", userId.toString())
  .executeUpdate();
```

**Remediation:**
- **Primary fix:** Update plan code block to use `set_config()`:
  ```java
  em.createNativeQuery("SELECT set_config('app.current_user_id', :id, true)")
    .setParameter("id", userId.toString())
    .getSingleResult();
  ```
- **Defense-in-depth:** Add a comment in the plan explicitly warning against string concatenation for `SET LOCAL`.

**References:**
- CWE-89: SQL Injection
- Critical Review v3, Finding C1

---

### Finding #2: RlsAspect `@Order` Undefined — Potential Cross-Tenant Data Leak

**Vulnerability:** Broken Access Control — OWASP A01 (Broken Access Control)
**Severity:** Critical
**Confidence:** High
**Attack Complexity:** Low (non-deterministic, but guaranteed under load)

**Location:**
- File: Plan Task 2.4, Step 6 — `RlsAspect.java` (lines 439–454)
- Related: `SecurityConfig.java` (no `@EnableTransactionManagement(order=...)` specified)

**Risk & Exploit Path:**

The `RlsAspect` has no `@Order` annotation. Spring's `TransactionInterceptor` also defaults to `Ordered.LOWEST_PRECEDENCE`. When two aspects share the same order:

1. Spring does not guarantee execution order.
2. If `RlsAspect` fires before `TransactionInterceptor` opens the transaction, `set_config(..., true)` executes outside a transaction.
3. PostgreSQL treats `is_local=true` without an active transaction as session-scoped — equivalent to `SET` (not `SET LOCAL`).
4. The `app.current_user_id` value persists on the connection after commit.
5. HikariCP returns the connection to the pool with User A's ID still active.
6. User B's next request reuses the connection and inherits User A's RLS context.
7. **User B sees User A's photos, albums, keywords — full cross-tenant data access.**

This is a **race condition** that may not manifest in development but will occur in production under concurrent load.

**Evidence / Trace:**

```java
// RlsAspect — no @Order annotation
@Aspect @Component
public class RlsAspect {
    @Before("@annotation(org.springframework.transaction.annotation.Transactional)")
    public void setRlsContext() { ... }   // ← MAY FIRE BEFORE TRANSACTION OPENS
}

// connection-init-sql resets to nil UUID on connection creation, but NOT on pool return
// hikari:
//   connection-init-sql: "SET app.current_user_id = '00000000-...'"
```

**Remediation:**
- **Primary fix:** Add explicit ordering:
  ```java
  @EnableTransactionManagement(order = 0)  // on SecurityConfig or any @Configuration

  @Aspect @Component @Order(1)  // fires inside the transaction
  public class RlsAspect { ... }
  ```
- **Defense-in-depth:** Add HikariCP `connectionReturnInterceptor` or use `connection-test-query` that resets `app.current_user_id` to the nil UUID on pool return. Alternatively, verify that `connection-init-sql` fires on checkout, not just creation (HikariCP behavior: `connection-init-sql` only fires on connection creation, NOT on checkout).
- **Architectural improvement:** Add an integration test that performs two sequential authenticated requests as different users on a 1-connection pool, asserting tenant isolation.

**References:**
- CWE-863: Incorrect Authorization
- Critical Review v3, Finding C2
- Spring AOP documentation on advice ordering

---

### Finding #3: V4 Flyway Migration Contains Literal Password

**Vulnerability:** Hardcoded Credentials — OWASP A02 (Cryptographic Failures)
**Severity:** High
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: Plan Task 2.4, Step 4 — `V4__create_jpt_auth_role.sql` (line 395)

**Risk & Exploit Path:**

The migration specifies:
```sql
CREATE ROLE jpt_auth WITH LOGIN PASSWORD 'SET_VIA_SECRETS' BYPASSRLS;
```

1. `'SET_VIA_SECRETS'` is a literal string, not a Flyway placeholder (`${jpt_auth_password}`).
2. The `jpt_auth` role is created with the literal password `SET_VIA_SECRETS`.
3. This role has `BYPASSRLS` — it can read and modify ALL rows in `users` and `email_tokens`.
4. If the migration runs in production, the `jpt_auth` role exists with a known, guessable password.
5. Any attacker with network access to PostgreSQL (e.g., from a compromised container on the `backend` network) can authenticate as `jpt_auth` and bypass all RLS protections.

Additionally, as Critical Review v3 noted, the `authDataSource` expects password `${JPT_AUTH_PASSWORD}`, which won't match `SET_VIA_SECRETS` — so the auth system is also broken.

**Evidence / Trace:**

```sql
-- V4 migration (plan):
CREATE ROLE jpt_auth WITH LOGIN PASSWORD 'SET_VIA_SECRETS' BYPASSRLS;   -- ← LITERAL PASSWORD

-- Compare V2 migration (correct pattern):
CREATE ROLE jpt_app WITH LOGIN PASSWORD '${jpt_app_password}';          -- ← Flyway placeholder
```

**Remediation:**
- **Primary fix:** Use Flyway placeholder:
  ```sql
  CREATE ROLE jpt_auth WITH LOGIN PASSWORD '${jpt_auth_password}' BYPASSRLS;
  ```
- Add to `application.yml`:
  ```yaml
  spring.flyway.placeholders.jpt_auth_password: ${JPT_AUTH_PASSWORD}
  ```
- **Defense-in-depth:** Add a CI check that greps Flyway migrations for literal `PASSWORD '` strings that don't use `${...}` syntax.

**References:**
- CWE-798: Use of Hard-coded Credentials
- Critical Review v3, Finding C3

---

### Finding #4: `jpt_auth` Role Grants Are Overly Broad

**Vulnerability:** Excessive Privileges — OWASP A01 (Broken Access Control)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: Plan Task 2.4, Step 4 — `V4__create_jpt_auth_role.sql` (lines 396–399)

**Risk & Exploit Path:**

The V4 migration grants:
```sql
GRANT SELECT, INSERT, UPDATE ON users, email_tokens TO jpt_auth;
```

The `jpt_auth` role has `BYPASSRLS` and can:
- **SELECT all users** — read every user's email, password hash, OAuth credentials, quota, lockout status
- **UPDATE any user** — change any user's password hash, set `email_verified = true`, clear lockout, modify quota
- **INSERT users** — create users with arbitrary data

Auth operations require:
- `SELECT users` (login: lookup by email; OAuth: lookup by provider+id)
- `INSERT users` (registration, OAuth first-login)
- `UPDATE users` (failed login counter, lockout timestamp, password hash on reset)
- `SELECT, INSERT, DELETE email_tokens` (verification and reset flows)

The `UPDATE` grant is overly broad. A compromised auth service could modify any user's `quota_bytes` to `0` (denial of service) or set `password_hash` to a known value (account takeover). The `DELETE` grant on `email_tokens` is missing (needed for token consumption), while `INSERT` on `email_tokens` is needed for token creation.

**Evidence / Trace:**

```sql
-- Current (plan):
GRANT SELECT, INSERT, UPDATE ON users, email_tokens TO jpt_auth;
-- Missing: DELETE on email_tokens (for token consumption after verification)
-- Overly broad: UPDATE on users (can change quota_bytes, used_bytes, etc.)
```

**Remediation:**
- **Primary fix:** Use column-level grants for UPDATE on users:
  ```sql
  GRANT SELECT, INSERT ON users TO jpt_auth;
  GRANT UPDATE (password_hash, failed_login_attempts, locked_until, email_verified,
                oauth_provider, oauth_id) ON users TO jpt_auth;
  GRANT SELECT, INSERT, DELETE ON email_tokens TO jpt_auth;
  ```
- This follows the same principle used for `worker_db_user` (V3 migration), which correctly uses column-level UPDATE grants.

**References:**
- CWE-250: Execution with Unnecessary Privileges

---

### Finding #5: Full-Text Search Query — Potential SQL Injection via `plainto_tsquery`

**Vulnerability:** SQL Injection — OWASP A03 (Injection)
**Severity:** Low
**Confidence:** Low (Requires Verification)
**Attack Complexity:** High

**Location:**
- File: Plan Task 2.2 — `PhotoRepository.java` (lines 277–284)

**Risk & Exploit Path:**

The native query uses a parameterized `:query` parameter:
```java
@Query(value = "SELECT * FROM photos WHERE user_id = :userId AND deleted_at IS NULL " +
       "AND search_vector @@ plainto_tsquery('english', :query)", nativeQuery = true)
Page<Photo> searchByText(@Param("userId") UUID userId, @Param("query") String query, Pageable pageable);
```

`plainto_tsquery()` is designed to accept plain text and converts it to a tsquery — it does NOT interpret special tsquery syntax (`&`, `|`, `!`, `<->`). This is the safe choice (vs. `to_tsquery()` which would accept operators).

However, Spring Data JPA's `Pageable` integration with native queries generates dynamic `ORDER BY` and `LIMIT/OFFSET` clauses. If `Pageable` contains a `Sort` with an unsanitized property name (from query parameter `?sort=malicious`), Spring Data may inject it directly into the SQL.

**This is a known Spring Data JPA issue** with native queries + `Pageable` + `Sort`. Spring Data does validate sort properties against entity metamodel for JPQL queries, but native queries bypass this validation.

**Evidence / Trace:**

```java
// If the controller accepts: GET /search?q=sunset&page=0&size=50&sort=MALICIOUS_SQL
// Spring Data may append: ORDER BY MALICIOUS_SQL
// to the native query, enabling SQL injection
```

**Remediation:**
- **Primary fix:** Either:
  1. Hardcode the `ORDER BY` in the native query and don't pass `Sort` through `Pageable`, OR
  2. Use `PageRequest.of(page, size)` without sort in the controller (strip sort from user input), OR
  3. Validate sort property names against an allowlist before constructing `Pageable`
- **Defense-in-depth:** Apply the same pattern to all native `@Query` methods that accept `Pageable`.

**References:**
- CWE-89: SQL Injection
- Spring Data JPA native query Sort injection (documented limitation)

---

### Finding #6: JWT Secret Key Management — HS256 Symmetric Key Risks

**Vulnerability:** Cryptographic Weakness — OWASP A02 (Cryptographic Failures)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: Plan Task 2.3 — `JwtService.java` (lines 299–334)
- Related: `application.yml` (`app.jwt-secret: ${JWT_SECRET}`)

**Risk & Exploit Path:**

The plan uses HS256 (HMAC-SHA256) with a symmetric secret. Security considerations:

1. **Single point of compromise:** If the JWT secret is leaked (environment variable exposure, container inspection, log leak), an attacker can forge JWTs for any user. With asymmetric signing (RS256), leaking the public key is harmless.

2. **No key rotation mechanism in Phase 2:** The design doc mentions a "15-minute drain window" for key rotation, but the `JwtService` implementation only supports a single key. During rotation, there is no mechanism to validate tokens signed with the old key while issuing new tokens with the new key.

3. **Dev profile fallback secret:** `application-dev.yml` sets `jwt-secret: dev-secret-change-me-in-prod-must-be-at-least-256-bits-long-ok`. If the dev profile is accidentally activated in production (e.g., by omitting `SPRING_PROFILES_ACTIVE`), this known secret is used. The JWT secret is effectively public.

This is rated Medium rather than High because:
- The JWT secret is not hardcoded in production config (requires `$JWT_SECRET` env var).
- 15-minute expiry limits the window of exploitation for forged tokens.
- The dev profile issue requires a deployment misconfiguration.

**Evidence / Trace:**

```yaml
# application-dev.yml:
app:
  jwt-secret: ${JWT_SECRET:dev-secret-change-me-in-prod-must-be-at-least-256-bits-long-ok}
  # ← Known default value. If $JWT_SECRET is unset and dev profile is active, JWT is forgeable.
```

**Remediation:**
- **Primary fix (short-term):** Ensure production startup fails fast if `JWT_SECRET` is not set and profile is not `dev`/`test`. Add a `@PostConstruct` validation in `JwtService`:
  ```java
  @PostConstruct
  void validateSecret() {
      if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
          Preconditions.checkState(jwtSecret.length() >= 43, "JWT_SECRET must be >= 256 bits");
          Preconditions.checkState(!jwtSecret.contains("change-me"), "Default JWT_SECRET detected");
      }
  }
  ```
- **Architectural improvement (future):** Consider RS256 with key pair rotation for zero-downtime key rotation.
- **Defense-in-depth:** Remove the fallback default from `application-dev.yml` — require `JWT_SECRET` to always be set, even in dev (can be set in `docker-compose.dev.yml`).

**References:**
- CWE-321: Use of Hard-coded Cryptographic Key
- CWE-326: Inadequate Encryption Strength

---

### Finding #7: Account Lockout — No Lockout Counter Reset on Successful Login

**Vulnerability:** Business Logic Flaw — OWASP A07 (Authentication Failures)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan Task 2.5, Step 3 — `AuthService.java` (lines 584–591)

**Risk & Exploit Path:**

The plan specifies:
- 5 failed attempts → 15-minute lock
- Always performs bcrypt regardless of lockout (timing mitigation)
- Returns generic 401 for both wrong-password and locked accounts

**Missing:** The plan does not specify resetting `failed_login_attempts` to 0 after a successful login. Without this:

1. User enters wrong password 4 times (counter = 4).
2. User enters correct password (counter still = 4).
3. User enters wrong password once more (counter = 5 → locked for 15 minutes).

This creates a **persistent near-lockout state** where a legitimate user is always one wrong attempt away from being locked out — a denial-of-service vector. An attacker who knows a user's email can send 4 wrong attempts, then wait. The next time the legitimate user makes a single typo, they're locked.

**Evidence / Trace:**

```
// Plan Task 2.5, Step 3:
// "Account lockout: 5 failures → 15 min lock"
// "Always perform BCrypt.checkpw() regardless of lockout status"
// NO mention of: reset counter on successful login
```

**Remediation:**
- **Primary fix:** On successful authentication (correct password AND not locked), reset `failed_login_attempts = 0` and `locked_until = NULL`.
- **Defense-in-depth:** Consider an exponential backoff model (1s, 2s, 4s, 8s, ...) instead of a hard lockout, which is less susceptible to DoS.

**References:**
- CWE-307: Improper Restriction of Excessive Authentication Attempts

---

### Finding #8: Refresh Token — No Token Family Tracking for Replay Detection

**Vulnerability:** Session Management Flaw — OWASP A07 (Authentication Failures)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: Plan Task 2.5, Step 4 — `RefreshTokenService.java` (lines 593–607)

**Risk & Exploit Path:**

The Redis key for refresh tokens stores `{"userId": "...", "issuedAt": "...", "family": "..."}` — the `family` field is present but never described in the rotation logic. The rotation flow is:

1. Validate incoming token → delete old → generate new → store new → return new.

If an attacker steals a refresh token before the legitimate user uses it:
- Attacker uses the stolen token → gets new token pair (old one deleted).
- Legitimate user tries to use the now-deleted old token → 401.
- The legitimate user is logged out, but the attacker has a valid session.

The `family` field could enable **token family tracking** (as described in RFC draft for refresh token rotation): if a token from a previously consumed family is replayed, ALL tokens in that family are revoked. But the plan doesn't implement this detection — the `family` field is stored but never checked.

Without family-based replay detection:
- An attacker with a stolen refresh token gets a full session.
- The only signal is the legitimate user being logged out (which they may attribute to a session timeout).
- No automatic revocation of the attacker's token occurs.

**Evidence / Trace:**

```
// Redis value schema (plan Task 2.5, Step 4):
// {"userId": "...", "issuedAt": "...", "family": "..."}
//                                       ^^^^^^ stored but never used in rotation logic
```

**Remediation:**
- **Primary fix:** Implement family-based replay detection:
  1. On rotation, store the family ID in the new token.
  2. If a token with a known family but already-consumed hash is presented, revoke ALL tokens in that family (read `user_refresh:{userId}`, filter by family, delete all).
  3. Log the replay as a security event.
- **Architectural improvement:** Add a security event log for token replay detection, unusual login locations, or concurrent sessions.

**References:**
- CWE-384: Session Fixation
- Auth0 Refresh Token Rotation documentation

---

### Finding #9: `RlsContext` ThreadLocal Not Cleared in Error Paths

**Vulnerability:** Broken Access Control — OWASP A01 (Broken Access Control)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: Plan Task 2.4, Steps 1, 6 — `RlsInterceptor.java` and `RlsContext.java` (lines 431–434)

**Risk & Exploit Path:**

The plan specifies that `RlsInterceptor` (a Spring `HandlerInterceptor`):
- In `preHandle()`: extracts user ID from `SecurityContext`, stores in `RlsContext` (`ThreadLocal<UUID>`).
- In `afterCompletion()`: clears the `ThreadLocal`.

If an exception occurs during request processing that bypasses `afterCompletion()` — such as a Servlet container error, an OutOfMemoryError, or a filter-chain exception before the interceptor is registered — the `ThreadLocal` retains the previous user's ID.

In servlet containers with thread pooling (Tomcat), the next request on the same thread inherits the stale `RlsContext`. Combined with Finding #2 (aspect ordering), this could result in cross-tenant data access.

However, the `RlsAspect` reads from `RlsContext` only when the value is non-null, and `afterCompletion()` is called by Spring MVC for both success and exception paths (including controller exceptions). The risk is limited to non-Spring-managed exceptions at the Servlet container level.

**Evidence / Trace:**

```java
// RlsInterceptor (plan):
// preHandle(): RlsContext.setCurrentUserId(userId);   // ← ThreadLocal set
// afterCompletion(): RlsContext.clear();               // ← ThreadLocal cleared
//
// If afterCompletion() is never called (container error), ThreadLocal leaks to next request
```

**Remediation:**
- **Primary fix:** Add a Servlet `Filter` (registered before all interceptors) that clears `RlsContext` in a `finally` block:
  ```java
  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
      try { chain.doFilter(req, res); }
      finally { RlsContext.clear(); }
  }
  ```
- **Defense-in-depth:** The `connection-init-sql` with nil UUID provides partial protection, but only on new connections. The `RlsAspect` + `assert_user_context()` call provides additional protection (nil UUID assertion would fail).

**References:**
- CWE-212: Improper Removal of Sensitive Information Before Storage or Transfer

---

### Finding #10: `GET /actuator/health` Exposed Without Rate Limiting

**Vulnerability:** Security Misconfiguration — OWASP A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: Plan Task 2.4, Step 2 — `SecurityConfig.java` (line 385)

**Risk & Exploit Path:**

`/actuator/health` is listed as a public path (no auth required) and has no rate limiting (neither Nginx IP-based nor Bucket4j per-user). While the health endpoint is standard practice for container orchestration health checks:

1. **Information disclosure:** Spring Boot Actuator's `/actuator/health` can expose component health details (database, Redis, disk space) if `management.endpoint.health.show-details` is set to `always` or `when_authorized`. The plan does not explicitly configure this.
2. **DoS vector:** Without rate limiting, the health endpoint can be used for HTTP flood attacks. The endpoint triggers database and Redis health checks, amplifying the impact.
3. **Other actuator endpoints:** The plan only lists `/actuator/health` as public. However, Spring Boot Actuator exposes additional endpoints (`/actuator/info`, `/actuator/env`, `/actuator/metrics`, `/actuator/beans`, etc.) that may be enabled by default. If the plan does not explicitly restrict actuator exposure, sensitive endpoints could be accessible.

**Evidence / Trace:**

```java
// SecurityConfig (plan):
// Public paths: "/auth/**", "/actuator/health"
// No explicit actuator configuration in application.yml
```

**Remediation:**
- **Primary fix:** Explicitly configure actuator in `application.yml`:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health
    endpoint:
      health:
        show-details: never
  ```
- **Defense-in-depth:** Add Nginx rate limiting for `/actuator/health` (e.g., 30r/m per IP). Restrict access to health endpoint to internal networks only if possible.

**References:**
- CWE-200: Exposure of Sensitive Information to an Unauthorized Actor

---

### Finding #11: No Password Breach Check (Credential Stuffing Prevention)

**Vulnerability:** Authentication Weakness — OWASP A07 (Authentication Failures)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** N/A (missing control)

**Location:**
- File: Plan Task 2.5, Steps 2–3 — `RegisterRequest.java`, `AuthService.java` (lines 571–591)

**Risk & Exploit Path:**

Password validation is `@Size(min = 12)` only. The plan does not include:
- Breach database check (e.g., HaveIBeenPwned k-anonymity API)
- Common password dictionary check
- Password complexity requirements beyond minimum length

A 12-character minimum is good (exceeds NIST 800-63B's 8-character recommendation), but without breach checking, users can register with passwords like `password1234` or `qwertyuiop12` that appear in every credential dump.

This is rated Low because:
- 12-character minimum is a strong baseline.
- Bcrypt cost factor 12 provides significant brute-force resistance.
- Account lockout limits online attacks.
- This is a defense-in-depth gap, not a direct vulnerability.

**Remediation:**
- **Recommended:** Add HaveIBeenPwned k-anonymity check during registration (non-blocking — warn, don't reject).
- **Alternative:** Add a top-1000 common password check at the application layer.

**References:**
- NIST SP 800-63B Section 5.1.1.2 (Memorized Secret Verifiers)

---

### Finding #12: Missing `Secure` and `SameSite` Cookie Attributes in Plan

**Vulnerability:** Session Management — OWASP A07 (Authentication Failures)
**Severity:** Low
**Confidence:** Medium (Requires Verification)
**Attack Complexity:** Medium

**Location:**
- File: Plan Task 2.5, Step 6 — `AuthController.java` (lines 614–619)

**Risk & Exploit Path:**

The plan specifies JWT and refresh tokens are set as `httpOnly` cookies. It does not explicitly mention:
- `Secure` flag (only transmit over HTTPS)
- `SameSite=Lax` or `SameSite=Strict` attribute
- `Path` attribute (cookie scope)
- `Domain` attribute

Without `Secure`, cookies are transmitted over plain HTTP (e.g., if a user navigates to `http://` by mistake or if an attacker performs SSL stripping). Without `SameSite`, older browsers may send cookies on cross-origin requests.

Spring Boot's `server.servlet.session.cookie` properties may set defaults, but JWT cookies are set manually by `AuthController` — they don't inherit session cookie configuration.

**Evidence / Trace:**

```java
// Plan Task 2.5 test assertion:
// .andExpect(cookie().httpOnly("jwt", true));
// No assertion for: cookie().secure("jwt", true)
// No assertion for: SameSite attribute
```

**Remediation:**
- **Primary fix:** When creating cookie in `AuthController`:
  ```java
  ResponseCookie jwt = ResponseCookie.from("jwt", token)
      .httpOnly(true)
      .secure(true)        // HTTPS only
      .sameSite("Lax")     // or "Strict"
      .path("/")
      .maxAge(Duration.ofMinutes(15))
      .build();
  ```
- Add test assertions for `Secure` and `SameSite` attributes.

**References:**
- CWE-614: Sensitive Cookie in HTTPS Session Without 'Secure' Attribute

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attacks

**Finding #2 + Finding #9 = Critical cross-tenant leak:**
If the `RlsAspect` fires outside the transaction (Finding #2) AND the `RlsContext` ThreadLocal is not cleared (Finding #9), the cross-tenant leak is amplified: the stale ThreadLocal value propagates to a stale `set_config` that persists on the connection. Both conditions must be addressed to prevent tenant isolation failure.

**Finding #3 + Network Access = Account Takeover:**
The literal password in V4 migration + any container compromise on the `backend` network allows `jpt_auth` login with `BYPASSRLS`, enabling mass user credential exfiltration (password hashes) or direct password hash modification (immediate account takeover for any user).

### Implicit Trust Assumptions

1. **`UUID.toString()` is always safe for SQL context.** This is true for Java's `UUID.toString()`, but the plan should document this assumption to prevent future refactoring from introducing a string source.

2. **`RlsContext.getCurrentUserId()` always returns a value from the authenticated `SecurityContext`.** If a custom filter or error handler sets an arbitrary value in `RlsContext`, it would be trusted by `RlsAspect`. The `assert_user_context()` DB function mitigates this (it rejects nil UUID but not arbitrary valid UUIDs).

3. **`connection-init-sql` fires on every connection creation.** This is correct for HikariCP, but it does NOT fire on connection checkout from the pool. If a connection is returned to the pool with a stale `app.current_user_id` (due to Finding #2), the init SQL won't clear it until the connection is evicted and recreated.

### Defense-in-Depth Gaps

1. **No security event logging.** The plan does not specify logging for: failed login attempts (beyond counter increment), account lockout events, refresh token replay, cross-tenant access attempts (assert_user_context failures), rate limit hits, or OAuth2 login failures. Without security event logs, incident detection and forensics are impossible.

2. **No request ID / correlation ID.** Without a correlation ID propagated through logs, tracing a cross-tenant data leak or authentication bypass across API → RLS → PostgreSQL audit logs would be extremely difficult.

### Deployment Context

The Docker Compose stack provides good network isolation (`backend` internal network). The `api` container has `cap_drop: ALL` and `no-new-privileges`. However:

- The `backup` service (MinIO `mc mirror` sidecar) lacks equivalent hardening (no `cap_drop`, no `security_opt`). If this service is compromised, it has broader capabilities than necessary.
- Environment variables containing secrets (`JWT_SECRET`, `DB_PASS`, `JPT_AUTH_PASSWORD`) are visible via `docker inspect`. Docker Swarm secrets (file-based) would be more secure.

---

## 1. Executive Summary

Phase 2 of the JPhotoTagger SaaS conversion implements the critical security foundation: authentication, authorization, JWT-based session management, CSRF protection, row-level security, and rate limiting. The overall architecture is sound — the design demonstrates strong security thinking with defense-in-depth (application-layer + RLS), timing-safe authentication, proper CSRF handling for SPAs, and token rotation for refresh tokens.

However, the plan contains **two critical implementation bugs** that were identified in Critical Implementation Review v3 but have not been incorporated into the v3.0 plan text: the `SET LOCAL` parameterized query incompatibility (Finding #1) and the undefined `@Order` between `RlsAspect` and `TransactionInterceptor` (Finding #2). If implemented as written, the first causes all authenticated requests to crash; the second causes non-deterministic cross-tenant data leakage. Additionally, the V4 Flyway migration contains a literal password for a `BYPASSRLS` role (Finding #3), which would create a backdoor in production.

The plan also has several medium-severity gaps: missing login counter reset (Finding #7), unused refresh token family tracking (Finding #8), ThreadLocal leak potential (Finding #9), and overly broad `jpt_auth` grants (Finding #4). These are design-level gaps that should be addressed before implementation.

The codebase is **not ready for production** until the critical findings are resolved in the plan text. The medium-severity findings should be addressed before or during implementation.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | `SET LOCAL` doesn't support parameterized queries | A03 — Injection | Critical | High | 1 | BLOCK |
| 2 | RlsAspect `@Order` undefined — cross-tenant leak | A01 — Broken Access Control | Critical | High | 1 | BLOCK |
| 3 | V4 migration contains literal BYPASSRLS password | A02 — Cryptographic Failures | High | Confirmed | 1 | BLOCK |
| 4 | `jpt_auth` role grants overly broad | A01 — Broken Access Control | Medium | High | 1 | FIX |
| 5 | Native query + Pageable Sort injection | A03 — Injection | Low | Low | 3+ (all native queries) | VERIFY |
| 6 | JWT HS256 symmetric key risks | A02 — Cryptographic Failures | Medium | Medium | 1 | FIX |
| 7 | No login counter reset on success | A07 — Auth Failures | Medium | High | 1 | FIX |
| 8 | Refresh token family tracking unused | A07 — Auth Failures | Medium | Medium | 1 | FIX |
| 9 | RlsContext ThreadLocal not cleared in error paths | A01 — Broken Access Control | Medium | Medium | 1 | FIX |
| 10 | Actuator health exposed without rate limit | A05 — Misconfiguration | Low | Confirmed | 1 | IMPROVE |
| 11 | No password breach database check | A07 — Auth Failures | Low | Confirmed | 1 | IMPROVE |
| 12 | Missing Secure/SameSite cookie attributes in plan | A07 — Auth Failures | Low | Medium | 1 | FIX |

---

## 3. Security Quality Score (SQS)

**Calculation:**

| Finding | Severity | Deduction |
|---------|----------|-----------|
| #1 | Critical | −40 |
| #2 | Critical | −40 |
| #3 | High | −20 |
| #4 | Medium | −8 |
| #6 | Medium | −8 |
| #7 | Medium | −8 |
| #8 | Medium | −8 |
| #9 | Medium | −8 |
| #5 | Low | −2 |
| #10 | Low | −2 |
| #11 | Low | −2 |
| #12 | Low | −2 |

**Raw score:** 100 − 40 − 40 − 20 − 8 − 8 − 8 − 8 − 8 − 2 − 2 − 2 − 2 = **−48** → clamped to **0**

**Hard gates triggered:** Yes
- Unremediated Critical findings: #1, #2
- Hardcoded credentials (BYPASSRLS role): #3

**Final SQS:** 0/100
**Posture:** Unacceptable — block deployment, urgent remediation required

**Note:** This score reflects the plan as written. Findings #1, #2, and #3 were already identified in Critical Review v3 but the plan text was not updated. If the CR v3 recommendations are incorporated (as stated in the plan's changelog but not in the code blocks), the adjusted score would be:

100 − 8 − 8 − 8 − 8 − 8 − 2 − 2 − 2 − 2 = **52/100** (Unacceptable — medium findings accumulate)

With all medium fixes applied: **100 − 2 − 2 − 2 − 2 = 92/100** (Strong)

---

## 4. Positive Security Observations

1. **Defense-in-depth RLS architecture.** Application-layer authorization checks combined with PostgreSQL RLS as a safety net is excellent. The `connection-init-sql` with nil UUID provides an additional failsafe. The `assert_user_context()` function provides fail-fast behavior.

2. **Timing-safe authentication.** Always performing bcrypt comparison regardless of lockout status eliminates the timing side-channel that would reveal whether an account exists or is locked. The generic 401 response prevents user enumeration.

3. **Proper CSRF handling for SPAs.** Using `SpaCsrfTokenRequestHandler` with `CookieCsrfTokenRepository.withHttpOnlyFalse()` is the correct Spring Security 6 pattern for SPA + cookie-based auth. Exempting only pre-auth endpoints is appropriate.

4. **Refresh token rotation with Redis.** Cryptographically random tokens, SHA-256 storage (not plaintext), rotation on use, and bulk revocation on password change follow current best practices. The `user_refresh:{userId}` set enables efficient bulk revocation.

5. **OAuth2 anti-hijacking.** Refusing to auto-merge by email prevents OAuth2 account pre-hijacking attacks. Explicit linking via Settings page after credential verification is the correct approach.

---

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Title | Why Prioritized | Effort | Owner |
|----------|---------|-------|-----------------|--------|-------|
| 1 | #1, #2 | RLS implementation bugs (`set_config` + `@Order`) | Cross-tenant data leak — foundational security failure. Both must be fixed together. | Quick Win | Backend |
| 2 | #3 | V4 migration literal password | Hardcoded BYPASSRLS credential — instant full DB access from any compromised container | Quick Win | Backend |
| 3 | #4 | `jpt_auth` overly broad grants | Reduces blast radius of auth service compromise | Quick Win | Backend |
| 4 | #7, #9, #12 | Login counter reset + ThreadLocal cleanup + cookie attributes | Authentication and session management correctness — low-effort fixes with high defensive value | Quick Win | Backend |
| 5 | #6, #8 | JWT key validation + refresh token family tracking | Defense-in-depth improvements that prevent production incidents | Moderate | Backend + Security |
