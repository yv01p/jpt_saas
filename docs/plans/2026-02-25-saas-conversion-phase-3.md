# JPhotoTagger SaaS Conversion — Phase 3: Storage & Media — MinIO, Upload Pipeline, Worker

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

### Task 3.1: MinIO Configuration & Service

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/config/MinioConfig.java`
- Create: `api/src/main/java/org/jphototagger/api/service/StorageService.java`

**Step 1: Write failing test — pre-signed URL generation**

```java
@Test
void generatePresignedUrl_returnsThumbnailUrlWith15MinExpiry() { }

@Test
void generatePresignedUrl_returnsOriginalUrlWith1HourExpiry() { }
```

**Step 2: Implement StorageService**

- Upload object to MinIO (streaming, no heap buffering)
- Generate pre-signed URLs (15 min thumbnails, 1 hour originals)
- Delete objects
- Bucket path layout: `{user_id}/originals/{photo_id}.{ext}`, `{user_id}/thumbnails/{photo_id}_{size}.jpg`

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: MinIO storage service with pre-signed URLs"
```

### Task 3.2: Upload Endpoint with Deduplication

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/controller/PhotoController.java`
- Modify: `api/src/main/java/org/jphototagger/api/service/PhotoService.java`

**Step 1: Flyway migration — fix unique constraint for soft-deleted re-uploads**

The V1 migration's `UNIQUE (user_id, content_hash)` constraint does not exclude soft-deleted rows. If a user deletes a photo (soft delete) and re-uploads the same file, the INSERT violates the constraint. Fix by replacing the full unique constraint with a partial unique index:

```sql
-- V4__fix_content_hash_unique_constraint.sql
ALTER TABLE photos DROP CONSTRAINT IF EXISTS photos_user_id_content_hash_key;
CREATE UNIQUE INDEX photos_user_content_hash_active_idx ON photos (user_id, content_hash) WHERE deleted_at IS NULL;
```

This allows re-uploading a file that was previously soft-deleted while still preventing duplicate active uploads.

**Step 2: Write failing tests**

```java
@Test
void upload_streamsToMinioAndEnqueuesJob() { }

@Test
void upload_rejectsDuplicateContentHash() { }

@Test
void upload_rejectsWhenQuotaExceeded() { }

@Test
void upload_concurrentDuplicatesHandledByDbConstraint() { }

@Test
void upload_rejectsUnverifiedUser() { }

@Test
void upload_allowsVerifiedUser() { }

@Test
void upload_succeedsAfterSoftDeletedDuplicate() { }
```

**Step 3: Implement upload endpoint**

- `POST /photos/upload` — multipart upload
- **Email verification gate:** Reject uploads from users where `email_verified = false` with `403 Forbidden` and message "Email verification required before uploading". This implements the design doc's soft-gating requirement (v4.0, [CR#5]).
- Compute SHA-256 while streaming
- Fast-path dedup check: query by (user_id, content_hash) WHERE deleted_at IS NULL
- DB-level dedup: catch `UniqueConstraintViolationException` → 409
- Quota check with `SELECT FOR UPDATE` on user row
- Stream to MinIO
- Insert `photos` row with `processing_status = 'pending'`
- Enqueue `photo-jobs` to Redis Streams

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: upload endpoint with dedup, quota enforcement, email gate, Redis job enqueue"
```

### Task 3.3: Worker — Spring Boot Application Scaffold

**Files:**
- Create: `worker/src/main/java/org/jphototagger/worker/JptWorkerApplication.java`
- Create: `worker/src/main/resources/application.yml`

**Step 1: Write failing test — worker context loads**

**Step 2: Implement worker application class**

**Step 3: Configure `application.yml` with restricted DB user and HikariCP pool size 5**

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: worker Spring Boot scaffold"
```

### Task 3.4: Worker — Redis Streams Consumer

**Files:**
- Create: `worker/src/main/java/org/jphototagger/worker/consumer/PhotoJobConsumer.java`
- Create: `worker/src/main/java/org/jphototagger/worker/consumer/DeleteJobConsumer.java`

**Step 1: Write failing test — consumer processes job**

```java
@Test
void photoJobConsumer_updatesStatusToProcessing() { }

@Test
void photoJobConsumer_validatesPhotoExistsBeforeProcessing() { }

@Test
void photoJobConsumer_discardsJobForNonExistentPhoto() { }
```

**Step 2: Implement PhotoJobConsumer**

- `XREADGROUP` on `photo-jobs` stream
- Validate photo_id exists and status is `pending`
- Update status to `processing`
- Call processing pipeline (Task 3.5)
- Update status to `done` or `failed`
- `XACK` on success

**Step 3: Implement DeleteJobConsumer**

- Consume `delete-jobs`
- Delete MinIO objects by storage key
- `XACK`

**Step 4: Implement XAUTOCLAIM recovery (scheduled every 5 min)**

**Step 5: Implement startup re-enqueue recovery**

Query all `pending`/`processing` rows where `deleted_at IS NULL`, re-enqueue to Redis Streams.

**Step 6: Run tests, verify pass**

**Step 7: Commit**

```bash
git commit -m "feat: worker Redis Streams consumers with recovery"
```

### Task 3.5: Worker — Image Processing Pipeline

**Files:**
- Create: `worker/src/main/java/org/jphototagger/worker/pipeline/ImageProcessor.java`
- Create: `worker/src/main/java/org/jphototagger/worker/pipeline/TikaValidator.java`
- Create: `worker/src/main/java/org/jphototagger/worker/pipeline/ThumbnailGenerator.java`
- Create: `worker/src/main/java/org/jphototagger/worker/pipeline/MetadataExtractor.java`

**Step 1: Write failing tests**

```java
@Test
void tikaValidator_rejectsNonImageFile() { }

@Test
void tikaValidator_acceptsJpeg() { }

@Test
void thumbnailGenerator_createsSmAndMdThumbnails() { }

@Test
void metadataExtractor_extractsExifData() { }
```

**Step 2: Implement TikaValidator**

Apache Tika content-type check. Reject non-image files before any processing.

**Step 3: Implement ThumbnailGenerator**

- Download original from MinIO to tmpfs
- For RAW: `libraw` CLI via ProcessBuilder (extract embedded JPEG)
- `libvips` CLI via ProcessBuilder (resize to sm/md thumbnails)
- Upload thumbnails to MinIO
- All CLI calls use explicit argument arrays — never shell strings
- Files referenced by UUID storage keys only

**Step 4: Implement MetadataExtractor**

- `metadata-extractor` (Java) as primary
- ExifTool `-fast2` as fallback via ProcessBuilder
- Write extracted EXIF/IPTC/XMP to `photo_metadata` table as JSONB
- Populate `caption`, `title`, `description` on `photos` from IPTC/XMP

**Step 5: Wire into ImageProcessor pipeline**

Orchestrate: Tika → libraw → libvips → metadata-extractor → ExifTool fallback → update DB

**Step 6: Run tests, verify pass**

**Step 7: Commit**

```bash
git commit -m "feat: worker image processing pipeline — Tika, libraw, libvips, metadata"
```

### Task 3.6: Worker — Scheduled Tasks

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`
- Create: `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`
- Create: `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`
- Create: `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java`

**Step 1: Write failing tests**

```java
@Test
void trashPurge_deletesPhotosOlderThanRetentionWindow() { }

@Test
void trashPurge_enqueuesMinioDeleteJob() { }

@Test
void orphanReconciliation_detectsOrphanedMinioObjects() { }

@Test
void unverifiedPurge_deletesAccountsOlderThan7Days() { }

@Test
void unverifiedPurge_keepsVerifiedAccounts() { }
```

**Step 2: Implement TrashPurgeScheduler**

- `@Scheduled(cron = "0 0 3 * * *")` — daily at 3 AM
- Delete photos where `deleted_at < now() - retention_days`
- Enqueue `delete-job` for each purged photo
- Cascade deletes `photo_keywords`, `album_photos`, `shares`

**Step 3: Implement OrphanReconciliationScheduler**

- `@Scheduled(cron = "0 0 4 * * SUN")` — weekly
- Compare MinIO object listing vs DB `storage_key` values
- Enqueue unreferenced objects for deletion

**Step 4: Implement UnverifiedAccountPurgeScheduler**

- `@Scheduled(cron = "0 30 3 * * *")` — daily at 3:30 AM (after trash purge)
- Delete users where `email_verified = false` AND `created_at < now() - INTERVAL '7 days'`
- Cascade: delete all photos (enqueue MinIO delete jobs), keywords, albums, saved searches
- Uses the `authDataSource` (BYPASSRLS) since unverified user cleanup must access users table directly
- Implements design doc requirement (v4.0, [CR#5]): "unverified accounts soft-gated (no uploads) with 7-day auto-purge"

**Step 5: Run tests, verify pass**

**Step 6: Commit**

```bash
git commit -m "feat: scheduled tasks — trash purge, orphan reconciliation, unverified account purge"
```

---

**Next Phase:** [Phase 4: React Frontend](2026-02-25-saas-conversion-phase-4.md)
