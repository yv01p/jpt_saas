# JPhotoTagger SaaS Conversion Design

**Date:** 2026-02-24
**Status:** Approved

## Overview

Convert JPhotoTagger from a single-user Java Swing desktop application into a multi-user web SaaS application. Target scale: a few thousand users. The existing Java domain, metadata, and repository modules are preserved and wrapped with a Spring Boot REST API. The Swing UI is discarded and replaced with a React web frontend. Search is handled by PostgreSQL full-text search and JSONB GIN indexes — the embedded Lucene module is not carried forward.

---

## Section 1: Overall Architecture

```
┌─────────────────────────────────────────────────────┐
│                      VPS                            │
│                                                     │
│  ┌──────────┐    ┌─────────────────────────────┐   │
│  │  Nginx   │───▶│   Spring Boot 3 (Java 21)   │   │
│  │ (reverse │    │                             │   │
│  │  proxy + │    │  ┌──────────────────────┐   │   │
│  │  static) │    │  │  Existing modules    │   │   │
│  └──────────┘    │  │  (Domain, Metadata,  │   │   │
│       │          │  │   Repositories,      │   │   │
│       │          │  │   Thumbs)            │   │   │
│  ┌────▼─────┐    │  └──────────────────────┘   │   │
│  │  React   │    │                             │   │
│  │  (built  │    │  REST API + WebSocket       │   │
│  │  static) │    └──────────────┬──────────────┘   │
│  └──────────┘                   │                  │
│                    ┌────────────┼────────────┐     │
│                    ▼            ▼            ▼     │
│              ┌──────────┐ ┌────────┐ ┌─────────┐  │
│              │PostgreSQL│ │ MinIO  │ │ Redis   │  │
│              │(metadata,│ │(photos,│ │(sessions│  │
│              │ users,   │ │ thumbs)│ │ cache)  │  │
│              │ shares)  │ └────────┘ └─────────┘  │
│              └──────────┘                         │
└─────────────────────────────────────────────────────┘
```

- **Nginx** serves the React app as static files and reverse-proxies API calls to Spring Boot.
- **MinIO** handles all binary storage (originals + thumbnails). It is S3-compatible, making hyperscaler migration trivial.
- **PostgreSQL** holds all metadata, user accounts, and sharing data.
- **Redis** handles session caching and rate limiting.
- Everything runs as Docker containers via Docker Compose.

---

## Section 2: Backend Structure & Java Upgrade Strategy

The existing codebase is reorganized into a Gradle multi-module project, replacing Ant/NetBeans.

```
jpt-saas/
├── api/              ← existing API module (interfaces) — migrate to Java 21
├── domain/           ← existing Domain module — migrate to Java 21
├── repositories/     ← existing Repositories module — PostgreSQL replaces HSQLDB
├── metadata/         ← existing Exif/IPTC/XMP modules — migrate + audit libraries
├── thumbnails/       ← existing thumbnail logic — keep ImageMagick CLI approach
├── web/              ← NEW: Spring Boot app, REST controllers, security config
└── shared/           ← NEW: DTOs, shared utilities
```

The Swing UI modules are excluded from the new build — not migrated, not deleted (kept in git history), simply not referenced.

### Java Upgrade Strategy (prerequisite track — must complete before SaaS conversion)

The Java upgrade from 7 to 21 is sequenced **before** the SaaS conversion, not in parallel. The existing desktop app serves as the regression baseline throughout the upgrade. SaaS conversion begins only on the verified-stable Java 21 codebase.

- **Phase 1 — Compile:** Get the project compiling on Java 21 with zero functional changes. Fix deprecations, remove `sun.*` usages, migrate build tooling to Gradle 8.
- **Phase 2 — Libraries:** Update each library to its current version. ImgRdr is tackled here — replaced if found to be unmaintained. The Lucene module is not migrated; search is replaced by PostgreSQL FTS in the SaaS layer.
- **Phase 3 — Modernise:** Adopt Java 21 idioms where beneficial: records for DTOs, virtual threads for I/O-heavy operations (photo uploads, thumbnail generation).
- **Validation gate:** The desktop app must pass its full existing test suite on Java 21 before SaaS conversion phases begin. This isolates upgrade bugs from multi-tenancy bugs.

### Image Library Concerns

| Library | Risk | Notes |
|---|---|---|
| metadata-extractor | Low | Well-maintained, API review needed |
| XMPCore | Low | Modern versions support Java 21 |
| ImgRdr | **High** | Obscure/possibly unmaintained — investigate early, replace if needed |
| ImageMagick (CLI) | None | External process, Java-version independent |

**Additional server-context concerns:**
- **Concurrency:** Some image libraries are not thread-safe. Must be verified under concurrent load.
- **Memory:** Java ImageIO loads full images into heap. Streaming upload directly to MinIO avoids buffering originals in memory.
- **HEIC/HEIF:** No native Java 21 support. Delegate to ImageMagick CLI for HEIC processing.

---

## Section 3: Data Layer

### PostgreSQL — row-level multi-tenancy

Every table includes a `user_id` column. All queries are scoped to the authenticated user, enforced at the repository layer.

```sql
users           (id, email, password_hash, oauth_provider, oauth_id, quota_bytes, used_bytes, created_at)
photos          (id, user_id, filename, storage_key, size_bytes, content_hash, taken_at, uploaded_at,
                 search_vector tsvector GENERATED ALWAYS AS (
                   to_tsvector('english', coalesce(filename,''))
                 ) STORED)
photo_metadata  (photo_id, exif_data jsonb, iptc_data jsonb, xmp_data jsonb)
keywords        (id, user_id, name, parent_id)   -- hierarchical adjacency list, per-user
photo_keywords  (photo_id, keyword_id)
albums          (id, user_id, name, created_at)
album_photos    (album_id, photo_id)
shares          (id, user_id, resource_type, resource_id, token, expires_at, permissions)
saved_searches  (id, user_id, name, query_json)
```

Schema versioning managed by **Flyway** from day one.

### Search — PostgreSQL FTS + JSONB GIN Indexes

Search is handled entirely within PostgreSQL. No separate search index or service.

**Full-text search** on filenames and extracted text fields via `tsvector`:
```sql
-- GIN index on the generated tsvector column
CREATE INDEX photos_search_idx ON photos USING GIN (search_vector);

-- Query
SELECT * FROM photos
WHERE user_id = ? AND search_vector @@ plainto_tsquery('english', ?);
```

**Metadata field queries** via JSONB GIN indexes:
```sql
-- GIN indexes on JSONB columns
CREATE INDEX photo_exif_gin ON photo_metadata USING GIN (exif_data);
CREATE INDEX photo_iptc_gin ON photo_metadata USING GIN (iptc_data);

-- Exact field match (e.g. camera model)
SELECT p.* FROM photos p
JOIN photo_metadata m ON m.photo_id = p.id
WHERE p.user_id = ? AND m.exif_data @> '{"Make": "Canon"}';

-- JSONB path query (e.g. ISO range)
WHERE m.exif_data->>'ISOSpeedRatings' BETWEEN '100' AND '400';
```

**Keyword search** queries the existing `keywords` + `photo_keywords` tables directly — no FTS needed. Subtree queries use recursive CTEs (`WITH RECURSIVE`).

> **Future consideration (4.2):** If keyword trees become deep (>5 levels) or recursive CTE performance degrades, evaluate PostgreSQL's `ltree` extension for efficient ancestor/descendant path queries. This is a Flyway migration to change the `parent_id` column to an `ltree` path.

This approach eliminates the Lucene module entirely, provides ACID consistency between data and search (no reindex lag), and is fully covered by the standard PostgreSQL backup.

> **Future consideration (4.1):** If query patterns reveal that a small set of EXIF fields (camera model, ISO, focal length, GPS) dominate search traffic, introduce a flat `photo_exif` table with typed columns for those fields via a Flyway migration. The `exif_data` JSONB blob is retained for the full raw payload. Don't do this speculatively — let actual query patterns drive the decision.

### Storage Quotas

`users.quota_bytes` defines the per-user storage limit (default configured via `app.default-quota-bytes`, e.g. 10 GB). `users.used_bytes` is maintained as a running total. Enforcement at the upload endpoint:

```
IF used_bytes + new_file_size > quota_bytes → reject with 413 (quota exceeded)
```

The check and `used_bytes` increment run inside a single serializable transaction to prevent race conditions with concurrent uploads. The `/settings` view displays current usage vs. quota.

### PostgreSQL Row Level Security (defense-in-depth)

RLS is enabled on all tenant tables (`photos`, `photo_metadata`, `keywords`, `photo_keywords`, `albums`, `album_photos`, `shares`, `saved_searches`). Policy:

```sql
CREATE POLICY tenant_isolation ON photos
  USING (user_id = current_setting('app.current_user_id')::uuid);
```

The application sets `app.current_user_id` on each connection checkout via a Spring `ConnectionPreparer` or Hibernate `SessionEventListener`. Application-layer `WHERE user_id = ?` enforcement remains; RLS is the inviolable safety net that catches any missed clause.

### MinIO — object storage layout

```
bucket: jpt-photos/
  {user_id}/originals/{photo_id}.{ext}
  {user_id}/thumbnails/{photo_id}_sm.jpg
  {user_id}/thumbnails/{photo_id}_md.jpg
```

Photos are delivered via **pre-signed URLs** — Spring Boot generates a time-limited URL, the React client fetches the image directly from MinIO. Large binary transfers never pass through the Spring Boot process.

---

## Section 4: Authentication

### Spring Security with JWT + OAuth2

```
Email/password → Spring Security → JWT issued → stored in httpOnly cookie
Google/GitHub  → OAuth2 callback → user created/matched → JWT issued → same cookie
```

- JWT stored in **httpOnly cookie** (not localStorage) — prevents XSS token theft.
- Refresh tokens stored in Redis with expiry.

### User Lifecycle

- Email registration includes email verification (link sent via SMTP).
- Password reset via email token.
- OAuth2 accounts are **never auto-merged by email**. If an OAuth login arrives for an email that already has a password account, the login is blocked and the user is shown: "An account with this email already exists. Log in with your password to link your Google account." Account linking is completed explicitly from the Settings page after the user authenticates with their existing credentials. Silent auto-merge is a known account pre-hijacking vector (OWASP).

### Authorization

- Every API endpoint is secured by default.
- Resource ownership checked at service layer: `photo.userId == currentUser.id` before any operation.
- Share tokens validated separately — public share links bypass user auth but are scoped strictly to the shared resource.

### CSRF Protection

JWT in an httpOnly cookie is still sent automatically on cross-site requests, so CSRF protection is required. Spring Security CSRF is **enabled** (never disabled for API endpoints). The **double-submit cookie pattern** is used:

- Spring Security sets a `XSRF-TOKEN` cookie (readable by JS, not httpOnly).
- The SPA reads it and includes it as an `X-XSRF-TOKEN` header on all mutating requests (POST, PUT, DELETE, PATCH).
- A TanStack Query request interceptor attaches the header globally.
- `CookieCsrfTokenRepository.withHttpOnlyFalse()` is configured in Spring Security — no custom implementation needed.

### Dependencies

```
spring-boot-starter-security
spring-boot-starter-oauth2-client
jjwt
spring-boot-starter-mail
spring-data-redis
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
/share/:token         — public share view (no login required)
/settings             — account, storage usage, linked OAuth accounts
```

### Performance

- Thumbnails served via pre-signed MinIO URLs — Spring Boot not in the binary path.
- Virtualized grid renders only visible rows, handles thousands of photos smoothly.
- Progressive loading: small thumbnail first, medium on hover.

### Upload Flow

- Chunked multipart upload to Spring Boot.
- Spring Boot computes SHA-256 (`content_hash`) while streaming — no extra pass needed.
- If `content_hash` already exists for this `user_id`, the upload is rejected with `409 Conflict` and the existing photo ID is returned. The file is never written to MinIO.
- Otherwise, Spring Boot streams directly to MinIO — never buffers full image in heap.
- On upload completion, a job is enqueued to Redis Streams.

> Note: deduplication is per-user only. Two users uploading the same file store separate MinIO objects — cross-user object sharing is out of scope (it complicates deletion and multi-tenancy).

### Async Job Processing

Post-upload work (thumbnail generation, metadata extraction) runs outside the HTTP request cycle via a dedicated async pipeline:

```
Upload API → Redis Streams (job queue) → JobConsumer (bounded thread pool)
                                              ├── ImageMagick CLI (thumbnails → MinIO)
                                              └── metadata-extractor (EXIF/IPTC/XMP → PostgreSQL)
```

- A dedicated `ThreadPoolTaskExecutor` (configurable max workers, e.g. 4) bounds OS-process concurrency from ImageMagick.
- Jobs survive API restarts — Redis Streams retains unconsumed entries.
- The upload API returns immediately after writing to MinIO; the UI polls or receives a WebSocket notification when processing completes.

---

## Section 6: Sharing

- Libraries are private by default.
- Users can generate share tokens for albums, collections, or individual photos.
- Share links can be time-limited and optionally password-protected.
- The `/share/:token` route is accessible without authentication, scoped strictly to the shared resource.

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
    depends_on: [api]

  api:
    build: ./backend
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
    command: server /data --console-address ":9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 5s
      timeout: 5s
      retries: 10

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD}
    volumes: [redis_data:/data]
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]
      interval: 5s
      timeout: 5s
      retries: 10
```

**SSL:** Certbot (Let's Encrypt) with Nginx — free, auto-renewing certificates. Certbot runs as a sidecar container, writing certificates to a shared volume mounted by Nginx. Caddy is not used; Nginx already handles both static serving and reverse proxying.

**Backups:**
- PostgreSQL: daily `pg_dump` to offsite storage (Backblaze B2 via restic).
- MinIO: use `mc mirror` (MinIO Client) for consistent incremental object replication to Backblaze B2 — this uses MinIO's own tooling and avoids snapshotting internal object layout mid-write. Raw filesystem backup via restic is retained as a disaster-recovery fallback only, performed with the MinIO container stopped.

```sh
# Primary: consistent incremental mirror via mc
mc mirror jpt-photos/ b2/jpt-photos-backup/ --remove --watch

# DR fallback: filesystem snapshot (MinIO stopped)
docker stop minio && restic backup /var/lib/docker/volumes/minio_data && docker start minio
```

### Hyperscaler Migration Path

No code changes required — only environment variable changes:

| Component | Self-hosted | Hyperscaler |
|---|---|---|
| PostgreSQL | Docker container | AWS RDS / GCP Cloud SQL |
| MinIO | Docker container | AWS S3 / GCP GCS (S3-compatible API) |
| Redis | Docker container | AWS ElastiCache |
| API | Docker container | ECS / Cloud Run (same image) |

---

## Section 8: Testing Strategy

### Backend (JUnit 5 + Testcontainers)

- **Unit tests:** Domain logic, metadata parsing, keyword hierarchy, service layer ownership checks, share token validation.
- **Search tests:** PostgreSQL FTS and JSONB query correctness tested via Testcontainers (real PostgreSQL) — keyword search, filename search, EXIF field queries.
- **Integration tests:** Testcontainers spins up real PostgreSQL + MinIO + Redis in Docker. Upload pipeline, metadata extraction, and thumbnail generation tested end-to-end against real services.
- **Security tests:** Verify every endpoint rejects unauthenticated requests; verify user A cannot access user B's photos; share token scope enforcement.

### Frontend (Vitest + Playwright)

- **Unit tests (Vitest + React Testing Library):** Component behaviour — forms, validation, error states. Behaviour tests, not snapshot tests.
- **E2E tests (Playwright):** Critical user journeys — register → upload photo → tag → share → view share link. Runs against a local Docker Compose stack.

### CI Pipeline

```
On every PR:
  1. Gradle build + unit tests
  2. Integration tests (Testcontainers)
  3. Frontend tests (Vitest)
  4. Playwright E2E (required before merge)

On merge to master:
  5. npm run build (React production build)
  6. rsync react-build/ to VPS:/srv/jpt/react-build/
  7. docker compose exec nginx nginx -s reload
  8. ./gradlew bootJar → rsync JAR to VPS → docker compose restart api
```

CI via GitHub Actions or self-hosted Forgejo.

---

## Section 9: Implementation Phases

| Phase | Focus | Key Deliverables |
|---|---|---|
| 0 | Java Upgrade | Gradle multi-module build, Java 21 compile, library updates, ImgRdr audit/replace — desktop app must pass full test suite before Phase 1 begins. Lucene module is not migrated. |
| 1 | Foundation | Spring Boot scaffold, PostgreSQL + Flyway schema, Docker Compose stack, RLS policies |
| 2 | Backend API | Spring Security auth, core REST endpoints, async job infrastructure (Redis Streams) |
| 3 | Storage & Media | MinIO integration, streaming upload, thumbnail pipeline, metadata extraction |
| 4 | React Frontend | Auth flows, photo grid, single photo view, metadata panel, keyword tree, albums |
| 5 | Sharing & Polish | Share tokens, public share views, storage quotas, backups, monitoring |
