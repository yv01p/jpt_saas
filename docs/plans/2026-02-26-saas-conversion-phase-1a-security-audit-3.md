# Security Audit Report — Phase 1a: Spring Boot Scaffold & Database

> **Audit version:** 3
> **Date:** 2026-02-26
> **Scope:** All implemented files in Phase 1a — Gradle config, Spring Boot scaffold, application configs, Flyway migrations V1–V3, and test classes.
> **Methodology:** Three-pass white-box review (reconnaissance → systematic hunting → compositional analysis).

---

## Finding #1: `assert_user_context()` is Advisory-Only — No Automatic Enforcement

**Vulnerability:** Missing Mandatory Security Control — A01 (Broken Access Control)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/resources/db/migration/V2__rls_policies.sql`, Lines 23–30

**Risk & Exploit Path:**
The `assert_user_context()` function exists but is never called automatically. It depends on Phase 2 application code to invoke `SELECT assert_user_context()` after every `SET LOCAL`. If Phase 2 forgets to call it, or any new code path skips it, the nil-UUID safety net silently returns empty results instead of failing loudly. This is not a vulnerability *today* (no API endpoints exist yet), but it's a defense-in-depth gap in the schema layer that could become critical if Phase 2 implementation is incomplete.

**Evidence / Trace:**
```sql
-- V2__rls_policies.sql:23-30
CREATE FUNCTION assert_user_context() RETURNS void AS $$
BEGIN
    IF current_setting('app.current_user_id', true) IS NULL
       OR current_setting('app.current_user_id', true) = '00000000-0000-0000-0000-000000000000' THEN
        RAISE EXCEPTION 'app.current_user_id is not set (nil UUID or missing)';
    END IF;
END;
$$ LANGUAGE plpgsql;
-- ← Function created but never bound to a trigger or called automatically
```

**Remediation:**
- **Primary fix (Phase 2):** Ensure the tenant-context interceptor calls `SELECT assert_user_context()` atomically after `SET LOCAL`. Consider a PostgreSQL event trigger or wrapping it in a mandatory before-statement trigger on sensitive tables if the application layer cannot guarantee it.
- **Defense-in-depth:** Add an integration test in Phase 2 that verifies every controller endpoint calls the assertion.

---

## Finding #2: `worker_db_user` Not Subject to RLS — Can Read All Users' Photos

**Vulnerability:** Broken Access Control — A01 (Broken Access Control)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: `api/src/main/resources/db/migration/V3__worker_db_user.sql`, Lines 14, 17
- File: `api/src/main/resources/db/migration/V2__rls_policies.sql` (RLS enabled but no policy for `worker_db_user`)

**Risk & Exploit Path:**
`worker_db_user` has `SELECT ON photos` and `INSERT, UPDATE ON photo_metadata`. RLS is enabled on these tables but `FORCE ROW LEVEL SECURITY` only applies to table owners, not arbitrary non-owner roles — wait, actually `FORCE ROW LEVEL SECURITY` applies to the table owner. For non-owner roles (which `worker_db_user` is), RLS policies apply by default. The existing `tenant_photos` policy uses `current_setting('app.current_user_id')::uuid`, but the worker likely won't set this variable. If the worker doesn't set `app.current_user_id`, `current_setting(..., true)` returns NULL, the cast to UUID would fail or the comparison would return false, effectively blocking all reads.

**Requires Verification:** How will the worker set `app.current_user_id`? If the worker connection doesn't set this GUC, `current_setting('app.current_user_id', true)` returns NULL, and `NULL::uuid` comparison will fail. The worker would be unable to read *any* photos, which breaks its functionality. The worker needs either:
1. A separate RLS policy (e.g., `FOR SELECT USING (true)` to `worker_db_user`), or
2. The worker must `SET app.current_user_id` per-job, or
3. `worker_db_user` must bypass RLS (but it's not a superuser or table owner).

This is a **functional correctness** issue that also has security implications — the "fix" chosen will determine whether the worker has overly broad or correctly scoped access.

**Remediation:**
- **Primary fix (Phase 2):** Design the worker's RLS strategy explicitly. Option A: Add a `worker_db_user`-specific policy `FOR SELECT USING (processing_status IN ('pending', 'processing'))` to scope reads to only processable photos. Option B: Have the worker `SET LOCAL app.current_user_id` per job from the job payload.
- **Defense-in-depth:** Document the chosen approach and add integration tests.

---

## Finding #3: RLS Policies Use `USING` Only — No Separate `WITH CHECK` Clause

**Vulnerability:** Potential Access Control Bypass — A01 (Broken Access Control)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** High

**Location:**
- File: `api/src/main/resources/db/migration/V2__rls_policies.sql`, Lines 49–79

**Risk & Exploit Path:**
When a policy has only a `USING` clause (no `WITH CHECK`), PostgreSQL applies the `USING` expression for both read and write checks. This is correct behavior — it means a user can only INSERT/UPDATE rows where `user_id` matches their own ID. However, this relies on the application always setting `user_id` correctly on new rows. If application code inserts a row with a different `user_id` (e.g., mass assignment from request JSON), the INSERT would be rejected by RLS, which is actually the desired fail-safe behavior.

**Assessment:** This is actually a **positive** security property. The implicit `WITH CHECK` derived from `USING` ensures users cannot write rows for other tenants. No action needed — flagged for completeness.

---

## Finding #4: `shares.resource_id` Has No Foreign Key Constraint

**Vulnerability:** Data Integrity / Potential IDOR Setup — A01 (Broken Access Control)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: `api/src/main/resources/db/migration/V1__core_schema.sql`, Line 125

**Risk & Exploit Path:**
`shares.resource_id` is a bare UUID with no FK to `photos(id)` or `albums(id)`. This is a common pattern for polymorphic references (`resource_type` + `resource_id`), but it means the database cannot enforce that the referenced resource exists or belongs to the same user. If Phase 2 code creates a share without verifying ownership of `resource_id`, a user could create a share link pointing to another user's photo/album. RLS on `shares` only restricts who can *see* the share record, not whether the share's `resource_id` points to an owned resource.

**Remediation:**
- **Primary fix (Phase 2):** Application-layer validation must verify `resource_id` belongs to the authenticated user before creating a share. Add a database trigger or application-level check.
- **Defense-in-depth:** Consider separate `photo_shares` and `album_shares` tables with proper FKs if the polymorphic pattern proves error-prone.

---

## Finding #5: `connection-init-sql` Nil UUID Persists Across Pooled Connections

**Vulnerability:** Security Misconfiguration — A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** High

**Location:**
- File: `api/src/main/resources/application.yml`, Line 12

**Risk & Exploit Path:**
```yaml
connection-init-sql: "SET app.current_user_id = '00000000-0000-0000-0000-000000000000'"
```
This runs once when HikariCP initializes a connection. If `SET LOCAL` (transaction-scoped) is used correctly in Phase 2, this is safe — `SET LOCAL` overrides for the transaction duration, and the session-level value reverts after. However, if any code path uses a connection outside a transaction (e.g., JPA with auto-commit for reads), the nil UUID would be active. The `users_no_nil_uuid` CHECK constraint ensures no real user has this ID, so queries return empty — fail-safe but silent.

**Assessment:** The design is sound as a safety net. The plan explicitly documents this. Low risk, but requires Phase 2 to *always* use transactions for tenant-scoped queries.

**Remediation:**
- Phase 2 must ensure all tenant-scoped operations run within explicit transactions with `SET LOCAL`.

---

## Finding #6: Dev Profile Contains Weak Default Credentials

**Vulnerability:** Hardcoded Credentials — A07 (Identification and Authentication Failures)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/resources/application-dev.yml`, Lines 3, 5, 7–8, 11, 14–15

**Risk & Exploit Path:**
```yaml
password: ${DB_PASS:jpt}               # ← default "jpt"
jwt-secret: dev-secret-change-me-...    # ← predictable
access-key: ${MINIO_ACCESS_KEY:minioadmin}
secret-key: ${MINIO_SECRET_KEY:minioadmin}
```
These are dev-only defaults gated behind the `dev` profile. Production `application.yml` has no defaults for secrets (requires env vars). Risk is limited to: (1) accidentally running the dev profile in production, or (2) a developer's local environment being exposed.

**Assessment:** Acceptable for dev profile. The production config correctly requires env vars with no fallbacks. No immediate action needed.

**Remediation:**
- Ensure deployment scripts/Docker Compose never activate the `dev` profile. Add a startup check in Phase 2 that fails if `dev` profile is active and `DB_PASS` or `JWT_SECRET` are at their default values.

---

## Finding #7: `jpt_app` Granted DELETE on All Tables Including `users`

**Vulnerability:** Excessive Privileges — A01 (Broken Access Control)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: `api/src/main/resources/db/migration/V2__rls_policies.sql`, Line 14

**Risk & Exploit Path:**
```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jpt_app;
```
The `jpt_app` role can DELETE from `users`. While RLS restricts this to only the authenticated user's own row (user can only delete themselves), the grant is broader than necessary. If the application only soft-deletes users (or doesn't support account deletion at all), the DELETE grant on `users` is unnecessary attack surface.

**Remediation:**
- Consider revoking DELETE on `users` from `jpt_app` if account deletion is not a feature, or implementing it as an admin-only operation via the superuser role.
- Low priority — RLS mitigates cross-tenant risk.

---

## Executive Summary

The Phase 1a implementation establishes a solid security foundation for a multi-tenant SaaS application. The schema design demonstrates mature security thinking: Row Level Security on all tenant tables, a nil-UUID safety net preventing data leakage from uninitialized connections, a CHECK constraint preventing the sentinel UUID from being a real user, separate database roles with least-privilege (especially the well-scoped `worker_db_user`), and Flyway-managed passwords via placeholders instead of hardcoded values.

The most important findings are architectural gaps that become relevant in Phase 2: the `assert_user_context()` function being advisory-only (Finding #1), the unresolved question of how `worker_db_user` interacts with RLS (Finding #2), and the need for application-layer validation on polymorphic `shares.resource_id` (Finding #4). None of these are exploitable today since no API endpoints exist yet.

The codebase is well-positioned for Phase 2 implementation, with the critical caveat that the RLS system is inert until the per-request `SET LOCAL` interceptor is built. The security of the entire multi-tenant model hinges on that interceptor being correct and comprehensive.

---

## Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | `assert_user_context()` advisory-only | A01 | Medium | Confirmed | 1 | Phase 2 |
| 2 | `worker_db_user` RLS interaction undefined | A01 | Medium | High | 1 | Phase 2 |
| 3 | No separate `WITH CHECK` (benign) | A01 | Low | High | 10 | OK |
| 4 | `shares.resource_id` no FK | A01 | Low | Medium | 1 | Phase 2 |
| 5 | Nil UUID `connection-init-sql` | A05 | Low | High | 1 | OK |
| 6 | Dev profile weak defaults | A07 | Low | Confirmed | 4 | OK |
| 7 | `jpt_app` DELETE on `users` | A01 | Low | Medium | 1 | Monitor |

---

## Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical | 0 | 0 |
| High | 0 | 0 |
| Medium | 2 | −16 |
| Low | 5 (grouped: 3 benign/acceptable) | −6 |

**Final SQS:** 78/100
**Hard gates triggered:** No
**Posture:** Acceptable — deploy Phase 1a with remediation commitment for Findings #1, #2, and #4 in Phase 2.

---

## Positive Security Observations

1. **Comprehensive RLS coverage.** Every tenant table (10/10) has RLS enabled and forced, with policies using direct `user_id` equality — no correlated subqueries that could be bypassed or cause performance-based information leakage.
2. **Nil-UUID safety net.** The `connection-init-sql` + `users_no_nil_uuid` CHECK constraint ensures uninitialized connections return empty results rather than leaking data. This is a well-thought-out defense-in-depth measure.
3. **Least-privilege worker role.** `worker_db_user` is restricted to `SELECT` on `photos` and column-level `UPDATE` — cannot access `users`, `shares`, or other sensitive tables. This is excellent principle-of-least-privilege design.
4. **No hardcoded production secrets.** Production `application.yml` requires env vars for all secrets (`DB_PASS`, `JWT_SECRET`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`) with no fallback defaults. Dev defaults are properly isolated to `application-dev.yml`.
5. **Separate Flyway datasource.** Flyway uses the `jpt` superuser role (exempt from RLS) while the application uses `jpt_app` (subject to RLS). This cleanly separates migration privileges from runtime privileges.

---

## Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #2 — Worker RLS strategy | Blocks Phase 2 worker implementation; wrong choice creates data leak or broken worker | Moderate | Backend |
| 2 | #1 — Assert function enforcement | Must be wired into Phase 2 interceptor; missing it silently degrades to empty results | Quick Win | Backend |
| 3 | #4 — Share resource_id validation | Must be enforced in Phase 2 share endpoints; missing it enables cross-tenant share creation | Quick Win | Backend |
| 4 | #7 — Revoke DELETE on users | Low risk but easy to tighten | Quick Win | Backend |
| 5 | #6 — Dev profile startup guard | Prevents accidental dev-in-prod | Quick Win | DevOps |
