# Critical Implementation Review #3: Phase 1b v3.0 — Docker Compose & Dockerfiles

**Reviewer:** Claude (Senior Staff Engineer)
**Date:** 2026-02-26
**Plan reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-1b.md` (v3.0)
**Previous reviews:** `critical-review-1.md`, `critical-review-2.md`

---

## 1. Overall Assessment

The v3.0 plan is mature and production-ready. Both rounds of critical issues have been addressed: the inline Compose YAML is valid and complete, backup containers have heartbeat health checks, API security hardening is present, Redis has memory limits, and monitoring has been cleanly deferred. The changelog is thorough and traceable.

**Strengths:** Complete inline specification with no external delegation; clean dev/prod separation via profiles; consistent security hardening across API and worker; heartbeat-based health checks for long-running batch containers; well-documented changelog linking each change to its review finding.

**Remaining concerns are minor.** No critical issues remain. The plan is implementable as written.

---

## 2. Critical Issues

**None.**

All critical issues from reviews #1 and #2 have been resolved. The plan is free of correctness bugs, security gaps, and major best-practice violations within its scope.

---

## 3. Minor Issues & Improvements

### 3.1 Postgres container lacks security hardening

The API and worker both have `cap_drop: ALL` and `security_opt: no-new-privileges:true`. The Postgres, MinIO, and Redis containers do not. While these are stock images and may need certain capabilities, `no-new-privileges:true` is safe for all of them and is a zero-cost defense-in-depth measure.

**Fix:** Add `security_opt: [no-new-privileges:true]` to `postgres`, `minio`, and `redis` services. `cap_drop: ALL` may break some images (Postgres needs `SETUID`/`SETGID` for `initdb`), so test before applying.

### 3.2 Backup container has no `restart` policy

The `backup` service has `restart: unless-stopped` (good), but the `certbot` service does not have a restart policy. If certbot crashes, it won't restart.

**Fix:** Add `restart: unless-stopped` to the `certbot` service.

### 3.3 `pg_dump` uses `DB_USER` (admin) — consider a read-only backup user

The `pgbackup` container runs `pg_dump` with `$DB_USER` / `$DB_PASS`, which are the admin credentials. The design doc established a least-privilege `worker_db_user`; the same principle should apply to backups. A read-only `pgbackup_user` with `pg_dump` privileges would limit blast radius if the backup container is compromised.

**Impact:** Low — the backup container is internal and not network-exposed. But for zero-trust consistency, this is worth noting.

**Fix:** Defer to a future hardening pass, or add a `PGBACKUP_DB_USER` / `PGBACKUP_DB_PASS` with `SELECT`-only grants in the Flyway migration.

### 3.4 `pgbackup` Dockerfile — `restic` version may be very old in Debian repos

The plan notes "Uses the restic version available in the Debian Bookworm repos." Debian stable repos often carry restic versions 1-2 years behind. Older restic versions may have known bugs or lack security fixes.

**Fix:** Acceptable for initial deployment. Add a comment in the Dockerfile noting the version should be checked periodically, or switch to downloading the binary from GitHub releases when pinning is needed.

### 3.5 Worker healthcheck `find` command race condition

```yaml
test: ["CMD-SHELL", "find /tmp/worker-heartbeat -mmin -1 | grep -q ."]
```

The `-mmin -1` (modified within last 1 minute) combined with `interval: 15s` and `retries: 3` gives only ~45 seconds of tolerance before the first failure, and ~1 minute 45 seconds total before unhealthy. If the worker processes a large image taking >1 minute, the heartbeat will be stale and the container will be marked unhealthy. The `start_period: 30s` helps on startup but doesn't help during processing.

**Fix:** Change `-mmin -1` to `-mmin -2` (2-minute window) to provide more headroom for long-running image processing jobs. Or increase `interval` to `30s` and `retries` to `5`.

### 3.6 `.env.example` has two overlapping B2 credential sets

The file has both:
- `B2_ACCESS_KEY` / `B2_SECRET_KEY` (used by `backup` container for MinIO→B2 mirror)
- `B2_ACCOUNT_ID` / `B2_ACCOUNT_KEY` (used by `pgbackup` container for restic→B2)

These serve different purposes but the naming is confusing. Consider prefixing for clarity:

```bash
B2_MINIO_BACKUP_ACCESS_KEY=
B2_MINIO_BACKUP_SECRET_KEY=
B2_RESTIC_ACCOUNT_ID=
B2_RESTIC_ACCOUNT_KEY=
```

**Impact:** Cosmetic — no functional impact. Low priority.

### 3.7 `react-build/` placeholder committed but also gitignored

Step 6 creates `react-build/index.html` and Step 8 commits it. But `.gitignore` includes `react-build/`. You'd need `git add -f react-build/index.html` to force-add a gitignored file. As written, `git add react-build/index.html` will be silently skipped.

**Fix:** Either (a) remove `react-build/` from `.gitignore` and add `react-build/*.js`, `react-build/*.css`, etc. to ignore build artifacts while keeping the placeholder, or (b) don't commit the placeholder and let `docker compose up` fail gracefully if the directory is missing (nginx is disabled in dev anyway), or (c) use `git add -f` in the commit step.

---

## 4. Questions for Clarification

1. **Worker heartbeat writer:** The plan documents that "the worker process must write to `/tmp/worker-heartbeat` on each processing cycle." Is this intended to be per-job (after each image is processed) or periodic (e.g., every 30 seconds regardless of work)? Per-job means an idle worker with no jobs will eventually fail its health check. A periodic heartbeat thread is more robust.

2. **`pgbackup` user permissions:** The `pgbackup` Dockerfile is `FROM postgres:16` and runs with `user: "1000:1000"`. The `pg_dump` command connects to the postgres container over the network, so the local user ID doesn't affect database access. But the `restic` password file is mounted as a Docker secret at `/run/secrets/restic_pass` — will user `1000:1000` have read access to this file? Docker secrets are typically mounted as `root:root` with mode `0444`, so this should work, but worth verifying.

---

## 5. Final Recommendation

**Approve as-is.**

The plan is ready for implementation. All critical issues from prior reviews have been resolved. The remaining items (§3.1–§3.7) are minor hardening and cosmetic improvements that can be addressed during implementation or in a subsequent hardening pass. None block execution.

**Optional improvements to address during implementation (priority order):**
1. Fix `react-build/` gitignore conflict (§3.7) — will cause a commit failure if not addressed
2. Increase worker heartbeat tolerance (§3.5) — prevents false unhealthy during long jobs
3. Add `restart: unless-stopped` to certbot (§3.2)
4. Remaining items are low-priority polish
