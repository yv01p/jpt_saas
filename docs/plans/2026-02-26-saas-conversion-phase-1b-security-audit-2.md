# Security Audit #2 — Phase 1b: Docker Compose & Dockerfiles

**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Date:** 2026-02-26
**Scope:** `docs/plans/2026-02-25-saas-conversion-phase-1b.md` v4.0 — Docker Compose stack, Dockerfiles, environment configuration, nginx stub, backup services
**Prior Audit:** `2026-02-26-saas-conversion-phase-1b-security-audit-1.md` (against v3.0)
**Methodology:** Three-pass white-box review (reconnaissance → systematic hunting → compositional analysis)
**Cross-reference:** `docs/plans/2026-02-24-saas-conversion-design.md` v4.0 (design doc with security architecture)

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Components in scope:** Same 9-service Docker Compose stack as audit #1 (nginx, api, worker, postgres, minio, redis, backup, pgbackup, certbot), plus Dockerfiles, dev overrides, env template, nginx stub, and secrets directory.

**What changed since audit #1 (v3.0 → v4.0):**
1. Network segmentation added: `frontend` and `backend` (internal: true) networks
2. Dev ports now bind `127.0.0.1` (postgres, minio, redis, api)
3. API Dockerfile now creates and runs as non-root `appuser`
4. Secrets via env vars accepted with documentation note in `.env.example`
5. Nginx stub has prominent warning comment
6. Shared MinIO root credentials deferred to Phase 2

**Trust boundaries (updated):**
1. Internet → nginx (frontend network) → api (bridges frontend+backend) → datastores (backend, internal)
2. Worker → backend network only (no external exposure)
3. Backup services → backend network → external B2

---

## Assessment of Prior Audit Remediations

| Prior # | Title | v4.0 Status | Assessment |
|---------|-------|-------------|------------|
| 1 | No network segmentation | **Fixed** | Frontend/backend split with `internal: true` on backend. API correctly bridges both. Nginx and certbot on frontend only. ✓ |
| 2 | Dev ports bind 0.0.0.0 | **Fixed** | All dev ports now `127.0.0.1:PORT:PORT`. ✓ |
| 3 | Secrets via env vars | **Accepted** | Documented in `.env.example` with migration plan. Reasonable for single-operator. ✓ |
| 4 | API runs as root | **Fixed** | `appuser` created and `USER appuser` set. ✓ |
| 5 | Backup shell injection | **Accepted** | Theoretical risk, requires host compromise. ✓ |
| 6 | Nginx no TLS/headers | **Accepted** | Warning comment added. Placeholder by design. ✓ |
| 7 | Unpinned restic | **Accepted** | Deliberate decision documented. ✓ |
| 8 | Shared MinIO root creds | **Deferred to Phase 2** | Tracked. ✓ |

All 8 prior findings have been appropriately addressed, fixed, or accepted with documentation.

---

## New Findings (v4.0)

### Finding #1: Certbot Container Missing Security Hardening

**Vulnerability:** Missing container hardening — Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 235–246 (certbot service)

**Risk & Exploit Path:**
The certbot container lacks `cap_drop: ALL`, `security_opt: no-new-privileges:true`, and `restart: unless-stopped` — hardening that is consistently applied to api, worker, and backup containers. Certbot runs on the frontend network and handles ACME challenges from the internet. While certbot itself is a well-maintained tool with minimal attack surface, the inconsistency means a compromised certbot container would have more capabilities than necessary.

**Evidence / Trace:**
```yaml
certbot:
    image: certbot/certbot
    networks:
      - frontend
    volumes:
      - certbot_certs:/etc/letsencrypt
      - certbot_www:/var/www/certbot
    entrypoint: >
      # ← No cap_drop, no security_opt, no restart policy
```

Compare with api service which has all three.

**Remediation:**
- Primary fix: Add `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, and `restart: unless-stopped` to certbot service, matching the hardening pattern of other services.
- Note: Certbot needs `NET_BIND_SERVICE` only if binding to privileged ports directly, which it doesn't here — it uses volume-mounted webroot.

---

### Finding #2: Backup Container Missing Security Hardening

**Vulnerability:** Missing container hardening — Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 171–198 (backup service)

**Risk & Exploit Path:**
The backup container (minio/mc) has `restart: unless-stopped` but lacks `cap_drop: ALL` and `security_opt: no-new-privileges:true`. It connects to external B2 storage and handles MinIO root credentials. If compromised, the attacker retains full Linux capabilities within the container.

**Evidence / Trace:**
```yaml
backup:
    image: minio/mc
    networks:
      - backend
    restart: unless-stopped
    # ← No cap_drop, no security_opt
```

**Remediation:**
- Primary fix: Add `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]` to the backup service.

---

### Finding #3: pgbackup Container Missing cap_drop and security_opt

**Vulnerability:** Missing container hardening — Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 200–233 (pgbackup service)

**Risk & Exploit Path:**
The pgbackup service sets `user: "1000:1000"` (non-root, good) but lacks `cap_drop: ALL` and `security_opt: no-new-privileges:true`. It has database credentials and B2 access keys. Same rationale as Finding #2.

**Evidence / Trace:**
```yaml
pgbackup:
    build: ./pgbackup
    networks:
      - backend
    restart: unless-stopped
    user: "1000:1000"
    # ← No cap_drop, no security_opt
```

**Remediation:**
- Primary fix: Add `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]`.

**Note on Findings #1–#3:** These three findings share the same root cause (inconsistent hardening) and can be grouped as a single remediation item.

---

### Finding #4: Postgres Container Missing Hardening and Runs as Root (by default)

**Vulnerability:** Database container lacks hardening — Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 122–137 (postgres service)

**Risk & Exploit Path:**
The postgres container has no `cap_drop`, no `security_opt`, and no explicit non-root user. The official postgres image does drop to the `postgres` user internally via its entrypoint, so this is not a true root-as-PID-1 issue, but the absence of `cap_drop: ALL` and `no-new-privileges` leaves unnecessary Linux capabilities available. Postgres is on the internal backend network (good) and not directly exposed in production (good).

**Evidence / Trace:**
```yaml
postgres:
    image: postgres:16
    networks:
      - backend
    # ← No cap_drop, no security_opt
```

**Remediation:**
- Primary fix: Add `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]`. Note: postgres needs `CHOWN`, `SETUID`, `SETGID`, `FOWNER`, and `DAC_READ_SEARCH` capabilities for its entrypoint — use `cap_add` to add only these back, or accept this finding as low risk since the container is on an internal-only network.
- Acceptable trade-off: The official postgres image manages its own privilege dropping. Adding strict `cap_drop` may break the entrypoint. Test before applying.

---

### Finding #5: MinIO Container Missing Security Hardening

**Vulnerability:** Missing container hardening — Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 139–155 (minio service)

**Risk & Exploit Path:**
Same pattern as Findings #1–#4. MinIO has no `cap_drop` or `security_opt`. MinIO runs as UID 1000 by default in its official image, so this is a defense-in-depth concern.

**Remediation:**
- Primary fix: Add `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]`.

---

### Finding #6: Redis Container Missing Security Hardening

**Vulnerability:** Missing container hardening — Security Misconfiguration (A05)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 157–169 (redis service)

**Risk & Exploit Path:**
Redis has no `cap_drop` or `security_opt`. The redis-alpine image runs as root by default. Redis is on the internal backend network and password-protected, mitigating the risk.

**Remediation:**
- Primary fix: Add `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]`.

**Note on Findings #4–#6:** These datastore containers are all on the internal backend network, which significantly limits exposure. The hardening is defense-in-depth. These three plus Findings #1–#3 form a single group: "Apply consistent hardening to all containers."

---

### Finding #7: Certbot Writes to Shared TLS Certificate Volume

**Vulnerability:** Shared writable volume between services — Broken Access Control (A01)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Lines 39–40 (nginx certbot_certs:ro), Lines 239–240 (certbot certbot_certs:rw)

**Risk & Exploit Path:**
The `certbot_certs` volume is writable by certbot and read-only by nginx. This is the correct pattern. However, if the certbot container is compromised, an attacker could write a malicious TLS certificate to the shared volume, enabling a MitM attack after nginx reloads. This is a low-probability attack requiring certbot container compromise first.

**Evidence / Trace:**
```yaml
nginx:
    volumes:
      - certbot_certs:/etc/letsencrypt:ro    # ← Read-only, correct
certbot:
    volumes:
      - certbot_certs:/etc/letsencrypt       # ← Read-write, necessary for certbot
```

**Remediation:**
- This is the standard certbot pattern and is architecturally correct. No change needed. Noted for completeness.
- Defense-in-depth: When implementing nginx reload after cert renewal, use `nginx -t` to validate the certificate before reloading.

**Status:** Informational — no action required.

---

### Finding #8: API Actuator Health Endpoint Potentially Exposed

**Vulnerability:** Information disclosure via actuator — Security Misconfiguration (A05)
**Severity:** Medium
**Confidence:** Medium (depends on Phase 2 Spring Boot config)
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-1b.md`, Line 77 (healthcheck URL)
- Related: Lines 362–368 (nginx /api/ proxy_pass)

**Risk & Exploit Path:**
The API health check uses `http://localhost:8080/actuator/health`. The nginx stub proxies all `/api/*` to the API backend. This means `/api/actuator/health` (and potentially other actuator endpoints like `/api/actuator/env`, `/api/actuator/beans`, `/api/actuator/configprops`) would be publicly accessible unless Spring Boot is configured to restrict actuator exposure. The `/actuator/env` endpoint can expose environment variables including secrets.

**Evidence / Trace:**
```yaml
# Health check accesses actuator internally — fine
healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]

# But nginx proxies everything under /api/ to the backend
location /api/ {
    proxy_pass http://api:8080/;   # ← /api/actuator/* → http://api:8080/actuator/*
}
```

**Remediation:**
- Primary fix: In Phase 2 Spring Boot configuration, restrict actuator endpoints:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health
    endpoint:
      health:
        show-details: never
  ```
- Defense-in-depth: Add nginx location block to deny actuator access:
  ```nginx
  location /api/actuator/ {
      deny all;
      return 404;
  }
  ```
- **Requires verification:** This depends on Phase 2 Spring Boot configuration. If actuator is properly restricted at the application level, this is not exploitable. Flagged here because the infrastructure layer (nginx) does not block it.

---

## Executive Summary

Phase 1b v4.0 represents a significant improvement over v3.0. All 8 findings from the prior audit have been appropriately addressed — three were fixed directly (network segmentation, loopback-only dev ports, non-root API user), three were accepted with documentation (env var secrets, nginx stub, unpinned restic), one was accepted as theoretical (backup shell injection), and one was deferred to Phase 2 with tracking (MinIO credential scoping).

The remaining findings in this audit are predominantly low-severity hardening inconsistencies: 6 of 9 services have `cap_drop: ALL` and `no-new-privileges`, but certbot, backup, pgbackup, and the three datastore containers do not. This is a defense-in-depth gap, not an exploitable vulnerability, especially since the datastores sit on an internal-only network.

The one medium-severity finding (#8) concerns potential actuator endpoint exposure through the nginx proxy. This is a Phase 2 implementation concern — the infrastructure plan creates the exposure path, but Spring Boot configuration (not yet written) determines whether it's exploitable. It should be tracked as a Phase 2 security requirement.

Overall, the plan is ready for implementation with the hardening items tracked for follow-up.

---

## Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | Certbot missing hardening | A05 | Low | Confirmed | — | FIX |
| 2 | Backup missing hardening | A05 | Low | Confirmed | — | FIX |
| 3 | pgbackup missing hardening | A05 | Low | Confirmed | — | FIX |
| 4 | Postgres missing hardening | A05 | Low | Confirmed | — | ACCEPT |
| 5 | MinIO missing hardening | A05 | Low | Confirmed | — | FIX |
| 6 | Redis missing hardening | A05 | Low | Confirmed | — | FIX |
| 7 | Certbot shared cert volume | A01 | Informational | High | — | INFO |
| 8 | Actuator potentially exposed via nginx | A05 | Medium | Medium | — | TRACK |

---

## Security Quality Score (SQS)

**Prior audit remediation assessment:** All 8 prior findings resolved. Starting from clean baseline.

| Finding Severity | Count | Grouped? | Deduction |
|-----------------|-------|----------|-----------|
| Critical | 0 | — | 0 |
| High | 0 | — | 0 |
| Medium | 1 (#8) | No | −8 |
| Low | 5 (#1–3, #5–6, grouped as 1) + 1 (#4) | #1–3+#5–6 grouped | −2 (group) − 2 (#4) = −4 |
| Informational | 1 (#7) | No | −1 |

**Final SQS:** 87/100
**Hard gates triggered:** No
**Posture:** Strong — deploy with standard monitoring

---

## Positive Security Observations

1. **All prior audit findings addressed** — The v3.0→v4.0 iteration demonstrates a healthy security review cycle. Network segmentation, loopback-only dev ports, and non-root API user were all implemented correctly.
2. **Backend network is `internal: true`** — Datastores are not reachable from outside the Docker network, significantly limiting attack surface.
3. **Worker container remains best-practice** — Read-only filesystem, tmpfs, cap_drop, no-new-privileges, non-root user, tini init. This is the gold standard for container hardening.
4. **Health checks with `condition: service_healthy`** — Ensures proper startup ordering and prevents services from accepting traffic before dependencies are ready.
5. **Separate worker DB credentials with restricted role** — Least-privilege principle correctly applied to database access, enforced via Flyway migration.

---

## Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #1–3, #5–6 — Consistent container hardening | Defense-in-depth; 5 services missing `cap_drop`/`no-new-privileges` | Quick Win | DevOps |
| 2 | #8 — Actuator exposure via nginx | Medium severity; depends on Phase 2 config but should be tracked now | Quick Win (nginx block) | Backend/DevOps |
| 3 | #4 — Postgres hardening | Low risk (internal network); may require testing with `cap_add` | Moderate (testing) | DevOps |
| 4 | Prior #8 (deferred) — MinIO credential scoping | Tracked for Phase 2; create service accounts with least-privilege | Moderate | Backend/DevOps |
| 5 | Prior #3 (accepted) — Docker secrets migration | Track for multi-tenant production | Significant Refactor | DevOps |
