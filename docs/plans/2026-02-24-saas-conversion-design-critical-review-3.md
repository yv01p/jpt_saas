# Critical Design Review v3: JPhotoTagger SaaS Conversion

**Reviewed:** 2026-02-24
**Original Plan:** `2026-02-24-saas-conversion-design.md` (v2.0)
**Prior Reviews:** v1 (`-critical-review-1.md`), v2 (`-critical-review-2.md`)
**Reviewer:** Senior Principal Software Architect

---

## Context

Reviews v1 and v2 drove substantial revisions. The v2.0 design resolves all prior critical issues: RLS with `SET LOCAL`, Redis Streams consumer groups with `XACK`/`XAUTOCLAIM`, `UNIQUE (user_id, content_hash)` database constraint, MinIO via Nginx proxy with pre-signed URL expiry defined, worker container separation, ImageMagick replaced by libraw + libvips, search vector denormalized across caption/title/description/filename, numeric EXIF cast corrected, JWT expiry documented (15 min), two-layer rate limiting (Nginx + Bucket4j), quota with `SELECT FOR UPDATE`, supervised backup sidecar (no `--watch`), B2 bucket versioning, and a four-alert monitoring baseline.

This review focuses exclusively on **new gaps in the v2.0 design**.

---

## 1. Overall Assessment

The v2.0 design is genuinely well-resolved and production-ready in its core decisions. The security posture is meaningfully improved over prior revisions. However, three operational gaps remain that could cause data loss or correctness failures in production, two security gaps need addressing before the sharing and media phases ship, and two significant implementation decisions are left unspecified.

The **first component expected to fail** under production conditions is no longer the image processing stack (well-contained in the worker with Tika validation + libvips + libraw). The next failure point is **Redis job queue data loss on VPS reboot**: a missing Redis AOF persistence directive means pending thumbnail/metadata jobs disappear silently on restart with no in-UI error and no monitoring alert (because gone jobs don't appear in the pending-entry count).

---

## 2. Critical Issues

### 2.1 Photo Deletion Is Entirely Absent — A Core Operation with Cascading Correctness Implications

**Description:** The design has no section, schema note, or phase covering what happens when a user deletes a photo, album, or keyword. The schema has multiple referencing tables (`photo_keywords`, `album_photos`, `shares`) and two resource-tracking counters (`users.used_bytes`). A deletion requires:
- Removing the PostgreSQL row (cascading to junction tables)
- Deleting both MinIO objects (`{user_id}/originals/{photo_id}.{ext}` and both thumbnails)
- Decrementing `users.used_bytes` by the deleted photo's `size_bytes`
- Invalidating any active `shares` rows referencing the photo

**Why it matters:** Omitting any step creates durable correctness defects:
- Orphaned MinIO objects inflate storage costs without being reflected in quota — silent, unbounded growth.
- Orphaned `used_bytes` slowly poisons quota accuracy; users are blocked from uploading even though actual MinIO usage is lower.
- Active share links to deleted photos either return 404 (confusing for recipients) or, if the storage key is reused, serve different content — a data retention violation.
- For professional photographers, accidental deletion recovery ("Trash" bin with a retention window) is a standard expectation. Designing deletion as permanent-by-default from the start forecloses this cleanly.

**Suggestion:** Add a deletion design section addressing:
- **Soft delete vs. hard delete:** A `deleted_at` timestamp on `photos` enables a Trash view and a configurable retention window before permanent deletion. MinIO objects are only deleted at permanent purge time. Strongly recommended for a professional photographer audience.
- **Cascade strategy:** `ON DELETE CASCADE` on `photo_keywords(photo_id)` and `album_photos(photo_id)`. Shares referencing deleted photos should be invalidated (either cascade or `deleted_at`-aware share lookup).
- **Async MinIO cleanup:** The deletion transaction records the storage keys to delete; a `delete-job` is enqueued to Redis Streams. This keeps the HTTP response fast and prevents blocking on MinIO network calls. A periodic orphan-reconciliation sweep (compare `photos.storage_key` set against MinIO object listing) catches any failures.
- **Quota decrement:** `UPDATE users SET used_bytes = used_bytes - ? WHERE id = ?` inside the same deletion transaction that removes the `photos` row.

---

### 2.2 Redis Job Queue Has No Persistence Guarantee — Silent Job Loss on VPS Reboot

**Description:** The Docker Compose configuration starts Redis with `redis-server --requirepass ${REDIS_PASSWORD}`. No `--appendonly yes` is specified. Redis's default persistence is RDB snapshots on interval (save every 900 seconds if 1 key changed, or not at all with `--save ""`). A VPS reboot between snapshots — routine during OS security updates — loses all pending Redis Streams entries since the last checkpoint.

**Why it matters:** A user uploads 200 RAW photos in a session. Two hours later the VPS reboots for a kernel patch. All 200 pending thumbnail-generation jobs are gone from the stream. Photos appear in the library with no thumbnails and no extracted metadata. There is no UI error, no retry mechanism, and critically: the four-alert monitoring baseline would **not** trigger because the jobs no longer exist — a zero pending-entry count reads as healthy. The failure mode is completely silent.

**Suggestion:** Choose one option and document it:
- **Option A (minimal, recommended):** Add `--appendonly yes --appendfsync everysec` to the Redis command in Docker Compose. Provides AOF persistence with at most 1 second of job loss on crash. Jobs active at crash time (not yet `XACK`'d) survive and are reclaimed by the existing `XAUTOCLAIM` sweep within 5 minutes of restart.
- **Option B (defense-in-depth):** Add a `processing_status` column to `photos` (`pending | processing | done | failed`). At worker startup, re-enqueue all rows where `processing_status IN ('pending', 'processing')`. PostgreSQL becomes the authoritative job source of truth; Redis Streams is a durable-enough dispatch layer. This survives any Redis failure, not just unclean shutdowns. The same column also enables the polling approach for upload-complete notifications (see 2.5).

Option A is a one-line fix. Option B is more architecturally robust. Both can coexist.

---

### 2.3 Worker Uses the Same Database Credentials as the API — Violates Least Privilege

**Description:** Both `api` and `worker` containers receive identical `DB_USER, DB_PASS` environment variables. The API legitimately needs broad access: `users`, `photos`, `keywords`, `albums`, `shares`, `saved_searches`, `sessions`. The worker only needs to write to `photo_metadata`, and update a subset of columns on `photos` (storage key, content hash, processing status). The worker has no legitimate need to read `users.password_hash`, modify `shares`, query `sessions`, or touch `saved_searches`.

**Why it matters:** The worker's attack surface is the highest in the system — it parses untrusted binary RAW files through multiple native processes (libraw, libvips, ExifTool Perl). If a crafted RAW file exploits a parser vulnerability in the worker, the attacker inherits full database access: all user credentials, all metadata, the ability to modify shares and impersonate users. The worker is the most likely exploitation entry point, yet it currently holds the most privileged database credential.

**Suggestion:** Create a separate `worker_db_user` PostgreSQL role with minimal grants:
```sql
CREATE ROLE worker_db_user WITH LOGIN PASSWORD '...';
GRANT SELECT ON photos TO worker_db_user;   -- read photo_id, storage_key
GRANT INSERT, UPDATE ON photo_metadata TO worker_db_user;
GRANT UPDATE (storage_key, content_hash, processing_status, size_bytes) ON photos TO worker_db_user;
-- NOT granted: users, shares, keywords, albums, saved_searches, sessions
```
The `worker` Docker Compose service uses `WORKER_DB_USER, WORKER_DB_PASS` instead of `DB_USER, DB_PASS`. Implement via a Flyway migration that creates the role and grants.

---

### 2.4 Share Token Security Gap — Entropy and Rate Limiting Unspecified

**Description:** The `shares` table has a `token` column but the design does not specify the token generation method (random? UUID? how many bits?), storage format (plaintext or hash?), or rate limiting on the unauthenticated `/share/:token` route.

**Why it matters:** The `/share/:token` route bypasses user authentication — it is publicly accessible by design. If tokens have low entropy (e.g., a short random string, a UUID v1 with predictable timestamp component, or a sequential integer) and there is no rate limiting, an attacker can enumerate tokens and access all shared photos across all users. For professional photographers sharing client galleries, this is a meaningful privacy breach. The absence of rate limiting on `/share/` is also a gap given that Nginx rate limiting is already configured for `/api/auth/login` and `/api/auth/register`.

**Suggestion:**
- **Token generation:** Use `SecureRandom` to generate 256 bits (32 bytes) encoded as URL-safe base64 (43 characters). This is equivalent in entropy to a session token and makes enumeration computationally infeasible.
- **Storage:** Store only a `SHA-256(token)` hash in the `shares` table. The plaintext token is returned once on creation. A database dump does not expose all share links. On share lookup, hash the incoming token and compare against stored hashes.
- **Rate limiting:** Add a Nginx `limit_req_zone` on the `/share/` path (same pattern as login — e.g., 60 requests per minute per IP).
- **Default token lifetime:** Define the default `expires_at` for a "permanent" share (e.g., NULL = never expires, or a configurable default of 365 days).

---

### 2.5 WebSocket Notification Is Mentioned but Entirely Unspecified — A Non-Decision Deferred to Implementation

**Description:** Section 5 states: "the UI polls or receives a WebSocket notification when processing completes." WebSocket is not in the Spring Boot dependency list, not in the architecture diagram, and has no design anywhere. Spring WebSocket (STOMP over SockJS or raw WebSocket) requires: a message broker (in-memory or Redis-backed), authenticated WebSocket handshake, CORS configuration for the upgrade, and Nginx proxy headers (`Upgrade`, `Connection`). If it's out of scope, "polls" is the answer — but `/api/photos/:id/status` doesn't exist as a described endpoint, and `processing_status` is not in the schema.

**Why it matters:** "Or receives a WebSocket notification" leaves an ambiguous implementation surface. If a developer interprets this as "implement WebSocket," they will spend significant time on a non-trivial feature that wasn't planned. If they choose polling, they'll discover the schema is missing `processing_status` and the polling endpoint doesn't exist. Either way, this non-decision becomes an implementation-time surprise.

**Suggestion:** Make an explicit decision:
- **Option A (recommended for MVP):** UI polls `/api/photos/{id}/status` every 3 seconds after upload until `processing_status` is `done` or `failed`. Add `processing_status VARCHAR(16) DEFAULT 'pending'` to the `photos` schema. This also resolves issue 2.2 Option B. No WebSocket dependency, no additional Nginx config, no broker.
- **Option B:** Add `spring-boot-starter-websocket`, specify broker type (in-memory sufficient at this scale), authenticate the WebSocket handshake using the existing JWT cookie, add `proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";` to the Nginx `/api/` block. More complex, marginal UX improvement over polling at "a few thousand users."

Choose explicitly in the design document.

---

### 2.6 PostgreSQL Backup Is Described in Prose but Not Implemented in Docker Compose

**Description:** Section 7 states: "PostgreSQL: Daily pg_dump to Backblaze B2 via restic." The MinIO backup sidecar exists and is fully specified in the Docker Compose YAML. There is no corresponding `pgbackup` service in the Compose spec for PostgreSQL.

**Why it matters:** At this scale, the PostgreSQL database is irreplaceable — it contains all user accounts, all metadata (EXIF, IPTC, XMP), keywords, albums, sharing links, and saved searches. If the VPS disk fails, MinIO photos survive (B2 mirror is running), but all metadata is gone. Restoring photos without keywords and albums defeats the purpose of a photo tagging application. A backup mentioned only in prose will not be deployed at launch — it will be added "eventually" after a near-miss.

**Suggestion:** Add a `pgbackup` service to Docker Compose alongside the `backup` sidecar:
```yaml
pgbackup:
  image: postgres:16   # same version as postgres service — includes pg_dump
  restart: unless-stopped
  entrypoint: >
    /bin/sh -c "
      apk add --no-cache restic 2>/dev/null || apt-get install -y restic;
      while true; do
        PGPASSWORD=$$DB_PASS pg_dump -h postgres -U $$DB_USER $$DB_NAME |
        restic backup --stdin --stdin-filename postgres.sql
          -r b2:jpt-db-backup --password-file /run/secrets/restic_pass;
        sleep 86400;
      done"
  environment:
    DB_USER, DB_PASS, DB_NAME
    B2_ACCOUNT_ID, B2_ACCOUNT_KEY
  depends_on:
    postgres: { condition: service_healthy }
```
(Exact implementation may vary — the point is that the backup must be a container in the Compose file, not a prose paragraph.)

---

## 3. Alternative Architectural Challenge

**Alternative: Replace Redis Streams Job Queue with PostgreSQL `SKIP LOCKED` Queue**

Instead of Redis Streams for job dispatch, use PostgreSQL's built-in advisory lock / `SKIP LOCKED` pattern for job claiming:

```sql
-- photo_jobs table or processing_status column on photos
SELECT id, photo_id FROM photos
WHERE processing_status = 'pending'
ORDER BY uploaded_at
LIMIT 1
FOR UPDATE SKIP LOCKED;

-- Worker claims job, processes, marks complete
UPDATE photos SET processing_status = 'done' WHERE id = ?;
```

Worker threads each claim one job atomically — `SKIP LOCKED` ensures two workers never claim the same row. No separate queue service is needed.

**Pro:** Eliminates Redis as a required dependency for job durability (issue 2.2 disappears entirely — PostgreSQL ACID guarantees job persistence through any failure mode). Job history is queryable in the relational database for auditing, debugging, and retry logic. Redis can be demoted to session cache + rate limiting only (or eliminated if cookie-based sessions are used instead of Redis). One fewer stateful service reduces operational burden on a single VPS. Failed workers roll back their transaction — the job automatically returns to `pending` status.

**Con:** Redis Streams is more scalable for high-throughput job queues. `SKIP LOCKED` adds PostgreSQL write load proportional to job poll frequency — negligible for "a few thousand users" but has a lower ceiling than Redis at scale. The `XAUTOCLAIM` timeout semantics for stuck-job recovery (job claimed but not completed) require an explicit `processing_timeout` column and a scheduled sweep, similar in complexity to the `XAUTOCLAIM` approach. Switching away from Redis Streams also removes the built-in consumer group fan-out if multiple worker replicas are ever needed.

---

## 4. Minor Issues & Improvements

1. **Redis AOF persistence missing.** Docker Compose runs Redis without `--appendonly yes`. Even if issue 2.2 is resolved at the application layer (Option B), `--appendonly yes --appendfsync everysec` should be added to the Redis command for defense-in-depth against rate-limit bucket loss and session cache loss on restart.

2. **HTTPS redirect and security headers absent from Nginx config.** The Nginx snippet shows only `listen 80` implied — no explicit HTTP→HTTPS redirect server block and no HSTS header (`Strict-Transport-Security: max-age=31536000; includeSubDomains`). SSL stripping remains possible without an explicit redirect. Security headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, a basic `Content-Security-Policy`) are also absent and important for a SaaS serving user-uploaded content.

3. **Grafana and Prometheus network access not restricted.** The Docker Compose adds both services but neither has an explicit port mapping shown or network-level restriction documented. If Docker's default bridge exposes them publicly (or if the firewall allows port 3000/9090), Prometheus leaks system internals and Grafana's admin interface is publicly accessible. Either restrict them to an internal Docker network or put them behind an Nginx auth proxy (`auth_basic`). Document the access method.

4. **Spring Boot multipart upload size limit not configured.** Spring Boot's default is `spring.servlet.multipart.max-file-size=1MB`. Professional RAW files (CR3, ARW, NEF) are 50–100 MB. Without `spring.servlet.multipart.max-file-size=200MB` and `spring.servlet.multipart.max-request-size=200MB` in `application.yml`, large RAW uploads fail with a confusing Spring 500/413 before reaching the upload controller — before the quota check, before MinIO. Nginx also defaults to `client_max_body_size 1m` and needs `client_max_body_size 250m` in the `/api/` location block.

5. **HikariCP pool size not specified — connection budget not documented.** HikariCP defaults to 10 connections per application instance. With `api` and `worker` each running a pool, that's 20 connections. PostgreSQL defaults to `max_connections = 100`. As more worker replicas are added, this ceiling is hit silently (requests queue, then time out). Add explicit `spring.datasource.hikari.maximum-pool-size` in both applications and document the connection budget: `(api_instances × api_pool) + (worker_instances × worker_pool) < postgres.max_connections`.

6. **`album_photos` has no user-ownership cross-check constraint.** `album_photos (album_id, photo_id)` has no `user_id` — the application must verify that `albums.user_id == photos.user_id` before inserting. A missed check allows User A to add User B's photo to User A's album, creating a cross-tenant data reference that bypasses RLS (the junction table itself has no `user_id`). Consider a database trigger or a CHECK via a subquery-based constraint that enforces same-owner on insert.

7. **B2 version retention policy unspecified.** B2 bucket versioning is enabled for deletion recovery — good. But no lifecycle rule is mentioned. Without a retention policy, all version history accumulates indefinitely at B2 storage rates. Define a retention window (e.g., 90-day version history) and add a B2 lifecycle rule to expire versions older than that threshold.

8. **Worker container lacks explicit Linux capability hardening.** The worker runs as non-root (good). No mention of `--cap-drop ALL`, `--security-opt no-new-privileges`, or a seccomp profile in the worker Compose service. libraw and ExifTool parse untrusted binary data; even with Tika pre-validation, a crafted JPEG passing content-type checks could exploit a parser. Capability dropping is a one-line Compose addition and should be standard for any container processing untrusted input.

---

## 5. Questions for Clarification

1. **Photo deletion and soft delete:** Is a Trash / recycle bin in scope for MVP, or is deletion immediate and permanent? This determines whether `deleted_at` needs to be in the Phase 1 schema or can be a later migration.

2. **WebSocket or polling:** Has a decision been made? If polling, will `processing_status` be added to the `photos` schema in Phase 1 (which also resolves Redis persistence concern via Option B in issue 2.2)?

3. **Worker database credentials:** Is the intent to use a restricted `worker_db_user` PostgreSQL role, or is a shared credential acceptable given the VPS-only threat model at this scale?

4. **Share token format:** Is there an existing decision on token entropy and storage (plaintext vs. hash)?

5. **PostgreSQL backup implementation:** Is there a specific tool preference for the `pgbackup` container — pg_dump piped to restic, pgbackup, Barman, or other?

---

## 6. Final Recommendation

**Approve with targeted revisions — three issues must be resolved before Phase 1 begins; three before their respective phases ship.**

The architecture is mature and secure for its target scale. The v2.0 design reflects genuine care in resolving prior feedback. Before implementation begins:

1. **Add a PostgreSQL backup container to Docker Compose** (2.6) — the MinIO backup sidecar exists; the DB backup must be equally concrete in the Compose spec before Phase 1 ships. Database loss without metadata is catastrophic.

2. **Make an explicit WebSocket vs. polling decision** (2.5) — add `processing_status` to the `photos` schema (Phase 1) if polling is chosen; specify WebSocket infrastructure if not. This column also enables Redis persistence defense-in-depth via Option B (2.2).

3. **Add `--appendonly yes --appendfsync everysec` to Redis command** (2.2 / Minor 1) — one-line fix that eliminates silent job loss on VPS reboot.

Before Phase 3 (Storage & Media):

4. **Design photo deletion behavior** (2.1) — soft vs. hard delete, async MinIO cleanup via Redis Streams, quota decrement atomicity, share invalidation. Must be in the schema from Phase 1 if soft delete is chosen.

Before Phase 5 (Sharing):

5. **Specify share token entropy and rate limiting** (2.4) — 256-bit `SecureRandom`, store hash, Nginx rate limit on `/share/` path.
6. **Create a restricted `worker_db_user`** (2.3) — before the worker is deployed in Phase 3.

Minor issue 4 (Spring multipart size limits + Nginx `client_max_body_size`) must be configured in the Phase 1 Spring Boot scaffold — the first RAW upload attempt will fail without it.
