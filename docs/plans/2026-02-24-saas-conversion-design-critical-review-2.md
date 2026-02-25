# Critical Design Review v2: JPhotoTagger SaaS Conversion

**Reviewed:** 2026-02-24
**Original Plan:** `2026-02-24-saas-conversion-design.md`
**Prior Review:** `2026-02-24-saas-conversion-design-critical-review-1.md`
**Reviewer:** Senior Principal Software Architect

---

## Context

The design was substantially revised after v1. All six critical issues and nearly all minor issues from v1 are now resolved: RLS added, async pipeline (Redis Streams) specified, Java upgrade sequenced before SaaS conversion, OAuth merge blocked and made explicit, quotas designed, MinIO backup corrected to `mc mirror`, CSRF fully specified, deduplication added, Redis password added, `depends_on` health checks added, and CI/CD deployment steps documented.

This review focuses on **new gaps in the revised design** only.

---

## 1. Overall Assessment

The revised design is significantly stronger. Core architecture is sound, security posture is good, and the async pipeline is well-specified. However, five issues were introduced or left unresolved in the revision:

1. **MinIO is unreachable from the browser** — the pre-signed URL pattern breaks if MinIO has no externally accessible endpoint.
2. **RLS session variable pollution** — `app.current_user_id` must be reset between connection pool checkouts, or one user can read another's data.
3. **Deduplication race condition** — no database unique constraint backs the content-hash check.
4. **Redis Streams consumer groups not mentioned** — job survival across restarts requires explicit consumer group usage.
5. **ImageMagick is an unmitigated RCE surface** — processing untrusted user files with an unconfined ImageMagick process is a known critical vulnerability class.

---

## 2. Critical Issues

### 2.1 MinIO Pre-Signed URLs Are Unreachable from the Browser

**Description:** The design states: "Spring Boot generates a time-limited [pre-signed] URL, the React client fetches the image directly from MinIO." However, the Docker Compose snippet shows no `ports` mapping for the MinIO service, and the architecture diagram shows MinIO inside the VPS boundary with no external exposure.

**Why it matters:** A pre-signed URL embeds the MinIO host and port. If MinIO is not reachable on a publicly accessible host:port, the browser fetch fails. Every photo in the photo grid — the primary UI surface — would return a network error. This is a complete failure of the photo delivery path.

**Suggestion:** Choose one of the following and document it explicitly:
- **Option A (recommended):** Route MinIO traffic through Nginx: `location /photos/ { proxy_pass http://minio:9000/; }`. Pre-signed URLs are generated against the public Nginx domain. No MinIO port exposure needed. Nginx can also enforce HTTPS and add security headers.
- **Option B:** Expose MinIO port 9000 in Docker Compose and configure MinIO's `MINIO_SERVER_URL` to the public domain. Ensure the port is firewalled to HTTPS-only and MinIO TLS is configured, or place it behind Nginx SNI passthrough.

The pre-signed URL expiry duration should also be defined explicitly (e.g., 15 minutes for thumbnails, 1 hour for originals). Too short breaks long browsing sessions; too long means a leaked URL grants prolonged access.

---

### 2.2 RLS Session Variable Pollution via Connection Pool

**Description:** The RLS policy uses `current_setting('app.current_user_id')`. The design says this is set "on each connection checkout via a Spring `ConnectionPreparer` or Hibernate `SessionEventListener`." With HikariCP, connections are reused across requests. If `app.current_user_id` is set on checkout but not cleared on checkin, a pooled connection returned from User A's request retains User A's ID when handed to User B's request — until User B's `ConnectionPreparer` runs. If that hook runs after any query is executed (e.g., in a transaction with eager loading), User B reads User A's data.

**Why it matters:** This is a cross-tenant data leak in the defense-in-depth layer that was explicitly added to prevent cross-tenant leaks. The exact failure mode depends on the hook timing relative to query execution — and it's subtle enough to pass unit tests while failing under concurrent load.

**Suggestion:**
- Use a `DataSourceWrapper` or Hikari's `connectionInitSql` to set a sane default: `SET app.current_user_id = '00000000-0000-0000-0000-000000000000'` on connection creation.
- On each request, set the variable **in the same transaction** as the first query, not just on checkout: `SET LOCAL app.current_user_id = ?` (transaction-scoped, automatically reset on commit/rollback). `SET LOCAL` is safer than `SET SESSION` for this pattern because it cannot leak across transaction boundaries even if the reset is forgotten.
- Add a Testcontainers integration test that explicitly verifies RLS rejects a cross-user query after simulating a connection pool reuse scenario.

---

### 2.3 Content-Hash Deduplication Has No Database Enforcement

**Description:** The design says: "If `content_hash` already exists for this `user_id`, the upload is rejected with 409 Conflict." The check is application-layer only. No unique constraint is mentioned on `(user_id, content_hash)` in the schema DDL.

**Why it matters:** Two concurrent uploads of the same file from the same user can both pass the application-layer check before either completes writing. Result: duplicate rows in `photos`, duplicate objects in MinIO, and `used_bytes` double-incremented. The quota check is also a serializable transaction but the deduplication check is not described with the same atomicity.

**Suggestion:** Add a database-level unique constraint:
```sql
ALTER TABLE photos ADD CONSTRAINT photos_user_content_unique UNIQUE (user_id, content_hash);
```
The application check remains as a fast-path (return early without touching MinIO), but the database constraint is the inviolable guard. On `UniqueConstraintViolationException`, return 409. This also means the application never needs to reason about race windows — the DB enforces it.

---

### 2.4 Redis Streams Consumer Groups Not Specified

**Description:** The design states "Jobs survive API restarts — Redis Streams retains unconsumed entries." This is true for entries appended to a stream, but **not** true for in-flight messages being actively processed when the consumer crashes. Without consumer groups and explicit `XACK`, crashed-mid-processing messages become "pending" and require manual recovery via `XAUTOCLAIM` or a pending entry monitor. Without consumer groups at all, a restarted consumer reads from its last known ID — but if that ID isn't persisted, it starts from the latest entry, silently skipping all jobs queued during the restart window.

**Why it matters:** A VPS restart during a large upload batch (e.g., 500 RAW files) would silently lose all thumbnail generation and metadata extraction jobs. Photos would appear in the library with no thumbnails and no metadata, with no error shown to the user and no mechanism to trigger reprocessing.

**Suggestion:** Explicitly specify consumer groups:
- Create a consumer group on the stream: `XGROUP CREATE photo-jobs processors $ MKSTREAM`
- Each `JobConsumer` uses `XREADGROUP` and sends `XACK` only after the job completes successfully.
- Add a periodic `XAUTOCLAIM` sweep (or a Spring scheduled task) to reclaim messages that have been pending longer than a timeout (e.g., 5 minutes), indicating a crashed consumer.
- Document the "stuck job" monitoring alert: if pending entries in the stream exceed a threshold, alert on it.

---

### 2.5 ImageMagick Is an Unmitigated RCE Surface

**Description:** The design uses ImageMagick CLI to process user-uploaded photos. ImageMagick has a documented history of critical vulnerabilities (ImageTragick/CVE-2016-3714 and successors) that allow remote code execution via malformed image files. The design does not mention any sandboxing, policy restrictions, or input validation before passing files to ImageMagick.

**Why it matters:** A malicious user uploads a crafted file (disguised as a JPEG) containing a shell escape in metadata or a malicious delegate rule. ImageMagick processes it and executes arbitrary code as the application user. On a single-VPS deployment with no process isolation, this is a full server compromise. The vulnerability surface is active and ongoing — new ImageMagick CVEs are published regularly.

**Suggestion:**
- **Validate content type server-side before ImageMagick:** Use Apache Tika or Java's `URLConnection.guessContentTypeFromStream()` to verify the file is actually an image before spawning ImageMagick. Reject non-image content types immediately.
- **Restrict ImageMagick policy:** Mount a hardened `/etc/ImageMagick-7/policy.xml` in the container that disables delegates, limits resource usage (memory, disk, time), and restricts coders to safe image formats only (JPEG, PNG, TIFF, HEIC via explicit allowlist). The default ImageMagick policy is permissive.
- **Run ImageMagick as a non-root unprivileged user** with no network access in the container. Consider running thumbnail generation in a separate sidecar container with no access to PostgreSQL or Redis — limiting blast radius on compromise.
- Keep ImageMagick pinned to a specific version in the Docker image and include it in the dependency update cadence.

---

## 3. Alternative Architectural Challenge

**Alternative: Event-Driven Architecture with Separate Media Processing Service**

Instead of embedding the job consumer (`JobConsumer` + ImageMagick) inside the Spring Boot API container, extract media processing into a dedicated sidecar service:

```
api/          → REST API only, no ImageMagick, no metadata-extractor
worker/       → JobConsumer + ImageMagick + metadata-extractor, separate Docker image
```

Both containers consume from the same Redis Streams queue. The `worker` container runs with no inbound network ports, no PostgreSQL write credentials for user-facing tables (write-only to `photo_metadata`, `photos.processing_status`), and a hardened ImageMagick policy.

**Pro:** Security blast radius is contained — an ImageMagick RCE can only reach what the worker container has access to (no API credentials, no user session data). Worker can scale independently from the API (run 2 workers, 1 API instance). Crash of the worker doesn't affect API availability.

**Con:** Two Docker images to build and maintain. Local development is slightly more complex (docker-compose adds one service). Shared library modules (`metadata/`, `thumbnails/`) must be in the `shared/` Gradle module to be reusable across both containers.

This is a meaningful improvement over the current design for a small cost, and it directly addresses issue 2.5.

---

## 4. Minor Issues & Improvements

1. **`search_vector` is filename-only but the design promises more.** The generated column:
   ```sql
   search_vector tsvector GENERATED ALWAYS AS (
     to_tsvector('english', coalesce(filename,''))
   ) STORED
   ```
   only indexes `filename`. The design says "full-text search on filenames and extracted text fields." EXIF/IPTC captions and descriptions live in `photo_metadata` (a separate table). A generated column cannot span tables, so including them requires either denormalization (copy caption fields to `photos`) or a trigger that updates `search_vector`. The current schema does not support the advertised capability without additional work.

2. **ISO BETWEEN query uses string comparison.** The example:
   ```sql
   WHERE m.exif_data->>'ISOSpeedRatings' BETWEEN '100' AND '400';
   ```
   `->>'field'` returns `text`. String comparison of numbers is incorrect for range queries: `'99' > '400'` lexicographically. Cast explicitly: `(m.exif_data->>'ISOSpeedRatings')::integer BETWEEN 100 AND 400`.

3. **JWT revocation on logout/password change not addressed.** Short-lived JWTs (typically 15 min) in httpOnly cookies cannot be invalidated server-side. If a user changes their password (implying account compromise), existing JWTs are still valid until expiry. The refresh token in Redis can be revoked, but the short-lived JWT cannot. For a photo service, this is acceptable — document the decision explicitly and set JWT expiry to ≤15 minutes.

4. **Rate limiting is mentioned but not designed.** Redis is listed as handling rate limiting in Section 1, but no rate limiting design appears anywhere. At minimum, define: brute-force protection on `/login` (e.g., 10 attempts per IP per minute), per-user upload rate limiting, and API endpoint rate limiting. `spring-boot-starter-data-redis` + Bucket4j or a Nginx-level rate limit (`limit_req_zone`) are both viable.

5. **Quota check uses SERIALIZABLE but SELECT FOR UPDATE is simpler.** The design specifies a serializable transaction for quota enforcement. `SERIALIZABLE` isolation is correct but has higher abort rates and retry overhead. A simpler approach for this specific pattern: `SELECT used_bytes FROM users WHERE id = ? FOR UPDATE` — this takes a row-level lock, runs the check, and increments atomically without needing serializable isolation on the full transaction.

6. **`mc mirror --watch` for backups is a foreground process.** The backup command `mc mirror jpt-photos/ b2/jpt-photos-backup/ --remove --watch` runs continuously (watch mode). This is a daemon, not a cron job. This should be in a dedicated sidecar container with a restart policy, not a shell script. Also, `--remove` means objects deleted from MinIO are also deleted from the backup — this prevents accidental deletion recovery. Consider `--remove` carefully or use versioned Backblaze B2 buckets.

7. **Monitoring gap persists.** Phase 5 still mentions "monitoring" without any specifics. At minimum, define: Micrometer metrics on the Spring Boot API (exported to Prometheus), disk usage alert on the VPS (MinIO volume approaching full), Redis memory alert, and a dead-letter monitoring on the Redis Streams pending entries.

---

## 5. Questions for Clarification

1. **MinIO network exposure (issue 2.1):** How is the MinIO pre-signed URL accessible from the browser given no port mapping is shown in Docker Compose? Is Nginx proxying MinIO traffic, or is MinIO's port exposed directly?

2. **`SET LOCAL` vs. `SET SESSION` for RLS (issue 2.2):** Is the `ConnectionPreparer` hook aware of transaction boundaries? Does it reset the variable on connection return, or just set it on checkout?

3. **Redis Streams consumer groups:** Are consumer groups and `XACK` in scope for the async pipeline, or is the design relying on a simpler offset-tracking mechanism?

4. **`search_vector` scope:** Is the intent for full-text search to cover only filenames, or also IPTC captions, titles, and descriptions? If the latter, how will the tsvector be populated across tables?

5. **Worker separation:** Is there appetite for the worker/API split described in Section 3? It adds a minor build-system complexity in exchange for meaningful security isolation.

---

## 6. Final Recommendation

**Approve with targeted revisions — four issues must be resolved before implementation begins.**

The architecture is mature and most v1 concerns are well-resolved. The design is ready for implementation once:

1. **MinIO accessibility is resolved** — specify Nginx proxy path for MinIO or document the port exposure plan (2.1).
2. **Use `SET LOCAL` for RLS session variable** — prevent cross-request contamination via connection pool (2.2).
3. **Add `UNIQUE (user_id, content_hash)` to the schema** — enforce deduplication at the database layer (2.3).
4. **Specify Redis Streams consumer groups + XACK** — or explicitly document the pending-entry recovery mechanism (2.4).

Issue 2.5 (ImageMagick) should be addressed before the Phase 3 implementation starts (not before planning approval). The worker/API split (Section 3 alternative) is the cleanest long-term resolution and worth adopting early since the Gradle multi-module structure already supports it.

Minor issues 4.1 (search_vector scope) and 4.2 (ISO BETWEEN cast) are implementation-level bugs that must be caught before the search feature ships.
