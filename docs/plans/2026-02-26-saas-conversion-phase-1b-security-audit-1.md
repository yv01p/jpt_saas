# Security Audit — Phase 1b: Docker Compose & Dockerfiles

**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Date:** 2026-02-26
**Scope:** `docs/plans/2026-02-25-saas-conversion-phase-1b.md` v3.0 — Docker Compose stack, Dockerfiles, environment configuration, nginx stub, backup services
**Methodology:** Three-pass white-box review (reconnaissance → systematic hunting → compositional analysis)

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Components in scope:**
- `docker-compose.yml` — 8 services: nginx, api, worker, postgres, minio, redis, backup, pgbackup, certbot
- `docker-compose.dev.yml` — dev overrides exposing ports, weakening credentials
- `.env.example` — environment variable template with placeholder secrets
- `nginx.conf` — reverse proxy stub (HTTP only, no TLS)
- `api/Dockerfile`, `worker/Dockerfile`, `pgbackup/Dockerfile`
- `secrets/` directory provisioning

**Trust boundaries identified:**
1. Internet → nginx (port 80/443) → api container (port 8080)
2. api → postgres, minio, redis (credentials via env vars)
3. worker → postgres (restricted user), minio, redis
4. backup → minio → B2 (external cloud)
5. pgbackup → postgres → B2 (external cloud, via restic)

**Sensitive data flows:**
- Database credentials, JWT secret, OAuth secrets, SMTP credentials, B2 keys — all via `${VAR}` env interpolation
- Restic password — via Docker secrets file mount

---

## Findings

### Finding #1: No Docker Network Segmentation

**Vulnerability:** Missing network isolation — Security Misconfiguration (A05)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Medium

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 33–241 (docker-compose.yml)

**Risk & Exploit Path:**
All 8 services share the default Docker Compose network. If the nginx or api container is compromised, the attacker has direct network access to postgres, redis, and minio without any segmentation. The design doc (v4.0) specifies an `internal` network for monitoring services, but Phase 1b removed all network definitions when monitoring was deferred.

**Evidence / Trace:**
The `docker-compose.yml` in the plan has no `networks:` section at all. The design doc Section 7 specifies:
```yaml
networks:
  internal: true   # ← Referenced in design doc but absent from Phase 1b plan
```

**Remediation:**
- Primary fix: Add a `backend` network for postgres/minio/redis (internal: true), and a `frontend` network for nginx/api. Only the api service bridges both. This limits blast radius if nginx is compromised.
- Defense-in-depth: When monitoring is added, ensure it uses its own isolated network as the design doc specifies.

**References:**
- CWE-653: Improper Isolation or Compartmentalization

---

### Finding #2: PostgreSQL Exposed Without Network Restriction

**Vulnerability:** Database port not bound to internal network — Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 115–128 (postgres service)
- Related: Lines 253–256 (dev override exposing port 5432)

**Risk & Exploit Path:**
In `docker-compose.dev.yml`, postgres is exposed on `5432:5432` with hardcoded credentials (`jpt`/`jpt`). This binds to `0.0.0.0` by default. If the dev machine has any external network interface (cloud VM, shared network), the database is reachable from outside.

**Evidence / Trace:**
```yaml
postgres:
    ports: ["5432:5432"]        # ← Binds 0.0.0.0:5432
    environment:
      POSTGRES_PASSWORD: jpt    # ← Trivial password
```

**Remediation:**
- Primary fix: Bind to loopback only: `ports: ["127.0.0.1:5432:5432"]`. Apply same to redis (6379) and minio (9000, 9001).
- Defense-in-depth: Add a comment in `.env.example` warning against using dev credentials on network-accessible machines.

**References:**
- CWE-668: Exposure of Resource to Wrong Sphere

---

### Finding #3: Secrets Passed via Environment Variables (Not Docker Secrets)

**Vulnerability:** Credential exposure via environment variables — Cryptographic Failures / Data Exposure (A02)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 49–59 (api env), Lines 84–92 (worker env), Lines 170–174 (backup env), Lines 200–205 (pgbackup env)

**Risk & Exploit Path:**
All credentials (DB passwords, JWT secret, OAuth secrets, SMTP credentials, MinIO keys, B2 keys) are passed via environment variables. Environment variables are visible via `docker inspect`, `/proc/*/environ` inside the container, and are often leaked into crash dumps, logging frameworks, and child process environments. The plan correctly uses Docker secrets for the restic password but not for any other credential.

**Evidence / Trace:**
```yaml
api:
    environment:
      DB_PASS: ${DB_PASS}           # ← Visible in docker inspect
      JWT_SECRET: ${JWT_SECRET}     # ← Visible in docker inspect
      GOOGLE_CLIENT_SECRET: ${GOOGLE_CLIENT_SECRET}  # ← Visible in docker inspect
```
Meanwhile, pgbackup correctly uses:
```yaml
    secrets:
      - restic_pass               # ← Mounted as file, not in env
```

**Remediation:**
- Primary fix: Migrate critical secrets (JWT_SECRET, DB_PASS, GOOGLE_CLIENT_SECRET, SMTP_PASS, MINIO_SECRET_KEY, B2 keys) to Docker secrets files. Application reads from `/run/secrets/<name>`. This is a moderate refactor that also requires Spring Boot config changes.
- Acceptable trade-off: For an initial single-user deployment, environment variables are a common pattern. Document this as a known risk and plan migration to secrets before multi-user production. Mark as **Accepted Risk** if the operator controls the Docker host.

**References:**
- CWE-526: Exposure of Sensitive Information Through Environmental Variables

---

### Finding #4: API Dockerfile Runs as Root

**Vulnerability:** Container runs as root user — Security Misconfiguration (A05)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Medium

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 411–419 (api/Dockerfile)

**Risk & Exploit Path:**
The API Dockerfile has no `USER` directive, so the Java process runs as root (UID 0) inside the container. If an attacker achieves RCE via a deserialization bug or dependency vulnerability, they have root access within the container, making container escape significantly easier. The worker Dockerfile correctly creates and uses a non-root user.

**Evidence / Trace:**
```dockerfile
# api/Dockerfile — NO USER directive
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/app.jar app.jar
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
# ← VULNERABLE: runs as root
```

vs. worker/Dockerfile:
```dockerfile
RUN addgroup -S worker && adduser -S worker -G worker
USER worker    # ← Correct
```

**Remediation:**
- Primary fix: Add non-root user to API Dockerfile:
```dockerfile
RUN addgroup -S appuser && adduser -S appuser -G appuser
USER appuser
```

---

### Finding #5: Backup Entrypoint Shell Injection Surface via Environment Variables

**Vulnerability:** Shell expansion of environment variables in entrypoint — Injection (A03)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 161–169 (backup entrypoint), Lines 189–198 (pgbackup entrypoint)

**Risk & Exploit Path:**
Both backup and pgbackup services use inline shell scripts in `entrypoint` that expand environment variables (`$$MINIO_ACCESS_KEY`, `$$DB_PASS`, etc.) directly in shell context. If an attacker can influence these environment variable values (e.g., via a compromised `.env` file or orchestration layer), they could inject shell commands. The pgbackup service is particularly sensitive as `PGPASSWORD` and `DB_USER` are expanded into a `pg_dump` command line.

**Evidence / Trace:**
```yaml
pgbackup:
    entrypoint: >
      /bin/sh -c "
        while true; do
          PGPASSWORD=$$DB_PASS pg_dump -h postgres -U $$DB_USER $$DB_NAME |  # ← Shell expansion
```

**Remediation:**
- Primary fix: This is low risk because environment variables are set by the operator's `.env` file (trusted input). However, for defense-in-depth, consider using `pg_dump` with a `.pgpass` file or `PGPASSFILE` pointing to a Docker secret instead of inline `PGPASSWORD` shell expansion.
- Note: The `$$` syntax in Compose YAML produces a literal `$` in the shell, which is correct. The risk is theoretical and requires attacker control of the `.env` file.

---

### Finding #6: Nginx Stub Has No Security Headers and No TLS

**Vulnerability:** Missing security headers and HTTPS — Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 315–343 (nginx.conf)

**Risk & Exploit Path:**
The nginx stub serves HTTP only (port 80) with no security headers (no CSP, no HSTS, no X-Frame-Options). While documented as a placeholder, if accidentally deployed to production as-is, all traffic would be unencrypted and the app would be vulnerable to clickjacking, MIME sniffing, etc.

**Evidence / Trace:**
```nginx
server {
    listen 80;   # ← HTTP only, no TLS
    # No add_header directives
```

**Remediation:**
- Primary fix: Already documented as a placeholder. Add a prominent `# WARNING: NOT FOR PRODUCTION — NO TLS, NO SECURITY HEADERS` comment at the top of the file. The design doc v4.0 specifies the full production nginx config with TLS and headers.
- Defense-in-depth: Consider adding a simple check in the deployment script that refuses to deploy if the nginx config doesn't contain `ssl_certificate`.

---

### Finding #7: pgbackup Dockerfile Does Not Pin Restic Version

**Vulnerability:** Unpinned dependency version — Vulnerable Components (A06)
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 480–488 (pgbackup/Dockerfile)

**Risk & Exploit Path:**
The pgbackup Dockerfile installs restic via `apt-get install -y restic` without version pinning. A future `docker build` could pull a different restic version with breaking changes or, in a supply-chain attack scenario, a compromised package. The plan changelog notes this was a deliberate decision (v2.0, dismissed [3.5]), accepting distro-provided versions.

**Evidence / Trace:**
```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends \
    restic \    # ← No version pin
```

**Remediation:**
- Accepted trade-off per changelog. For additional safety, consider adding a checksum verification step or pinning to a specific Debian package version (e.g., `restic=0.16.*`).

---

### Finding #8: MinIO Uses Root Credentials for Worker and Backup

**Vulnerability:** Shared admin credentials across services — Broken Access Control (A01)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 53–55 (api env), Lines 88–89 (worker env), Lines 171–172 (backup env)

**Risk & Exploit Path:**
All three services (api, worker, backup) use the same `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`, which are the MinIO root credentials (`MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`). This means the worker (which should have limited access) and the backup sidecar both have full MinIO admin access — they can delete all data, create/destroy buckets, and modify policies. If the worker is compromised, the attacker has full storage admin access.

The design doc specifies that the B2 backup credentials should be write-only (no delete), but the MinIO credentials themselves are not scoped.

**Evidence / Trace:**
```yaml
minio:
    environment:
      MINIO_ROOT_USER: ${MINIO_ACCESS_KEY}      # ← Root credentials
      MINIO_ROOT_PASSWORD: ${MINIO_SECRET_KEY}   # ← Same key used by api, worker, backup
```

**Remediation:**
- Primary fix: Create separate MinIO service accounts with scoped policies: api gets read/write to user paths, worker gets read/write to processing paths, backup gets read-only. Create these via an init container or startup script using `mc admin user add` and `mc admin policy attach`.
- This is a Phase 2+ concern but should be tracked.

---

## Executive Summary

Phase 1b defines a well-structured Docker Compose stack with good baseline hardening on the worker container (non-root user, read-only filesystem, cap_drop, no-new-privileges, tmpfs). The API container receives similar Compose-level hardening but its Dockerfile lacks a non-root user directive — an inconsistency that should be fixed before first deployment.

The most architecturally significant gaps are: (1) no network segmentation between services, meaning a compromised nginx or api container has direct access to all datastores; (2) all secrets except the restic password are passed via environment variables rather than Docker secrets; and (3) MinIO root credentials are shared across all services without scoping.

None of these are critical/exploitable vulnerabilities in isolation — they are defense-in-depth gaps that increase blast radius if any single component is compromised. For a single-user initial deployment, the risk posture is acceptable provided these are remediated before multi-tenant production.

## Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | No Docker network segmentation | A05 | Medium | Confirmed | 1 | FIX |
| 2 | PostgreSQL dev port binds 0.0.0.0 | A05 | Low | Confirmed | 3 (redis, minio) | FIX |
| 3 | Secrets via env vars not Docker secrets | A02 | Medium | High | 1 | ACCEPT/PLAN |
| 4 | API Dockerfile runs as root | A05 | Medium | Confirmed | 1 | FIX |
| 5 | Backup shell expansion surface | A03 | Low | Medium | 2 | ACCEPT |
| 6 | Nginx stub has no TLS/headers | A05 | Low | Confirmed | 1 | ACCEPT (placeholder) |
| 7 | Unpinned restic version | A06 | Low | Medium | 1 | ACCEPT |
| 8 | Shared MinIO root credentials | A01 | Medium | Confirmed | 1 | PLAN |

## Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical | 0 | 0 |
| High | 0 | 0 |
| Medium | 4 | −32 |
| Low | 4 | −8 |

**Final SQS:** 60/100
**Hard gates triggered:** No
**Posture:** Unacceptable — remediation required before production deployment

**Note:** This score reflects the plan as written. Findings #1 and #4 are quick fixes that would bring the score to 76 (Acceptable). Finding #3 can be accepted with documentation for initial single-user deployment. Finding #8 is a Phase 2 concern.

**Adjusted SQS (after fixing #1 and #4):** 76/100 — Acceptable with remediation commitment.

## Positive Security Observations

1. **Worker container hardening is excellent** — `read_only: true`, `tmpfs`, `cap_drop: ALL`, `no-new-privileges`, non-root user, and tini as init process. This is best-practice container security.
2. **API container Compose-level hardening** — `cap_drop: ALL` and `no-new-privileges` applied (added in v3.0 review cycle).
3. **Restic password uses Docker secrets** — The pgbackup service correctly uses the secrets mechanism rather than environment variables for the restic encryption password.
4. **Health checks on all services** — Every service has a meaningful health check, and `depends_on` uses `condition: service_healthy` to enforce startup ordering.
5. **Separate worker DB credentials** — The worker uses `WORKER_DB_USER`/`WORKER_DB_PASS` with a restricted PostgreSQL role, not the full-privilege API credentials.

## Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #4 — API runs as root | Trivial fix, eliminates root-in-container risk | Quick Win | Backend |
| 2 | #1 — No network segmentation | Limits blast radius of any container compromise | Quick Win | DevOps |
| 3 | #2 — Dev ports bind 0.0.0.0 | Prevents accidental exposure on dev VMs | Quick Win | DevOps |
| 4 | #8 — Shared MinIO root creds | Violates least-privilege; track for Phase 2 | Moderate | Backend/DevOps |
| 5 | #3 — Secrets via env vars | Lower priority for single-user; plan for multi-tenant | Significant Refactor | DevOps |
