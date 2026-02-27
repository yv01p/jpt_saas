# Security Audit — Phase 0 Implementation

> **Audit Date:** 2026-02-26
> **Audited Artifact:** `docs/plans/2026-02-25-saas-conversion-phase-0.md` (implementation)
> **Scope:** All committed code from Phase 0 — build system, Flyway migrations V1–V3, application config, Spring Boot scaffold, database tests

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Entry points (Phase 0):** None exposed — no controllers, no REST endpoints, no CLI commands. The Spring Boot `JptSaasApplication` boots but serves nothing beyond Spring Security defaults and Actuator.

**Trust boundaries:**
- Flyway superuser → PostgreSQL DDL
- `jpt_app` role → all tables (filtered by RLS)
- `worker_db_user` role → photos (SELECT), photo_metadata (INSERT/UPDATE), photos columns (UPDATE on 4 columns)
- HikariCP connection-init-sql → sets nil UUID as default user context

**Sensitive data flows:**
- Database passwords in Flyway placeholders (`${jpt_app_password}`, `${worker_db_user_password}`)
- JWT secret, MinIO credentials, Redis password in application.yml (env vars)
- Dev profile defaults: hardcoded passwords for local development

**Technology stack:** Java 21, Spring Boot 3.4.2, PostgreSQL 16 (RLS), Flyway, HikariCP, Testcontainers

---

## Findings

### Finding #1: RLS Policies Lack WITH CHECK Clause — Silent Data Rejection on INSERT/UPDATE

**Vulnerability:** Incomplete Row-Level Security Policy — A01 (Broken Access Control)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Medium

**Location:**
- File: `api/src/main/resources/db/migration/V2__rls_policies.sql`, Lines 49–79

**Risk & Exploit Path:**
RLS policies use only `USING` (which filters SELECT/UPDATE/DELETE) but omit `WITH CHECK` (which validates INSERT/UPDATE new rows). In PostgreSQL, when `WITH CHECK` is absent, the `USING` expression is used for both. This means the current behavior is actually correct — `jpt_app` can only INSERT rows where `user_id` matches `app.current_user_id`. However, this is implicit behavior that relies on PostgreSQL's default. An explicit `WITH CHECK` clause makes the security intent clear and prevents subtle bugs if policies are later split into separate SELECT vs INSERT policies.

The real risk: if a future developer adds a permissive SELECT-only policy (e.g., for share links), the implicit WITH CHECK from the original policy would still apply — but only if both policies remain in effect. Split policies without explicit WITH CHECK are a common source of RLS bypass.

**Evidence / Trace:**
```sql
-- V2__rls_policies.sql:55
CREATE POLICY tenant_photos ON photos
    USING (user_id = current_setting('app.current_user_id')::uuid);  -- ← No WITH CHECK
```

**Remediation:**
- Primary fix: Add explicit `WITH CHECK` clause to all policies:
  ```sql
  CREATE POLICY tenant_photos ON photos
      USING (user_id = current_setting('app.current_user_id')::uuid)
      WITH CHECK (user_id = current_setting('app.current_user_id')::uuid);
  ```
- Create V4 migration to drop and recreate policies with explicit `WITH CHECK`.

---

### Finding #2: shares.resource_id Has No Foreign Key Constraint

**Vulnerability:** Broken referential integrity — A01 (Broken Access Control)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/resources/db/migration/V1__core_schema.sql`, Lines 121–132

**Risk & Exploit Path:**
`shares.resource_id` is a plain UUID column with no FK to `photos(id)` or `albums(id)`. This is a polymorphic reference (resource_type = 'photo' | 'album'), which PostgreSQL cannot enforce with a single FK. Without application-layer enforcement, a user could create a share pointing to a non-existent or deleted resource. Combined with a future privileged share-link endpoint (that bypasses RLS), this could lead to dangling references or confusing error states.

**Evidence / Trace:**
```sql
-- V1__core_schema.sql:121-132
CREATE TABLE shares (
    ...
    resource_type VARCHAR(50) NOT NULL CHECK (resource_type IN ('photo', 'album')),
    resource_id UUID NOT NULL,  -- ← No FK constraint
    ...
);
```

**Remediation:**
- Primary fix: Add application-layer validation in Phase 1 share creation endpoint — verify resource exists and belongs to the user before creating the share.
- Defense-in-depth: Consider splitting into `photo_shares` and `album_shares` tables with proper FKs, or add a trigger-based FK check.

---

### Finding #3: Worker Role Not Subject to RLS — Full Table Scan on photos

**Vulnerability:** Excessive data exposure to worker role — A01 (Broken Access Control)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: `api/src/main/resources/db/migration/V3__worker_db_user.sql`, Lines 14, 17
- File: `api/src/main/resources/db/migration/V2__rls_policies.sql`, Lines 33–44

**Risk & Exploit Path:**
`worker_db_user` has `SELECT ON photos` but is not subject to RLS (no `FORCE ROW LEVEL SECURITY` applies to this role since it's not the table owner and doesn't have a policy explicitly including it). The worker can read ALL users' photos and photo_metadata. If the worker process is compromised (e.g., via a malicious image exploiting an ImageIO vulnerability), the attacker gains read access to every photo record in the system.

The worker legitimately needs cross-tenant access to process jobs, but the blast radius of a compromised worker is the entire photos table.

**Evidence / Trace:**
```sql
-- V3__worker_db_user.sql:14
GRANT SELECT ON photos TO worker_db_user;
-- V3__worker_db_user.sql:17
GRANT INSERT, UPDATE ON photo_metadata TO worker_db_user;
```

`FORCE ROW LEVEL SECURITY` on `photos` applies to the table owner, but `worker_db_user` is neither the owner nor has a policy applied to it — so RLS is effectively bypassed for this role (standard PostgreSQL behavior: non-owner roles without explicit policies and without FORCE are simply denied, but here GRANTs override).

**Remediation:**
- Primary fix: In Phase 1, scope worker queries to only fetch `processing_status = 'pending'` photos. Add a worker-specific RLS policy or use a view that filters by status.
- Defense-in-depth: Add a `processing_claimed_at` column and `processing_worker_id` to prevent double-processing and limit the window of exposure.
- Monitoring: Log all worker queries, alert on anomalous SELECT patterns.

---

### Finding #4: Flyway Passwords Injected via Placeholders in Plain SQL

**Vulnerability:** Credential exposure in migration logs — A02 (Cryptographic Failures / Data Exposure)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/resources/db/migration/V2__rls_policies.sql`, Line 8
- File: `api/src/main/resources/db/migration/V3__worker_db_user.sql`, Line 6
- File: `api/src/main/resources/application.yml`, Lines 25–26

**Risk & Exploit Path:**
Flyway replaces `${jpt_app_password}` and `${worker_db_user_password}` inline in the SQL before execution. If Flyway debug logging is enabled (common during troubleshooting), or if the migration is logged at SQL level (PostgreSQL `log_statement = 'all'`), the passwords appear in cleartext in log files. Additionally, the `flyway_schema_history` table stores a checksum of the migration, and some Flyway configurations can store the full SQL text.

**Evidence / Trace:**
```sql
-- V2__rls_policies.sql:8
CREATE ROLE jpt_app WITH LOGIN PASSWORD '${jpt_app_password}';  -- ← Placeholder replaced with cleartext password
```

```yaml
# application.yml:25-26
placeholders:
  jpt_app_password: ${DB_PASS}
  worker_db_user_password: ${WORKER_DB_PASS}
```

**Remediation:**
- Primary fix: Use `ALTER ROLE ... PASSWORD` in a separate, non-logged step, or use PostgreSQL's `\password` command via a bootstrap script outside Flyway.
- Alternative: Use `SCRAM-SHA-256` password hashing and pass pre-hashed passwords: `CREATE ROLE jpt_app WITH LOGIN PASSWORD 'SCRAM-SHA-256$...';`
- Defense-in-depth: Ensure `log_statement` is NOT set to `'all'` in production PostgreSQL config. Set `log_min_duration_statement` instead.

---

### Finding #5: Dev Profile JWT Secret Is Predictable

**Vulnerability:** Weak cryptographic key in dev profile — A02 (Cryptographic Failures)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/resources/application-dev.yml`, Line 11

**Risk & Exploit Path:**
The dev profile uses `dev-secret-change-me-in-prod-must-be-at-least-256-bits-long-ok` as the JWT secret. If the dev profile is accidentally activated in production (e.g., `SPRING_PROFILES_ACTIVE=dev`), all JWTs become forgeable. The production `application.yml` correctly requires `${JWT_SECRET}` without a default, so this would only fire if the dev profile is explicitly activated.

**Evidence / Trace:**
```yaml
# application-dev.yml:11
jwt-secret: ${JWT_SECRET:dev-secret-change-me-in-prod-must-be-at-least-256-bits-long-ok}
```

**Remediation:**
- Primary fix: In Phase 1, add a startup validation bean that rejects known dev secrets when `spring.profiles.active` does NOT include `dev`.
- Defense-in-depth: Document in deployment runbook that `SPRING_PROFILES_ACTIVE` must never include `dev` in production.

---

### Finding #6: Dev Profile Uses Default MinIO Credentials (minioadmin/minioadmin)

**Vulnerability:** Default credentials in dev profile — A07 (Identification and Authentication Failures)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `api/src/main/resources/application-dev.yml`, Lines 14–15

**Risk & Exploit Path:**
Same class of risk as Finding #5 — if dev profile is activated in production, MinIO is accessible with default credentials. However, MinIO should not be exposed to the internet in any case.

**Evidence / Trace:**
```yaml
# application-dev.yml:14-15
access-key: ${MINIO_ACCESS_KEY:minioadmin}
secret-key: ${MINIO_SECRET_KEY:minioadmin}
```

**Remediation:**
- Primary fix: Same startup validation as Finding #5 — reject default MinIO credentials outside dev profile.

---

### Finding #7: No `updated_at` Auto-Update Trigger on Any Table

**Vulnerability:** Audit trail gap — A09 (Security Logging and Monitoring Failures)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** N/A

**Location:**
- File: `api/src/main/resources/db/migration/V1__core_schema.sql` — all tables with `updated_at` column

**Risk & Exploit Path:**
`updated_at` columns exist on `users`, `photos`, `keywords`, `albums`, `shares` but have no `DEFAULT now()` on update, no trigger, and no `@PreUpdate` JPA hook (no entities exist yet). If the application layer forgets to set `updated_at`, the column remains NULL, creating gaps in the audit trail. This is a Phase 1 concern but worth noting now.

**Remediation:**
- Primary fix: In Phase 1, either add a PostgreSQL trigger (`CREATE FUNCTION update_timestamp()`) or rely on JPA `@PreUpdate` consistently. A database trigger is more defensive.

---

## Executive Summary

Phase 0 establishes a **strong security foundation** for a multi-tenant SaaS application. The database layer implements Row-Level Security with FORCE on all tenant tables, role separation between the API and worker, nil-UUID fail-safe on connection init, and column-level GRANT restrictions on the worker role. The Flyway migrations are well-structured with idempotent role creation.

The findings are predominantly **Medium severity** and relate to defense-in-depth gaps rather than exploitable vulnerabilities. The most actionable issues are: (1) adding explicit `WITH CHECK` to RLS policies before Phase 1 ships, (2) addressing the Flyway password-in-SQL pattern before production deployment, and (3) scoping worker access more tightly in Phase 1.

No code-level vulnerabilities were found because Phase 0 has no application code beyond a Spring Boot main class and tests. The real security test comes in Phase 1 when controllers, authentication, and file upload handling are implemented.

## Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | RLS policies lack explicit WITH CHECK | A01 | Medium | Confirmed | 10 (all policies) | REVIEW |
| 2 | shares.resource_id has no FK constraint | A01 | Medium | Confirmed | 1 | REVIEW |
| 3 | Worker role not subject to RLS | A01 | Medium | High | 1 | REVIEW |
| 4 | Flyway passwords in plain SQL | A02 | Medium | High | 2 (jpt_app, worker) | REVIEW |
| 5 | Dev profile JWT secret is predictable | A02 | Low | Confirmed | 1 | REVIEW |
| 6 | Dev profile default MinIO credentials | A07 | Low | Confirmed | 1 | REVIEW |
| 7 | No updated_at auto-update trigger | A09 | Low | Confirmed | 5 tables | REVIEW |

## Security Quality Score (SQS)

| Finding Severity | Count | Grouped? | Deduction |
|-----------------|-------|----------|-----------|
| Critical | 0 | — | 0 |
| High | 0 | — | 0 |
| Medium | 4 | No | −32 |
| Low | 3 | Yes (dev config group) | −2 + −2 = −4 |

**Final SQS:** 64/100
**Hard gates triggered:** No
**Posture:** Unacceptable (< 70)

> **Context note:** This score reflects the Phase 0 *implementation* state. Three of the four Medium findings are defense-in-depth issues that become exploitable only when Phase 1 application code is added. The score should be re-evaluated after Phase 1a addresses the remediation items. The project is NOT deploying Phase 0 to production — this is a build milestone, not a deployment milestone.

## Positive Security Observations

1. **Nil-UUID fail-safe on HikariCP connection-init-sql.** Every connection starts with a user context that matches no real user (and is CHECK-constrained off the users table). Forgetting to set context results in zero results, not data leakage. This is an excellent defense-in-depth pattern.

2. **FORCE ROW LEVEL SECURITY on all tenant tables.** Even table owners are subject to RLS, preventing accidental bypass by the Flyway migration user or during maintenance operations.

3. **Column-level GRANT on worker role.** The worker can only UPDATE `storage_key`, `content_hash`, `processing_status`, `size_bytes` on photos — not `user_id`, `filename`, or other sensitive fields. This is textbook least-privilege.

4. **Dependency verification metadata (gradle/verification-metadata.xml).** SHA-256 checksums pinned for all dependencies, protecting against supply chain attacks. This is uncommon at this project stage and shows security maturity.

5. **Comprehensive RLS and permission tests (RlsTest, WorkerDbUserTest).** The test suite validates both positive and negative access paths, including cross-tenant isolation and worker role restrictions. These tests run against real PostgreSQL via Testcontainers, not mocks.

## Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #1 — Add WITH CHECK to RLS policies | Prevents subtle bypass if policies are split in future phases. Low effort, high defensive value. | Quick Win | Backend |
| 2 | #4 — Flyway password exposure | Credentials in logs are a compliance blocker. Must be resolved before any production deployment. | Moderate | DevOps |
| 3 | #3 — Scope worker role access | Reduces blast radius of compromised worker. Should be addressed when worker implementation begins in Phase 1. | Moderate | Backend |
| 4 | #2 — shares.resource_id validation | Application-layer FK enforcement needed when share endpoints are built in Phase 2. | Quick Win | Backend |
| 5 | #5/#6 — Dev profile startup guard | Single bean validates no dev secrets in non-dev profiles. Implement in Phase 1 alongside auth. | Quick Win | Backend |
