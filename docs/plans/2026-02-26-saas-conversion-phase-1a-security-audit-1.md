# Security Audit — Phase 1a: Spring Boot Scaffold & Database

> **Auditor:** LCSA (Lead Cyber-Security Auditor)
> **Date:** 2026-02-26
> **Target:** `docs/plans/2026-02-25-saas-conversion-phase-1a.md` (v4.0)
> **Scope:** Plan review — Gradle build, Spring Boot config, Flyway migrations (V1–V3), RLS policies, test scaffolding. This is a white-box review of the planned implementation before code is written.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Entry points in scope:** Spring Boot application config (application.yml), database schema (Flyway migrations), database roles and RLS policies.

**Trust boundaries:**
- Flyway migrations run as `jpt` (superuser) — full schema control
- API runtime uses `jpt_app` (non-superuser, RLS-subject)
- Worker uses `worker_db_user` (restricted grants)
- `app.current_user_id` session variable is the RLS pivot — set per-request in Phase 2

**Sensitive data flows:** User credentials (password_hash), email tokens, JWT secret, database passwords, MinIO credentials, photo metadata/EXIF.

**Out of scope:** Authentication/authorization middleware (Phase 2), API controllers, frontend, Docker Compose, Nginx, CI/CD.

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: Default MinIO Credentials in Application Config

**Vulnerability:** Hardcoded Default Credentials — OWASP A07 (Identification and Authentication Failures)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan, Task 1.1, Step 4 — `application.yml`, lines 207–208

**Risk & Exploit Path:**
The MinIO configuration uses `minioadmin:minioadmin` as default values for access and secret keys:
```yaml
minio:
  access-key: ${MINIO_ACCESS_KEY:minioadmin}  # ← DEFAULT CREDENTIALS
  secret-key: ${MINIO_SECRET_KEY:minioadmin}  # ← DEFAULT CREDENTIALS
```
If environment variables are not set (misconfigured deployment, dev profile accidentally used in prod), the application connects to MinIO with well-known default credentials. An attacker who reaches MinIO (even indirectly via SSRF) could perform arbitrary bucket operations.

**Remediation:**
- **Primary fix:** Remove defaults — use `${MINIO_ACCESS_KEY}` and `${MINIO_SECRET_KEY}` without fallback values. Application fails fast on startup if not configured, which is the correct behavior for secrets.
- **Defense-in-depth:** Only provide defaults in `application-dev.yml` for local development. Production profile must have no credential defaults.

---

### Finding #2: `jpt_app` and `worker_db_user` Hardcoded Passwords in Migrations

**Vulnerability:** Hardcoded Credentials in Source Code — OWASP A07
**Severity:** High
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan, Task 1.3, Step 3 — `V2__rls_policies.sql`, line 592
- File: Plan, Task 1.4, Step 3 — `V3__worker_db_user.sql`, line 753

**Risk & Exploit Path:**
Both `CREATE ROLE` statements use `PASSWORD 'changeme'`:
```sql
CREATE ROLE jpt_app WITH LOGIN PASSWORD 'changeme';
CREATE ROLE worker_db_user WITH LOGIN PASSWORD 'changeme';
```
Flyway migrations are version-controlled and immutable. Even with comments saying "must be overridden in production," the `changeme` password is baked into the migration history. If the `ALTER ROLE` override step is missed during deployment, both roles are accessible with a trivial password. The `jpt_app` role has full DML access to all tables; `worker_db_user` can read all photos and write metadata.

**Remediation:**
- **Primary fix:** Use environment variable substitution in the migration via Flyway placeholders:
  ```sql
  CREATE ROLE jpt_app WITH LOGIN PASSWORD '${jpt_app_password}';
  ```
  Configure `spring.flyway.placeholders.jpt_app_password` from an environment variable. If Flyway placeholders are undesirable in SQL, use a `V2.1__set_passwords.sql` repeatable migration (`R__`) that runs `ALTER ROLE jpt_app PASSWORD ...` using a placeholder.
- **Defense-in-depth:** Add a startup health check that verifies `jpt_app` password is not `changeme` (query `pg_authid` or attempt connection with known-bad password and assert failure).

---

### Finding #3: RLS Bypass Window — `connection-init-sql` Nil UUID is Insufficient

**Vulnerability:** Broken Access Control — OWASP A01
**Severity:** Medium
**Confidence:** Medium (depends on Phase 2 implementation)
**Attack Complexity:** Medium

**Location:**
- File: Plan, Task 1.1, Step 4 — `application.yml`, line 181

**Risk & Exploit Path:**
The safety net relies on `connection-init-sql` setting `app.current_user_id` to a nil UUID. This is connection-scoped, not transaction-scoped. If a request handler fails to call `SET LOCAL app.current_user_id` (bug, new endpoint, exception before interceptor runs), the connection retains the nil UUID from init. The nil UUID returns empty results (good), but this creates a silent failure mode — a broken endpoint returns no data instead of a 500 error, making the bug hard to detect.

More critically, the plan notes this interceptor doesn't exist until Phase 2. During Phase 1a development/testing, any ad-hoc queries through the application context run with the nil UUID and silently return nothing, potentially masking schema or data issues.

**Remediation:**
- **Primary fix:** Ensure Phase 2 implements the `SET LOCAL` interceptor with mandatory coverage (integration test that asserts every controller endpoint sets the user context). This is already noted as a cross-phase dependency — just flagging for tracking.
- **Defense-in-depth:** Add a database-side assertion function that raises an exception if `app.current_user_id` equals the nil UUID, callable from application code to fail-fast rather than silently return empty.

---

### Finding #4: `jpt_app` Has DELETE on `users` Table (No RLS on `users`)

**Vulnerability:** Broken Access Control — OWASP A01
**Severity:** High
**Confidence:** Confirmed
**Attack Complexity:** Medium

**Location:**
- File: Plan, Task 1.3, Step 3 — `V2__rls_policies.sql`, lines 596–597

**Risk & Exploit Path:**
The migration grants `jpt_app` full DML (`SELECT, INSERT, UPDATE, DELETE`) on ALL tables, including `users`. But the `users` table has **no RLS policy** — it is not listed in the `ENABLE ROW LEVEL SECURITY` or `CREATE POLICY` statements.

This means the `jpt_app` role can:
1. `SELECT * FROM users` — enumerate all users, emails, password hashes, OAuth IDs
2. `UPDATE users SET quota_bytes = 999999999999 WHERE id = ?` — self-escalate quota
3. `DELETE FROM users WHERE id = ?` — delete arbitrary users
4. `UPDATE users SET password_hash = ? WHERE email = ?` — take over any account

Any SQL injection or application-layer authorization bug in Phase 2+ becomes a full user-table compromise because there's no RLS safety net on the most sensitive table.

**Remediation:**
- **Primary fix:** Add RLS policy on `users`:
  ```sql
  ALTER TABLE users ENABLE ROW LEVEL SECURITY;
  ALTER TABLE users FORCE ROW LEVEL SECURITY;
  CREATE POLICY tenant_users ON users
      USING (id = current_setting('app.current_user_id')::uuid);
  ```
  This ensures a user can only see/modify their own row.
- **Architectural improvement:** For operations that need cross-user access (admin, registration, login), use the `jpt` superuser connection or a dedicated service role that bypasses RLS. Registration and login flows should use a separate datasource or `SET ROLE` to a role exempt from RLS on `users`.

---

### Finding #5: `email_tokens` Table Has No RLS Policy

**Vulnerability:** Broken Access Control — OWASP A01
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Medium

**Location:**
- File: Plan, Task 1.3, Step 3 — `V2__rls_policies.sql` (absent)

**Risk & Exploit Path:**
The `email_tokens` table stores password reset and email verification token hashes. It has no RLS policy, meaning `jpt_app` can query all tokens across all users. If an attacker achieves SQL injection, they can:
1. Enumerate all pending password reset tokens
2. Correlate `token_hash` values with users to target account takeover

While the tokens are hashed (SHA-256), the absence of tenant isolation on this table is a defense-in-depth gap.

**Remediation:**
- **Primary fix:** Add RLS on `email_tokens`:
  ```sql
  ALTER TABLE email_tokens ENABLE ROW LEVEL SECURITY;
  ALTER TABLE email_tokens FORCE ROW LEVEL SECURITY;
  CREATE POLICY tenant_email_tokens ON email_tokens
      USING (user_id = current_setting('app.current_user_id')::uuid);
  ```
- **Note:** Registration/verification flows may need to bypass this RLS via a privileged role, since the user may not be authenticated yet when verifying email.

---

### Finding #6: `shares` Table RLS May Be Too Restrictive for Public Share Access

**Vulnerability:** Business Logic Flaw — Access Control Design Gap
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** N/A (design issue)

**Location:**
- File: Plan, Task 1.3, Step 3 — `V2__rls_policies.sql`, lines 632–633

**Risk & Exploit Path:**
The `tenant_shares` RLS policy restricts access to `user_id = current_setting('app.current_user_id')::uuid`. But share links are accessed by **unauthenticated** users (or users who are not the share owner). The share validation flow needs to:
1. Look up a share by `token_hash` (no user context)
2. Verify expiry
3. Load the referenced resource

With this RLS policy, an unauthenticated request (nil UUID context) cannot look up any share. The application will need a privileged code path to validate share tokens.

**Remediation:**
- **Architectural:** Document that the share-link validation endpoint must use a privileged role or bypass RLS. Consider a separate `POLICY` for `SELECT` on `shares` that allows access by `token_hash` match, or handle share validation in a service that uses the superuser datasource.

---

### Finding #7: Dev Profile JWT Secret in Source Code

**Vulnerability:** Hardcoded Secret — OWASP A02 (Cryptographic Failures)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: Plan, Task 1.1, Step 5 — `application-dev.yml`, line 226

**Risk & Exploit Path:**
```yaml
app:
  jwt-secret: ${JWT_SECRET:dev-secret-change-me-in-prod-must-be-at-least-256-bits-long-ok}
```
The dev JWT secret is in version control. While gated behind the `dev` profile and clearly labeled, if the dev profile is accidentally activated in production (common misconfiguration), all JWTs are signed with a known key, enabling full authentication bypass.

**Remediation:**
- **Primary fix:** Acceptable for development as-is, but add a startup validator that checks `spring.profiles.active` and rejects the dev JWT secret if the profile is `prod` or unset. The production `application.yml` already requires `${JWT_SECRET}` without default (good).
- **Defense-in-depth:** Already mitigated by having no default in `application.yml`. Low residual risk.

---

### Finding #8: Test Profile Uses Shared Flyway/App Datasource URL

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: Plan, Task 1.1, Step 6 — `application-test.yml`, lines 234–237

**Risk & Exploit Path:**
The test profile uses `jdbc:tc:postgresql:16:///jpt` for both `spring.datasource.url` and `spring.flyway.url`. Since Testcontainers uses the default superuser for both, Flyway migrations and the application runtime share the same superuser connection. This means RLS tests that use `SET ROLE jpt_app` are testing correctly (as documented), but the test doesn't validate that the production separation of Flyway (superuser) and app (jpt_app) datasource configurations works end-to-end.

**Remediation:**
- **Note:** Already documented as a known test/prod divergence in the plan header. Acceptable for Phase 1a. Consider adding an integration test in a later phase that validates dual-datasource configuration.

---

### Finding #9: No Rate Limiting on Database Role Creation

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Low
**Confidence:** Low (requires pre-existing access)
**Attack Complexity:** High

**Location:**
- File: Plan, Task 1.3, Step 3 — `V2__rls_policies.sql`, lines 587–593

**Risk & Exploit Path:**
The `IF NOT EXISTS` guard on role creation means re-running the migration (or a repeatable migration) won't fail — but it also means the `PASSWORD 'changeme'` is only set on first run. If an operator manually creates the role with a strong password before Flyway runs, the migration silently skips creation but also skips the `GRANT` statements — wait, no, the grants are outside the `DO $$` block, so they always execute. This is actually fine.

**Remediation:**
- No action needed. The `IF NOT EXISTS` pattern with grants outside the conditional block is correct.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack: Finding #2 + Finding #4 = Critical

If `jpt_app` retains the `changeme` password (Finding #2) AND the `users` table lacks RLS (Finding #4), an attacker who discovers the database port (default 5432) can connect as `jpt_app` with `changeme` and dump the entire `users` table including password hashes and OAuth tokens. **This combination elevates to Critical severity.**

### Implicit Trust Assumptions

1. **Phase 2 will implement `SET LOCAL` correctly.** The entire RLS security model depends on an interceptor that doesn't exist yet. The plan documents this dependency, which is good, but there's no enforcement mechanism ensuring Phase 2 is complete before deployment.

2. **`FORCE ROW LEVEL SECURITY` does not apply to superusers.** The plan correctly documents that `jpt` (superuser) bypasses RLS, and the Flyway datasource uses `jpt`. However, if any application code accidentally uses the Flyway datasource instead of the app datasource, RLS is silently bypassed.

### Defense-in-Depth Gaps

- The `users` and `email_tokens` tables are the highest-value targets and have no RLS protection.
- The `worker_db_user` has `SELECT ON photos` without RLS filtering — it can read all photos metadata across all users. Consider whether RLS should apply to the worker role with a worker-specific policy, or whether this is acceptable given the worker's restricted network position.

---

## Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar | Status |
|---|-------|----------|----------|------------|---------|--------|
| 1 | Default MinIO credentials in config | A07 | Medium | High | — | FIX |
| 2 | Hardcoded `changeme` passwords in migrations | A07 | High | High | 2 instances | **BLOCK** |
| 3 | RLS bypass window — nil UUID silent failure | A01 | Medium | Medium | — | FIX |
| 4 | No RLS on `users` table | A01 | High | Confirmed | — | **BLOCK** |
| 5 | No RLS on `email_tokens` table | A01 | Medium | Confirmed | — | FIX |
| 6 | `shares` RLS too restrictive for public access | Logic | Low | Medium | — | DESIGN |
| 7 | Dev JWT secret in source code | A02 | Low | High | — | ACCEPT |
| 8 | Test profile datasource divergence | A05 | Low | Medium | — | ACCEPT |
| 9 | Role creation idempotency (false positive) | A05 | Low | Low | — | OK |

---

## Executive Summary

Phase 1a establishes a solid foundation with well-thought-out RLS policies, least-privilege worker role, and careful separation of Flyway (superuser) and application (restricted) database roles. The plan reflects multiple rounds of review with progressive hardening.

However, two issues warrant blocking deployment: (1) the `users` table — the single highest-value target containing password hashes, emails, and OAuth credentials — has **no RLS policy**, while all other tenant tables do; and (2) database role passwords are hardcoded as `changeme` in immutable Flyway migrations, creating a deployment footgun. The combination of these two issues creates a critical chain: a missed password override exposes the entire user table to anyone who can reach PostgreSQL.

The remaining findings are medium/low severity and represent reasonable design trade-offs or items that need architectural decisions (e.g., how share-link validation bypasses RLS). The plan's documentation of cross-phase dependencies and known test/prod divergences demonstrates security-conscious engineering.

---

## Security Quality Score (SQS)

| Finding | Severity | Deduction |
|---------|----------|-----------|
| #2 | High | −20 |
| #4 | High | −20 |
| #1 | Medium | −8 |
| #3 | Medium | −8 |
| #5 | Medium | −8 |
| #6 | Low | −2 |
| #7 | Low | −2 |
| #8 | Low | −2 |

**Final SQS:** 30/100
**Hard gates triggered:** No (no unremediated Critical as standalone — the Critical chain is a composition)
**Posture:** Unacceptable — block deployment, urgent remediation required on Findings #2 and #4.

---

## Positive Security Observations

1. **RLS-by-default architecture.** All tenant data tables have RLS policies with `FORCE ROW LEVEL SECURITY`. This is a strong defense-in-depth posture.
2. **Nil UUID safety net.** The `connection-init-sql` with a CHECK constraint preventing the nil UUID from being a real user is a thoughtful failsafe against uninitialized RLS context.
3. **Least-privilege worker role.** `worker_db_user` has column-level grants (not table-level) on `photos` and no access to `users`, `shares`, or authentication tables. Excellent practice.
4. **Flyway/app datasource separation.** Using separate credentials for Flyway (superuser) and the application (RLS-subject) prevents migration operations from being constrained by RLS, while ensuring runtime queries are.
5. **Comprehensive test coverage.** RLS tests validate tenant isolation with `SET ROLE` simulation, worker tests verify negative permission grants, and schema tests cover all tables. Tests are well-structured with proper cleanup.

---

## Prioritized Remediation Roadmap

| Priority | Finding | Title | Why | Effort | Owner |
|----------|---------|-------|-----|--------|-------|
| 1 | #4 | Add RLS to `users` table | Highest-value table, unprotected; enables full user enumeration/takeover if any app-layer bug exists | Quick Win | Backend |
| 2 | #2 | Remove hardcoded `changeme` passwords | Creates critical chain with #4; deployment footgun | Moderate (Flyway placeholder setup) | Backend/DevOps |
| 3 | #5 | Add RLS to `email_tokens` table | Contains password reset token hashes; defense-in-depth | Quick Win | Backend |
| 4 | #1 | Remove MinIO default credentials | Prevents misconfigured deployment from using well-known credentials | Quick Win | Backend |
| 5 | #3 | Add fail-fast on nil UUID | Convert silent empty-result failure to loud error for faster bug detection | Moderate | Backend |
