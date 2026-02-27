# Critical Implementation Review #2: Phase 1b v2.0 — Docker Compose & Dockerfiles

**Reviewer:** Claude (Senior Staff Engineer)
**Date:** 2026-02-26
**Plan reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-1b.md` (v2.0)
**Previous review:** `docs/plans/2026-02-25-saas-conversion-phase-1b-critical-review-1.md`

---

## 1. Overall Assessment

The v2.0 plan is a substantial improvement. All 8 critical issues and most minor issues from the first review have been addressed. The full inline Compose YAML is valid, health checks are defined, JVM flags are present, JAR naming is fixed, and dev overrides are clean. The plan is now implementable as written.

**Remaining concerns are minor-to-moderate.** The most significant are: (1) network isolation for Prometheus/Grafana doesn't match the design doc's intent, (2) the API container lacks the same security hardening applied to the worker, and (3) the backup containers have no error reporting when backup jobs fail silently.

---

## 2. Critical Issues

### 2.1 Prometheus and Grafana network configuration doesn't achieve isolation

**Problem:** The plan puts Prometheus and Grafana on both `default` and `internal` networks:
```yaml
networks:
  - default
  - internal
```
The design doc says they should be on the `internal` network only, with access via SSH tunnel. By also being on the `default` network, they are reachable from any container on the default bridge and potentially from the host if ports are accidentally exposed in a future change. This contradicts the zero-trust requirement.

**Impact:** Weakened network isolation; monitoring services accessible to all containers rather than being segmented.

**Fix:** Remove `default` from the Prometheus and Grafana network lists. They only need `internal`. Prometheus scrapes the API via the `internal` network — but the API is only on `default`. Either:
- (a) Put the API on both `default` and `internal` so Prometheus can reach it, or
- (b) Keep Prometheus on `default` but do NOT put Grafana on `default` — Grafana only needs to reach Prometheus.

Option (a) is cleaner. Add `networks: [default, internal]` to the API service and use `networks: [internal]` only for Prometheus and Grafana.

### 2.2 Backup and pgbackup containers fail silently

**Problem:** Both `backup` and `pgbackup` run infinite `while true` loops with `sleep`. If `mc mirror` or `pg_dump | restic backup` fails, the error is logged to stdout but there is no alerting mechanism. A backup job could fail for weeks without anyone noticing.

**Impact:** Data loss risk — backups may silently stop working.

**Fix:** Add a health check or failure-tracking mechanism. At minimum:
- Add a file-based heartbeat (similar to the worker): write a timestamp file on successful backup, health check verifies the file is recent (e.g., within 2 hours for `backup`, within 25 hours for `pgbackup`).
- Or document that Prometheus/Grafana alerts will monitor backup success (and add this to the monitoring phase scope).

---

## 3. Minor Issues & Improvements

### 3.1 API container lacks security hardening present on worker

The worker has `cap_drop: ALL`, `security_opt: no-new-privileges:true`, and `read_only: true`. The API container has none of these. While the API needs write access for certain operations (e.g., temp files), it should still get `cap_drop: ALL` and `security_opt: no-new-privileges:true` — these don't affect normal Java operation.

**Fix:** Add to the `api` service:
```yaml
cap_drop:
  - ALL
security_opt:
  - no-new-privileges:true
```

### 3.2 Redis `maxmemory` not configured

The design doc mentions a monitoring alert for Redis memory >80% of `maxmemory`, but no `maxmemory` is set in the Redis command. Without it, Redis will consume unlimited memory until OOM-killed.

**Fix:** Add `--maxmemory 256mb --maxmemory-policy noeviction` (or appropriate limit) to the Redis command, or document this as a deployment-time configuration.

### 3.3 Nginx stub missing `certbot_www` volume and ACME challenge location

The stub `nginx.conf` doesn't include the `/.well-known/acme-challenge/` location or serve from the `certbot_www` volume. When certbot tries to renew via webroot, it will fail because nginx doesn't serve the challenge files.

**Fix:** Since nginx is disabled in dev via profiles, this only matters in production. Either add the ACME location to the stub now, or document clearly that the stub is dev-only and the full nginx config (from the design doc) must replace it before production deployment.

### 3.4 Postgres data not on `internal` network

All data stores (Postgres, MinIO, Redis) communicate only with the API and worker. They don't need to be on the `default` network. Moving them to `internal` and putting the API/worker on both networks would follow least-privilege networking.

**Fix:** Low priority — the default Docker bridge doesn't expose container ports to the host unless `ports:` is declared. But for defense-in-depth, consider restricting data stores to `internal` only.

### 3.5 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` env var naming

The plan uses `MINIO_ROOT_USER: ${MINIO_ACCESS_KEY}` and `MINIO_ROOT_PASSWORD: ${MINIO_SECRET_KEY}`. This works but is confusing — the `.env` variable names suggest S3 API keys while they're actually root admin credentials. Not a bug, but could cause confusion during credential rotation.

### 3.6 Missing `prometheus.yml` stub

The Compose file mounts `./prometheus.yml:/etc/prometheus/prometheus.yml:ro` but the plan doesn't create this file. Prometheus will fail to start without it.

**Fix:** Add a minimal `prometheus.yml` stub:
```yaml
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['api:8080']
```
Or disable Prometheus in dev via profiles (it's already in the `monitoring` profile in the dev override, so this is only an issue for production).

### 3.7 `react-build` directory should be in `.gitignore`

The plan creates `react-build/index.html` as a placeholder. This directory will be populated by the frontend build and shouldn't be committed. Add `react-build/` to `.gitignore`.

---

## 4. Questions for Clarification

1. **MinIO `MINIO_SERVER_URL`:** The design doc states `MINIO_SERVER_URL=https://app.example.com` must be set so pre-signed URLs use the public domain. This environment variable is missing from the MinIO service and `.env.example`. Is this deferred to a later phase?

2. **Worker `DB_USER` vs `WORKER_DB_USER`:** The worker environment uses `DB_URL: ${DB_URL}` (which embeds the API's DB connection string including the API user) but `DB_USER: ${WORKER_DB_USER}`. The DB_URL already contains the user — is the intent for the worker to override the user from the URL? If so, the worker's Spring config needs to use `DB_USER` from env, not parse it from `DB_URL`. Clarify the intended credential flow.

3. **`nginx.conf` proxy stripping:** The stub uses `proxy_pass http://api:8080/;` (trailing slash) which strips `/api/` prefix. The design doc explicitly calls this out. Is the API's Spring Boot routing already designed for paths without the `/api/` prefix (e.g., `/auth/login` not `/api/auth/login`)? This is a Phase 2 concern but worth confirming the contract now.

---

## 5. Final Recommendation

**Approve with changes.**

The plan is well-structured and implementable. The v2.0 revisions resolved all critical issues from the first review. The remaining issues are moderate (network isolation, silent backup failures) and minor (missing prometheus.yml stub, API hardening). None require a major rewrite — they can be addressed as incremental amendments before implementation begins.

**Priority changes:**
1. Fix Prometheus/Grafana network isolation (Critical §2.1)
2. Add backup failure detection mechanism or document as future scope (Critical §2.2)
3. Add `cap_drop`/`security_opt` to API service (Minor §3.1)
4. Add Redis `maxmemory` configuration (Minor §3.2)
5. Create `prometheus.yml` stub or document dependency (Minor §3.6)
6. Clarify `MINIO_SERVER_URL` and worker DB credential flow (Questions §Q1, §Q2)
