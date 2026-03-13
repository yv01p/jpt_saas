# Security Audit — Phase 5: Sharing & Polish

**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Date:** 2026-03-12
**Plan Version:** 6.0
**Subject:** `docs/plans/2026-02-25-saas-conversion-phase-5.md`
**Audit Number:** SA-P5-1

---

## Scope & Assumptions

This audit reviews the Phase 5 implementation plan against the existing codebase (commit `b1ca041db`). The analysis covers:

- **Task 5.1:** Share Token Service (backend)
- **Task 5.2:** Share Frontend (public view + management UI)
- **Task 5.3:** Nginx Configuration Enhancements
- **Task 5.4:** Monitoring (Prometheus + Grafana + Alertmanager)
- **Task 5.5:** CI Pipeline (GitHub Actions)
- **Task 5.6:** Final Integration Test (E2E)

**Assumptions:**
- The existing security controls (RLS, JWT auth, container hardening) are correctly implemented per prior phase audits (SA-P1 through SA-P4)
- The plan will be implemented as written; deviations would require re-audit
- Single-VPS deployment model as documented

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### New Entry Points Introduced by Phase 5

| Entry Point | Auth Required | Trust Boundary | Data Sensitivity |
|---|---|---|---|
| `GET /share/{token}` | No | Public internet → DB (RLS bypass) | Photo metadata, image URLs, GPS coordinates |
| `GET /share/{token}/photos` | No | Public internet → DB (RLS bypass) | Album photo listing with metadata |
| `POST /shares` | Yes | Authenticated user → DB | Creates share records |
| `DELETE /shares/{id}` | Yes | Authenticated user → DB | Revokes share access |
| `GET /shares` | Yes | Authenticated user → DB | Lists user's shares |
| `/actuator/prometheus` | Network-only | Docker backend network → metrics | Application internals, connection counts, error rates |
| Grafana (port 3000) | SSH tunnel | Admin → monitoring | Full system metrics |
| Alertmanager (port 9093) | Network-only | Docker backend → SMTP | Alert routing, SMTP credentials in config |
| MailPit (CI only, port 8025) | No (CI) | CI environment | Email content including verification tokens |

### New Privileged Database Role

`share_reader` with `BYPASSRLS` and `SELECT` on `shares`, `photos`, `albums`, `album_photos`, `photo_metadata` — the second BYPASSRLS role after `jpt_auth`.

### New Sensitive Data Flows

1. **Share token lifecycle:** `SecureRandom(256-bit)` → plaintext returned once to user → SHA-256 hash stored → hash compared on lookup
2. **GPS metadata exposure:** Share lookup returns photo metadata; GPS conditionally stripped based on `include_gps` flag
3. **SMTP credentials:** Alertmanager config file contains SMTP credentials in plaintext (not env-var substitutable)
4. **Deploy SSH key:** GitHub secret → workflow file → VPS access with full rsync write capability

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: `share_reader` BYPASSRLS Role Over-Privileged — Reads All Users' Data

**Vulnerability:** Excessive Database Privileges — A01 (Broken Access Control)
**Severity:** High
**Confidence:** Confirmed
**Attack Complexity:** Medium

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 38–52 (Task 5.1, Step 1)
- Related: `api/src/main/resources/application.yml` (secondary DataSource config)

**Risk & Exploit Path:**

The `share_reader` role has `BYPASSRLS` and `SELECT` on `shares`, `photos`, `albums`, `album_photos`, and `photo_metadata`. Because it bypasses RLS, any query executed through the `shareReaderDataSource` can read **all rows across all tenants** — not just the shared resource.

If any code path using `shareReaderDataSource` has a SQL injection vulnerability, a parameter tampering flaw, or even a logic bug in the share lookup query, the attacker gains read access to every user's photos, albums, and metadata across the entire system.

The plan states `ShareService.lookupShare()` uses this DataSource, but does not specify whether **only** `lookupShare()` uses it. If the `shareReaderDataSource` bean is injected into a broader repository or service, unintended code paths gain RLS-bypass capability.

**Evidence / Trace:**

```sql
-- V10__share_reader_role.sql
CREATE ROLE share_reader WITH LOGIN PASSWORD '${share_reader_password}' BYPASSRLS;  -- ← BYPASSRLS on 5 tables
GRANT SELECT ON shares, photos, albums, album_photos, photo_metadata TO share_reader;
```

The ShareService will use this DataSource:
```java
// ShareService.lookupShare() — uses shareReaderDataSource
// Any query on this connection bypasses ALL RLS policies
```

**Remediation:**
- **Primary fix:** Instead of `BYPASSRLS`, create `share_reader` as a regular role and add **targeted RLS policies** that allow it to read shares by token_hash regardless of user_id:
  ```sql
  CREATE ROLE share_reader WITH LOGIN PASSWORD '${share_reader_password}';
  -- Add policy: share_reader can read shares by token_hash (no user context needed)
  CREATE POLICY share_reader_lookup ON shares FOR SELECT TO share_reader USING (true);
  -- For photos/albums: only allow access to resources referenced by a valid share
  CREATE POLICY share_reader_photos ON photos FOR SELECT TO share_reader
      USING (id IN (SELECT resource_id FROM shares WHERE resource_type = 'photo'));
  ```
- **Architectural improvement:** If `BYPASSRLS` is retained for simplicity, the `shareReaderDataSource` bean must be **package-private** or otherwise scoped so that only a single `ShareLookupRepository` class can access it. Add an ArchUnit test to enforce this constraint.
- **Defense-in-depth:** The share lookup query should use a parameterized native query that selects only the specific columns needed (not `SELECT *`), and should validate the token format (hex string, 64 chars) before querying.

**References:**
- CWE-250: Execution with Unnecessary Privileges
- PostgreSQL RLS documentation: BYPASSRLS grants unrestricted read across all policies

---

### Finding #2: Share Token Enumeration via Timing Side-Channel

**Vulnerability:** Timing-Based Token Enumeration — A07 (Identification and Authentication Failures)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 106–113 (Task 5.1, Step 3)
- Related: `ShareRepository.findByTokenHash(String)` (existing entity)

**Risk & Exploit Path:**

The plan specifies SHA-256 hashing of share tokens before database lookup. The lookup flow is: receive plaintext token → SHA-256 hash → database query by hash. If a valid hash returns a row and an invalid hash returns nothing, the difference in response time (DB hit vs. miss) could theoretically leak information.

However, the practical risk is low because:
1. The token space is 256 bits (2^256 possible values) — brute force is computationally infeasible
2. The nginx rate limit (60r/m) constrains enumeration bandwidth
3. SHA-256 hashing adds consistent overhead regardless of DB result

This is noted as **Requires Verification** — confirm that the controller returns the same HTTP status and response time for invalid tokens vs. expired tokens vs. deleted-resource tokens.

**Evidence / Trace:**

```java
// ShareController — GET /share/{token}
// 1. Hash the token: SHA-256(token) → tokenHash
// 2. Query: SELECT * FROM shares WHERE token_hash = ?
// 3. If not found → 404
// 4. If found but expired → 404
// 5. If found but resource deleted → 404
```

**Remediation:**
- **Primary fix:** Return identical 404 responses for all failure cases (not found, expired, deleted resource) — the plan already specifies this (`expiredShareReturns404`, `shareToDeletedPhotoReturns404`). Confirm implementation uses a single code path for all failures.
- **Defense-in-depth:** The 60r/m rate limit is appropriate. Consider adding a small constant-time delay (e.g., 50ms) to all share lookup responses to flatten timing variations, though this is optional given the 256-bit token space.

**References:**
- CWE-208: Observable Timing Discrepancy

---

### Finding #3: Missing Authorization Check on `DELETE /shares/{id}` — Potential IDOR

**Vulnerability:** Insecure Direct Object Reference — A01 (Broken Access Control)
**Severity:** High
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 122 (Task 5.1, Step 4)

**Risk & Exploit Path:**

The plan specifies `DELETE /shares/{id}` as authenticated, but does not explicitly describe ownership verification. RLS should enforce this (the `shares` table has `user_id = current_setting('app.current_user_id')::uuid` policy), so a user cannot delete another user's shares through the normal `jpt_app` connection.

However, if the controller or service inadvertently uses the `shareReaderDataSource` (which has `BYPASSRLS`) for the delete operation, or if a developer adds write permissions to `share_reader` in the future, an authenticated user could delete any user's share by guessing/enumerating share UUIDs.

**Requires Verification:** Confirm that `DELETE /shares/{id}` uses the standard `jpt_app` DataSource with RLS active, not the `shareReaderDataSource`.

**Evidence / Trace:**

```
DELETE /shares/{id} — revoke share (authenticated)
```

The plan does not specify which DataSource is used for mutations. If only `lookupShare()` uses `shareReaderDataSource`, this is safe. But the plan should be explicit.

**Remediation:**
- **Primary fix:** Explicitly state in the plan that `POST /shares`, `DELETE /shares/{id}`, and `GET /shares` use the **primary DataSource** (with RLS). Only `GET /share/{token}` (unauthenticated lookup) uses `shareReaderDataSource`.
- **Defense-in-depth:** Add integration test: `deleteShare_byOtherUser_returns404()` to verify RLS prevents cross-tenant deletion.
- **Architectural improvement:** Name the secondary DataSource bean unambiguously (e.g., `shareTokenLookupDataSource`) to signal it is only for the single unauthenticated lookup path.

**References:**
- CWE-639: Authorization Bypass Through User-Controlled Key

---

### Finding #4: Alertmanager SMTP Credentials in Plaintext Config File

**Vulnerability:** Hardcoded/Plaintext Credentials — A02 (Cryptographic Failures)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 325–340 (Task 5.4, Step 4)

**Risk & Exploit Path:**

The `alertmanager.yml` configuration contains SMTP credentials in plaintext. The plan acknowledges that "Alertmanager does not support env var substitution in its config file, so this value must be set manually before deployment." This means:

1. SMTP credentials will be committed to the config file or manually edited on the VPS
2. If committed to git, credentials are in version history permanently
3. If the file is on the VPS at `/opt/jpt/alertmanager.yml` and is rsynced from the repo, it must be excluded like `.env`
4. Docker container can expose the file via `docker exec` or volume inspection

**Evidence / Trace:**

```yaml
# alertmanager.yml
global:
  smtp_smarthost: 'smtp.example.com:587'  # ← actual SMTP host goes here
  smtp_from: 'alerts@yourdomain.com'
  smtp_require_tls: true
# No auth_username/auth_password shown, but SMTP typically requires them
```

**Remediation:**
- **Primary fix:** Add `alertmanager.yml` to the rsync `--exclude` list in `deploy.yml` (alongside `.env` and `secrets/`). Manage it as a VPS-local file like `.env`.
- **Alternative:** Use Docker secrets to mount SMTP credentials and configure alertmanager to read from the secrets file path. Alertmanager supports `smtp_auth_password_file` since v0.22.0:
  ```yaml
  global:
    smtp_auth_password_file: /run/secrets/smtp_password
  ```
- **Defense-in-depth:** Ensure `alertmanager.yml` is in `.gitignore` if it will contain production credentials. Ship `alertmanager.yml.example` with placeholder values instead.

**References:**
- CWE-256: Plaintext Storage of a Password
- CWE-798: Use of Hard-coded Credentials

---

### Finding #5: Node-Exporter `pid: host` and Root Filesystem Mount Expand Container Escape Surface

**Vulnerability:** Excessive Container Privileges — A05 (Security Misconfiguration)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 356–367 (Task 5.4, Step 5)

**Risk & Exploit Path:**

The `node-exporter` service uses `pid: host` (shares the host PID namespace) and mounts `/proc`, `/sys`, and `/` (root filesystem) as read-only volumes. While these are required for accurate host metrics, they significantly expand the attack surface:

1. `pid: host` allows the container to see all host processes, including their environment variables (which may contain secrets)
2. `/proc` mount exposes `/proc/[pid]/environ` for every host process — readable within the container
3. Root filesystem mount at `/rootfs:ro` exposes the entire host filesystem for reading

If `node-exporter` has a vulnerability (RCE, path traversal), an attacker could read host secrets, process environments, and filesystem contents.

**Evidence / Trace:**

```yaml
node-exporter:
  image: prom/node-exporter:v1.8.1
  pid: host                          # ← sees all host processes
  volumes:
    - /proc:/host/proc:ro            # ← host process info including env vars
    - /sys:/host/sys:ro
    - /:/rootfs:ro                   # ← entire host filesystem readable
```

**Remediation:**
- **Primary fix:** This configuration is standard for node-exporter and functionally required. Accept the risk with these mitigations:
  - Ensure node-exporter is on `backend` network only (no internet access) — confirmed in plan
  - Add `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]` (consistent with other services)
  - Pin the image hash (SHA256 digest) rather than just version tag
- **Defense-in-depth:** Add `read_only: true` to node-exporter container. The binary doesn't need write access.
- **Architectural improvement:** Consider whether `/:/rootfs:ro` can be replaced with just `/var/lib/docker:/var/lib/docker:ro` if only disk usage metrics are needed, reducing exposure.

**References:**
- CWE-250: Execution with Unnecessary Privileges

---

### Finding #6: Deploy Workflow SSH Key Has Unrestricted VPS Access

**Vulnerability:** Excessive Deployment Privileges — A01 (Broken Access Control)
**Severity:** High
**Confidence:** Medium
**Attack Complexity:** Medium

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 553–576 (Task 5.5, Step 2)

**Risk & Exploit Path:**

The deploy workflow uses `DEPLOY_SSH_KEY` with unrestricted SSH access to `VPS_USER@VPS_HOST`. The plan mentions "with `command=` restriction on VPS authorized_keys" in a parenthetical (line 607), but this is a comment — not an enforced configuration step. If the `command=` restriction is not implemented:

1. A compromised GitHub Actions runner (or leaked secret) provides full SSH access to the VPS
2. The attacker can execute arbitrary commands as the deploy user
3. `rsync --delete` with `--exclude='.env'` protects `.env` but the SSH session itself has no restrictions

Even with `command=` restriction, the current deployment pattern requires multiple different SSH operations (`ssh` for tagging, `rsync` for transfer, `ssh` for build, `ssh` for healthcheck), making a single `command=` restriction difficult to implement.

**Evidence / Trace:**

```yaml
- name: Setup SSH key
  run: |
    echo "${{ secrets.DEPLOY_SSH_KEY }}" > ~/.ssh/deploy_key
    chmod 600 ~/.ssh/deploy_key
    ssh-keyscan -H ${{ secrets.VPS_HOST }} >> ~/.ssh/known_hosts
- name: Rsync source to VPS
  run: |
    rsync -avz --delete ... ./ ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}:/opt/jpt/
- name: Build images and deploy on VPS
  run: |
    ssh -i ~/.ssh/deploy_key ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} \
      "cd /opt/jpt && docker compose build api worker && docker compose up -d"
```

**Remediation:**
- **Primary fix:** Make the `command=` restriction a mandatory implementation step in the plan (not just a comment). Document the exact `authorized_keys` entry. Since multiple commands are needed, use a deploy script on the VPS:
  ```
  # VPS: /opt/jpt/deploy.sh
  command="/opt/jpt/deploy.sh",no-agent-forwarding,no-port-forwarding,no-pty ssh-rsa AAAA...
  ```
- **Defense-in-depth:** Create a dedicated `deploy` user on the VPS with minimal permissions (only `/opt/jpt/` write access, `docker compose` capability). Don't use a user with sudo or root access.
- **Architectural improvement:** Consider GitHub Actions OIDC → VPS authentication to eliminate long-lived SSH keys entirely.

**References:**
- CWE-269: Improper Privilege Management

---

### Finding #7: `rsync --delete` Could Remove Production Runtime Files

**Vulnerability:** Destructive Deployment Operation — A05 (Security Misconfiguration)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 567–571 (Task 5.5, Step 2)

**Risk & Exploit Path:**

`rsync -avz --delete` synchronizes the repo to `/opt/jpt/` and deletes any files on the VPS that aren't in the repo. The `--exclude` list protects `.env` and `secrets/`, but other runtime-generated files could be deleted:

1. `alertmanager.yml` (if managed on-VPS per Finding #4 remediation)
2. Docker volumes data directories (if collocated under `/opt/jpt/`)
3. Any manually created configuration overrides
4. `prometheus.yml` with production-specific SMTP host values
5. Let's Encrypt certificates if stored under the project directory

**Evidence / Trace:**

```bash
rsync -avz --delete \
  --exclude='.env' \
  --exclude='secrets/' \
  -e "ssh -i ~/.ssh/deploy_key" \
  ./ ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}:/opt/jpt/
```

**Remediation:**
- **Primary fix:** Expand the `--exclude` list to cover all VPS-local files:
  ```bash
  --exclude='.env'
  --exclude='secrets/'
  --exclude='alertmanager.yml'
  --exclude='certbot/'
  --exclude='data/'
  ```
- **Defense-in-depth:** Add `--dry-run` as a first step in the deploy workflow, log the deletions, and only proceed if no unexpected files would be removed. Or use `--backup --backup-dir=/opt/jpt-backup/` to preserve deleted files.
- **Alternative:** Use `rsync` without `--delete` and manage cleanup separately, or use a more targeted deployment that only syncs specific directories (source code, configs).

**References:**
- CWE-1188: Initialization with an Insecure Default

---

### Finding #8: CI Environment File `.env.ci` Contains Predictable Secrets

**Vulnerability:** Weak Test Credentials Leaking to Production — A07 (Identification and Authentication Failures)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 472–517 (Task 5.5, Step 1)

**Risk & Exploit Path:**

The `.env.ci` file contains predictable test credentials (`ci_test_password`, `ci_test_jwt_secret_that_is_at_least_32_characters_long`). These are appropriate for CI (ephemeral, isolated), but risks include:

1. If `.env.ci` is accidentally used in production (e.g., `cp .env.ci .env` on the VPS)
2. JWT secret is predictable — anyone can forge valid JWTs
3. All database passwords are identical (`ci_test_password`)

The plan correctly documents these as "not real secrets" and the CI environment as "ephemeral, isolated."

**Evidence / Trace:**

```
JWT_SECRET=ci_test_jwt_secret_that_is_at_least_32_characters_long
DB_PASS=ci_test_password
REDIS_PASSWORD=ci_test_redis
```

**Remediation:**
- **Primary fix:** Add a prominent comment at the top of `.env.ci`: `# CI-ONLY — DO NOT use in production. All values are intentionally weak test defaults.`
- **Defense-in-depth:** Add a startup check in the application that rejects known-weak JWT secrets in production profile:
  ```java
  if (profile.equals("prod") && jwtSecret.startsWith("ci_test")) {
      throw new IllegalStateException("CI test JWT secret detected in production!");
  }
  ```
- **Already mitigated:** The deploy workflow uses `rsync --exclude='.env'`, so `.env.ci` won't overwrite production `.env`.

**References:**
- CWE-1393: Use of Default Password

---

### Finding #9: MinIO Proxy Path Guard Not Specified for `/minio/` Location Block

**Vulnerability:** Missing Access Control on Object Storage Proxy — A01 (Broken Access Control)
**Severity:** High
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 229 (Task 5.3, Step 2, item 5)

**Risk & Exploit Path:**

The plan specifies adding a `/minio/` location block with "regex path guard and `Authorization ""` header stripping per design Section 7" but does not include the actual nginx configuration. The design document (SA#2) specifies a regex guard: `/photos/{user_id}/(originals|thumbnails)/{photo_id}`.

If the regex path guard is not correctly implemented:
1. An attacker could access any MinIO object by crafting a path that bypasses the guard
2. Cross-user photo access becomes possible (reading other users' originals/thumbnails)
3. The `Authorization ""` stripping prevents credential leakage but doesn't prevent unauthorized path access

**Evidence / Trace:**

```nginx
# Plan only says:
# 5. `/minio/` — MinIO proxy with regex path guard and `Authorization ""` header stripping per design Section 7
# No actual nginx config block provided (unlike items 1-4 which have explicit configs)
```

**Remediation:**
- **Primary fix:** Include the complete `/minio/` location block in the plan with the regex path guard:
  ```nginx
  location ~ ^/minio/photos/[0-9a-f-]{36}/(originals|thumbnails)/[0-9a-f-]{36}\. {
      proxy_set_header Authorization "";
      proxy_pass http://minio:9000;
      # ...
  }
  ```
- **Defense-in-depth:** Ensure MinIO bucket policy is private (no anonymous access) as a fallback if the nginx guard is bypassed. The design doc confirms this is already specified.

**References:**
- CWE-284: Improper Access Control

---

### Finding #10: CSP `style-src 'unsafe-inline'` Weakens XSS Protection

**Vulnerability:** Weakened Content Security Policy — A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 232–235 (Task 5.3, Step 3)

**Risk & Exploit Path:**

Adding `'unsafe-inline'` to `style-src` allows inline `<style>` tags and `style=` attributes, which weakens the CSP's ability to prevent style-based XSS attacks (CSS injection, data exfiltration via CSS selectors). This is a known trade-off required by Tailwind CSS and shadcn/ui.

The risk is low because:
1. `script-src` remains restricted (no `'unsafe-inline'` for scripts)
2. CSS-only injection attacks have limited impact compared to script injection
3. This is a documented, intentional trade-off (SA#12/CR#7)

**Evidence / Trace:**

```nginx
style-src 'self' 'unsafe-inline';  # ← Required by Tailwind/shadcn
```

**Remediation:**
- **Primary fix:** Accept this as a known trade-off. Document it in the nginx config with a comment explaining why it's necessary.
- **Future improvement:** When Tailwind supports CSP nonces (planned feature), migrate to `style-src 'self' 'nonce-{random}'` to eliminate `unsafe-inline`.

**References:**
- CWE-79: Cross-site Scripting (style injection variant)

---

### Finding #11: Share Frontend Renders User-Controlled Metadata Without Explicit Sanitization

**Vulnerability:** Potential Stored XSS via Photo Metadata — A03 (Injection) / A07 overlap
**Severity:** Medium
**Confidence:** Low
**Attack Complexity:** Medium

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 170–180 (Task 5.2, Step 4)

**Risk & Exploit Path:**

The SharePage renders photo metadata (EXIF/IPTC/XMP) on a public, unauthenticated page. Photo metadata fields (title, description, keywords, camera model, etc.) originate from uploaded files and may contain attacker-controlled content.

If a user uploads a JPEG with malicious EXIF fields (e.g., `ImageDescription: <script>alert(1)</script>`), and the SharePage renders these fields without proper encoding, the public share page becomes a stored XSS vector. The public page has no authentication — any visitor is affected.

**Requires Verification:** React's JSX auto-escapes string values in `{}` expressions, which mitigates this if metadata is rendered as text content. However, if `dangerouslySetInnerHTML` is used for rich formatting, or if metadata values are placed in HTML attributes without encoding, XSS is possible.

**Evidence / Trace:**

```tsx
// SharePage.tsx
if (share.resourceType === 'photo') {
  return <SharedPhotoView photo={photo} includeGps={share.includeGps} />;
  // photo.metadata contains user-uploaded EXIF data
  // If rendered as: <p>{photo.metadata.description}</p> → safe (React auto-escapes)
  // If rendered as: <div dangerouslySetInnerHTML={{__html: photo.metadata.description}} /> → XSS
}
```

**Remediation:**
- **Primary fix:** Ensure all metadata rendering uses React's default JSX escaping (plain `{value}` expressions). Explicitly prohibit `dangerouslySetInnerHTML` in share-related components. Add a comment: `// SECURITY: Never use dangerouslySetInnerHTML for user-uploaded metadata`.
- **Defense-in-depth:** Server-side sanitize metadata fields during photo processing (strip HTML tags from EXIF text fields in the worker).
- **Already mitigated (partially):** CSP blocks inline scripts (`script-src 'self'`), so even if XSS payload is injected, execution is blocked by CSP in compliant browsers.

**References:**
- CWE-79: Improper Neutralization of Input During Web Page Generation

---

### Finding #12: Deploy Workflow Rollback Re-Tags Images Without Verification

**Vulnerability:** Unreliable Rollback Mechanism — A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-5.md`, Lines: 596–603 (Task 5.5, Step 2)

**Risk & Exploit Path:**

The rollback mechanism tags current images as `:previous` before deployment and restores them on failure. However:

1. If the deploy job fails during `docker compose build` (before `docker compose up`), the `:previous` tag already points to the current (working) images — rollback works correctly.
2. If two deploys happen in succession, the second deploy overwrites `:previous` with the first deploy's images. If the second deploy fails, rollback restores the first deploy (which may also be broken).
3. There is no verification that `:previous` images are healthy before restoring them.
4. `2>/dev/null || true` silently ignores tagging failures — if no `:previous` tag exists (first deploy), rollback tries to restore a non-existent image.

**Evidence / Trace:**

```yaml
- name: Tag current images for rollback
  run: |
    ssh ... "docker tag jpt-api:latest jpt-api:previous 2>/dev/null || true && \
             docker tag jpt-worker:latest jpt-worker:previous 2>/dev/null || true"
# ...
- name: Rollback on failure
  if: failure()
  run: |
    ssh ... "cd /opt/jpt && \
             docker compose stop api worker && \
             docker tag jpt-api:previous jpt-api:latest && \     # ← may not exist
             docker tag jpt-worker:previous jpt-worker:latest && \
             docker compose up -d api worker"
```

**Remediation:**
- **Primary fix:** Check that `:previous` images exist before attempting rollback:
  ```bash
  docker image inspect jpt-api:previous >/dev/null 2>&1 && \
    docker tag jpt-api:previous jpt-api:latest && \
    docker compose up -d api worker
  ```
- **Defense-in-depth:** Keep multiple rollback tags (`:previous-1`, `:previous-2`) for deeper rollback history. Or use Docker image digests stored in a file for reliable reference.

**References:**
- CWE-754: Improper Check for Unusual or Exceptional Conditions

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack: Share Token + BYPASSRLS → Full Data Exfiltration

If an attacker discovers a SQL injection in the share lookup path (Finding #1), the `BYPASSRLS` role provides unrestricted read access to all tenant data. Combined with the public (unauthenticated) endpoint, this creates an unauthenticated data exfiltration path without any rate-limit bypass needed (60 requests/minute is sufficient for structured extraction).

**Chain:** Unauthenticated `GET /share/{token}` → SQL injection in share lookup query → `share_reader` BYPASSRLS connection → full database read (all users' photos, metadata, keywords, albums).

**Mitigation:** Use parameterized queries (which Spring Data JPA does by default), restrict `share_reader` with targeted RLS policies instead of BYPASSRLS (Finding #1 remediation), and limit the columns returned by share lookup queries.

### Implicit Trust: Worker → Redis → API

The monitoring stack (Prometheus) scrapes both API and worker metrics. If the Prometheus instance is compromised (network-adjacent attack), it can see application internals from both services. This is an accepted risk for monitoring systems but should be noted.

### Defense-in-Depth Gap: Monitoring Stack Network Exposure

All monitoring services (Prometheus, Grafana, Alertmanager, node-exporter, redis-exporter) are on the `backend` network. While this network is marked `internal: true` (no direct internet access), any container on the backend network can access all monitoring services. A compromised API or worker container could access Prometheus data, Grafana, or Alertmanager configuration.

### Deployment Context: CI nginx.ci.conf Divergence

The plan creates `nginx.ci.conf` as a stripped-down copy of `nginx.prod.conf`. These files will drift over time — security header additions to `nginx.prod.conf` may not be reflected in `nginx.ci.conf`, meaning E2E tests won't validate new security controls. Consider generating `nginx.ci.conf` from `nginx.prod.conf` via a template/sed script to keep them synchronized.

---

## 1. Executive Summary

Phase 5 introduces a significant new attack surface through the **public share endpoint** (`GET /share/{token}`), which is the first unauthenticated data-access path in the application. The plan's approach of using a dedicated `share_reader` PostgreSQL role with `BYPASSRLS` is functional but over-privileged — a single vulnerability in the share lookup path could expose all tenant data across the system.

The **CI/CD deployment pipeline** introduces a second area of concern. The SSH-based deployment has broad VPS access, `rsync --delete` risks removing production files, and the rollback mechanism lacks robustness. The `alertmanager.yml` SMTP credential handling needs explicit guidance to prevent plaintext secrets in version control.

The plan demonstrates strong security awareness overall — 256-bit token generation, SHA-256 hash storage, GPS stripping, rate limiting on share endpoints, container hardening, and network isolation are all correctly designed. The five critical reviews have resolved many issues that would otherwise appear in this audit. The remaining findings are primarily **privilege scoping** and **deployment hardening** concerns rather than fundamental architectural flaws.

**Recommendation:** Address the High-severity findings (F1, F3, F6, F9) before implementation. The remaining Medium and Low findings can be addressed during or after implementation.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | `share_reader` BYPASSRLS Over-Privileged | A01 | High | Confirmed | 1 | FIX |
| 2 | Share Token Timing Side-Channel | A07 | Medium | Medium | 1 | VERIFY |
| 3 | Missing Ownership Check on DELETE /shares/{id} | A01 | High | Medium | 1 | VERIFY |
| 4 | Alertmanager SMTP Credentials in Plaintext | A02 | Medium | Confirmed | 1 | FIX |
| 5 | Node-Exporter Excessive Container Privileges | A05 | Medium | Confirmed | 1 | ACCEPT |
| 6 | Deploy SSH Key Unrestricted VPS Access | A01 | High | Medium | 1 | FIX |
| 7 | rsync --delete Risks Production Files | A05 | Medium | High | 1 | FIX |
| 8 | CI .env.ci Predictable Secrets | A07 | Low | High | 1 | MITIGATE |
| 9 | MinIO Proxy Path Guard Not Specified | A01 | High | Medium | 1 | FIX |
| 10 | CSP style-src unsafe-inline | A05 | Low | Confirmed | 1 | ACCEPT |
| 11 | Share Page Metadata XSS Risk | A03 | Medium | Low | 1 | VERIFY |
| 12 | Deploy Rollback Without Verification | A05 | Low | High | 1 | MITIGATE |

---

## 3. Security Quality Score (SQS)

**Calculation:**

| Finding Severity | Count | Deduction |
|---|---|---|
| Critical | 0 | 0 |
| High | 4 (F1, F3, F6, F9) | −80 |
| Medium | 4 (F2, F4, F5, F7) | −32 |
| Low | 3 (F8, F10, F12) + 1 (F11 at Low-Medium) | −6 |

**Raw Score:** 100 − 80 − 32 − 6 = **-18** (floored to 0)

However, adjusting for confidence levels:
- F3 (Medium confidence, RLS likely protects): effective deduction −10
- F6 (Medium confidence, `command=` noted in plan): effective deduction −10
- F9 (Medium confidence, design doc specifies guard): effective deduction −10

**Adjusted Score:** 100 − 50 − 32 − 6 = **12**/100

This score reflects the **plan-level** analysis. Many of these findings may be resolved by correct implementation following existing patterns. However, the plan itself should explicitly address these concerns.

**Re-scored with Requires Verification items excluded (F2, F3, F9, F11):**
100 − 20 (F1) − 20 (F6) − 8 (F4) − 8 (F5) − 8 (F7) − 2 (F8) − 2 (F10) − 2 (F12) = **30**/100

**Final SQS:** 30/100 (plan-level, pre-implementation)
**Hard gates triggered:** No (no Critical findings, no hardcoded production secrets confirmed)
**Posture:** Unacceptable — remediate High findings in plan before implementation

---

## 4. Positive Security Observations

1. **256-bit SecureRandom tokens with SHA-256 hash storage** — industry-best-practice for share tokens. Plaintext returned once, never stored. Token space (2^256) makes enumeration infeasible.

2. **GPS stripping by default** — privacy-protective design. Location data only included when explicitly opted in via `includeGps` flag. Good GDPR/privacy posture.

3. **Thorough rate limiting** — separate nginx rate-limit zones for login (10r/m), register (5r/m), and share (60r/m) demonstrate defense-in-depth. Application-level Bucket4j limits provide a second layer.

4. **Container hardening consistency** — the plan follows the established pattern of `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, `mem_limit`, and `read_only` for new monitoring services (redis-exporter).

5. **Deploy gated on CI success** — `workflow_run` trigger with `conclusion == 'success'` check ensures Trivy vulnerability scanning passes before deployment. This prevents deploying code with known HIGH/CRITICAL CVEs.

---

## 5. Prioritized Remediation Roadmap

### Priority 1: Scope `share_reader` Role (Finding #1)
- **Why:** BYPASSRLS on 5 tables creates tenant-isolation bypass risk on the only unauthenticated data path
- **Effort:** Moderate — requires RLS policy design for the `share_reader` role, or strict bean scoping with ArchUnit test
- **Owner:** Backend

### Priority 2: Specify MinIO Proxy Path Guard (Finding #9)
- **Why:** Missing configuration for a critical access control — without the regex guard, cross-user object access is possible
- **Effort:** Quick Win — add the nginx location block already specified in the design doc
- **Owner:** DevOps

### Priority 3: Harden Deploy SSH Access (Finding #6)
- **Why:** Unrestricted SSH access from CI to VPS; `command=` restriction is documented but not enforced
- **Effort:** Quick Win — add `authorized_keys` entry with `command=` restriction, create deploy script
- **Owner:** DevOps

### Priority 4: Clarify DataSource Usage for Share Mutations (Finding #3)
- **Why:** Ambiguity about which DataSource is used for `DELETE /shares/{id}` creates IDOR risk if wrong DataSource is chosen
- **Effort:** Quick Win — add explicit note to plan; add integration test
- **Owner:** Backend

### Priority 5: Protect Alertmanager Credentials (Finding #4) + Expand rsync Excludes (Finding #7)
- **Why:** SMTP credentials in version control; production files at risk from `rsync --delete`
- **Effort:** Quick Win — add excludes, use `smtp_auth_password_file`, add `.gitignore` entry
- **Owner:** DevOps
