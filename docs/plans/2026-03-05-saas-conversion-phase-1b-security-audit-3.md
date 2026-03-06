# Security Audit #3 — Phase 1b: Docker Compose & Dockerfiles (Implementation Review)

**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Date:** 2026-03-05
**Scope:** Implemented files for `docs/plans/2026-02-25-saas-conversion-phase-1b.md` v4.0 — actual files on disk, not the plan document.
**Prior Audits:**
- `2026-02-26-saas-conversion-phase-1b-security-audit-1.md` (against v3.0 plan)
- `2026-02-26-saas-conversion-phase-1b-security-audit-2.md` (against v4.0 plan)
**Methodology:** Three-pass white-box review (reconnaissance → systematic hunting → compositional analysis)
**Files Reviewed:**
- `docker-compose.yml`
- `docker-compose.dev.yml`
- `.env.example`
- `nginx.conf`
- `api/Dockerfile`
- `worker/Dockerfile`
- `pgbackup/Dockerfile`
- `api/.dockerignore`, `worker/.dockerignore`
- `api/build.gradle.kts`
- `.gitignore`, `secrets/.gitignore`
- `secrets/restic_pass.txt` (existence confirmed, gitignored)

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Services (9):** nginx, api, worker, postgres, minio, redis, backup, pgbackup, certbot.

**Trust boundaries:**
1. Internet → nginx (frontend network, ports 80/443) → api (bridges frontend + backend)
2. api → postgres, minio, redis (backend internal network, via env-var credentials)
3. worker → postgres (restricted user), minio, redis (backend only)
4. backup → minio (root credentials) → external B2
5. pgbackup → postgres (API-level credentials) → external B2 (via restic)
6. certbot → Let's Encrypt challenge → shared `certbot_certs` volume → nginx

**Sensitive data flows:**
- DB credentials, JWT secret, OAuth secrets, SMTP credentials, MinIO root creds, B2 keys — all via `${VAR}` interpolation from `.env` into Docker environment variables
- Restic password — via Docker secret file mount (`/run/secrets/restic_pass`)
- TLS certificates — `certbot_certs` volume shared read-write by certbot, read-only by nginx

**Container hardening state:**

| Service | Non-root user | cap_drop: ALL | no-new-privileges | read_only | tini |
|---------|--------------|---------------|-------------------|-----------|------|
| api | appuser (Dockerfile) | ✓ | ✓ | ✗ | ✗ |
| worker | worker (Dockerfile) | ✓ | ✓ | ✓ | ✓ |
| postgres | postgres (image internal) | ✗ | ✗ | ✗ | — |
| minio | uid 1000 (image default) | ✗ | ✗ | ✗ | — |
| redis | root | ✗ | ✗ | ✗ | — |
| backup | root | ✗ | ✗ | ✗ | — |
| pgbackup | 1000:1000 | ✗ | ✗ | ✗ | — |
| certbot | root | ✗ | ✗ | ✗ | — |
| nginx | nginx (image internal) | ✗ | ✗ | ✗ | — |

**Prior audit remediation status:**

| Finding | Audit | Status in Implementation |
|---------|-------|--------------------------|
| #1 Network segmentation | Audit #1 | Fixed ✓ (frontend/backend split) |
| #2 Dev ports 0.0.0.0 | Audit #1 | Fixed ✓ (127.0.0.1) |
| #3 Secrets via env vars | Audit #1 | Accepted ✓ (documented in .env.example) |
| #4 API runs as root | Audit #1 | Fixed ✓ (appuser) |
| #5 Backup shell injection | Audit #1 | Accepted ✓ |
| #6 Nginx no TLS/headers | Audit #1 | Accepted ✓ (warning comment) |
| #7 Unpinned restic | Audit #1 | Accepted ✓ |
| #8 Shared MinIO root creds | Audit #1 | Deferred — NOT yet fixed |
| #1–3, #5–6 Container hardening | Audit #2 | Recommended FIX — NOT implemented |
| #4 Postgres hardening | Audit #2 | Accepted — OK |
| #7 Certbot shared cert volume | Audit #2 | Informational — OK |
| #8 Actuator endpoint exposure | Audit #2 | Track (Phase 2) — NOT yet addressed |

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: Redis Health Check Fails — REDIS_PASSWORD Not Injected into Container Environment

**Vulnerability:** Non-functional health check causing startup deadlock — Security Misconfiguration (A05)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** N/A (configuration error, not attacker-triggered)

**Location:**
- File: `docker-compose.yml`, Lines 125–137 (redis service)
- Related: `docker-compose.yml`, Lines 39–53 (api `depends_on`), Lines 68–75 (worker `depends_on`)

**Risk & Exploit Path:**
The redis service passes its password via `command:` using `${REDIS_PASSWORD}` (Compose interpolates this from `.env` at parse time into the literal command string). However, the redis container has **no `environment:` section**, so `REDIS_PASSWORD` is not set in the container's runtime environment.

The health check is:
```
REDISCLI_AUTH=$$REDIS_PASSWORD redis-cli ping
```

In Docker Compose YAML, `$$` is an escape sequence that produces a literal `$`, so Docker receives:
```
REDISCLI_AUTH=$REDIS_PASSWORD redis-cli ping
```

When Docker executes this inside the redis container, `$REDIS_PASSWORD` is evaluated against the container's environment. Because `REDIS_PASSWORD` is not in the container environment (only in the command-line string baked in at Compose parse time), `REDISCLI_AUTH` is empty. Redis, started with `--requirepass <real-password>`, rejects the unauthenticated `redis-cli ping` with `NOAUTH Authentication required`, and the health check exits non-zero.

After 10 failed retries (5s × 10 = 50s), Docker marks redis as `unhealthy`. Both `api` and `worker` have `depends_on: redis: condition: service_healthy` — they will never start.

**Operational note:** The Phase 1b verification step started only `postgres minio redis` in isolation (not api/worker), so this dependency failure was not exercised. The bug would surface the first time the full stack is brought up.

**Evidence / Trace:**
```yaml
# docker-compose.yml:125 — redis has NO environment: section
redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD} ...  # ← password baked in at parse time
    healthcheck:
        test: ["CMD-SHELL", "REDISCLI_AUTH=$$REDIS_PASSWORD redis-cli ping"]
        # ← $$REDIS_PASSWORD → runtime $REDIS_PASSWORD → empty in container env → NOAUTH

# docker-compose.yml:39,68 — api and worker block on redis healthy
depends_on:
    redis:
        condition: service_healthy  # ← Never satisfied: redis always unhealthy
```

**Remediation:**
- Primary fix: Add `REDIS_PASSWORD` to the redis service's `environment:` section so the health check can resolve it at runtime:
  ```yaml
  redis:
      environment:
          REDIS_PASSWORD: ${REDIS_PASSWORD}
      healthcheck:
          test: ["CMD-SHELL", "REDISCLI_AUTH=$$REDIS_PASSWORD redis-cli ping"]
  ```
- Defense-in-depth: Use the exec form for the health check to avoid shell expansion ambiguity:
  ```yaml
  test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]
  ```
  Note: The exec form doesn't support `$$` escaping, so `${REDIS_PASSWORD}` here would need the environment variable to be present.

**References:**
- CWE-730: Reachable Assertion / CWE-693: Protection Mechanism Failure (monitoring)

---

### Finding #2: MinIO and MinIO-MC Images Unpinned (`:latest` tag)

**Vulnerability:** Unpinned container image versions — Vulnerable Components / Supply Chain (A06)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docker-compose.yml`, Line 108 (minio service)
- File: `docker-compose.yml`, Line 140 (backup service)

**Risk & Exploit Path:**
Both the MinIO server (`minio/minio`) and MinIO Client (`minio/mc`) images have no tag, defaulting to `:latest`. Every `docker compose pull` or fresh build can pull a different image version. Risks:

1. **Non-reproducible deployments** — Two deploys weeks apart may run different MinIO versions, introducing breaking API changes, behavior differences, or unintended vulnerability exposure.
2. **Supply chain attack surface** — If Docker Hub for `minio/minio` or `minio/mc` is compromised, a malicious `:latest` push is automatically pulled on the next `docker compose pull`.
3. **Audit trail loss** — It's impossible to determine which MinIO version is running post-deployment without inspecting the image digest.

MinIO controls all user photo storage and the backup pipeline — it is the highest-value target in this stack after the database.

Compare: `redis:7-alpine`, `postgres:16`, `eclipse-temurin:21-jre-alpine` all have meaningful version tags.

**Evidence / Trace:**
```yaml
minio:
    image: minio/minio    # ← :latest — changes unpredictably

backup:
    image: minio/mc       # ← :latest — changes unpredictably
```

**Remediation:**
- Primary fix: Pin to a specific release tag:
  ```yaml
  image: minio/minio:RELEASE.2024-10-02T17-50-41Z
  image: minio/mc:RELEASE.2024-09-16T17-22-11Z
  ```
  Check the MinIO GitHub releases page for the latest stable tag.
- Architectural improvement: Consider pinning to digest (`image: minio/minio@sha256:...`) for cryptographic guarantee of immutability.
- Defense-in-depth: Add a `docker compose pull` step to CI/CD with explicit version verification before deploying.

**References:**
- CWE-1104: Use of Unmaintained Third Party Components
- OWASP A06:2021 – Vulnerable and Outdated Components

---

### Finding #3: No Resource Limits on Any Container

**Vulnerability:** Unconstrained resource consumption — Security Misconfiguration (A05) / Business Logic (resource exhaustion)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low (once a user account exists)

**Location:**
- File: `docker-compose.yml`, all service definitions (no `deploy.resources.limits` or `mem_limit`/`cpus` keys)

**Risk & Exploit Path:**
No container has CPU or memory limits. When the worker processes user-uploaded images (the primary use case), a malicious actor can:

1. Upload an extremely large or malformed image (e.g., a "decompression bomb" — a small archive that expands to gigabytes, or a crafted TIFF/RAW that triggers excessive memory allocation in `libraw-utils` or `vips-tools`).
2. The worker allocates unbounded memory, consuming all host RAM.
3. The Linux OOM killer begins terminating processes on the host, potentially killing postgres, redis, or the API — a complete host-level denial of service.

Additionally, an API endpoint that triggers expensive operations (large query result sets, complex image metadata extraction) can consume all CPU cycles, starving other services.

Worker-specific risk: `tmpfs /tmp:size=512M` limits temporary filesystem usage (good), but this does not limit heap memory.

**Evidence / Trace:**
```yaml
# Every service in docker-compose.yml is missing resource limits, e.g.:
worker:
    build: ./worker
    tmpfs:
        - /tmp:size=512M     # ← tmpfs is limited (good)
    # ← No mem_limit, no cpus, no deploy.resources.limits
    #    A malicious image can OOM the host

api:
    build: ./api
    # ← No mem_limit, no cpus
    #    Expensive requests starve all other containers
```

**Remediation:**
- Primary fix: Add memory and CPU limits to at minimum the worker and api services. Use Compose v2 compatible syntax:
  ```yaml
  api:
      mem_limit: 512m
      cpus: '1.0'

  worker:
      mem_limit: 1g      # Allow headroom for image processing
      cpus: '2.0'
  ```
  For Compose v3 with swarm compatibility:
  ```yaml
  deploy:
      resources:
          limits:
              memory: 512M
              cpus: '1.0'
  ```
  Note: `deploy.resources` requires `--compatibility` flag in non-swarm mode or Docker Compose v2.
- Architectural improvement: Implement file size limits at the API layer (multipart upload max-size) before files reach the worker. This is a Phase 2 application-level control.
- Defense-in-depth: Configure kernel-level cgroup limits if available, and add monitoring alerts for memory consumption above 80%.

**References:**
- CWE-400: Uncontrolled Resource Consumption
- OWASP A05:2021 – Security Misconfiguration

---

### Finding #4 (Carry-over from Audit #2, Findings #1–3 and #5–6): Inconsistent Container Hardening — certbot, backup, pgbackup, minio, redis Missing cap_drop/security_opt

**Vulnerability:** Missing Linux capability dropping and privilege escalation prevention — Security Misconfiguration (A05)
**Severity:** Low (grouped)
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- `docker-compose.yml`, Lines 203–214 (certbot) — no `cap_drop`, no `security_opt`, no `restart: unless-stopped`
- `docker-compose.yml`, Lines 139–166 (backup) — no `cap_drop`, no `security_opt`
- `docker-compose.yml`, Lines 168–201 (pgbackup) — has `user: "1000:1000"` but no `cap_drop`, no `security_opt`
- `docker-compose.yml`, Lines 107–123 (minio) — no `cap_drop`, no `security_opt`
- `docker-compose.yml`, Lines 125–137 (redis) — no `cap_drop`, no `security_opt`

**Risk & Exploit Path:**
These five services retain the full set of Linux capabilities (30+ capabilities including `NET_ADMIN`, `SYS_PTRACE`, `SYS_ADMIN`, etc.) that a compromised process could use to escalate within the container or attempt container escape. While all are on the `backend` internal network (reducing external exposure), inconsistent hardening means the blast radius of a compromise is larger than necessary.

This was identified as a FIX in audit #2. The implementation (matching plan v4.0) did not address it.

**Remediation:**
- Primary fix: Apply `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]` to certbot, backup, pgbackup, minio, and redis.
- Exception: postgres may require `CHOWN`, `SETUID`, `SETGID` for its internal privilege drop. Add only those back via `cap_add`. Test before applying.
- Additional: Add `restart: unless-stopped` to certbot (currently missing, unlike all other long-running services).

---

### Finding #5 (Carry-over from Audit #2, Finding #8): Actuator Endpoints Potentially Exposed via nginx `/api/` Proxy

**Vulnerability:** Information disclosure via Spring Boot Actuator — Security Misconfiguration (A05)
**Severity:** Medium
**Confidence:** Medium (depends on Phase 2 Spring Boot configuration)
**Attack Complexity:** Low

**Location:**
- File: `docker-compose.yml`, Line 45 (healthcheck URL: `http://localhost:8080/actuator/health`)
- File: `nginx.conf`, Lines 21–27 (proxy_pass `http://api:8080/`)
- File: `api/build.gradle.kts`, Line 22 (`spring-boot-starter-actuator` dependency)

**Risk & Exploit Path:**
The nginx configuration proxies all `/api/*` requests to the API backend at `http://api:8080/`. The Spring Boot Actuator is on the classpath (`spring-boot-starter-actuator`). By default, Spring Boot 3 exposes `/actuator/health` and `/actuator/info` — but additional endpoints (`/actuator/env`, `/actuator/beans`, `/actuator/configprops`, `/actuator/mappings`) can be enabled accidentally.

Via nginx, these would be accessible at:
- `https://example.com/api/actuator/env` → potentially exposes all environment variables including JWT_SECRET, DB credentials, SMTP credentials
- `https://example.com/api/actuator/beans` → full Spring context map

The healthcheck uses the actuator endpoint internally (fine). The risk is that the nginx proxy creates a public path to the same endpoint tree.

**Evidence / Trace:**
```nginx
# nginx.conf:21-27
location /api/ {
    proxy_pass http://api:8080/;   # ← /api/actuator/* → http://api:8080/actuator/*
}
```
```kotlin
// api/build.gradle.kts:22
implementation("org.springframework.boot:spring-boot-starter-actuator")  # ← on classpath
```

**Remediation:**
- Primary fix (application, Phase 2): Restrict actuator in `application.yml`:
  ```yaml
  management:
      endpoints:
          web:
              exposure:
                  include: health
      endpoint:
          health:
              show-details: never
              show-components: never
  ```
- Defense-in-depth (infrastructure, now): Add a deny block in nginx before the `/api/` proxy:
  ```nginx
  location /api/actuator/ {
      deny all;
      return 404;
  }
  ```
  This is a quick win that doesn't require application changes.

---

### Finding #6 (Carry-over from Audit #1, Finding #8): All Services Share MinIO Root Credentials

**Vulnerability:** Shared admin credentials, no least-privilege — Broken Access Control (A01)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low (once any of api/worker/backup is compromised)

**Location:**
- `docker-compose.yml`, Lines 25–26 (api env: `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`)
- `docker-compose.yml`, Lines 63–65 (worker env: same keys)
- `docker-compose.yml`, Lines 154–157 (backup env: same keys)
- `docker-compose.yml`, Lines 115–116 (minio env: `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` = same keys)

**Risk & Exploit Path:**
`MINIO_ACCESS_KEY` and `MINIO_SECRET_KEY` are the MinIO root admin credentials. All three application services (api, worker, backup) use these same credentials. A compromise of any one service grants the attacker:
- Full MinIO admin access (create/delete buckets, modify policies, read all user data)
- Ability to create MinIO service accounts to maintain persistence
- Ability to disable the backup service account

The worker is the highest-risk vector: it processes untrusted user input (images with potential exploit payloads) using libraw and vips.

**Remediation:**
- Primary fix (Phase 2 init container): Create scoped MinIO service accounts:
  - api: read/write to `jpt-photos/<user_id>/` paths only
  - worker: read from upload path, write to processed path
  - backup: read-only on all buckets (no write, no delete)
  Use `mc admin user add` and `mc admin policy attach` in a startup script.
- Defense-in-depth: Enable MinIO audit logging to detect unexpected bucket operations.

---

## Pass 3: Cross-Cutting & Compositional Analysis

**Chained attacks:**

1. **Finding #3 + worker's tool chain**: Malicious image upload → worker OOM via libraw/vips → host-level DoS. No resource limits means this DoS affects all services on the host, not just the worker. Severity of #3 elevates when combined with the worker's use of image-processing tools on untrusted data.

2. **Finding #6 + Finding #3**: Worker is compromised via image exploit → attacker uses MinIO root credentials → deletes all user data → no resource limits means exfiltration is uncapped. Double impact.

3. **Finding #5 + Spring Boot default config**: If Phase 2 does not explicitly restrict actuator and the `/api/actuator/env` endpoint is enabled (even accidentally via misconfiguration), Finding #5 becomes critical — it would expose the JWT secret, DB password, and all other credentials visible in the container environment.

4. **Finding #1 + Finding #4**: Redis is unhealthy → api never starts → fix is to add REDIS_PASSWORD to env → but redis still lacks `cap_drop` (Finding #4). The fix for #1 inadvertently makes the redis container's environment more visible via `docker inspect`, slightly amplifying #4's risk for the password.

**Deployment context:**
- `secrets/restic_pass.txt` exists on disk with content; correctly gitignored by both `secrets/.gitignore` (`*` / `!.gitignore`) and root `.gitignore` (`secrets/`). File permissions were not verified — `openssl rand -base64 32 > secrets/restic_pass.txt` creates a file with default umask permissions (typically `644`). On a shared host, this means other users could read the restic encryption key.
- `.env` is gitignored (`/.env` in `.gitignore`). No `.env` file was found in the repository. Clean.
- No CI/CD pipeline in scope. No secret scanning in commit hooks confirmed.

---

## Executive Summary

This audit reviews the actual implemented files against the v4.0 plan specification, cross-referencing two prior audits. The implementation faithfully matches the v4.0 plan. The three new findings are: a broken Redis health check that will prevent the full stack from starting in any environment where `REDIS_PASSWORD` is set; unpinned MinIO images; and absent container resource limits.

The most operationally urgent finding is the Redis health check (Finding #1): `REDIS_PASSWORD` is not injected into the redis container environment, so `REDISCLI_AUTH=$$REDIS_PASSWORD` in the health check resolves to an empty string at runtime. Redis returns `NOAUTH`, the health check fails, and both `api` and `worker` — which have `condition: service_healthy` on redis — never start. This misconfiguration went undetected because the Phase 1b verification step started redis in isolation, not as part of the full stack.

Four findings carry over from prior audits and remain unaddressed in the implementation: inconsistent container hardening (certbot, backup, pgbackup, minio, redis lack `cap_drop`/`no-new-privileges`), actuator endpoint exposure through the nginx proxy, MinIO root credential sharing across all services, and certbot missing `restart: unless-stopped`. These were identified in audit #2 as FIX or TRACK items; the plan was not updated to a v5.0 to incorporate them.

The codebase is not ready for production deployment without at minimum fixing the Redis health check (Finding #1, trivial fix) and addressing the actuator nginx block (Finding #5, quick win). The MinIO credential scoping (Finding #6) and resource limits (Finding #3) should be resolved before accepting production traffic.

---

## Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | Redis health check always fails — REDIS_PASSWORD not in container env | A05 | Medium | High | — | BLOCK |
| 2 | MinIO/MinIO-MC images unpinned (:latest) | A06 | Low | Confirmed | — | FIX |
| 3 | No resource limits on any container | A05 | Medium | High | — | FIX |
| 4 | Certbot/backup/pgbackup/minio/redis missing cap_drop & security_opt | A05 | Low | Confirmed | 5 services | FIX (group) |
| 5 | Actuator endpoints reachable via nginx /api/ proxy | A05 | Medium | Medium | — | TRACK/BLOCK |
| 6 | MinIO root credentials shared across api, worker, backup | A01 | Medium | Confirmed | — | PLAN |

---

## Security Quality Score (SQS)

**Scoring methodology:** Score reflects all currently-open findings in the implementation, including unaddressed carry-overs from prior audits.

| Finding | Severity | Grouped? | Deduction |
|---------|----------|----------|-----------|
| #1 Redis health check | Medium | No | −8 |
| #2 MinIO :latest | Low | No | −2 |
| #3 No resource limits | Medium | No | −8 |
| #4 Container hardening (5 services) | Low | Yes (≥3 similar) | −2 |
| #5 Actuator via nginx | Medium | No | −8 |
| #6 MinIO root creds | Medium | No | −8 |

**Total deduction:** −36

**Final SQS:** 64/100
**Hard gates triggered:** No (no Critical findings, no hardcoded secrets in source, no known-exploited CVEs)
**Posture:** Unacceptable — block deployment, urgent remediation required

**Note:** Fixing Finding #1 (trivial) and Finding #5 nginx block (quick win) would raise the score to 80 — just below the Acceptable threshold. Adding resource limits (#3) and fixing container hardening (#4 group) would reach 92 — Strong posture.

---

## Positive Security Observations

1. **Implementation faithfully matches the reviewed v4.0 plan.** All v4.0 security improvements (network segmentation, loopback-only dev ports, non-root API user, warning comment on nginx stub) are correctly present in the actual files. No drift between plan and implementation.
2. **Worker container is best-practice hardened.** `read_only: true`, tmpfs-only writable space, `cap_drop: ALL`, `no-new-privileges`, non-root user, and tini as PID 1 — this is the correct pattern for a container processing untrusted data.
3. **Secrets directory is properly gitignored.** `secrets/.gitignore` uses `* / !.gitignore` and the root `.gitignore` includes `secrets/`. No secrets are committed.
4. **`.env` is gitignored and absent from the repository.** The `.env` file is correctly excluded; only `.env.example` is tracked.
5. **Separate worker DB credentials.** `WORKER_DB_USER`/`WORKER_DB_PASS` are distinct from the API's credentials, correctly applying least-privilege at the database layer.

---

## Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #1 — Redis health check broken | Prevents the entire stack from starting; trivial one-line fix | Quick Win | DevOps |
| 2 | #5 — Actuator nginx block | Add `deny all` location block before it becomes exploitable in Phase 2 | Quick Win | DevOps |
| 3 | #3 — No resource limits | Worker processes untrusted images; OOM can take down the host | Moderate | DevOps |
| 4 | #4 — Container hardening group | Defense-in-depth; 5 services missing `cap_drop`/`no-new-privileges`; low blast radius | Quick Win (× 5) | DevOps |
| 5 | #6 — MinIO root creds | Phase 2 init container; required before production traffic | Moderate | Backend/DevOps |
