# Critical Design Review v4: JPhotoTagger SaaS Conversion

**Reviewed:** 2026-02-24
**Original Plan:** `2026-02-24-saas-conversion-design.md` (v3.0)
**Prior Reviews:** v1 (`-critical-review-1.md`), v2 (`-critical-review-2.md`), v3 (`-critical-review-3.md`)
**Reviewer:** Senior Principal Software Architect

---

## Context

Reviews v1–v3 drove substantial, well-considered revisions. The v3.0 design resolves all prior critical issues: soft-delete with quota-correct deletion, Redis AOF persistence + `processing_status` recovery column, worker database least privilege, 256-bit share token with SHA-256 storage, polling decision, PostgreSQL backup container, HTTPS redirect + security headers, Grafana/Prometheus internal-network restriction, Spring multipart limits, HikariCP connection budget, B2 90-day lifecycle, and worker capability hardening.

This review focuses exclusively on **new gaps in the v3.0 design**.

---

## 1. Overall Assessment

The v3.0 design is production-quality for its target scale. Security posture is meaningfully strong: RLS, least-privilege DB credentials, hardened worker container, secure share tokens, CSRF, two-layer rate limiting. The three-reviews-deep iteration shows in the level of detail.

Three new issues carry real reliability or security risk. The highest-severity is a **Nginx configuration bug** that silently disables all rate limiting in the published config. Two additional issues — an unimplemented `secrets:` definition that prevents the stack from starting, and a feature commitment (password-protected shares) with zero design — need resolution before their respective phases.

---

## 2. Critical Issues

### 2.1 `limit_req_zone` Directives Are in the Wrong Nginx Context — Rate Limiting Is Silently Disabled

**Description:** The Nginx configuration places `limit_req_zone` directives inside the `server {}` block:

```nginx
server {
    listen 443 ssl;
    ...
    limit_req_zone $binary_remote_addr zone=login:10m    rate=10r/m;
    limit_req_zone $binary_remote_addr zone=register:10m rate=5r/m;
    limit_req_zone $binary_remote_addr zone=share:10m    rate=60r/m;
    ...
    location /api/auth/login { limit_req zone=login burst=5 nodelay; ... }
```

`limit_req_zone` is an **`http`-context directive** in Nginx. It is not valid inside a `server {}` block. Nginx will either reject the config at startup (making the entire service fail to start) or, on some versions, silently ignore the out-of-context directives while accepting `limit_req` references to undefined zones — causing `limit_req` to fail open with a "zone not found" warning and no rate limiting applied.

**Why it matters:** This disables brute-force protection on `/api/auth/login`, `/api/auth/register`, and crucially `/share/` (token enumeration protection). The rate limiting design is correct; only the placement is wrong. This is a one-line structural fix but it's a defect-class issue because the intended security control does not exist as written.

**Suggestion:** Move all three `limit_req_zone` directives to the `http {}` block (above or outside all `server {}` blocks). The `limit_req` directives in each `location {}` block stay where they are. In practice, the full `nginx.conf` wraps `server {}` blocks in an `http {}` block — the zones must live there:

```nginx
http {
    limit_req_zone $binary_remote_addr zone=login:10m    rate=10r/m;
    limit_req_zone $binary_remote_addr zone=register:10m rate=5r/m;
    limit_req_zone $binary_remote_addr zone=share:10m    rate=60r/m;

    server { ... }
}
```

---

### 2.2 `pgbackup` Container Has Two Defects That Prevent Reliable Backups

**Description:** The `pgbackup` service has two independent reliability defects:

**Defect A — Silent restic install failure:**
```yaml
entrypoint: >
  /bin/sh -c "
    apt-get install -y restic 2>/dev/null;
    while true; do
      PGPASSWORD=$$DB_PASS pg_dump -h postgres ... |
      restic backup --stdin ...
```
`apt-get install -y restic 2>/dev/null` suppresses all errors. If `restic` is not in the `postgres:16` Debian package repo (it is, as `restic`, but only from Debian Bullseye/Bookworm — `postgres:16` is Debian Bookworm, so it's present), or if the network is unreachable at startup, the install fails silently. The shell continues to the `while true` loop, runs `pg_dump | restic backup` — and `restic` is simply not found. The pipe silently discards all `pg_dump` output. The container runs indefinitely, appearing healthy, with no backups ever written.

**Defect B — Missing `secrets:` top-level definition:**
```yaml
  pgbackup:
    ...
    secrets: [restic_pass]
```
There is no top-level `secrets:` section in the Compose file. Docker Compose will fail to start (`secret "restic_pass" is not defined`) — every `docker compose up` aborts before any service starts. This is a startup-blocking defect.

**Why it matters:** The PostgreSQL backup is the critical missing piece that was corrected in v3 — the design explicitly notes that database loss without metadata is catastrophic. Both defects return the system to "no PostgreSQL backup" in practice.

**Suggestion:**

For Defect A: Either pre-install restic in a custom image (`FROM postgres:16` + `RUN apt-get install -y restic`) to avoid runtime installation, or at minimum remove `2>/dev/null` and add error checking:
```sh
apt-get install -y restic || { echo "FATAL: restic install failed"; exit 1; }
```

For Defect B: Add a top-level `secrets:` section to the Compose file:
```yaml
secrets:
  restic_pass:
    file: ./secrets/restic_pass.txt   # or use environment-based secret
```
Document how `restic_pass.txt` is provisioned (generated once, stored outside the repository, placed on the VPS before first `docker compose up`).

---

### 2.3 Password-Protected Shares Are Mentioned but Entirely Undesigned — Feature Commitment with Zero Implementation Path

**Description:** Section 6 (Sharing) states: "Share links can be time-limited and **optionally password-protected**." The `shares` table schema has no `password_hash` column. There is no API design, no UI design, no hashing strategy, and no rate-limiting consideration for password verification on the public share endpoint.

This is not a "future consideration" marked as such — it is stated as a current feature of the sharing system with no qualifier. The schema, API, and UI sections contain no trace of it.

**Why it matters:** A developer reading the spec will either implement it ad hoc (likely insecurely — plaintext password comparison, no brute-force protection) or raise it as a blocker at Phase 5. If implemented ad hoc, password-protected share links with no rate limiting become an enumeration target where an attacker can brute-force the share password. The link is already token-protected (256-bit), so password protection is security theater unless properly designed; if implemented poorly, it adds a new attack surface.

**Suggestion:** Make an explicit decision:
- **Option A (recommended):** Remove "optionally password-protected" from Section 6 until designed. The 256-bit token provides sufficient access control for "private" links. Password protection can be added as a named future feature.
- **Option B:** Design it now — add `password_hash VARCHAR NULL` to `shares` schema; use bcrypt on the share password (same as `users.password_hash`); add Nginx rate limiting on the password verification endpoint (same zone as `/share/`, or a tighter zone); share lookup checks `password_hash IS NULL OR bcrypt_verify(submitted_password, password_hash)`. Add a test case: brute-force of share password is blocked after N attempts.

---

### 2.4 `album_photos` Cross-Tenant Constraint Remains Application-Layer Only

**Description:** v3 review (minor issue 6) flagged that `album_photos (album_id, photo_id)` has no `user_id` and no RLS policy. The v3.0 design acknowledges this: "When adding a photo to an album, the service layer verifies `album.userId == photo.userId` before inserting into `album_photos` — cross-tenant junction records are prevented at the application layer." The v3 suggestion was to "consider a database trigger or a CHECK via a subquery-based constraint."

No database-level protection was added. RLS is enabled on `albums` and `photos`, but `album_photos` has no RLS policy — it is listed among the RLS-protected tables in Section 3 ("RLS is enabled on all tenant tables... `album_photos`") but no `user_id` column exists on the table and no policy body is shown. An RLS policy on `album_photos` without a `user_id` column cannot enforce ownership.

**Why it matters:** If a service-layer ownership check is missed (common in test paths, admin endpoints, or bulk operations), User A can add User B's photo to User A's album. The `album_photos` row is writable without restriction. This creates a cross-tenant reference that bypasses the photo's RLS policy — User A's queries against their album would return User B's photo, even though User B's `photos` row has `user_id = B` and the RLS policy on `photos` should block User A. The junction table acts as an RLS bypass.

**Suggestion:** Either enforce this at the database layer or clarify the RLS claim. Options:
- **Trigger approach:** A `BEFORE INSERT` trigger on `album_photos` that checks `(SELECT user_id FROM albums WHERE id = NEW.album_id) = (SELECT user_id FROM photos WHERE id = NEW.photo_id)` and raises an exception if they differ.
- **Correct the RLS claim:** If `album_photos` cannot have a meaningful RLS policy (no `user_id` column), remove it from the "RLS is enabled on all tenant tables" list and document that it is protected by application-layer checks only — ownership verification at the service layer is the sole guard.

---

## 3. Alternative Architectural Challenge

**Alternative: Replace the Three-Container Storage Layer (PostgreSQL + MinIO + Redis) with a Two-Service Model Using PostgreSQL for Everything**

Instead of three stateful services, run PostgreSQL + a lightweight object store (or use PostgreSQL's Large Object facility / pgBLOB approach) and remove Redis entirely by using PostgreSQL `SKIP LOCKED` for job queuing and Argon2-based sessions in the database.

The full substitution:
- **Redis → PostgreSQL `SKIP LOCKED` + server-side sessions table** (session rows with expiry, rate limit counters via advisory locks or a `rate_buckets` table)
- **MinIO → PostgreSQL Large Objects or an external-only S3 bucket** (not self-hosted — just use Backblaze B2 directly from the API for a VPS-single-tenant scenario)

**Pro:** Reduces stateful service count from 5 (PostgreSQL, MinIO, Redis, backup, pgbackup) to 2 (PostgreSQL, Certbot). All state is in one backup target. Eliminates Redis AOF tuning, Redis Streams consumer group complexity, and the MinIO-to-B2 mirror sidecar. ACID consistency between metadata and job state is trivial. At "a few thousand users," PostgreSQL `SKIP LOCKED` handles job throughput with ease. One VPS process to back up, one restore procedure to document.

**Con:** PostgreSQL Large Objects have poor ecosystem tooling and complicate schema migrations. Backblaze B2 direct access from the API breaks the Nginx proxy architecture (pre-signed URLs work differently). Most significantly: at growth, splitting storage concerns is much harder from a monolithic PostgreSQL model than from the already-separated MinIO architecture. The current three-service design is the correct foundation for the hyperscaler migration path described in Section 7.

---

## 4. Minor Issues & Improvements

1. **`restic forget` is documented but never triggered.** The backup section states the retention policy as `restic forget --keep-daily 30 --keep-weekly 12`, but the `pgbackup` container only runs `restic backup`. Without periodic `restic forget --prune`, the restic repository grows indefinitely. Add a weekly `restic forget` invocation to the container's loop (e.g., after every 7th daily backup, or via a separate `@Scheduled` Spring task that SSH-tunnels or via a second loop with `sleep 604800`).

2. **Worker Prometheus/Micrometer instrumentation is unspecified.** The API has Spring Boot Actuator + Micrometer. The worker processes jobs, tracks retries, and transitions `processing_status` — the Redis Streams pending-entry count alert depends on the worker consuming correctly. If the worker has no Micrometer endpoint, job processing latency, failure rates, and libvips/libraw invocation times are invisible. The four-alert baseline covers the queue depth but not worker-side processing failures. Add a `spring-boot-starter-actuator` + Micrometer dependency to the worker and expose metrics on a non-public port for Prometheus to scrape.

3. **CI/CD deployment mechanism is ambiguous for containerized services.** Steps 8–9 rsync a JAR to the VPS and run `docker compose restart api` / `docker compose restart worker`. The Compose services use `build: ./api` — rebuilding from source at the VPS level requires `docker compose build api` followed by `docker compose up -d api`, not just `restart`. If the deployment intends to rsync a pre-built JAR into a volume that the container reads at startup (rather than baking the JAR into the image), the Dockerfile(s) and volume mapping are not shown. Clarify whether deployment is: (a) rsync JAR + docker compose restart (JAR as volume mount), or (b) rsync full source + docker compose build + up --no-deps. Both work; neither is documented.

4. **No account deletion (GDPR right to erasure).** The settings page lists "account, storage usage, linked OAuth accounts" but no account deletion option. For professional photographers serving EU clients, the right to erasure is a legal requirement. Account deletion requires cascading removal of all photos (triggering the soft-delete / permanent purge flow), all keywords, albums, shares, sessions, and the `users` row. The absence of this feature is a legal liability if any EU users are anticipated. Consider adding a "Delete my account" action to Section 6 (Sharing & Polish, Phase 5) or flagging it as a known out-of-scope limitation.

5. **Email verification token expiry is unspecified.** Section 4 mentions "email verification (link sent via SMTP)" and "password reset via email token." Neither specifies token expiry, storage (Redis vs. PostgreSQL), or handling of unverified accounts (can they log in? are they auto-purged after N days?). For password reset tokens in particular, an unspecified or long expiry is a security gap (leaked reset tokens in email logs remain valid indefinitely). Recommend: verification tokens expire in 24 hours; password reset tokens expire in 1 hour; both stored in a `email_tokens (user_id, token_hash, purpose, expires_at)` table or as short-lived Redis keys.

6. **MinIO admin console is enabled on port 9001 with no access control documented.** The MinIO service starts with `--console-address ":9001"`. No external port is mapped, so it is inaccessible from the internet. However, it is accessible from any other container on the same Docker network — including the worker, backup, and pgbackup containers. If those containers were compromised, the console at `http://minio:9001` is accessible with the MinIO admin credentials. Consider disabling the console entirely in production (`--console-address ""` or removing the flag) since all MinIO administration is done via `mc` in the backup sidecar.

7. **CSP `style-src 'unsafe-inline'` is necessary but undocumented.** The Content-Security-Policy includes `style-src 'self' 'unsafe-inline'`. For Tailwind CSS (utility classes applied dynamically) and shadcn/ui, inline styles may be unavoidable. However, this relaxation is not explained in the design — a future developer may attempt to tighten it, breaking the UI. Add a brief comment: `# 'unsafe-inline' required for Tailwind/shadcn dynamic class injection — scope to review if CSP nonces become feasible`.

---

## 5. Questions for Clarification

1. **Password-protected shares:** Keep or remove from scope? If kept, will it be designed before Phase 5 implementation begins?

2. **`album_photos` RLS claim:** Should the "RLS enabled on all tenant tables" list be corrected to exclude `album_photos`, or will a trigger/constraint be added for enforcement?

3. **pgbackup `secrets:` definition:** How is the `restic_pass` secret provisioned on the VPS? (File, environment variable, Docker secrets manager?) This determines the correct Compose `secrets:` top-level definition.

4. **Account deletion scope:** Is GDPR right to erasure in scope for any phase, or is this a known out-of-scope limitation to be documented?

5. **Worker Actuator/Micrometer:** Is the worker intended to export metrics to Prometheus? If so, which port does it expose, and does the `prometheus.yml` scrape it?

---

## 6. Final Recommendation

**Approve with targeted fixes — two startup-blocking defects must be resolved before the stack is first deployed; one configuration security bug must be fixed before the application is exposed to the internet.**

The architecture is mature and well-defended. Three critical issues need resolution:

1. **Fix `limit_req_zone` placement in Nginx** (2.1) — zones must be in `http {}` context. The rate limiting design is correct; this is a one-structural-fix to make it actually work.

2. **Fix `pgbackup` container defects** (2.2) — add `secrets:` top-level definition (startup blocker) and remove silent error suppression on restic install (silent backup failure).

3. **Resolve password-protected shares ambiguity** (2.3) — either remove the feature claim or design it before Phase 5. As written, it is a feature commitment with no design, no schema, and a potential new attack surface.

Before implementation of Phase 5 (Sharing & Polish):

4. **Clarify `album_photos` cross-tenant protection** (2.4) — either add a database trigger or correct the RLS documentation to accurately reflect what is and isn't enforced at the DB layer.

Minor issues 1 (restic forget), 2 (worker Micrometer), and 5 (email token expiry) should be addressed in their respective phases; they do not block Phase 1.
