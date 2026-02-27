# Security Audit — Phase 1a: Spring Boot Scaffold & Database (Post-Remediation)

> **Auditor:** LCSA (Lead Cyber-Security Auditor)
> **Date:** 2026-02-26
> **Target:** `docs/plans/2026-02-25-saas-conversion-phase-1a.md` (v5.0)
> **Scope:** Re-audit after v1 security audit remediation. Focus on: correctness of applied fixes, newly introduced issues, and any remaining gaps. White-box review of the planned implementation before code is written.
> **Prior audit:** `2026-02-26-saas-conversion-phase-1a-security-audit-1.md` (9 findings, 6 remediated in v5.0)

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Changes since v4.0 (audit v1 target):**
- RLS added to `users` and `email_tokens` tables
- `assert_user_context()` PostgreSQL function added
- Flyway placeholders replace hardcoded passwords
- MinIO defaults removed from production config
- Flyway placeholder config added to `application.yml` and `application-dev.yml`
- Documentation comments added for shares RLS and privileged auth flows

**Trust boundaries (unchanged):**
- Flyway migrations run as `jpt` (superuser)
- API runtime uses `jpt_app` (non-superuser, RLS-subject)
- Worker uses `worker_db_user` (restricted grants, non-superuser, RLS-subject)
- `app.current_user_id` session variable is the RLS pivot

**Key observation:** The worker role `worker_db_user` is a non-superuser with explicit grants on `photos` and `photo_metadata` — both tables now have `ENABLE ROW LEVEL SECURITY`. The worker has no `SET LOCAL app.current_user_id` mechanism (it processes photos for all users). This creates a conflict between RLS enforcement and the worker's cross-tenant access requirement.

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: `worker_db_user` Blocked by RLS on `photos` and `photo_metadata`

**Vulnerability:** Broken Access Control / Denial of Service — Design Flaw
**Severity:** High
**Confidence:** Confirmed
**Attack Complexity:** N/A (functional failure, not an exploit)

**Location:**
- File: Plan, Task 1.3, Step 3 — `V2__rls_policies.sql`, lines 631–632 (ENABLE RLS on photos, photo_metadata)
- File: Plan, Task 1.4, Step 3 — `V3__worker_db_user.sql`, lines 799–805 (worker grants)

**Risk & Exploit Path:**
The `photos` and `photo_metadata` tables have `ENABLE ROW LEVEL SECURITY`. PostgreSQL RLS applies to ALL non-superuser, non-owner roles — including `worker_db_user`. The only RLS policies on these tables are:
```sql
CREATE POLICY tenant_photos ON photos
    USING (user_id = current_setting('app.current_user_id')::uuid);
CREATE POLICY tenant_photo_metadata ON photo_metadata
    USING (user_id = current_setting('app.current_user_id')::uuid);
```
The worker container uses `connection-init-sql` to set `app.current_user_id` to the nil UUID (or has no init SQL, in which case `current_setting` returns NULL). Either way, no rows match. The worker's `SELECT ON photos` and `INSERT, UPDATE ON photo_metadata` grants are rendered useless — every query returns zero rows, every insert/update is silently rejected by the `WITH CHECK` implied by `USING`.

**Impact:** The image processing pipeline is completely non-functional. Photos remain in `pending` status permanently. This is not a security vulnerability per se but a **security control breaking core functionality**, which typically leads to developers weakening or bypassing security to "make it work."

**Evidence / Trace:**
```
Worker connects as worker_db_user
  → connection-init-sql sets app.current_user_id = nil UUID (or NULL)
  → SELECT * FROM photos WHERE processing_status = 'pending'
  → RLS filters: user_id = '00000000-...'::uuid → matches nothing
  → Worker sees 0 jobs → image processing never happens
```

**Remediation:**
- **Primary fix:** Add role-specific RLS policies for `worker_db_user` in V2 (or V3):
  ```sql
  -- Worker needs cross-tenant access to process all photos
  CREATE POLICY worker_read_photos ON photos
      TO worker_db_user USING (true);
  CREATE POLICY worker_write_photo_metadata ON photo_metadata
      TO worker_db_user USING (true) WITH CHECK (true);
  ```
  PostgreSQL evaluates policies per-role: `jpt_app` still gets tenant-scoped policies, while `worker_db_user` gets unrestricted access within its grant limits (SELECT on photos, INSERT/UPDATE on photo_metadata).
- **Defense-in-depth:** The worker's column-level grants already limit what it can do (cannot DELETE photos, cannot access `users`/`shares`/etc.). The `USING (true)` policy is safe given the grant restrictions.
- **Test:** Add positive-path tests for worker (see Finding #3).

---

### Finding #2: Test Profile Fails on Unresolved Environment Variables

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** N/A (build/test failure)

**Location:**
- File: Plan, Task 1.1, Step 4 — `application.yml`, lines 178, 192, 194–195
- File: Plan, Task 1.1, Step 6 — `application-test.yml`, lines 241–253

**Risk & Exploit Path:**
The base `application.yml` defines several properties with no default values:
```yaml
spring:
  datasource:
    password: ${DB_PASS}              # ← no default
  flyway:
    password: ${FLYWAY_DB_PASS}       # ← no default
    placeholders:
      jpt_app_password: ${DB_PASS}              # ← no default
      worker_db_user_password: ${WORKER_DB_PASS} # ← no default
```
The `application-test.yml` does NOT override these properties. Spring Boot merges profiles — unoverridden properties from `application.yml` still resolve. Spring's property resolver throws `IllegalArgumentException` on unresolved `${}` placeholders at startup.

**Impact:** All tests fail to start. This blocks CI/CD and development. The likely "fix" by a developer under time pressure is to add insecure defaults to `application.yml`, undoing the security hardening from audit v1.

**Remediation:**
- **Primary fix:** Add placeholder overrides to `application-test.yml`:
  ```yaml
  spring:
    datasource:
      password: test
    flyway:
      password: test
      placeholders:
        jpt_app_password: testpass
        worker_db_user_password: testpass
  ```
  These are test-only values used by Testcontainers — they carry no security risk.

---

### Finding #3: No Positive-Path Tests for `worker_db_user` Grants

**Vulnerability:** Insufficient Test Coverage — Defense-in-Depth Gap
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** N/A

**Location:**
- File: Plan, Task 1.4, Step 1 — `WorkerDbUserTest.java`, lines 706–774

**Risk & Exploit Path:**
The `WorkerDbUserTest` only tests negative cases (worker CANNOT access users, CANNOT delete photos, CANNOT access shares). There are no tests verifying the worker CAN do what it needs:
1. `SELECT FROM photos` (read job details)
2. `UPDATE photos SET processing_status = ...` (mark jobs)
3. `INSERT INTO photo_metadata` (write extracted metadata)

Without positive tests, Finding #1 (worker blocked by RLS) would not be caught by the test suite. The tests would pass while the worker is completely non-functional.

**Remediation:**
- **Primary fix:** Add positive-path tests to `WorkerDbUserTest`:
  ```java
  @Test
  void workerCanReadPhotos() {
      // Insert test data as superuser
      UUID userId = UUID.randomUUID();
      UUID photoId = UUID.randomUUID();
      jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
          userId, userId + "@test.com", "hash");
      jdbc.update("INSERT INTO photos (id, user_id, filename) VALUES (?, ?, ?)",
          photoId, userId, "test.jpg");

      try {
          jdbc.execute("SET ROLE worker_db_user");
          Integer count = jdbc.queryForObject(
              "SELECT count(*) FROM photos WHERE id = ?",
              Integer.class, photoId);
          assertThat(count).isEqualTo(1);
      } finally {
          jdbc.execute("RESET ROLE");
          jdbc.update("DELETE FROM photos WHERE id = ?", photoId);
          jdbc.update("DELETE FROM users WHERE id = ?", userId);
      }
  }
  ```
  Similar tests for `UPDATE photos` (column-level) and `INSERT INTO photo_metadata`.

---

### Finding #4: Flyway Placeholder Injection via Special Characters in Password

**Vulnerability:** Injection — OWASP A03
**Severity:** Low
**Confidence:** High
**Attack Complexity:** High (requires operator-controlled env var to contain SQL metacharacters)

**Location:**
- File: Plan, Task 1.3, Step 3 — `V2__rls_policies.sql`, line 602
- File: Plan, Task 1.4, Step 3 — `V3__worker_db_user.sql`, line 791

**Risk & Exploit Path:**
Flyway placeholders perform naive text substitution before sending SQL to PostgreSQL:
```sql
CREATE ROLE jpt_app WITH LOGIN PASSWORD '${jpt_app_password}';
```
If the password environment variable contains a single quote (e.g., `p@ss'word` or a generated password from a secret manager), Flyway produces malformed SQL:
```sql
CREATE ROLE jpt_app WITH LOGIN PASSWORD 'p@ss'word';
```
This causes a migration failure at best, or SQL injection within the PL/pgSQL `DO $$` block at worst. While the attacker must control the deployment environment variable (meaning they already have deployment access), this creates a fragility that could:
1. Break migrations during deployment with generated passwords
2. Lead operators to use weak passwords to avoid the issue

**Remediation:**
- **Primary fix:** Move role password management out of the `DO $$` block and use `EXECUTE` with `format()` for proper literal escaping:
  ```sql
  DO $$
  BEGIN
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jpt_app') THEN
          EXECUTE format('CREATE ROLE jpt_app WITH LOGIN PASSWORD %L',
                         current_setting('app.jpt_app_password'));
      END IF;
  END
  $$;
  ```
  However, this requires passing the password as a PostgreSQL setting rather than a Flyway placeholder, which adds complexity.
- **Pragmatic fix:** Document that Flyway placeholder passwords must not contain single quotes. Add a CI check that validates the password value. Accept the residual risk given that operators control the env vars.
- **Alternative:** Use a repeatable migration (`R__set_role_passwords.sql`) with `ALTER ROLE ... PASSWORD ...` that runs after initial creation, allowing the CREATE ROLE to use a throwaway password.

---

### Finding #5: `assert_user_context()` Function Has No Test

**Vulnerability:** Insufficient Test Coverage
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** N/A

**Location:**
- File: Plan, Task 1.3, Step 3 — `V2__rls_policies.sql`, lines 617–624
- File: Plan, Task 1.3, Step 1 — `RlsTest.java` (absent)

**Risk & Exploit Path:**
The `assert_user_context()` function was added as a fail-fast mechanism, but no test verifies:
1. The function exists after migration
2. It raises an exception when `app.current_user_id` is nil UUID
3. It raises an exception when `app.current_user_id` is not set
4. It passes when a valid UUID is set

Without tests, the function could be broken (e.g., typo in the nil UUID string) and the safety net would be silently absent.

**Remediation:**
- **Primary fix:** Add tests to `RlsTest`:
  ```java
  @Test
  void assertUserContextRejectsNilUuid() {
      assertThatThrownBy(() ->
          txTemplate.executeWithoutResult(status -> {
              jdbc.execute("SET LOCAL app.current_user_id = '00000000-0000-0000-0000-000000000000'");
              jdbc.execute("SELECT assert_user_context()");
          }))
          .hasMessageContaining("not set");
  }

  @Test
  void assertUserContextAcceptsValidUuid() {
      txTemplate.executeWithoutResult(status -> {
          jdbc.execute("SET LOCAL app.current_user_id = '" + UUID.randomUUID() + "'");
          jdbc.execute("SELECT assert_user_context()");
          status.setRollbackOnly();
      });
  }
  ```

---

### Finding #6: `jpt_app` Has Full DML on `flyway_schema_history`

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: Plan, Task 1.3, Step 3 — `V2__rls_policies.sql`, line 608

**Risk & Exploit Path:**
```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jpt_app;
```
This grants `jpt_app` full DML on `flyway_schema_history` (which exists in the `public` schema). If an attacker gains `jpt_app` access (e.g., via SQL injection), they could:
1. Delete migration records to mask schema tampering
2. Insert fake migration records to prevent legitimate migrations from running
3. Modify checksums to cause Flyway validation failures (denial of service)

The `ALTER DEFAULT PRIVILEGES` also ensures any future Flyway-internal tables get the same grants.

**Remediation:**
- **Primary fix:** Revoke `jpt_app` access to `flyway_schema_history` after the broad grant:
  ```sql
  REVOKE ALL ON flyway_schema_history FROM jpt_app;
  ```
  Place this after the `GRANT ... ON ALL TABLES` statement.
- **Note:** This is low severity because exploiting it requires pre-existing `jpt_app` access, and the blast radius is limited to migration state.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Remediation Verification from Audit v1

| v1 Finding | Status | Verification |
|------------|--------|-------------|
| #1 MinIO defaults | **Fixed** | `application.yml` has no defaults; `application-dev.yml` has defaults. Correct. |
| #2 Hardcoded passwords | **Fixed** | Flyway placeholders used. New risk: placeholder injection (Finding #4 above). |
| #3 Nil UUID silent fail | **Fixed** | `assert_user_context()` function added. Needs tests (Finding #5). |
| #4 No RLS on `users` | **Fixed** | `tenant_users` policy added. Correct `USING (id = ...)` clause. |
| #5 No RLS on `email_tokens` | **Fixed** | `tenant_email_tokens` policy added. Correct. |
| #6 Shares RLS design | **Documented** | Comment added noting privileged path needed. Acceptable. |
| #7 Dev JWT secret | **Accepted** | No change needed. Correct. |
| #8 Test datasource | **Accepted** | No change needed. Correct. |
| #9 Role creation | **False positive** | Confirmed. |

### Newly Introduced Issues

The v5.0 remediation introduced two new issues:
1. **Finding #1 (Worker RLS):** Pre-existing design gap that became visible because the audit correctly added RLS to more tables — but didn't account for the worker's cross-tenant access pattern. The v1 audit noted the worker has `SELECT ON photos` without RLS filtering but recommended it as acceptable. Now that all tables have RLS, the worker is blocked.
2. **Finding #2 (Test config):** The removal of default values from `application.yml` (correct for security) breaks the test profile which inherits those properties.

### Implicit Trust Assumptions

1. **Flyway placeholder values are SQL-safe.** No validation or escaping occurs (Finding #4).
2. **`application-test.yml` inherits safe defaults from `application.yml`.** False — it inherits unresolvable `${}` expressions (Finding #2).

### Defense-in-Depth Assessment

The v5.0 plan significantly improved defense-in-depth:
- `users` and `email_tokens` now have RLS (closing the two highest-value gaps from v1)
- `assert_user_context()` provides fail-fast on misconfigured requests
- Credentials are externalized via Flyway placeholders

Remaining gaps are low severity and relate to test coverage and edge-case robustness.

---

## Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar | Status |
|---|-------|----------|----------|------------|---------|--------|
| 1 | Worker blocked by RLS on `photos`/`photo_metadata` | Logic/A01 | High | Confirmed | 2 tables | **BLOCK** |
| 2 | Test profile fails on unresolved env vars | A05 | Medium | High | 4 properties | FIX |
| 3 | No positive-path tests for worker grants | Testing | Low | Confirmed | — | FIX |
| 4 | Flyway placeholder SQL injection via special chars | A03 | Low | High | 2 instances | FIX |
| 5 | `assert_user_context()` has no test | Testing | Low | Confirmed | — | FIX |
| 6 | `jpt_app` has DML on `flyway_schema_history` | A05 | Low | Confirmed | — | FIX |

---

## Executive Summary

The v5.0 plan successfully remediates all six findings from security audit v1. The `users` and `email_tokens` tables now have RLS, credentials are externalized via Flyway placeholders, and the `assert_user_context()` function provides a fail-fast safety net. The security posture has improved substantially.

However, one high-severity issue was introduced: the worker role (`worker_db_user`) is now blocked by RLS on `photos` and `photo_metadata`. The worker needs cross-tenant access to process photos for all users, but the tenant-scoped RLS policies return zero rows for any non-tenant context. This is a functional failure that will break the image processing pipeline, and it warrants blocking until resolved. The fix is straightforward — add role-specific `USING (true)` policies for `worker_db_user`.

The remaining findings are medium-to-low severity: the test profile will fail due to unresolved environment variables (a direct consequence of the correct decision to remove defaults from production config), and several test coverage gaps exist. These are quick fixes that don't require architectural changes.

---

## Security Quality Score (SQS)

| Finding | Severity | Deduction |
|---------|----------|-----------|
| #1 | High | −20 |
| #2 | Medium | −8 |
| #3 | Low | −2 |
| #4 | Low | −2 |
| #5 | Low | −2 |
| #6 | Low | −2 |

**Final SQS:** 64/100
**Hard gates triggered:** No
**Posture:** Unacceptable — block deployment, remediate Finding #1 (worker RLS) before proceeding.

---

## Positive Security Observations

1. **All v1 audit findings addressed.** Every remediation was applied correctly with appropriate architectural notes for cross-phase dependencies. No regressions on previously fixed issues.
2. **RLS now covers all 10 tenant tables.** With `users` and `email_tokens` added, there are no unprotected high-value tables. This is comprehensive tenant isolation.
3. **`assert_user_context()` is a sound design.** A database-side assertion that fails loudly on misconfigured requests is superior to silent empty-result failures. Well-implemented with both NULL and nil UUID checks.
4. **Credential externalization is consistent.** Production config has no credential defaults anywhere — `DB_PASS`, `FLYWAY_DB_PASS`, `JWT_SECRET`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `WORKER_DB_PASS` all require explicit configuration. Dev profile provides safe local defaults.
5. **Documentation of privileged-path requirements.** The plan clearly notes that login, registration, email verification, and share-link validation must use privileged roles in Phase 2. This prevents future developers from accidentally using `jpt_app` for these flows.

---

## Prioritized Remediation Roadmap

| Priority | Finding | Title | Why | Effort | Owner |
|----------|---------|-------|-----|--------|-------|
| 1 | #1 | Add worker RLS policies on `photos`/`photo_metadata` | Blocks entire image processing pipeline; highest functional impact | Quick Win (2 SQL statements) | Backend |
| 2 | #2 | Add test profile Flyway placeholder and password overrides | Blocks all test execution; likely to cause security regression if developers add defaults to prod config | Quick Win | Backend |
| 3 | #3 | Add positive-path worker tests | Validates Finding #1 fix; catches future RLS regressions | Quick Win | Backend |
| 4 | #5 | Add `assert_user_context()` tests | Validates safety net function; 2 simple tests | Quick Win | Backend |
| 5 | #6 | Revoke `jpt_app` from `flyway_schema_history` | Defense-in-depth; 1 SQL statement | Quick Win | Backend |
