# Security Audit — Phase 5: Sharing & Polish (v7.0)

**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Date:** 2026-03-12
**Plan Version:** 7.0
**Subject:** `docs/plans/2026-02-25-saas-conversion-phase-5.md`
**Audit Number:** SA-P5-2
**Previous Audit:** SA-P5-1 (against v6.0, 12 findings — all addressed in v7.0)

---

## Scope & Assumptions

This audit reviews the Phase 5 plan **v7.0** — the version that incorporated SA-P5-1 remediations and Critical Review #6. The focus is on:

1. **Residual risk** from SA-P5-1 findings that were mitigated but not fully eliminated
2. **New attack surface** introduced by v7.0 changes (e.g., `SimpleEmailService`, startup validation, `deploy.sh`)
3. **Issues missed by SA-P5-1** — gaps in the first audit's coverage

**Assumptions:**
- Existing security controls (RLS, JWT auth, container hardening, rate limiting) are correctly implemented per prior phase audits (SA-P1 through SA-P4)
- The plan will be implemented as written; deviations would require re-audit
- Single-VPS deployment model as documented
- SA-P5-1 remediations (SA-F1 through SA-F12) are incorporated into v7.0

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Changes Since SA-P5-1

| Change (v7.0) | Security Relevance |
|---|---|
| `SimpleEmailService` added (Prerequisite 2) | New SMTP-capable service; email content construction; token in URL |
| JWT startup validation (SA-F8) | Guards against CI secrets in production |
| `deploy.sh` with `command=` SSH restriction (SA-F6) | Deploy key scope narrowed — but `rsync --server*` pattern needs review |
| `ShareLookupRepository` as sole `shareReaderDataSource` consumer (SA-F1) | Code-level BYPASSRLS containment — ArchUnit enforced |
| `smtp_auth_password_file` for Alertmanager (SA-F4) | Credential handling improved |
| `COOKIE_SECURE` env var plumbing (M33) | Cookie security now configurable per environment |
| MailPit in CI (C21) | Test SMTP; REST API exposed on port 8025 |

### Attack Surface Map (Updated)

The entry points and trust boundaries from SA-P5-1 remain valid. New additions:

| Entry Point | Auth Required | Trust Boundary | Data Sensitivity |
|---|---|---|---|
| `SimpleEmailService.send*()` | N/A (internal) | API → SMTP server | Verification/reset tokens in email body |
| `deploy.sh` subcommands | SSH key | GitHub Actions → VPS | Docker operations, file transfer |

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: `SimpleEmailService` Constructs Verification URL via String Concatenation — Open Redirect / Token Leakage Vector

**Vulnerability:** Improper URL Construction — A03 (Injection)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 49–64 (Task 5.1, Prerequisite 2)

**Risk & Exploit Path:**

The `SimpleEmailService` constructs the verification URL by concatenating `baseUrl` with the token:

```java
msg.setText(baseUrl + "/auth/verify?token=" + token);
```

1. **`app.base-url` injection:** If `app.base-url` is set to a malicious value (e.g., via misconfigured environment variable), verification emails redirect users to an attacker-controlled domain. This is a configuration-dependent risk — the value comes from `${app.base-url}` which is operator-controlled, not user-controlled.

2. **Token in URL:** The verification token appears in the URL query string. While the email body itself is controlled, if the user's email client or browser logs/leaks the URL (Referer headers, browser history, proxy logs), the token could be exposed. However, verification tokens are single-use and short-lived (24h), so the practical risk is low.

3. **Missing URL encoding:** If the token contains characters that need URL encoding (unlikely for base64url, but defensive coding requires it), the URL could be malformed.

**Evidence / Trace:**

```java
@Service
@Profile("!dev & !test")
public class SimpleEmailService implements EmailService {
    @Value("${app.base-url}") private String baseUrl;  // ← operator-controlled

    @Override
    public void sendVerificationEmail(String to, String token) {
        var msg = new SimpleMailMessage();
        msg.setTo(to);
        msg.setSubject("Verify your email");
        msg.setText(baseUrl + "/auth/verify?token=" + token);  // ← string concatenation
        mailSender.send(msg);
    }
}
```

**Remediation:**
- **Primary fix:** Use `UriComponentsBuilder` for safe URL construction:
  ```java
  String url = UriComponentsBuilder.fromUriString(baseUrl)
      .path("/auth/verify")
      .queryParam("token", token)
      .toUriString();
  ```
- **Defense-in-depth:** Add `@PostConstruct` validation that `app.base-url` is a valid HTTPS URL (or HTTP in dev) and does not end with `/`.
- **Note:** Token-in-URL is an accepted pattern for email verification (matches existing `AuthController` flow). The single-use, short-lived nature of verification tokens adequately mitigates leakage risk.

**References:**
- CWE-79: Improper Neutralization of Input During Web Page Generation (URL construction variant)
- CWE-601: URL Redirection to Untrusted Site

---

### Finding #2: `deploy.sh` `rsync --server*` Wildcard Pattern Allows Arbitrary File Writes

**Vulnerability:** Command Restriction Bypass — A01 (Broken Access Control)
**Severity:** High
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 647–665 (Task 5.5, Step 2)

**Risk & Exploit Path:**

The `deploy.sh` script uses a wildcard match for rsync:

```bash
rsync\ --server*) $SSH_ORIGINAL_COMMAND ;;
```

This pattern matches any `SSH_ORIGINAL_COMMAND` starting with `rsync --server`, then **executes the entire `SSH_ORIGINAL_COMMAND` verbatim** via `$SSH_ORIGINAL_COMMAND`. The rsync `--server` mode is invoked by the rsync client automatically, but the pattern does not restrict:

1. **Destination path:** The rsync could write to any path the `deploy` user has access to, not just `/opt/jpt/`. A compromised CI pipeline could rsync malicious files to `/home/deploy/.ssh/authorized_keys` (removing the `command=` restriction) or to any other writable location.

2. **rsync flags:** The pattern allows any rsync flags after `--server`, including `--delete` which could be directed at paths outside `/opt/jpt/`.

3. **Shell expansion:** Since `$SSH_ORIGINAL_COMMAND` is unquoted, shell word-splitting applies, but `set -euo pipefail` and `case` pattern matching limits this.

**Evidence / Trace:**

```bash
#!/bin/bash
set -euo pipefail
cd /opt/jpt
case "${SSH_ORIGINAL_COMMAND:-$1}" in
    # ... safe subcommands ...
    rsync\ --server*) $SSH_ORIGINAL_COMMAND ;;  # ← executes arbitrary rsync command
    *)             echo "Unknown command" >&2; exit 1 ;;
esac
```

An attacker with the deploy key could execute:
```bash
# From compromised CI or stolen key:
ssh deploy@vps rsync --server --sender -e.Lsfx . /etc/passwd
# Reads /etc/passwd via rsync protocol
```

**Remediation:**
- **Primary fix:** Restrict rsync destination to `/opt/jpt/` by validating `SSH_ORIGINAL_COMMAND` contains the expected path:
  ```bash
  rsync\ --server*)
      # Validate that the rsync command targets /opt/jpt/ only
      if echo "$SSH_ORIGINAL_COMMAND" | grep -q '/opt/jpt'; then
          $SSH_ORIGINAL_COMMAND
      else
          echo "rsync restricted to /opt/jpt/" >&2; exit 1
      fi ;;
  ```
- **Better alternative:** Use `rrsync` (restricted rsync, ships with rsync) in `authorized_keys`:
  ```
  command="/usr/bin/rrsync /opt/jpt/",no-agent-forwarding,no-port-forwarding,no-pty ssh-ed25519 AAAA...
  ```
  Then use a separate deploy key (or the same key with a different `authorized_keys` entry) for the SSH command execution (`tag-previous`, `build`, `healthcheck`, `rollback`). This fully separates file transfer from command execution.
- **Defense-in-depth:** Ensure the `deploy` user's home directory and `.ssh/` are owned by root and not writable by `deploy`, preventing authorized_keys modification.

**References:**
- CWE-78: Improper Neutralization of Special Elements used in an OS Command
- CWE-269: Improper Privilege Management

---

### Finding #3: JWT Startup Validation Only Checks `ci_test` Prefix — Bypassable

**Vulnerability:** Insufficient Secret Validation — A07 (Identification and Authentication Failures)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 76–86 (Task 5.1, Prerequisite 2)

**Risk & Exploit Path:**

The SA-F8 startup validation rejects JWT secrets starting with `ci_test`:

```java
if (jwtSecret.startsWith("ci_test")) {
    throw new IllegalStateException("CI test JWT secret detected");
}
```

This check is trivially bypassable:
1. A misconfigured production deployment using `test_ci_jwt_secret...` would pass the check
2. Any weak secret not starting with `ci_test` (e.g., `changeme`, `secret123`, `password`) passes
3. The existing `JwtService` already validates minimum key length (256 bits / 43 base64 chars) and rejects `change-me`, but there is no entropy check

The plan's intent (preventing `.env.ci` in production) is correct, but the implementation is a point check rather than a proper secret strength validation.

**Evidence / Trace:**

```java
@PostConstruct
void validateSecrets() {
    if (jwtSecret.startsWith("ci_test")) {  // ← only catches this exact prefix
        throw new IllegalStateException("CI test JWT secret detected — do not use .env.ci in production");
    }
}
```

Meanwhile, the existing `JwtService` check:
```java
// JwtService.java (existing, confirmed in codebase)
// - Rejects "change-me"
// - Validates >= 43 base64 characters (256 bits)
```

**Remediation:**
- **Primary fix:** Extend the validation to also check against other known weak patterns and delegate to the existing `JwtService` key validation. The simplest robust approach:
  ```java
  @PostConstruct
  void validateSecrets() {
      Set<String> knownWeakPrefixes = Set.of("ci_test", "test_", "changeme", "secret", "password", "default");
      String lower = jwtSecret.toLowerCase();
      for (String prefix : knownWeakPrefixes) {
          if (lower.startsWith(prefix)) {
              throw new IllegalStateException(
                  "Weak JWT secret detected — see .env.example for generation instructions");
          }
      }
  }
  ```
- **Better alternative:** Since `JwtService` already validates minimum key length and rejects `change-me`, the SA-F8 check should focus on the specific CI credential issue. The current implementation is sufficient for its stated purpose (preventing `.env.ci` in production), even if not comprehensive. Mark as **acceptable with documentation** noting the existing `JwtService` validation provides the primary safeguard.
- **Defense-in-depth:** Add generation instructions to `.env.example`: `JWT_SECRET=  # Generate with: openssl rand -base64 64`

**References:**
- CWE-1393: Use of Default Password

---

### Finding #4: `ShareReaderDataSourceConfig` Package-Private Scoping Is Not Enforced by JVM

**Vulnerability:** Insufficient Access Control on RLS-Bypass DataSource — A01 (Broken Access Control)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 142 (Task 5.1, Step 1)

**Risk & Exploit Path:**

The SA-F1 remediation states the `@Bean` method for `shareReaderDataSource` must be "package-private (no `public` modifier) to prevent injection outside the config package." However:

1. **Spring ignores Java visibility for bean resolution.** Spring's IoC container uses reflection to invoke `@Bean` methods and resolve `@Autowired` injections. A package-private bean method does not prevent injection — any `@Autowired DataSource` or `@Qualifier("shareReaderDataSource") DataSource` in any package will still receive the bean.

2. **The ArchUnit test is the actual guard.** The plan correctly specifies an ArchUnit test (`shareReaderDataSource_onlyUsedByShareLookupRepository()`) to enforce the constraint. However, ArchUnit tests run at test time, not at compile time or runtime — a developer could add a new injection, forget to run tests, and deploy.

3. **The `@Bean` name is predictable.** Any class in the codebase can request `@Qualifier("shareReaderDataSource") DataSource` and receive the RLS-bypass connection.

**Evidence / Trace:**

```java
// ShareReaderDataSourceConfig.java
@Configuration
@ConfigurationProperties("app.share-reader")
class ShareReaderDataSourceConfig {  // package-private class — but Spring doesn't care
    @Bean
    DataSource shareReaderDataSource() {  // ← package-private method; Spring still registers the bean
        // ...
    }
}
```

```java
// ANY class in ANY package can still do:
@Autowired
@Qualifier("shareReaderDataSource")
private DataSource ds;  // ← gets the BYPASSRLS connection
```

**Remediation:**
- **Primary fix:** The ArchUnit test (SA-F1) is the correct enforcement mechanism. Package-private visibility is defense-in-depth but should not be presented as a primary control. Update the plan language to reflect this:
  > "Package-private bean scoping provides a code-level signal; the ArchUnit test is the **enforced** constraint."
- **Architectural improvement:** Instead of exposing the `DataSource` bean at all, inject it only internally within `ShareLookupRepository` via constructor injection in the config class itself:
  ```java
  @Configuration
  class ShareReaderConfig {
      @Bean
      ShareLookupRepository shareLookupRepository(
              @ConfigurationProperties("app.share-reader") DataSource ds) {
          return new ShareLookupRepository(ds);
      }
  }
  ```
  This way, no `DataSource` bean is registered in the application context — only the `ShareLookupRepository` is available for injection, and it internally holds the connection.
- **Defense-in-depth:** Keep the ArchUnit test. Add a CI gate that fails the build if ArchUnit tests fail (this should already be the case via `./gradlew build` including test).

**References:**
- CWE-250: Execution with Unnecessary Privileges
- Spring Framework documentation: Bean visibility is independent of Java access modifiers

---

### Finding #5: E2E Test Hardcodes MailPit URL — Test Reliability, Not a Security Issue But Masks Real Vulnerability

**Vulnerability:** Information Disclosure via Test Infrastructure — A09 (Security Logging and Monitoring Failures)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 771–781 (Task 5.6, Step 1)

**Risk & Exploit Path:**

The E2E test accesses MailPit's REST API at `http://localhost:8025/api/v1/messages` to extract verification tokens. The MailPit service in `docker-compose.ci.yml` exposes port 8025 on the host. While this is CI-only, the pattern reveals the test infrastructure's email interception capability.

The more significant concern: the E2E test retrieves `messages.messages[0]` — the **first** message. In a concurrent CI environment or if messages accumulate, this could retrieve the wrong email. This is a test reliability issue rather than a security vulnerability, but incorrect token extraction could mask authentication bugs.

**Evidence / Trace:**

```typescript
const messagesRes = await page.request.get('http://localhost:8025/api/v1/messages');
const messages = await messagesRes.json();
const verifyEmail = messages.messages[0];  // ← assumes first message is the verification email
```

**Remediation:**
- **Primary fix:** Filter messages by recipient email or subject instead of taking the first:
  ```typescript
  const messagesRes = await page.request.get(
    `http://localhost:8025/api/v1/search?query=to:${testEmail}`
  );
  ```
- **Defense-in-depth:** Clear MailPit messages before each test run to avoid stale data:
  ```typescript
  await page.request.delete('http://localhost:8025/api/v1/messages');
  ```

**References:**
- CWE-200: Exposure of Sensitive Information to an Unauthorized Actor (minor — CI-only)

---

### Finding #6: Share Token Format Validation Mismatch — Plan Specifies "Hex String, 64 Chars" but Token Is Base64url

**Vulnerability:** Input Validation Bypass — A03 (Injection)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Line: 144 (Task 5.1, SA-F1 fix note)
- Related: Line 777 (Task 5.6, E2E regex: `[A-Za-z0-9_-]+`)

**Risk & Exploit Path:**

The SA-F1 fix note states:

> "The share lookup query must also validate token format (hex string, 64 chars for SHA-256 output) before querying."

But the E2E test regex (C23 fix) uses `[A-Za-z0-9_-]+` — a base64url character class, not a hex character class. This is an internal contradiction in the plan:

- If tokens are `SecureRandom(256-bit)` encoded as **hex**, they are 64 hex characters: `[a-f0-9]{64}`
- If tokens are `SecureRandom(256-bit)` encoded as **base64url**, they are ~43 characters: `[A-Za-z0-9_-]{43}`
- The existing `RefreshTokenService` uses base64url encoding for its tokens

The validation rule ("hex string, 64 chars") would reject valid base64url tokens, breaking share lookup. Conversely, if the validation is wrong and too permissive, it fails to serve its purpose of preventing injection.

**Evidence / Trace:**

```
// Plan line 144 (SA-F1 fix):
"validate token format (hex string, 64 chars for SHA-256 output)"

// Plan line 777 (E2E regex, C23 fix):
const tokenMatch = emailData.Text.match(/\/auth\/verify\?token=([A-Za-z0-9_-]+)/);
// This regex matches base64url, not hex
```

Note: The "64 chars for SHA-256 output" description conflates the share token format with the SHA-256 hash format. The *hash* stored in the DB is 64 hex chars (SHA-256 output); the *token* sent to the user is the raw random bytes encoded in whatever format `ShareService` uses.

**Remediation:**
- **Primary fix:** Clarify the plan — the validation should match the actual encoding format:
  - If using hex encoding: validate `[a-f0-9]{64}`
  - If using base64url encoding (consistent with `RefreshTokenService`): validate `[A-Za-z0-9_-]{43}` (256 bits = 32 bytes = 43 base64url chars with padding stripped)
- **Implementation note:** The validation should be in `ShareController` or `ShareLookupRepository` before the DB query. Use a regex constant:
  ```java
  private static final Pattern SHARE_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
  // or for hex:
  private static final Pattern SHARE_TOKEN_PATTERN = Pattern.compile("[a-f0-9]{64}");
  ```
- **Defense-in-depth:** Return 404 (not 400) for format validation failures to avoid leaking the expected format to attackers.

**References:**
- CWE-20: Improper Input Validation

---

### Finding #7: `COOKIE_SECURE=false` in CI Disables Cookie Security — Document Risk Explicitly

**Vulnerability:** Cookie Security Downgrade — A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Line: 628 (`.env.ci`)
- Related: Line 69 (Task 5.1, M33 fix)

**Risk & Exploit Path:**

`COOKIE_SECURE=false` in `.env.ci` disables the `Secure` flag on JWT and refresh token cookies. This is necessary because CI uses HTTP-only nginx. However:

1. If `COOKIE_SECURE` defaults to `false` (rather than `true`) in any non-CI environment, cookies will be sent over unencrypted HTTP connections, exposing JWT tokens to network attackers.
2. The plan correctly specifies `${COOKIE_SECURE:true}` in `application.yml` (defaults to `true`), and `COOKIE_SECURE=true` in `.env.example`.

This is a correctly designed mechanism with appropriate defaults. The risk is documented and accepted. Noting for completeness.

**Evidence / Trace:**

```yaml
# application.yml
app:
  cookie-secure: ${COOKIE_SECURE:true}  # ← defaults to true, safe

# .env.ci
COOKIE_SECURE=false  # M33 fix — CI uses HTTP-only nginx

# .env.example
COOKIE_SECURE=true
```

**Remediation:**
- **Primary fix:** No change needed — the default is secure (`true`). The CI override is correct.
- **Defense-in-depth:** Add a startup log warning (not exception) when `cookie-secure=false` in non-test environments:
  ```java
  if (!cookieSecure && !List.of("dev", "test").contains(activeProfile)) {
      log.warn("SECURITY: cookie-secure=false in non-dev environment — cookies sent over HTTP");
  }
  ```

**References:**
- CWE-614: Sensitive Cookie in HTTPS Session Without 'Secure' Attribute

---

### Finding #8: Alertmanager Connected to Both `backend` and `frontend` Networks — Expands Blast Radius

**Vulnerability:** Excessive Network Access — A05 (Security Misconfiguration)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Line: 450 (Task 5.4, Step 5, M32 fix)

**Risk & Exploit Path:**

The M32 fix connects alertmanager to both `backend` and `frontend` networks because `backend` is `internal: true` (no external access), and alertmanager needs to reach external SMTP servers. However:

1. The `frontend` network is the public-facing network (nginx bridges it to the internet). Any service on the `frontend` network can potentially be reached by other services on that network, and alertmanager gains the ability to make outbound connections to any external host.

2. If alertmanager is compromised (CVE in the alertmanager binary, or via its web UI on port 9093), the attacker gains a foothold on **both** networks — they can pivot to backend services (PostgreSQL, Redis, MinIO) via the `backend` network AND make outbound connections via the `frontend` network.

3. Alertmanager's web UI (port 9093) is not mentioned as being access-restricted. If it's accessible from the `frontend` network, nginx could potentially proxy requests to it.

**Evidence / Trace:**

```yaml
alertmanager:
  image: prom/alertmanager:v0.27.0
  networks:
    - backend    # ← access to postgres, redis, minio
    - frontend   # ← external network access (for SMTP)
```

**Remediation:**
- **Primary fix:** Create a dedicated `outbound` network (or `smtp` network) for alertmanager's external access, instead of using `frontend`:
  ```yaml
  networks:
    backend:
      internal: true
    frontend:
      # public-facing
    smtp:
      # outbound-only, no inbound services

  alertmanager:
    networks:
      - backend   # for Prometheus communication
      - smtp      # for SMTP only
  ```
- **Alternative (simpler):** Accept the current design but add alertmanager container hardening consistent with other services:
  ```yaml
  alertmanager:
    read_only: true
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
    mem_limit: 64m
    cpus: '0.25'
  ```
- **Defense-in-depth:** Ensure alertmanager port 9093 is NOT proxied by nginx and is accessible only via SSH port forwarding (same as Grafana).

**References:**
- CWE-441: Unintended Proxy or Intermediary (network bridging)
- CWE-250: Execution with Unnecessary Privileges (network access)

---

### Finding #9: `SimpleEmailService` Missing `sendPasswordResetEmail` Implementation — Comment-Only Specification

**Vulnerability:** Incomplete Implementation Specification — A07 (Identification and Authentication Failures)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** N/A

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Line: 63 (Task 5.1, Prerequisite 2)

**Risk & Exploit Path:**

The `SimpleEmailService` code snippet shows `sendVerificationEmail` fully implemented but `sendPasswordResetEmail` only as a comment (`// similar for sendPasswordResetEmail`). If the implementer interprets this literally and copies the verification URL pattern, the password reset URL must use a different path (`/auth/reset-password?token=`), not `/auth/verify?token=`.

If both methods use the same URL path, a verification token could be used for password reset (or vice versa), leading to:
1. Token confusion — a verification token used to reset a password
2. Incorrect user flow — user clicks "verify email" but hits password reset endpoint

**Evidence / Trace:**

```java
@Override
public void sendVerificationEmail(String to, String token) {
    var msg = new SimpleMailMessage();
    msg.setTo(to);
    msg.setSubject("Verify your email");
    msg.setText(baseUrl + "/auth/verify?token=" + token);
    mailSender.send(msg);
}
// similar for sendPasswordResetEmail  // ← underspecified
```

**Remediation:**
- **Primary fix:** Specify the `sendPasswordResetEmail` method explicitly:
  ```java
  @Override
  public void sendPasswordResetEmail(String to, String token) {
      var msg = new SimpleMailMessage();
      msg.setTo(to);
      msg.setSubject("Reset your password");
      msg.setText(baseUrl + "/auth/reset-password?token=" + token);
      mailSender.send(msg);
  }
  ```
- **Already mitigated:** The existing `AuthService` uses separate `email_tokens` rows with a `purpose` field (`VERIFICATION` vs `PASSWORD_RESET`), and validates the purpose on token consumption. So even if URLs were identical, the backend would reject a verification token used for password reset.

**References:**
- CWE-345: Insufficient Verification of Data Authenticity (token confusion)

---

### Finding #10: `nginx.ci.conf` CSP `img-src` Uses `http://localhost:9000` — E2E Tests Don't Validate Production CSP for Images

**Vulnerability:** Test/Production CSP Divergence — A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** N/A

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 572–576 (Task 5.5, M27 fix)

**Risk & Exploit Path:**

The CI nginx config uses `img-src 'self' data: blob: http://localhost:9000` while production uses `img-src 'self' data: blob: https://minio.yourdomain.com`. This means:

1. E2E tests cannot validate that the production CSP correctly allows MinIO image loading
2. If the production MinIO domain changes, E2E tests will still pass but production images will be CSP-blocked
3. The `data:` and `blob:` directives are consistent, so most CSP behavior is tested

This is an accepted CI limitation already documented (M27 fix comment: "intentional CI deviation"). SA-P5-1 noted this nginx config divergence in its Pass 3 cross-cutting analysis.

**Evidence / Trace:**

```nginx
# nginx.ci.conf (M27 fix)
# CI deviation: img-src uses localhost:9000 instead of production MinIO domain
img-src 'self' data: blob: http://localhost:9000;

# nginx.prod.conf
img-src 'self' data: blob: https://minio.yourdomain.com;
```

**Remediation:**
- **Primary fix:** Accept as documented trade-off. The `nginx-validate` CI job validates production nginx syntax.
- **Improvement:** Parameterize the MinIO URL in both configs using an environment variable or template variable, so they stay synchronized:
  ```nginx
  # Both configs:
  img-src 'self' data: blob: $MINIO_CSP_ORIGIN;
  ```
  However, nginx does not support variable interpolation in `add_header` directives, so this would require `envsubst` in the Docker entrypoint. Low priority.

**References:**
- CWE-1188: Initialization with an Insecure Default (configuration divergence)

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack: Deploy Key Compromise → `rsync --server*` → VPS Takeover

**SA-P5-1 Status:** Finding #6 was addressed by adding `deploy.sh` with `command=` restriction. However, the `rsync --server*` wildcard (Finding #2 above) creates a bypass path:

1. Attacker compromises GitHub Actions runner or steals `DEPLOY_SSH_KEY`
2. Attacker uses rsync to write a modified `deploy.sh` to `/opt/jpt/deploy.sh` (or any path writable by `deploy` user)
3. Next legitimate deploy invokes the modified `deploy.sh`, which now contains attacker's code
4. Attacker achieves arbitrary code execution on VPS

**Mitigation chain required:** (a) `rrsync` for file transfer scoping, (b) `deploy.sh` owned by root (not writable by `deploy` user), (c) separate SSH keys for rsync vs. command execution.

### Implicit Trust: `SimpleEmailService` Trusts `app.base-url`

The `SimpleEmailService` trusts `app.base-url` to be correct. If an attacker can modify environment variables on the VPS (e.g., via the rsync bypass above), they can redirect all verification and password reset emails to a phishing domain. This chain compounds with Finding #2.

### Defense-in-Depth Assessment for SA-P5-1 Remediations

| SA-P5-1 Finding | v7.0 Remediation | Residual Risk |
|---|---|---|
| F1 (BYPASSRLS) | Package-private bean + ArchUnit test + column-limited queries | **Medium** — Spring ignores visibility; ArchUnit is test-time only (Finding #4) |
| F2 (Timing) | Identical 404 via single code path | **Low** — adequate for 256-bit token space |
| F3 (DELETE IDOR) | Explicit DataSource documentation + integration test | **Low** — RLS enforced on primary DataSource |
| F4 (SMTP creds) | `smtp_auth_password_file` + `.gitignore` + rsync exclude | **Low** — properly addressed |
| F5 (node-exporter) | `read_only`, `cap_drop`, `security_opt`, resource limits | **Low** — standard monitoring container hardening |
| F6 (SSH key) | `deploy.sh` with `command=` restriction | **Medium** — rsync wildcard bypass (Finding #2) |
| F7 (rsync --delete) | Expanded exclude list (`alertmanager.yml`, `certbot/`) | **Low** — adequately addressed |
| F8 (CI secrets) | Comment header + startup validation | **Low** — validation is prefix-only (Finding #3) but JwtService provides primary guard |
| F9 (MinIO proxy) | Complete regex location block specified | **Low** — properly addressed |
| F10 (CSP) | Accepted trade-off | **Low** — documented |
| F11 (Metadata XSS) | Explicit `dangerouslySetInnerHTML` prohibition + comment | **Low** — React auto-escaping is the primary guard |
| F12 (Rollback) | `:previous` image existence check | **Low** — properly addressed |

### Deployment Context: Alertmanager Container Missing Hardening

The alertmanager service specification (Task 5.4, Step 5) includes Docker secrets for SMTP credentials and dual-network connectivity, but does **not** specify container hardening (`read_only`, `cap_drop`, `security_opt`, `mem_limit`, `cpus`) that is applied to every other service. Node-exporter (SA-F5) was hardened; alertmanager was not. This is an omission — the plan should be consistent.

---

## 1. Executive Summary

Phase 5 v7.0 demonstrates significant improvement over v6.0. The SA-P5-1 remediations are substantive and correctly address the spirit of each finding. The plan now has explicit DataSource scoping documentation, ArchUnit enforcement for the BYPASSRLS DataSource, container hardening for node-exporter, Docker secrets for Alertmanager SMTP credentials, a deploy script with command restrictions, and MinIO proxy path guards.

The most significant **new** concern is the `rsync --server*` wildcard in `deploy.sh` (Finding #2), which undermines the SA-F6 SSH restriction. An attacker with the deploy key could use rsync to write arbitrary files to the VPS, including modifying `deploy.sh` itself. This should be addressed with `rrsync` or path-restricted rsync validation.

The `ShareReaderDataSourceConfig` package-private scoping (Finding #4) is presented as a security control but Spring ignores Java visibility for bean resolution. The ArchUnit test is the actual enforcement mechanism, and this should be communicated clearly.

The remaining findings are Medium-to-Low severity — `SimpleEmailService` URL construction, JWT validation prefix check, token format validation mismatch, alertmanager network scoping, and CI-specific configurations. None represent fundamental architectural flaws.

**Recommendation:** Address Finding #2 (rsync wildcard) before deployment. The remaining findings can be addressed during implementation. The plan is ready for implementation with this single mandatory fix.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | SimpleEmailService URL String Concatenation | A03 | Medium | High | 1 | FIX |
| 2 | deploy.sh rsync Wildcard Allows Arbitrary Writes | A01 | High | High | 1 | BLOCK |
| 3 | JWT Startup Validation Prefix-Only Check | A07 | Medium | High | 1 | ACCEPT |
| 4 | Package-Private Bean Scoping Ineffective in Spring | A01 | Medium | Medium | 1 | FIX |
| 5 | E2E MailPit Message Selection Fragile | A09 | Low | High | 1 | FIX |
| 6 | Share Token Format Validation Mismatch | A03 | Medium | High | 1 | FIX |
| 7 | COOKIE_SECURE=false in CI Documented | A05 | Low | Confirmed | 1 | ACCEPT |
| 8 | Alertmanager Dual-Network Blast Radius | A05 | Medium | Confirmed | 1 | FIX |
| 9 | sendPasswordResetEmail Underspecified | A07 | Low | High | 1 | FIX |
| 10 | CI CSP img-src Divergence | A05 | Low | Confirmed | 1 | ACCEPT |

---

## 3. Security Quality Score (SQS)

**Calculation:**

| Finding Severity | Count | Deduction |
|---|---|---|
| Critical | 0 | 0 |
| High | 1 (F2) | −20 |
| Medium | 4 (F1, F3, F4, F6, F8) | −40 |
| Low | 4 (F5, F7, F9, F10) | −8 |

**Raw Score:** 100 − 20 − 40 − 8 = **32**/100

Adjusting for context:
- F3 (JWT validation): Existing `JwtService` provides primary guard; SA-F8 is supplemental → effective deduction −4
- F4 (package-private): ArchUnit test is the real guard; this is a documentation/clarity issue → effective deduction −4
- F8 (alertmanager network): High attack complexity, requires alertmanager RCE first → effective deduction −4

**Adjusted Score:** 100 − 20 − 28 − 8 = **44**/100

Comparing to SA-P5-1's score of 30/100 (against v6.0), this represents improvement. The v7.0 plan resolved 4 High-severity findings from SA-P5-1 (F1, F3, F6, F9), leaving 1 new High (the rsync wildcard).

**Final SQS:** 44/100
**Hard gates triggered:** No (no Critical findings, no hardcoded production secrets)
**Posture:** Unacceptable — remediate the High finding (F2: rsync wildcard) before deployment

---

## 4. Positive Security Observations

1. **Comprehensive SA-P5-1 remediation.** All 12 findings from the first audit were substantively addressed in v7.0, with code-level mitigations (ArchUnit tests, container hardening, Docker secrets, regex path guards) rather than superficial documentation changes.

2. **Defense-in-depth layering for `share_reader` BYPASSRLS.** Five independent controls: package-private bean, dedicated repository, ArchUnit test, column-limited parameterized queries, and token format validation. Even if one fails, the remaining layers provide protection.

3. **Deploy script with subcommand whitelist.** The `deploy.sh` script restricts SSH operations to five predefined subcommands (`tag-previous`, `build`, `healthcheck`, `rollback`, `rsync --server*`). This is a significant improvement over unrestricted SSH access.

4. **Alertmanager credential handling via Docker secrets.** Using `smtp_auth_password_file` (a proper runtime secret injection mechanism) instead of plaintext in the config file demonstrates mature secret management.

5. **Consistent container hardening applied to new services.** Node-exporter received `read_only`, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, and resource limits — matching the established pattern for all other services.

---

## 5. Prioritized Remediation Roadmap

### Priority 1: Restrict rsync Destination Path in deploy.sh (Finding #2)
- **Why:** The `rsync --server*` wildcard allows writing to any path writable by the `deploy` user, enabling deploy script modification and VPS takeover
- **Severity × Exploitability × Blast Radius:** High × Medium × Critical (full VPS compromise)
- **Effort:** Quick Win — replace `$SSH_ORIGINAL_COMMAND` passthrough with `rrsync /opt/jpt/` in authorized_keys, or validate the rsync destination path in the case statement
- **Owner:** DevOps

### Priority 2: Clarify Share Token Encoding Format (Finding #6)
- **Why:** Plan contradiction — SA-F1 says "hex, 64 chars" but E2E regex matches base64url. Incorrect validation would break share lookups or fail to validate
- **Effort:** Quick Win — decide on encoding format, update plan text and validation regex
- **Owner:** Backend

### Priority 3: Fix ShareReaderDataSourceConfig Documentation (Finding #4)
- **Why:** Package-private is presented as a security control but Spring ignores visibility. ArchUnit is the real guard — plan should reflect this
- **Effort:** Quick Win — update plan language
- **Owner:** Backend

### Priority 4: Use UriComponentsBuilder in SimpleEmailService (Finding #1)
- **Why:** String concatenation for URL construction is fragile; Spring provides safe builders
- **Effort:** Quick Win — use `UriComponentsBuilder` instead of string concatenation
- **Owner:** Backend

### Priority 5: Add Alertmanager Container Hardening (Finding #8)
- **Why:** Only monitoring service missing `read_only`, `cap_drop`, `security_opt`, resource limits. Connected to both networks, increasing blast radius
- **Effort:** Quick Win — add hardening directives matching other services
- **Owner:** DevOps
