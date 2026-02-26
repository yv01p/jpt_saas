# Critical Implementation Review: Phase 1a — Spring Boot Scaffold & Database

**Reviewer:** Senior Staff Engineer (Automated)
**Date:** 2026-02-26
**Plan:** `2026-02-25-saas-conversion-phase-1a.md`
**Version:** 1

---

## 1. Overall Assessment

The plan is well-structured, follows TDD discipline, and covers the core schema, RLS policies, and worker DB user with clear test-verify-commit cycles. The SQL schema is thoughtful — generated `tsvector`, GIN indexes, composite FKs for cross-tenant protection, and proper soft-delete support.

**Major concerns:** The RLS test is fundamentally ineffective (tests run as superuser, bypassing all policies), the `application.yml` contains a hardcoded JWT secret that will leak into version control, `connection-init-sql` sets a nil UUID on every connection (including migrations), and the `album_photos` composite FK references require unique constraints that may cause subtle query-planner issues. These are all fixable but must be addressed before implementation.

---

## 2. Critical Issues

### C1: RLS test does not actually test RLS enforcement

**Description:** `RlsTest.rlsPreventsAccessToOtherUsersPhotos()` inserts data, sets `app.current_user_id`, and queries — but Testcontainers connects as the PostgreSQL superuser, which bypasses RLS even with `FORCE ROW LEVEL SECURITY`. The test comment acknowledges this ("we may be superuser") and then asserts `isNotNull()` — which always passes regardless of RLS.

**Impact:** Zero confidence that RLS actually works. A misconfigured policy would pass this test suite. This is a **security-critical gap** for a multi-tenant system.

**Fix:** The test must connect as the `jpt_app` role (non-superuser) to validate RLS. Options:
1. Create a second `DataSource` bean in the test profile that connects as `jpt_app` and use it for the RLS assertion queries.
2. Use `SET ROLE jpt_app` before the assertion query (requires the superuser to `GRANT jpt_app TO` the test user).
3. At minimum, the `rlsPreventsAccessToOtherUsersPhotos` test should assert `count == 0`, not `isNotNull()`, and connect as a non-superuser role.

Also: `SET LOCAL` only works inside a transaction. Spring's `JdbcTemplate.execute()` may auto-commit, making the `SET LOCAL` a no-op. Wrap the insert + SET LOCAL + query in an explicit `@Transactional` test or use `TransactionTemplate`.

### C2: Hardcoded JWT secret in `application.yml` committed to VCS

**Description:** `app.jwt-secret: ${JWT_SECRET:dev-secret-change-me-in-prod-must-be-256-bits}` — the fallback value is a human-readable string that is only ~40 bytes and will be committed to version control. Any developer who forgets to set `JWT_SECRET` in production will run with this weak key.

**Impact:** If deployed with the default, all JWTs can be forged by anyone who reads the source code. This is a critical auth bypass.

**Fix:**
1. Remove the default value: `jwt-secret: ${JWT_SECRET}` — Spring Boot will fail to start if the env var is missing, which is the correct fail-safe behavior.
2. Add a `@PostConstruct` validator in a config class that rejects secrets shorter than 32 bytes in non-dev profiles.
3. The `application-dev.yml` profile can contain the dev-only fallback.

### C3: `connection-init-sql` sets nil UUID on every connection — including Flyway migrations

**Description:** `connection-init-sql: "SET app.current_user_id = '00000000-0000-0000-0000-000000000000'"` runs on every new HikariCP connection. This means Flyway migrations also run with this setting. Once RLS is enabled with `FORCE ROW LEVEL SECURITY`, any migration that touches RLS-protected tables will silently filter rows to only those matching the nil UUID.

**Impact:** Future migrations that do data transformations (e.g., backfills) will silently operate on zero rows if no data has `user_id = 00000000-...`. This is a subtle, hard-to-debug correctness issue.

**Fix:**
1. Flyway should use a separate datasource that connects as the table owner (superuser or migration-specific role) that is exempt from RLS. Spring Boot supports `spring.flyway.url/user/password` to override the application datasource for migrations.
2. Alternatively, the connection-init-sql should only be applied to the application datasource, not the Flyway datasource.

### C4: `album_photos` composite FK design has a prerequisite ordering issue

**Description:** The migration creates the `album_photos` table with `FOREIGN KEY (album_id, user_id) REFERENCES albums(id, user_id)` — but the `UNIQUE (id, user_id)` constraints on `albums` and `photos` are added *after* the `album_photos` table definition via `ALTER TABLE`. PostgreSQL requires the referenced unique constraint to exist *before* the FK is created.

**Impact:** The migration will fail with: `ERROR: there is no unique constraint matching given keys for referenced table "albums"`.

**Fix:** Move the `ALTER TABLE albums ADD CONSTRAINT albums_id_user_id_unique ...` and `ALTER TABLE photos ADD CONSTRAINT photos_id_user_id_unique ...` statements to *before* the `CREATE TABLE album_photos` statement.

### C5: `email_tokens` missing index on `user_id` and `expires_at`

**Description:** The `email_tokens` table will be queried by `user_id + purpose` (to find pending tokens) and by `expires_at` (for cleanup). There are no indexes beyond the PK and the `token_hash` unique index.

**Impact:** Token lookup and expiry cleanup will table-scan. At small scale this is fine, but the design doc specifies a 7-day auto-purge of unverified accounts, which implies a periodic query on `expires_at`.

**Fix:** Add `CREATE INDEX email_tokens_user_idx ON email_tokens (user_id, purpose);` and optionally an index on `expires_at` for the cleanup job.

### C6: `photo_keywords` and `photo_metadata` RLS policies use correlated subqueries — potential N+1

**Description:** The RLS policies for `photo_metadata` and `photo_keywords` use `USING (photo_id IN (SELECT id FROM photos))`. Since `photos` itself has RLS, this creates a nested RLS evaluation: every row access on `photo_metadata` triggers a filtered scan of `photos`.

**Impact:** For bulk metadata queries (e.g., "show metadata for 100 photos in album view"), PostgreSQL evaluates the subquery per row. With RLS on both tables, the query planner may not be able to flatten this efficiently, leading to O(n) subquery evaluations.

**Fix:**
1. Add `user_id` directly to `photo_metadata` and `photo_keywords` (denormalization) and use a direct equality RLS policy. This eliminates the correlated subquery.
2. If denormalization is rejected, at minimum add an `EXPLAIN ANALYZE` test for a bulk query (e.g., 1000 photos) to validate the query plan doesn't degrade.

---

## 3. Minor Issues & Improvements

### M1: `uuid-ossp` extension vs `gen_random_uuid()`

PostgreSQL 13+ has `gen_random_uuid()` built into core (no extension needed). Since the target is PostgreSQL 16, replace `uuid_generate_v4()` with `gen_random_uuid()` and remove `CREATE EXTENSION IF NOT EXISTS "uuid-ossp"`. Fewer dependencies = fewer permissions issues in managed PostgreSQL environments.

### M2: `jpt_app` and `worker_db_user` passwords are `'changeme'`

The migration hardcodes `PASSWORD 'changeme'` for both roles. This is fine for local dev, but:
- Document that these must be overridden in production (e.g., via `ALTER ROLE` post-deploy or Docker secrets).
- Consider using `SCRAM-SHA-256` auth by ensuring `password_encryption = scram-sha-256` in `postgresql.conf`.

### M3: Missing `ON DELETE` cascade behavior on `photos.user_id`

`photos` has `user_id UUID NOT NULL REFERENCES users(id)` — no cascade. If a user is deleted, the FK will block deletion. The design doc mentions unverified account purge. Decide: `ON DELETE CASCADE` (dangerous but simple) or explicit application-layer cleanup before user deletion.

### M4: `shares.resource_type` is unconstrained

`resource_type VARCHAR(50)` has no `CHECK` constraint. Add `CHECK (resource_type IN ('photo', 'album'))` to prevent invalid data.

### M5: `keywords` missing unique constraint on `(user_id, name, parent_id)`

Without this, a user can create duplicate keywords with the same name under the same parent, leading to data quality issues.

### M6: `saved_searches.query_json` lacks validation

No `CHECK` constraint on `query_json`. Consider adding `CHECK (query_json IS NOT NULL AND query_json != '{}'::jsonb)` at minimum.

### M7: Test `application-test.yml` should disable Redis

The test profile doesn't override Redis configuration. The `@SpringBootTest` context will attempt to connect to Redis at `localhost:6379`. Either:
- Add `spring.data.redis.url` pointing to a Testcontainers Redis, or
- Disable auto-configuration: `spring.autoconfigure.exclude: org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration`

### M8: `SchemaTest` should verify all tables, not just a subset

The test checks `users`, `photos.processing_status`, `photos.deleted_at`, and one constraint. Consider adding a parameterized test that verifies all 10 tables exist, providing regression coverage for the entire migration.

### M9: `application-dev.yml` is listed as a file to create but has no content specified

Task 1.1 lists `application-dev.yml` as a file to create but provides no content. This will be forgotten during implementation.

---

## 4. Questions for Clarification

1. **Flyway datasource separation:** Is there a plan for Flyway to use a privileged (non-RLS) connection? The current config routes both application and migration traffic through the same datasource with `connection-init-sql`, which conflicts with `FORCE ROW LEVEL SECURITY`.

2. **`jpt_app` role usage:** The `application.yml` datasource uses `${DB_USER:jpt}` — not `jpt_app`. When does the application switch to the `jpt_app` role? Is the intent for `jpt` to be the migration user and `jpt_app` to be the runtime user? This needs to be explicit.

3. **`email_tokens` table:** The design doc mentions email verification and password reset, but no `email_tokens` JPA entity or repository is planned in Phase 1a. Is the table being created speculatively, or is there a dependency from a later phase that requires it now?

4. **`content_hash` algorithm:** The schema has `content_hash VARCHAR(64)` which fits SHA-256 hex. Is this confirmed? Should the column comment document the algorithm?

---

## 5. Final Recommendation

**Major revisions needed.**

The plan has a solid structure and good test discipline, but contains:
- **1 security-critical issue** (C1: RLS tests don't test RLS)
- **1 security issue** (C2: hardcoded JWT secret)
- **1 correctness bug** (C4: FK ordering will cause migration failure)
- **1 subtle correctness risk** (C3: Flyway + RLS interaction)
- **1 performance concern** (C6: correlated subquery RLS policies)

**Key changes required before implementation:**
1. Fix FK ordering in V1 migration (C4) — migration will not run as-is.
2. Implement real RLS enforcement tests using a non-superuser role (C1).
3. Move JWT secret default to dev profile only (C2).
4. Separate Flyway datasource from application datasource (C3).
5. Evaluate and document the RLS subquery performance for `photo_metadata`/`photo_keywords` (C6).
6. Add Redis handling to test profile (M7).
7. Specify `application-dev.yml` content (M9).
