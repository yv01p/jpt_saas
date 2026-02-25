# JPhotoTagger SaaS Conversion Design

**Date:** 2026-02-25
**Version:** 4.0
**Status:** Approved

---

## Changelog

### v4.0 — 2026-02-25

Revisions following Security Audit v1 and Critical Design Review v4:

- **[SA#1/CR#2.1] Nginx rate-limit zone placement:** Moved `limit_req_zone` directives from `server {}` to `http {}` context — zones are only valid in `http {}`. Added `nginx -t` config validation to CI pipeline.
- **[SA#2] MinIO proxy access control:** MinIO bucket policy documented as **private** (hard requirement); added `proxy_set_header Authorization ""` to strip ambient credentials; added regex path guard constraining valid `/photos/` paths to `/{user_id}/(originals|thumbnails)/{photo_id}`.
- **[SA#3] Backup deletion propagation:** Removed `--remove` from `mc mirror`; enabled B2 Object Lock (immutable retention) on backup bucket; backup sidecar uses separate B2 credentials with write-only (no delete) permissions; added monitoring alert on bulk MinIO delete operations.
- **[SA#4] Redis healthcheck password:** Replaced `redis-cli -a` with `REDISCLI_AUTH` environment variable to avoid password exposure in process args and `docker inspect`.
- **[SA#5/CR#2.2] pgbackup container:** Replaced runtime `apt-get install` with custom Docker image (`FROM postgres:16` + pre-installed, version-pinned restic); added top-level `secrets:` definition for `restic_pass`; documented secret provisioning; added `restic forget --prune` to backup loop.
- **[SA#6] Password policy:** Specified minimum 12-character password length, bcrypt cost factor >= 12, account lockout after 5 failed attempts.
- **[SA#7] JWT secret management:** Specified HS256 with >= 256-bit key, documented key generation (`openssl rand -base64 64`), documented key rotation procedure.
- **[SA#8] Share link expiry:** Changed default from `NULL` (permanent) to 30-day expiry; users can explicitly create permanent links; added "Manage Shares" UI to Phase 5.
- **[SA#9] EXIF GPS in shares:** GPS coordinates stripped from EXIF data served via public share links by default; per-share opt-in for including location; user-level setting to control GPS display.
- **[SA#10] ExifTool injection prevention:** Specified `ProcessBuilder` with explicit argument arrays (no shell); files referenced by UUID storage keys only (never original filenames); added `read_only: true` + `tmpfs` working directory to worker container.
- **[SA#11/CR#3] CI/CD integrity:** Added artifact signing (JAR + React bundle) in CI with VPS verification; dedicated SSH deploy key; post-deploy healthcheck with rollback; clarified deployment model.
- **[CR#2.3] Password-protected shares:** Removed undesigned feature claim. 256-bit token provides sufficient access control; password protection deferred as named future feature.
- **[CR#2.4] `album_photos` cross-tenant protection:** Added `user_id` column to `album_photos` with composite foreign keys referencing `albums(id, user_id)` and `photos(id, user_id)`; RLS policy now enforceable; corrected RLS documentation.
- **[SA#12/CR#7] CSP `style-src 'unsafe-inline'`:** Documented as accepted trade-off required by Tailwind/shadcn dynamic class injection.
- **[SA#14] Nginx path rewriting:** Documented `proxy_pass` trailing-slash path stripping behavior; added catch-all `/api/auth/` rate limit; documented Spring Boot controller path mappings use post-rewrite paths.
- **[Cross-cutting] Worker payload validation:** Worker validates `photo_id` exists and `processing_status = 'pending'` in DB before processing any Redis Stream job.
- **[CR#5] Email token expiry:** Verification tokens expire in 24 hours; password reset tokens expire in 1 hour; stored in `email_tokens` table; unverified accounts soft-gated (no uploads) with 7-day auto-purge.
- **[CR#6] MinIO admin console:** Disabled in production via `MINIO_BROWSER=off`; all admin via `mc` CLI.

### v3.0 — 2026-02-24

Revisions following Critical Design Review v3:

- **[2.1] Photo deletion:** Added soft-delete design — `deleted_at TIMESTAMPTZ` on `photos` (Phase 1 schema); Trash view with configurable retention window (default 30 days); async MinIO cleanup via Redis Streams `delete-job`; quota decremented atomically in deletion transaction; all `photos` queries filter `deleted_at IS NULL`; active shares to deleted photos return 404; periodic orphan-reconciliation sweep.
- **[2.2] Redis job persistence:** Added `--appendonly yes --appendfsync everysec` to Redis command; added `processing_status VARCHAR(16) DEFAULT 'pending'` to `photos` schema — worker re-enqueues all `pending`/`processing` rows on startup as authoritative recovery mechanism.
- **[2.3] Worker DB least privilege:** Added `worker_db_user` PostgreSQL role with column-level grants via Flyway migration; worker service uses `WORKER_DB_USER`/`WORKER_DB_PASS` env vars. Full-privilege `api` credential is not shared with the worker.
- **[2.4] Share token security:** Token generated via `SecureRandom` (256-bit, URL-safe base64, 43 chars); only `SHA-256(token)` stored in `shares.token_hash`; plaintext returned once on creation; Nginx rate limit on `/share/` path (60 req/min per IP); `expires_at = NULL` default (permanent).
- **[2.5] WebSocket decision:** Polling chosen over WebSocket for MVP — UI polls `/api/photos/{id}/status` every 3 seconds post-upload until `done` or `failed`. No WebSocket dependency, no broker, no Nginx upgrade headers.
- **[2.6] PostgreSQL backup:** Added `pgbackup` service to Docker Compose (`pg_dump` piped to `restic`, stored in B2 bucket `jpt-db-backup`). Matches MinIO backup sidecar pattern. DB backup is now concrete infrastructure, not prose.
- **[M2] Nginx HTTPS + security headers:** Added HTTP→HTTPS redirect server block; HSTS (`max-age=31536000; includeSubDomains`); `X-Content-Type-Options: nosniff`; `X-Frame-Options: DENY`; `Referrer-Policy: strict-origin-when-cross-origin`; `Content-Security-Policy` baseline.
- **[M3] Grafana/Prometheus access control:** Both services restricted to the Docker internal network — no external port mapping. Accessed via SSH port forwarding only.
- **[M4] Upload size limits:** `spring.servlet.multipart.max-file-size=200MB` and `max-request-size=200MB` required in Phase 1 scaffold; Nginx `client_max_body_size 250m` on `/api/` location block.
- **[M5] HikariCP pool budget:** Explicit `maximum-pool-size` set for `api` (10) and `worker` (5); connection budget formula documented.
- **[M7] B2 version retention:** 90-day lifecycle rule on `jpt-photos-backup` B2 bucket — version history older than 90 days is automatically expired.
- **[M8] Worker container hardening:** Added `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]` to worker Compose service.

### v2.0 — 2026-02-24

Revisions following Critical Design Review v2:

- **[2.1] MinIO accessibility:** Added Nginx reverse proxy for MinIO (`/photos/` location block); pre-signed URLs generated against public Nginx domain; defined URL expiry durations (15 min thumbnails, 1 hour originals).
- **[2.2] RLS session variable:** Replaced `SET SESSION` with `SET LOCAL` (transaction-scoped, resets on commit/rollback); added Hikari `connectionInitSql` safe default; added explicit Testcontainers RLS-reuse integration test.
- **[2.3] Deduplication constraint:** Added `UNIQUE (user_id, content_hash)` database constraint; application-layer check is fast path only; DB constraint is authoritative guard.
- **[2.4] Redis Streams consumer groups:** Specified consumer groups, `XREADGROUP`/`XACK`, and `XAUTOCLAIM` recovery sweep for crashed-consumer recovery.
- **[2.5] Image processing stack:** Replaced ImageMagick with libraw (embedded preview extraction) + libvips (thumbnail resizing); metadata-extractor as primary metadata extractor; ExifTool with `-fast2` as fallback for exotic RAW/maker notes in worker container; Apache Tika content-type validation before any processing.
- **[3] Worker container:** Adopted separate `worker/` Docker image from day one; API container has no image processing dependencies.
- **[4.1] Search vector scope:** Denormalized `caption`, `title`, `description` into `photos` table; all four fields included in generated `search_vector`.
- **[4.2] EXIF numeric queries:** Fixed range queries on numeric EXIF fields to use explicit `::integer` cast.
- **[4.3] JWT expiry policy:** Documented 15-minute JWT expiry and refresh token revocation policy on password change.
- **[4.4] Rate limiting:** Added two-layer rate limiting — Nginx `limit_req_zone` for unauthenticated endpoints; Bucket4j + Redis for authenticated per-user limits.
- **[4.5] Quota enforcement:** Replaced SERIALIZABLE transaction with `SELECT FOR UPDATE` row lock.
- **[4.6] Backup sidecar:** Converted `mc mirror` to supervised sidecar container; removed `--watch` flag; enabled B2 bucket versioning for deletion recovery.
- **[4.7] Monitoring:** Added monitoring section — Micrometer + Prometheus + Grafana + four baseline alerts.

### v1.0 — 2026-02-24

Initial approved design after Critical Design Review v1 revisions. Resolved: RLS added, async pipeline (Redis Streams) specified, Java upgrade sequenced before SaaS conversion, OAuth auto-merge blocked, quotas designed, MinIO backup corrected to `mc mirror`, CSRF fully specified, deduplication added, Redis password added, `depends_on` health checks added, CI/CD deployment steps documented.

---

## Overview

Convert JPhotoTagger from a single-user Java Swing desktop application into a multi-user web SaaS application. Target scale: a few thousand users, primarily professional photographers. The existing Java domain, metadata, and repository modules are preserved and wrapped with a Spring Boot REST API. The Swing UI is discarded and replaced with a React web frontend. Search is handled by PostgreSQL full-text search and JSONB GIN indexes — the embedded Lucene module is not carried forward.

---

## Section 1: Overall Architecture

```
┌─────────────────────────────────────────────────────────┐
│                         VPS                             │
│                                                         │
│  ┌──────────┐    ┌────────────────────────────────────┐ │
│  │  Nginx   │───▶│     Spring Boot 3 (Java 21)        │ │
│  │ (reverse │    │     api/ module                    │ │
│  │  proxy + │    │                                    │ │
│  │  static +│    │  ┌─────────────────────────────┐   │ │
│  │  MinIO   │    │  │  Existing modules           │   │ │
│  │  proxy)  │    │  │  (Domain, Metadata,         │   │ │
│  └──────────┘    │  │   Repositories)             │   │ │
│       │          │  └─────────────────────────────┘   │ │
│       │          │                                    │ │
│  ┌────▼─────┐    │  REST API + polling endpoint       │ │
│  │  React   │    └──────────────────┬─────────────────┘ │
│  │  (built  │                       │ Redis Streams      │
│  │  static) │    ┌──────────────────▼─────────────────┐ │
│  └──────────┘    │     Worker (Java 21)               │ │
│                  │     worker/ module                  │ │
│                  │                                    │ │
│                  │  ┌─────────────────────────────┐   │ │
│                  │  │  libraw  (preview extract)  │   │ │
│                  │  │  libvips (thumbnail resize) │   │ │
│                  │  │  metadata-extractor (primary│   │ │
│                  │  │    RAW metadata)            │   │ │
│                  │  │  ExifTool -fast2 (fallback  │   │ │
│                  │  │    exotic RAW/maker notes)  │   │ │
│                  │  │  Apache Tika (validation)   │   │ │
│                  │  └─────────────────────────────┘   │ │
│                  │  Redis Streams consumer             │ │
│                  └──────────────────┬─────────────────┘ │
│                    ┌────────────────┼────────────┐      │
│                    ▼                ▼            ▼      │
│              ┌──────────┐    ┌────────┐   ┌──────────┐  │
│              │PostgreSQL│    │ MinIO  │   │  Redis   │  │
│              │(metadata,│    │(photos,│   │(sessions,│  │
│              │ users,   │    │ thumbs)│   │ streams, │  │
│              │ shares)  │    └────────┘   │ rate     │  │
│              └──────────┘                │ limits)  │  │
│                                          └──────────┘  │
└─────────────────────────────────────────────────────────┘
```

- **Nginx** serves the React app as static files, reverse-proxies API calls to Spring Boot, and proxies MinIO traffic on `/photos/` — MinIO is not directly exposed to the internet.
- **MinIO** handles all binary storage (originals + thumbnails). S3-compatible, making hyperscaler migration trivial.
- **PostgreSQL** holds all metadata, user accounts, and sharing data.
- **Redis** handles session caching, rate limiting, and the job queue (Redis Streams). AOF persistence enabled.
- **Worker** is a separate Docker container responsible for all image processing and metadata extraction. It has no inbound network ports and no access to user session data. Runs with dropped Linux capabilities.
- Everything runs as Docker containers via Docker Compose.

---

## Section 2: Backend Structure & Java Upgrade Strategy

The existing codebase is reorganized into a Gradle multi-module project, replacing Ant/NetBeans.

```
jpt-saas/
├── api/              ← Spring Boot app, REST controllers, security config — no image processing
├── worker/           ← NEW: Redis Streams consumer, libraw, libvips, metadata-extractor, ExifTool
├── domain/           ← existing Domain module — migrate to Java 21
├── repositories/     ← existing Repositories module — PostgreSQL replaces HSQLDB
├── metadata/         ← existing Exif/IPTC/XMP modules — migrate + audit libraries
├── thumbnails/       ← existing thumbnail logic — replaced by libvips in worker
└── shared/           ← NEW: DTOs, shared utilities — reused by api/ and worker/
```

The Swing UI modules are excluded from the new build — not migrated, not deleted (kept in git history), simply not referenced.

### Java Upgrade Strategy (prerequisite track — must complete before SaaS conversion)

The Java upgrade from 7 to 21 is sequenced **before** the SaaS conversion, not in parallel. The existing desktop app serves as the regression baseline throughout the upgrade. SaaS conversion begins only on the verified-stable Java 21 codebase.

- **Phase 1 — Compile:** Get the project compiling on Java 21 with zero functional changes. Fix deprecations, remove `sun.*` usages, migrate build tooling to Gradle 8.
- **Phase 2 — Libraries:** Update each library to its current version. ImgRdr is tackled here — replaced if found to be unmaintained. The Lucene module is not migrated; search is replaced by PostgreSQL FTS in the SaaS layer.
- **Phase 3 — Modernise:** Adopt Java 21 idioms where beneficial: records for DTOs, virtual threads for I/O-heavy operations (photo uploads, thumbnail generation).
- **Validation gate:** The desktop app must pass its full existing test suite on Java 21 before SaaS conversion phases begin. This isolates upgrade bugs from multi-tenancy bugs.

### Image Processing Stack

All image processing runs in the `worker/` container. The `api/` container has no image processing dependencies.

| Concern | Library | Notes |
|---|---|---|
| Embedded preview extraction | **libraw** (CLI) | Extracts embedded JPEG from RAW — no full decode needed |
| Thumbnail resizing | **libvips** (CLI) | Fast, low memory, good security record |
| Content-type validation | **Apache Tika** (Java) | Validates file is a real image before any processing |
| Metadata extraction (primary) | **metadata-extractor** (Java) | Pure Java, safe, covers mainstream cameras |
| Metadata extraction (fallback) | **ExifTool -fast2** (CLI) | Exotic RAW formats and maker notes; Perl runtime in worker only |

**RAW format strategy:** Most RAW files (CR2, CR3, NEF, ARW, RAF, ORF, RW2, DNG, etc.) contain an embedded full-resolution JPEG preview. libraw extracts this preview; libvips resizes it to thumbnails. Full RAW decode is not performed — this is sufficient for a photo tagging and sharing application. metadata-extractor reads EXIF/IPTC/XMP metadata directly from the RAW file header without decoding pixel data. ExifTool (`-fast2`) is used as a fallback for exotic maker notes and formats metadata-extractor does not cover (important given the professional photographer target audience).

**Security notes:**
- Apache Tika validates content type before any file is passed to libraw, libvips, or ExifTool. Non-image files are rejected immediately.
- libraw and libvips have significantly better security records than ImageMagick (no delegate-based RCE surface).
- ExifTool is pinned to a specific version, run with `-fast2` to limit parsing depth, and confined to the worker container with no network access and no API credentials.
- **All CLI tools are invoked via `ProcessBuilder` with explicit argument arrays** — never shell string concatenation. Files are referenced by MinIO-generated UUID storage keys, never original user-supplied filenames. This eliminates command injection via crafted filenames.
- The worker container runs as a non-root unprivileged user with all Linux capabilities dropped. The worker filesystem is `read_only: true` with a `tmpfs` mount for the working directory.

### Other Library Concerns

| Library | Risk | Notes |
|---|---|---|
| metadata-extractor | Low | Well-maintained, pure Java |
| XMPCore | Low | Modern versions support Java 21 |
| ImgRdr | **High** | Obscure/possibly unmaintained — investigate early, replace if needed |

### Application Configuration (Phase 1 requirements)

Both `api/` and `worker/` require explicit configuration in `application.yml` before first use:

**`api/src/main/resources/application.yml`:**
```yaml
spring:
  servlet:
    multipart:
      max-file-size: 200MB        # Professional RAW files are 50–100 MB
      max-request-size: 200MB
  datasource:
    hikari:
      maximum-pool-size: 10       # See connection budget below
```

**`worker/src/main/resources/application.yml`:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 5        # See connection budget below
```

**HikariCP connection budget:**
```
api instances:    1 × pool 10  =  10 connections
worker instances: 1 × pool  5  =   5 connections
                              ─────────────────
Total:                            15 connections
PostgreSQL max_connections default: 100

Budget is well within limits for initial single-instance deployment.
When adding worker replicas: (api_instances × 10) + (worker_instances × 5) < postgres.max_connections
```

---

## Section 3: Data Layer

### PostgreSQL — row-level multi-tenancy

Every table includes a `user_id` column. All queries are scoped to the authenticated user, enforced at the repository layer.

```sql
users           (id, email, password_hash, oauth_provider, oauth_id, quota_bytes, used_bytes, created_at,
                 failed_login_attempts INTEGER DEFAULT 0, locked_until TIMESTAMPTZ DEFAULT NULL)
email_tokens    (id, user_id, token_hash, purpose VARCHAR(16), expires_at TIMESTAMPTZ NOT NULL, created_at)
                -- purpose: 'verify' | 'reset'; token_hash = SHA-256(token); plaintext returned once via email
photos          (id, user_id, filename, caption, title, description,
                 storage_key, size_bytes, content_hash, taken_at, uploaded_at,
                 processing_status VARCHAR(16) DEFAULT 'pending',
                 -- processing_status values: pending | processing | done | failed
                 deleted_at TIMESTAMPTZ DEFAULT NULL,
                 -- deleted_at IS NULL = active; NOT NULL = soft-deleted (in Trash)
                 search_vector tsvector GENERATED ALWAYS AS (
                   to_tsvector('english',
                     coalesce(filename,'')    || ' ' ||
                     coalesce(title,'')       || ' ' ||
                     coalesce(caption,'')     || ' ' ||
                     coalesce(description,'')
                   )
                 ) STORED)
photo_metadata  (photo_id, exif_data jsonb, iptc_data jsonb, xmp_data jsonb)
keywords        (id, user_id, name, parent_id)   -- hierarchical adjacency list, per-user
photo_keywords  (photo_id, keyword_id)
albums          (id, user_id, name, created_at)
album_photos    (album_id, photo_id, user_id,
                 FOREIGN KEY (album_id, user_id) REFERENCES albums(id, user_id),
                 FOREIGN KEY (photo_id, user_id) REFERENCES photos(id, user_id))
                -- user_id denormalized for RLS enforcement + composite FK cross-tenant guard
shares          (id, user_id, resource_type, resource_id, token_hash, expires_at, permissions,
                 include_gps BOOLEAN DEFAULT FALSE)
                -- token_hash = SHA-256(token); plaintext token returned once on creation, never stored
                -- expires_at defaults to now() + 30 days; NULL = permanent (explicit opt-in)
saved_searches  (id, user_id, name, query_json)
```

`caption`, `title`, and `description` are denormalized onto the `photos` table so the generated `search_vector` column can index all four text fields in a single pass. These fields are populated from IPTC/XMP metadata during the worker's extraction step.

Schema versioning managed by **Flyway** from day one.

**Important:** All application queries on `photos` must include `AND deleted_at IS NULL` unless explicitly targeting the Trash view. The Trash view queries `WHERE deleted_at IS NOT NULL`.

### Worker Database User (Least Privilege)

The worker parses untrusted binary files through multiple native processes — it is the highest-risk component in the system. It must not hold the same database credentials as the API.

A dedicated Flyway migration creates a restricted role:

```sql
CREATE ROLE worker_db_user WITH LOGIN PASSWORD '${WORKER_DB_PASS}';

-- Worker needs to read photo job details
GRANT SELECT ON photos TO worker_db_user;

-- Worker writes extracted metadata
GRANT INSERT, UPDATE ON photo_metadata TO worker_db_user;

-- Worker updates only the columns it manages
GRANT UPDATE (storage_key, content_hash, processing_status, size_bytes)
  ON photos TO worker_db_user;

-- Explicitly NOT granted:
-- users (no access to password_hash, quota_bytes, email)
-- shares, keywords, albums, saved_searches, sessions
```

The `worker` Compose service uses `WORKER_DB_USER` and `WORKER_DB_PASS` environment variables. The `api` Compose service uses the full-privilege `DB_USER` / `DB_PASS`.

### Photo Deletion (Soft Delete)

Photos are soft-deleted — `deleted_at` is set on the `photos` row; the underlying MinIO objects are not removed immediately. A Trash view allows recovery within a configurable retention window (default: 30 days). After the retention window, photos are permanently purged.

**Delete flow (`DELETE /api/photos/{id}`):**

1. Service verifies ownership: `photo.userId == currentUser.id`.
2. Within a single transaction:
   - `UPDATE photos SET deleted_at = now() WHERE id = ? AND user_id = ?`
   - `UPDATE users SET used_bytes = used_bytes - ? WHERE id = ?` (decrement by `photos.size_bytes`)
3. A `delete-job` is enqueued to Redis Streams (outside the transaction, best-effort) with the storage keys to clean up from MinIO.
4. The API returns 204.

**Cascade strategy:**
- `photo_keywords(photo_id)` and `album_photos(photo_id)`: `ON DELETE CASCADE` — fired at permanent purge time, not at soft-delete time.
- `shares` referencing a soft-deleted photo: the share lookup queries `JOIN photos p ON p.id = s.resource_id WHERE p.deleted_at IS NULL`. Accessing a share whose photo is in Trash returns 404.

**Trash view (`GET /api/photos/trash`):**
Returns `photos WHERE user_id = ? AND deleted_at IS NOT NULL ORDER BY deleted_at DESC`.

**Restore (`POST /api/photos/{id}/restore`):**
Sets `deleted_at = NULL` and increments `users.used_bytes` in the same transaction.

**Permanent purge (scheduled task — `@Scheduled`):**
Runs daily. Permanently deletes all rows where `deleted_at < now() - interval '30 days'` (retention window is configurable via `app.trash-retention-days`). For each photo being purged:
1. Enqueues a `delete-job` to Redis Streams with the original and thumbnail storage keys.
2. Deletes the `photos` row — cascades to `photo_keywords`, `album_photos`.
3. Any remaining `shares` rows referencing the photo are deleted by cascade.

**Async MinIO cleanup (worker — `delete-job` consumer):**
The worker consumes `delete-job` entries from Redis Streams and calls MinIO to delete the listed object keys. Uses the same `XACK`/`XAUTOCLAIM` pattern as `photo-jobs`.

**Orphan reconciliation sweep (scheduled — weekly):**
A Spring `@Scheduled` task compares the set of `storage_key` values in `photos` (including soft-deleted) against the MinIO object listing. Any MinIO objects not referenced by any `photos` row are enqueued for deletion. Catches any failures in the async MinIO cleanup path.

### Search — PostgreSQL FTS + JSONB GIN Indexes

Search is handled entirely within PostgreSQL. No separate search index or service.

**Full-text search** on filenames, titles, captions, and descriptions via `tsvector`:
```sql
-- GIN index on the generated tsvector column
CREATE INDEX photos_search_idx ON photos USING GIN (search_vector);

-- Query (always filter deleted_at IS NULL)
SELECT * FROM photos
WHERE user_id = ? AND deleted_at IS NULL
  AND search_vector @@ plainto_tsquery('english', ?);
```

**Metadata field queries** via JSONB GIN indexes:
```sql
-- GIN indexes on JSONB columns
CREATE INDEX photo_exif_gin ON photo_metadata USING GIN (exif_data);
CREATE INDEX photo_iptc_gin ON photo_metadata USING GIN (iptc_data);

-- Exact field match (e.g. camera model)
SELECT p.* FROM photos p
JOIN photo_metadata m ON m.photo_id = p.id
WHERE p.user_id = ? AND p.deleted_at IS NULL
  AND m.exif_data @> '{"Make": "Canon"}';

-- Numeric EXIF range query — always cast to integer for correct range comparison
WHERE (m.exif_data->>'ISOSpeedRatings')::integer BETWEEN 100 AND 400;
WHERE (m.exif_data->>'FocalLength')::integer BETWEEN 50 AND 200;
```

> **Important:** `->>'field'` returns `text`. String comparison of numbers is incorrect for range queries — always cast to the appropriate numeric type (`::integer`, `::numeric`) before using `BETWEEN` or comparison operators.

**Keyword search** queries the existing `keywords` + `photo_keywords` tables directly — no FTS needed. Subtree queries use recursive CTEs (`WITH RECURSIVE`).

> **Future consideration (4.2):** If keyword trees become deep (>5 levels) or recursive CTE performance degrades, evaluate PostgreSQL's `ltree` extension for efficient ancestor/descendant path queries. This is a Flyway migration to change the `parent_id` column to an `ltree` path.

This approach eliminates the Lucene module entirely, provides ACID consistency between data and search (no reindex lag), and is fully covered by the standard PostgreSQL backup.

> **Future consideration (4.1):** If query patterns reveal that a small set of EXIF fields (camera model, ISO, focal length, GPS) dominate search traffic, introduce a flat `photo_exif` table with typed columns for those fields via a Flyway migration. The `exif_data` JSONB blob is retained for the full raw payload. Don't do this speculatively — let actual query patterns drive the decision.

### Storage Quotas

`users.quota_bytes` defines the per-user storage limit (default configured via `app.default-quota-bytes`, e.g. 10 GB). `users.used_bytes` is maintained as a running total. Enforcement at the upload endpoint:

```
IF used_bytes + new_file_size > quota_bytes → reject with 413 (quota exceeded)
```

The check and `used_bytes` increment use `SELECT FOR UPDATE` to take a row-level lock on the user row, preventing race conditions with concurrent uploads:

```sql
SELECT used_bytes FROM users WHERE id = ? FOR UPDATE;
-- check passes →
UPDATE users SET used_bytes = used_bytes + ? WHERE id = ?;
```

On soft-delete, `used_bytes` is decremented in the same transaction. On restore, `used_bytes` is re-incremented in the same transaction. On permanent purge, `used_bytes` is already correct (decremented at soft-delete time).

The `/settings` view displays current usage vs. quota.

### PostgreSQL Row Level Security (defense-in-depth)

RLS is enabled on all tenant tables (`photos`, `photo_metadata`, `keywords`, `photo_keywords`, `albums`, `album_photos`, `shares`, `saved_searches`). The `album_photos` table has a denormalized `user_id` column with composite foreign keys to enforce cross-tenant isolation at the database level (see schema above). Policy:

```sql
CREATE POLICY tenant_isolation ON photos
  USING (user_id = current_setting('app.current_user_id')::uuid);
```

**Session variable safety with HikariCP:** Connections are reused across requests by the connection pool. `SET SESSION` would allow User A's ID to leak into User B's request. Instead:

- Hikari `connectionInitSql` sets a safe default on connection creation:
  ```sql
  SET app.current_user_id = '00000000-0000-0000-0000-000000000000'
  ```
- Each request sets the variable with `SET LOCAL` inside the transaction:
  ```sql
  SET LOCAL app.current_user_id = ?
  ```
  `SET LOCAL` is transaction-scoped — it resets automatically on commit or rollback, making cross-request variable pollution impossible even if the reset hook is missed.

Application-layer `WHERE user_id = ?` enforcement remains; RLS is the inviolable safety net that catches any missed clause.

### MinIO — object storage layout

```
bucket: jpt-photos/
  {user_id}/originals/{photo_id}.{ext}
  {user_id}/thumbnails/{photo_id}_sm.jpg
  {user_id}/thumbnails/{photo_id}_md.jpg
```

Photos are delivered via **pre-signed URLs** — Spring Boot generates a time-limited URL, the React client fetches the image directly from MinIO via the Nginx proxy. Large binary transfers never pass through the Spring Boot process.

**Pre-signed URL expiry:**
- Thumbnails: **15 minutes** (short — used at high frequency in the photo grid)
- Originals: **1 hour** (longer — opened on demand for the single photo view)

If a browsing session outlasts a thumbnail URL, the client re-requests a fresh URL from the API. Do not set expiry longer than 1 hour — a leaked URL grants access for that duration.

---

## Section 4: Authentication

### Spring Security with JWT + OAuth2

```
Email/password → Spring Security → JWT issued → stored in httpOnly cookie
Google/GitHub  → OAuth2 callback → user created/matched → JWT issued → same cookie
```

- JWT stored in **httpOnly cookie** (not localStorage) — prevents XSS token theft.
- **JWT signing:** HS256 with a key of >= 256 bits of cryptographic randomness. Key generation: `openssl rand -base64 64`. **Key rotation procedure:** deploy new secret; keep old secret valid for 15 minutes to drain existing tokens; remove old secret after drain window.
- **JWT expiry: 15 minutes.** Short-lived JWTs cannot be revoked server-side; 15 minutes is the accepted risk for this application class.
- Refresh tokens stored in Redis with a configurable expiry (default: 30 days).
- On password change: refresh token is revoked immediately in Redis. The short-lived JWT remains valid for up to 15 minutes (accepted risk — documented). The attacker loses the ability to obtain new JWTs within one expiry window.

### Password Policy

- **Minimum password length:** 12 characters.
- **Hashing:** bcrypt with cost factor >= 12.
- **Account lockout:** After 5 consecutive failed login attempts, the account is locked for 15 minutes (`users.locked_until`). The lockout counter (`users.failed_login_attempts`) resets on successful login. This complements IP-based Nginx rate limiting — lockout protects against distributed brute-force across multiple IPs.

### User Lifecycle

- Email registration includes email verification (link sent via SMTP). **Verification tokens expire in 24 hours.** Unverified accounts can log in but cannot upload photos (soft gate). Accounts unverified after 7 days are auto-purged.
- Password reset via email token. **Reset tokens expire in 1 hour.** Both verification and reset tokens are stored in the `email_tokens` table as `SHA-256(token)` — plaintext returned once via email, never stored.
- OAuth2 accounts are **never auto-merged by email**. If an OAuth login arrives for an email that already has a password account, the login is blocked and the user is shown: "An account with this email already exists. Log in with your password to link your Google account." Account linking is completed explicitly from the Settings page after the user authenticates with their existing credentials. Silent auto-merge is a known account pre-hijacking vector (OWASP).

### Authorization

- Every API endpoint is secured by default.
- Resource ownership checked at service layer: `photo.userId == currentUser.id` before any operation.
- When adding a photo to an album, cross-tenant isolation is enforced by composite foreign keys on `album_photos` (`(album_id, user_id)` → `albums`, `(photo_id, user_id)` → `photos`) and RLS policy. The service layer also verifies `album.userId == photo.userId` as a fast-fail check.
- Share tokens validated separately — public share links bypass user auth but are scoped strictly to the shared resource.

### CSRF Protection

JWT in an httpOnly cookie is still sent automatically on cross-site requests, so CSRF protection is required. Spring Security CSRF is **enabled** (never disabled for API endpoints). The **double-submit cookie pattern** is used:

- Spring Security sets a `XSRF-TOKEN` cookie (readable by JS, not httpOnly).
- The SPA reads it and includes it as an `X-XSRF-TOKEN` header on all mutating requests (POST, PUT, DELETE, PATCH).
- A TanStack Query request interceptor attaches the header globally.
- `CookieCsrfTokenRepository.withHttpOnlyFalse()` is configured in Spring Security — no custom implementation needed.

### Rate Limiting

Two-layer rate limiting using existing infrastructure (Nginx + Redis):

**Layer 1 — Nginx (unauthenticated endpoints):**
```nginx
limit_req_zone $binary_remote_addr zone=login:10m    rate=10r/m;
limit_req_zone $binary_remote_addr zone=register:10m rate=5r/m;
limit_req_zone $binary_remote_addr zone=share:10m    rate=60r/m;

location /api/auth/login    { limit_req zone=login    burst=5 nodelay; }
location /api/auth/register { limit_req zone=register burst=3 nodelay; }
location /share/            { limit_req zone=share    burst=10 nodelay; }
```
Blocks brute-force before it reaches Spring Boot. Returns 429 automatically.

The `/share/` rate limit protects the unauthenticated share-link endpoint from token enumeration attacks.

**Layer 2 — Bucket4j + Redis (authenticated endpoints):**
Per-user token buckets stored in Redis, enforced in Spring Boot:

| Limit | Rate |
|---|---|
| Photo uploads | 100 per hour per user |
| API requests (general) | 1 000 per hour per user |

Bucket4j integrates with Spring Boot via an interceptor and uses the existing Redis connection.

### Dependencies

```
spring-boot-starter-security
spring-boot-starter-oauth2-client
jjwt
spring-boot-starter-mail
spring-data-redis
bucket4j-redis
```

---

## Section 5: React Frontend

### Stack

```
Vite + React 18
React Router v6       — client-side routing
TanStack Query        — API data fetching, caching, background sync
Zustand               — lightweight global state (auth, user prefs)
shadcn/ui + Tailwind  — component library, consistent styling
React Dropzone        — photo upload with drag & drop
TanStack Virtual      — virtualized photo grid
```

### Views

```
/login                — email/password + Google/GitHub buttons
/register             — sign up + email verification prompt
/library              — main photo grid (virtualized, lazy-loaded thumbnails)
/photo/:id            — single photo view + metadata panel (EXIF/IPTC/XMP)
/keywords             — hierarchical keyword tree
/albums               — album list + album detail views
/search               — search interface with saved searches
/trash                — Trash view (soft-deleted photos, 30-day retention window)
/share/:token         — public share view (no login required)
/settings             — account, storage usage, linked OAuth accounts
```

### Performance

- Thumbnails served via pre-signed MinIO URLs proxied through Nginx — Spring Boot not in the binary path.
- Virtualized grid renders only visible rows, handles thousands of photos smoothly.
- Progressive loading: small thumbnail first, medium on hover.

### Upload Flow

- Chunked multipart upload to Spring Boot (`api/`).
- Spring Boot computes SHA-256 (`content_hash`) while streaming — no extra pass needed.
- Application-layer fast path: if `content_hash` already exists for this `user_id`, reject with `409 Conflict` before touching MinIO.
- Database-level guard: `UNIQUE (user_id, content_hash)` constraint is the inviolable enforcement. On `UniqueConstraintViolationException`, return 409. Race conditions between concurrent uploads of the same file are handled by the DB, not application logic.
- Otherwise, Spring Boot streams directly to MinIO — never buffers full image in heap.
- On upload completion, a `photo-jobs` entry is enqueued to Redis Streams. The `photos` row is inserted with `processing_status = 'pending'`.

> Note: deduplication is per-user only. Two users uploading the same file store separate MinIO objects — cross-user object sharing is out of scope (it complicates deletion and multi-tenancy).

### Upload Status Polling

After upload completes, the UI polls `/api/photos/{id}/status` every 3 seconds until `processing_status` is `done` or `failed`.

```
GET /api/photos/{id}/status
→ { "id": "...", "processing_status": "pending" | "processing" | "done" | "failed" }
```

No WebSocket dependency. No broker. No Nginx upgrade headers. Polling stops on `done` or `failed`. On `failed`, the UI surfaces an error state on the photo card.

### Async Job Processing

Post-upload work (thumbnail generation, metadata extraction) runs in the `worker/` container outside the HTTP request cycle:

```
Upload API (api/) → Redis Streams (photo-jobs) → Worker (worker/)
                                                      ├── Apache Tika (content-type validation)
                                                      ├── libraw CLI (extract embedded JPEG preview from RAW)
                                                      ├── libvips CLI (resize to sm/md thumbnails → MinIO)
                                                      ├── metadata-extractor (EXIF/IPTC/XMP → PostgreSQL)
                                                      └── ExifTool -fast2 (fallback for exotic RAW/maker notes)
```

**Processing status state machine:**
```
INSERT with processing_status = 'pending'
  → Worker claims job: UPDATE ... SET processing_status = 'processing'
    → Worker succeeds: UPDATE ... SET processing_status = 'done'
    → Worker fails:    UPDATE ... SET processing_status = 'failed'
```

**Worker job validation:** Before processing any Redis Stream job, the worker validates that the referenced `photo_id` exists in the database and `processing_status = 'pending'`. Jobs referencing non-existent photos or photos in other states are `XACK`'d and discarded. This prevents processing of injected or stale job messages.

**Worker startup recovery:** On startup, the worker re-enqueues all rows where `processing_status IN ('pending', 'processing') AND deleted_at IS NULL`. This is the authoritative recovery path for any Redis persistence gap — PostgreSQL is the source of truth for unprocessed jobs.

**Consumer group configuration:**
```
XGROUP CREATE photo-jobs processors $ MKSTREAM
XGROUP CREATE delete-jobs cleanup    $ MKSTREAM
```

- The worker uses `XREADGROUP` (not `XREAD`) to consume jobs.
- `XACK` is sent only after the job completes successfully — in-flight jobs are never silently lost.
- A Spring `@Scheduled` task runs `XAUTOCLAIM` periodically (every 5 minutes) to reclaim messages pending longer than 5 minutes, indicating a crashed consumer. Reclaimed messages are reprocessed.
- Pending entry count is monitored (see Section 7 — Monitoring). Alert if pending entries exceed 50 for more than 10 minutes.

A dedicated `ThreadPoolTaskExecutor` (configurable max workers, e.g. 4) bounds concurrency for libraw/libvips/ExifTool processes.

---

## Section 6: Sharing

- Libraries are private by default.
- Users can generate share tokens for albums, collections, or individual photos.
- Share links default to 30-day expiry; users can explicitly create permanent links.
- The `/share/:token` route is accessible without authentication, scoped strictly to the shared resource.

### Share Token Security

**Token generation:**
```java
byte[] bytes = new byte[32];
new SecureRandom().nextBytes(bytes);
String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 43 chars
```
256-bit entropy — enumeration is computationally infeasible.

**Storage:**
Only `SHA-256(token)` is stored in `shares.token_hash`. The plaintext token is returned once at creation and never stored. A database dump does not expose active share links.

**Lookup:**
On `/share/:token`, hash the incoming token and query `WHERE token_hash = SHA-256(?)`.

**Default expiry:**
`expires_at` defaults to `now() + interval '30 days'` (configurable via `app.default-share-days = 30`). Users can explicitly set `expires_at = NULL` when creating a share to make it permanent. Expired shares are checked at access time — no background cleanup required.

**Manage Shares UI (Phase 5):**
The `/settings` page includes a "Manage Shares" section listing all active share links with creation date, expiry, and resource. Users can revoke individual shares or bulk-revoke.

**GPS metadata in shared photos:**
GPS coordinates are **stripped** from EXIF data served via public share links by default. The share creator can opt-in to including location data per share link (`include_gps BOOLEAN DEFAULT FALSE` on `shares`). A user-level setting (`/settings`) controls whether GPS data is displayed in the owner's own metadata panel (stored but hidden by default for privacy-conscious users).

> **Future consideration:** Password-protected share links are deferred. The 256-bit token provides sufficient access control. If needed, design will include: `password_hash` column on `shares`, bcrypt hashing, rate-limited password verification endpoint.

**Share to deleted photo:**
Share lookup joins `photos` and filters `deleted_at IS NULL`. Accessing a share whose photo is in Trash returns 404 with a user-facing message.

---

## Section 7: Deployment

### Docker Compose on VPS

```yaml
services:
  nginx:
    image: nginx:alpine
    ports: ["80:80", "443:443"]
    volumes:
      - ./react-build:/usr/share/nginx/html
      - ./nginx.conf:/etc/nginx/nginx.conf
      - certbot_certs:/etc/letsencrypt:ro
    depends_on: [api]

  api:
    build: ./api
    environment:
      DB_URL, DB_USER, DB_PASS
      MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY
      JWT_SECRET, REDIS_URL, REDIS_PASSWORD
      GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET
      SMTP_HOST, SMTP_USER, SMTP_PASS
    depends_on:
      postgres: { condition: service_healthy }
      minio:    { condition: service_healthy }
      redis:    { condition: service_healthy }

  worker:
    build: ./worker
    environment:
      DB_URL, WORKER_DB_USER, WORKER_DB_PASS   # restricted role — not the api DB user
      MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY
      REDIS_URL, REDIS_PASSWORD
    depends_on:
      postgres: { condition: service_healthy }
      minio:    { condition: service_healthy }
      redis:    { condition: service_healthy }
    # No exposed ports — inbound traffic not accepted
    # Runs as non-root unprivileged user (set in Dockerfile)
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    read_only: true
    tmpfs:
      - /tmp:size=512M    # Working directory for image processing

  postgres:
    image: postgres:16
    volumes: [postgres_data:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER"]
      interval: 5s
      timeout: 5s
      retries: 10

  minio:
    image: minio/minio
    volumes: [minio_data:/data]
    command: server /data
    environment:
      MINIO_BROWSER: "off"   # Disable admin console in production — all admin via mc CLI
    # No ports exposed externally — traffic routed via Nginx proxy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 5s
      timeout: 5s
      retries: 10

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD} --appendonly yes --appendfsync everysec
    # --appendonly yes: AOF persistence — at most 1 second of data loss on unclean shutdown
    # --appendfsync everysec: fsync every second (good balance of durability and performance)
    volumes: [redis_data:/data]
    healthcheck:
      test: ["CMD-SHELL", "REDISCLI_AUTH=$$REDIS_PASSWORD redis-cli ping"]
      interval: 5s
      timeout: 5s
      retries: 10

  backup:
    image: minio/mc
    restart: unless-stopped
    entrypoint: >
      /bin/sh -c "
        mc alias set minio http://minio:9000 $$MINIO_ACCESS_KEY $$MINIO_SECRET_KEY &&
        mc alias set b2 https://s3.us-west-004.backblazeb2.com $$B2_ACCESS_KEY $$B2_SECRET_KEY &&
        while true; do
          mc mirror minio/jpt-photos b2/jpt-photos-backup;
          sleep 3600;
        done"
    environment:
      MINIO_ACCESS_KEY, MINIO_SECRET_KEY
      B2_BACKUP_ACCESS_KEY, B2_BACKUP_SECRET_KEY  # Write-only B2 credentials (no delete permission)
    depends_on:
      minio: { condition: service_healthy }

  pgbackup:
    build: ./pgbackup    # Custom image: FROM postgres:16 + RUN apt-get install -y restic=X.Y.Z
    restart: unless-stopped
    user: "1000:1000"    # Non-root user
    entrypoint: >
      /bin/sh -c "
        while true; do
          PGPASSWORD=$$DB_PASS pg_dump -h postgres -U $$DB_USER $$DB_NAME |
          restic backup --stdin --stdin-filename postgres.sql
            -r b2:jpt-db-backup --password-file /run/secrets/restic_pass;
          restic forget --prune --keep-daily 30 --keep-weekly 12
            -r b2:jpt-db-backup --password-file /run/secrets/restic_pass;
          sleep 86400;
        done"
    environment:
      DB_USER, DB_PASS, DB_NAME
      B2_ACCOUNT_ID, B2_ACCOUNT_KEY
    secrets: [restic_pass]
    depends_on:
      postgres: { condition: service_healthy }

secrets:
  restic_pass:
    file: ./secrets/restic_pass.txt   # Generated once: openssl rand -base64 32 > secrets/restic_pass.txt
    # Provisioned on VPS before first docker compose up; not committed to git

  certbot:
    image: certbot/certbot
    volumes:
      - certbot_certs:/etc/letsencrypt
      - certbot_www:/var/www/certbot
    entrypoint: >
      /bin/sh -c "trap exit TERM; while :; do
        certbot renew --webroot -w /var/www/certbot;
        sleep 12h & wait $${!};
      done"

  prometheus:
    image: prom/prometheus:latest
    # No ports exposed externally — access via SSH tunnel only:
    #   ssh -L 9090:prometheus:9090 user@vps
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    networks: [internal]

  grafana:
    image: grafana/grafana:latest
    # No ports exposed externally — access via SSH tunnel only:
    #   ssh -L 3000:grafana:3000 user@vps
    volumes: [grafana_data:/var/lib/grafana]
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD}
    depends_on: [prometheus]
    networks: [internal]

networks:
  internal:
    internal: true   # Prometheus and Grafana are unreachable from the host network
```

### Nginx Configuration

```nginx
http {
    # Rate limiting zones — MUST be in http{} context, not server{} (nginx requirement)
    limit_req_zone $binary_remote_addr zone=login:10m    rate=10r/m;
    limit_req_zone $binary_remote_addr zone=register:10m rate=5r/m;
    limit_req_zone $binary_remote_addr zone=share:10m    rate=60r/m;
    limit_req_zone $binary_remote_addr zone=auth:10m     rate=20r/m;   # catch-all for /api/auth/

    # HTTP → HTTPS redirect
    server {
        listen 80;
        server_name app.example.com;
        location /.well-known/acme-challenge/ {
            root /var/www/certbot;
        }
        location / {
            return 301 https://$host$request_uri;
        }
    }

    server {
        listen 443 ssl;
        server_name app.example.com;

        ssl_certificate     /etc/letsencrypt/live/app.example.com/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/app.example.com/privkey.pem;

        # Security headers
        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
        add_header X-Content-Type-Options    "nosniff" always;
        add_header X-Frame-Options           "DENY" always;
        add_header Referrer-Policy           "strict-origin-when-cross-origin" always;
        add_header Content-Security-Policy
          "default-src 'self'; img-src 'self' blob: data:; script-src 'self'; style-src 'self' 'unsafe-inline';"
          always;
        # Note: style-src 'unsafe-inline' is required for Tailwind/shadcn dynamic class injection.
        # Review if CSP nonces become feasible in a future iteration.

        # Static React app
        location / {
            root /usr/share/nginx/html;
            try_files $uri /index.html;
        }

        # Spring Boot API — large uploads allowed (RAW files up to ~100 MB)
        # Note: proxy_pass with trailing slash strips the /api/ prefix.
        # Spring Boot controllers mount at post-rewrite paths (e.g., /auth/login, not /api/auth/login).
        location /api/ {
            proxy_pass http://api:8080/;
            client_max_body_size 250m;
        }

        location /api/auth/login    { limit_req zone=login    burst=5 nodelay;
                                       proxy_pass http://api:8080/auth/login; }
        location /api/auth/register { limit_req zone=register burst=3 nodelay;
                                       proxy_pass http://api:8080/auth/register; }

        # Catch-all rate limit for /api/auth/ — protects future auth endpoints
        location /api/auth/ { limit_req zone=auth burst=10 nodelay;
                               proxy_pass http://api:8080/auth/; }

        # Public share links — rate limited to prevent token enumeration
        location /share/ {
            limit_req zone=share burst=10 nodelay;
            root /usr/share/nginx/html;
            try_files $uri /index.html;
        }

        # MinIO object storage — proxied, not exposed directly
        # HARD REQUIREMENT: MinIO bucket policy MUST be private (no anonymous access).
        # Pre-signed URLs are the sole access path. Do not set public-read on jpt-photos bucket.
        location ~ ^/photos/[a-f0-9-]+/(originals|thumbnails)/[a-f0-9-]+ {
            proxy_pass http://minio:9000;
            proxy_set_header Host $host;
            proxy_set_header Authorization "";  # Strip ambient credentials at proxy layer
        }
    }
}
```

**Nginx config validation:** `nginx -t` must be included in the CI pipeline to catch configuration errors before deployment.

**Path rewriting note:** The trailing slash in `proxy_pass http://api:8080/` strips the `/api/` prefix. Spring Boot controllers mount at post-rewrite paths: `/auth/login`, `/auth/register`, `/photos/{id}`, etc. — not `/api/auth/login`. The catch-all `/api/auth/` location ensures all current and future auth endpoints are rate-limited.

Pre-signed URLs are generated against the public domain (e.g. `https://app.example.com/photos/...`). `MINIO_SERVER_URL=https://app.example.com` must be set in the MinIO environment so generated URLs embed the correct public host.

**SSL:** Certbot (Let's Encrypt) with Nginx — free, auto-renewing certificates. Certbot runs as a sidecar container.

### Backups

**PostgreSQL:** The `pgbackup` sidecar runs `pg_dump` piped to `restic` daily, stored in Backblaze B2 bucket `jpt-db-backup`. Restic provides deduplication, encryption, and retention management.

**MinIO:** The `backup` sidecar container runs `mc mirror` hourly (without `--remove` — deletions are **not** propagated to backups):

```sh
# Hourly incremental mirror via mc (runs in backup sidecar)
mc mirror minio/jpt-photos b2/jpt-photos-backup

# DR fallback: filesystem snapshot (MinIO stopped — manual procedure)
docker stop minio && restic backup /var/lib/docker/volumes/minio_data && docker start minio
```

**B2 bucket versioning and retention:**
- `jpt-photos-backup`: versioning enabled; **B2 Object Lock** enabled (immutable retention) — prevents deletion even with valid credentials; **90-day lifecycle rule** configured — object versions older than 90 days are automatically expired. Configure via B2 lifecycle rules (B2 CLI: `b2 update-bucket --lifecycleRules ...`).
- `jpt-db-backup`: managed by restic's `restic forget --prune --keep-daily 30 --keep-weekly 12` policy (runs after each daily backup in the pgbackup container loop).

**Backup security:**
- The backup sidecar uses **separate B2 credentials** (`B2_BACKUP_ACCESS_KEY` / `B2_BACKUP_SECRET_KEY`) with **write-only permissions** (no delete). Even if MinIO credentials are compromised and objects are deleted, the attacker cannot propagate deletions to B2.
- **Monitoring:** Alert on bulk delete operations in MinIO audit logs (threshold: >100 deletes in 5 minutes).

**Recovery from soft-delete accident:** MinIO object versions are retained for 90 days. Deleted objects restored from B2 versioning: `mc cp --version-id <id> b2/jpt-photos-backup/... minio/jpt-photos/...`.

### Monitoring

**Application metrics:** Spring Boot Actuator + Micrometer exports metrics to Prometheus. Grafana provides dashboards.

**Access:** Prometheus (port 9090) and Grafana (port 3000) are restricted to the Docker internal network. Access via SSH port forwarding:
```sh
ssh -L 3000:grafana:3000 -L 9090:prometheus:9090 user@vps
# then open http://localhost:3000 in browser
```

**Baseline alerts (four rules):**

| Alert | Threshold | Severity |
|---|---|---|
| VPS disk usage | >80% of volume | Warning — MinIO data volume approaching full |
| Redis memory | >80% of `maxmemory` | Warning |
| Redis Streams pending entries | >50 entries for >10 min | Critical — worker jobs stuck, manual intervention needed |
| API 5xx error rate | >1% of requests over 5 min | Critical |

Alerts delivered via email (SMTP already configured for registration emails).

### Hyperscaler Migration Path

No code changes required — only environment variable changes:

| Component | Self-hosted | Hyperscaler |
|---|---|---|
| PostgreSQL | Docker container | AWS RDS / GCP Cloud SQL |
| MinIO | Docker container | AWS S3 / GCP GCS (S3-compatible API) |
| Redis | Docker container | AWS ElastiCache |
| API | Docker container | ECS / Cloud Run (same image) |
| Worker | Docker container | ECS / Cloud Run (same image) |

---

## Section 8: Testing Strategy

### Backend (JUnit 5 + Testcontainers)

- **Unit tests:** Domain logic, metadata parsing, keyword hierarchy, service layer ownership checks, share token validation.
- **Search tests:** PostgreSQL FTS and JSONB query correctness tested via Testcontainers (real PostgreSQL) — keyword search, filename search, caption/title/description search, EXIF field queries with numeric cast.
- **Integration tests:** Testcontainers spins up real PostgreSQL + MinIO + Redis in Docker. Upload pipeline, metadata extraction, and thumbnail generation tested end-to-end against real services.
- **Security tests:**
  - Verify every endpoint rejects unauthenticated requests.
  - Verify user A cannot access user B's photos.
  - Share token scope enforcement.
  - **RLS reuse test:** Simulate HikariCP connection reuse — verify that after User A's request, a reused connection cannot read User A's data as User B. Confirms `SET LOCAL` resets correctly on transaction boundary.
  - **Worker DB user test:** Verify `worker_db_user` cannot read `users` table, cannot modify `shares`, cannot write to `saved_searches`.
  - **Share token hash test:** Verify that the stored value in `shares.token_hash` is the SHA-256 hash of the token, not the plaintext.
- **Deletion tests:**
  - Soft delete sets `deleted_at`; photo no longer appears in library queries.
  - Trash view returns soft-deleted photos.
  - Restore clears `deleted_at`; photo reappears in library.
  - Quota is decremented on soft-delete and re-incremented on restore.
  - Share to soft-deleted photo returns 404.
  - Permanent purge enqueues MinIO delete-job and removes the DB row.
- **Processing status tests:**
  - Upload sets `processing_status = 'pending'`.
  - Worker startup re-enqueues `pending`/`processing` rows.
  - Polling endpoint returns correct status at each state.

### Frontend (Vitest + Playwright)

- **Unit tests (Vitest + React Testing Library):** Component behaviour — forms, validation, error states. Behaviour tests, not snapshot tests.
- **E2E tests (Playwright):** Critical user journeys — register → upload RAW photo → tag → share → view share link. Runs against a local Docker Compose stack.

### CI Pipeline

```
On every PR:
  1. Gradle build + unit tests (api/ and worker/ modules)
  2. Integration tests (Testcontainers)
  3. Frontend tests (Vitest)
  4. Playwright E2E (required before merge)

On merge to master:
  5. npm run build (React production build)
  6. nginx -t (validate Nginx config)
  7. Sign artifacts: generate SHA-256 checksums for JAR + React bundle; sign with CI signing key
  8. rsync react-build/ + signed JARs to VPS via dedicated SSH deploy key (not a user key)
  9. On VPS: verify artifact signatures before proceeding
  10. docker compose build api worker (rebuild images with new JARs)
  11. docker compose up -d --no-deps api worker
  12. Post-deploy healthcheck: curl /actuator/health for 60s; on failure → rollback to previous image tag
  13. docker compose exec nginx nginx -s reload
```

**Deployment model:** CI builds JARs and React bundles locally, rsyncs them to the VPS. The VPS rebuilds Docker images (`docker compose build`) to bake the JARs into the images (not volume-mounted). This ensures images are self-contained and reproducible.

**SSH deploy key:** A dedicated SSH key pair is generated for CI→VPS deployment. The private key is stored as a CI secret; the public key is added to the VPS deploy user's `authorized_keys` with `command=` restrictions limiting it to rsync and docker compose operations.

CI via GitHub Actions or self-hosted Forgejo.

---

## Section 9: Implementation Phases

| Phase | Focus | Key Deliverables |
|---|---|---|
| 0 | Java Upgrade | Gradle multi-module build (including `api/`, `worker/`, `shared/`), Java 21 compile, library updates, ImgRdr audit/replace — desktop app must pass full test suite before Phase 1 begins. Lucene module is not migrated. |
| 1 | Foundation | Spring Boot scaffold with `application.yml` multipart limits (200MB) and HikariCP pool sizes; PostgreSQL + Flyway schema (including `UNIQUE (user_id, content_hash)`, denormalized caption/title/description, `processing_status`, `deleted_at`); `worker_db_user` Flyway migration; Docker Compose stack (all services including worker, backup, pgbackup, prometheus, grafana with internal network); RLS policies with `SET LOCAL`; Redis AOF persistence |
| 2 | Backend API | Spring Security auth (JWT 15-min expiry, refresh tokens, OAuth2), rate limiting (Nginx + Bucket4j + `/share/` rate limit), core REST endpoints, Redis Streams consumer groups in worker, processing status polling endpoint (`GET /api/photos/{id}/status`) |
| 3 | Storage & Media | MinIO integration, Nginx MinIO proxy, streaming upload, libraw + libvips thumbnail pipeline, metadata-extractor + ExifTool fallback, Apache Tika validation, `processing_status` state transitions in worker, worker startup re-enqueue recovery |
| 4 | React Frontend | Auth flows, photo grid, single photo view, metadata panel (EXIF/IPTC/XMP), keyword tree, albums, Trash view, upload status polling UI |
| 5 | Sharing & Polish | Share tokens (256-bit SecureRandom, SHA-256 storage, 30-day default expiry), public share views (GPS stripped by default), Manage Shares UI, storage quotas (`SELECT FOR UPDATE`), photo deletion (soft delete, restore, permanent purge, async MinIO cleanup), orphan reconciliation sweep, B2 lifecycle rules + Object Lock, monitoring dashboards and alerts |
