# JPhotoTagger SaaS Conversion Design

**Date:** 2026-02-24
**Status:** Approved

## Overview

Convert JPhotoTagger from a single-user Java Swing desktop application into a multi-user web SaaS application. Target scale: a few thousand users. The existing Java domain/metadata/search modules are preserved and wrapped with a Spring Boot REST API. The Swing UI is discarded and replaced with a React web frontend.

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
│       │          │  │   Search, Thumbs)    │   │   │
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
├── search/           ← existing Lucene module — REWRITE for Lucene 9.x
├── thumbnails/       ← existing thumbnail logic — keep ImageMagick CLI approach
├── web/              ← NEW: Spring Boot app, REST controllers, security config
└── shared/           ← NEW: DTOs, shared utilities
```

The Swing UI modules are excluded from the new build — not migrated, not deleted (kept in git history), simply not referenced.

### Java Upgrade Strategy (parallel track)

The Java upgrade from 7 to 21 runs in parallel with the SaaS conversion work.

- **Phase 1 — Compile:** Get the project compiling on Java 21 with zero functional changes. Fix deprecations, remove `sun.*` usages, migrate build tooling to Gradle 8.
- **Phase 2 — Libraries:** Update each library to its current version. Lucene and ImgRdr are tackled here — ImgRdr replaced if found to be unmaintained.
- **Phase 3 — Modernise:** Adopt Java 21 idioms where beneficial: records for DTOs, virtual threads for I/O-heavy operations (photo uploads, thumbnail generation).

### Image Library Concerns

| Library | Risk | Notes |
|---|---|---|
| metadata-extractor | Low | Well-maintained, API review needed |
| XMPCore | Low | Modern versions support Java 21 |
| ImgRdr | **High** | Obscure/possibly unmaintained — investigate early, replace if needed |
| Lucene | **High** | Major API-breaking changes from 3.x/4.x to 9.x — search layer rewrite required |
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
users           (id, email, password_hash, oauth_provider, oauth_id, created_at)
photos          (id, user_id, filename, storage_key, size_bytes, taken_at, uploaded_at)
photo_metadata  (photo_id, exif_json, iptc_json, xmp_json)
keywords        (id, user_id, name, parent_id)   -- hierarchical, per-user
photo_keywords  (photo_id, keyword_id)
albums          (id, user_id, name, created_at)
album_photos    (album_id, photo_id)
shares          (id, user_id, resource_type, resource_id, token, expires_at, permissions)
saved_searches  (id, user_id, name, query_json)
```

Schema versioning managed by **Flyway** from day one.

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
- OAuth2 accounts linked by email — if a user registers with email then logs in with Google using the same address, accounts merge automatically.

### Authorization

- Every API endpoint is secured by default.
- Resource ownership checked at service layer: `photo.userId == currentUser.id` before any operation.
- Share tokens validated separately — public share links bypass user auth but are scoped strictly to the shared resource.

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
- Spring Boot streams directly to MinIO — never buffers full image in heap.
- Background metadata extraction after upload completes.

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
      JWT_SECRET, REDIS_URL
      GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET
      SMTP_HOST, SMTP_USER, SMTP_PASS
    depends_on: [postgres, minio, redis]

  postgres:
    image: postgres:16
    volumes: [postgres_data:/var/lib/postgresql/data]

  minio:
    image: minio/minio
    volumes: [minio_data:/data]
    command: server /data --console-address ":9001"

  redis:
    image: redis:7-alpine
    volumes: [redis_data:/data]
```

**SSL:** Caddy or Certbot (Let's Encrypt) — free, auto-renewing certificates.

**Backups:**
- PostgreSQL: daily `pg_dump` to offsite storage (Backblaze B2 via restic).
- MinIO: restic backup of the data volume to the same destination.

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

- **Unit tests:** Domain logic, metadata parsing, keyword hierarchy, search queries, service layer ownership checks, share token validation.
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
```

CI via GitHub Actions or self-hosted Forgejo.

---

## Section 9: Implementation Phases

| Phase | Focus | Key Deliverables |
|---|---|---|
| 1 | Foundation | Gradle multi-module build, Java 21 compile, library audit (ImgRdr, Lucene) |
| 2 | Backend API | Spring Boot, PostgreSQL + Flyway, Spring Security auth, core REST endpoints |
| 3 | Storage & Media | MinIO integration, streaming upload, thumbnail generation, metadata extraction pipeline |
| 4 | React Frontend | Auth flows, photo grid, single photo view, metadata panel, keyword tree, albums |
| 5 | Sharing & Polish | Share tokens, public share views, storage usage tracking, backups, monitoring |
