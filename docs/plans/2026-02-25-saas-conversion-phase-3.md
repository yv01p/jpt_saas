# JPhotoTagger SaaS Conversion — Phase 3: Storage & Media — MinIO, Upload Pipeline, Worker

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

## Changelog

### v2.0 — 2026-03-04
Revised following critical implementation review (see `docs/plans/2026-02-25-saas-conversion-phase-3-critical-review-1.md`). All five critical issues and all fifteen minor issues addressed.

| ID | Change |
|----|--------|
| CI-1 | Renamed Flyway migration from `V4__` to `V5__` to avoid collision with existing `V4__create_jpt_auth_role.sql` |
| CI-2 | Added `minio.public-url` property; `StorageService` substitutes public URL into pre-signed URLs; added public-URL assertion test |
| CI-3 | Restructured upload transaction order to narrow the row lock scope; DB insert precedes MinIO upload; compensating transaction on MinIO failure |
| CI-4 | Added distributed Redis startup lock (`SET NX PX`) before recovery re-enqueue scan |
| CI-5 | Specified XAUTOCLAIM `min-idle-time = 30 min`; exposed as `worker.streams.claim-idle-time-ms` |
| MI-1 | Resolved by CI-3 (DB row inserted before MinIO upload; orphan reconciliation remains fallback) |
| MI-2 | All test stubs now require an assertion comment before implementation begins |
| MI-3 | Added `ProcessingStatus` enum step; used in JPA entity and all worker consumers |
| MI-4 | Added retry/dead-letter policy: max 3 retries then XACK + `processing_status = 'failed'` |
| MI-5 | All `ProcessBuilder` calls use `waitFor(5, TimeUnit.MINUTES)` + `destroyForcibly()` on timeout |
| MI-6 | Worker `docker-compose.yml` service specifies `tmpfs: - /tmp:size=1g` |
| MI-7 | `photo_metadata` writes use `INSERT ... ON CONFLICT (photo_id) DO UPDATE` |
| MI-8 | `TrashPurgeScheduler` enqueues delete-jobs via Lettuce pipeline, not N individual `XADD` calls |
| MI-9 | ShedLock added for all three schedulers |
| MI-10 | `UnverifiedAccountPurgeScheduler` explicitly orders: query keys → enqueue jobs → delete DB rows |
| MI-11 | Orphan reconciliation uses per-user MinIO prefix listing and `Stream<String>` DB cursor |
| MI-12 | Removed duplicate `Files:` block in Task 3.6 |
| MI-13 | Retention days sourced from `@Value("${jpt.trash.retention-days:30}")` |
| MI-14 | Consumer group names and stable consumer name (hostname + PID) documented |
| MI-15 | `delete-jobs` stream message schema defined: `photo_id`, `original_key`, `thumbnail_sm`, `thumbnail_md` |

### v1.0 — 2026-02-25
Initial plan.

---

### Task 3.1: MinIO Configuration & Service

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/config/MinioConfig.java`
- Create: `api/src/main/java/org/jphototagger/api/service/StorageService.java`

**Step 1: Write failing tests — pre-signed URL generation**

Each test stub must include a comment describing the expected assertion before implementation begins.

```java
@Test
void generatePresignedUrl_returnsThumbnailUrlWith15MinExpiry() {
    // assert URL expiry param is <= 900 seconds and URL starts with minio.public-url
}

@Test
void generatePresignedUrl_returnsOriginalUrlWith1HourExpiry() {
    // assert URL expiry param is <= 3600 seconds and URL starts with minio.public-url
}

@Test
void generatePresignedUrl_urlBeginsWithConfiguredPublicUrl() {
    // assert returned URL begins with the value of minio.public-url, not the internal hostname
}
```

**Step 2: Implement MinioConfig**

- Expose two properties:
  - `minio.url` — internal Docker hostname (e.g., `http://minio:9000`), used for server-to-server object upload/download
  - `minio.public-url` — Nginx-proxied public URL (e.g., `https://example.com`), used when generating pre-signed URLs returned to browsers
- Initialize `MinioClient` with `minio.url` for I/O operations
- `StorageService` substitutes `minio.public-url` into `GetPresignedObjectUrlArgs` before returning URLs to callers — never the internal endpoint

**Step 3: Implement StorageService**

- Upload object to MinIO (streaming, no heap buffering)
- Generate pre-signed URLs — substitute `minio.public-url` as base (15 min thumbnails, 1 hour originals)
- Delete objects
- Bucket path layout: `{user_id}/originals/{photo_id}.{ext}`, `{user_id}/thumbnails/{photo_id}_sm.jpg`, `{user_id}/thumbnails/{photo_id}_md.jpg`

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: MinIO storage service with pre-signed URLs and public URL substitution"
```

---

### Task 3.2: Upload Endpoint with Deduplication

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/controller/PhotoController.java`
- Modify: `api/src/main/java/org/jphototagger/api/service/PhotoService.java`

**Step 1: Define `ProcessingStatus` enum**

Define a shared enum accessible to both API and worker modules:

```java
public enum ProcessingStatus {
    PENDING, PROCESSING, DONE, FAILED;

    public String toDbValue() {
        return name().toLowerCase();
    }
}
```

Use `@Enumerated(EnumType.STRING)` on the `Photo` JPA entity. Use this enum (not raw strings) everywhere `processing_status` is read or written.

**Step 2: Flyway migration — fix unique constraint for soft-deleted re-uploads**

The V1 migration's `UNIQUE (user_id, content_hash)` constraint does not exclude soft-deleted rows. Fix by replacing the full unique constraint with a partial unique index:

```sql
-- V5__fix_content_hash_unique_constraint.sql
ALTER TABLE photos DROP CONSTRAINT IF EXISTS photos_user_id_content_hash_key;
CREATE UNIQUE INDEX photos_user_content_hash_active_idx ON photos (user_id, content_hash) WHERE deleted_at IS NULL;
```

This allows re-uploading a file that was previously soft-deleted while still preventing duplicate active uploads.

**Step 3: Write failing tests**

Each test stub must include a comment describing the expected assertion before implementation begins.

```java
@Test
void upload_streamsToMinioAndEnqueuesJob() {
    // assert HTTP 200, photo row created with processing_status=PENDING, Redis job enqueued
}

@Test
void upload_rejectsDuplicateContentHash() {
    // assert HTTP 409 when (user_id, content_hash) active row already exists
}

@Test
void upload_rejectsWhenQuotaExceeded() {
    // assert HTTP 403 when used_bytes + file_size > quota_bytes
}

@Test
void upload_concurrentDuplicatesHandledByDbConstraint() {
    // assert HTTP 409 when UniqueConstraintViolationException is caught from concurrent insert
}

@Test
void upload_rejectsUnverifiedUser() {
    // assert HTTP 403 with message "Email verification required before uploading"
}

@Test
void upload_allowsVerifiedUser() {
    // assert HTTP 200 for user with email_verified=true
}

@Test
void upload_succeedsAfterSoftDeletedDuplicate() {
    // assert HTTP 200 when same content_hash exists with deleted_at IS NOT NULL
}

@Test
void upload_minioFailureRollsBackQuotaAndPhotoRow() {
    // assert used_bytes unchanged and photo row absent when MinIO upload throws
}
```

**Step 4: Implement upload endpoint**

- `POST /photos/upload` — multipart upload
- **Email verification gate:** Reject uploads from users where `email_verified = false` with `403 Forbidden` and message "Email verification required before uploading" (design doc v4.0, [CR#5]).
- **Transaction order — keep the row lock as narrow as possible:**
  1. *(No transaction)* Stream request body; compute SHA-256 hash
  2. *(No transaction)* Fast-path dedup check — `SELECT` by `(user_id, content_hash) WHERE deleted_at IS NULL` (read-only, no lock)
  3. **Tx 1 (milliseconds):** `SELECT FOR UPDATE` on user row → validate quota → `INSERT INTO photos` (`processing_status = PENDING`, no `storage_key` yet) → increment `used_bytes` → **commit**
  4. *(No transaction)* Upload to MinIO
  5. **On MinIO failure:** compensating Tx — delete the photo row, decrement `used_bytes`, return 500
  6. **Tx 2 (milliseconds):** `UPDATE photos SET storage_key = ?, processing_status = PENDING WHERE id = ?` → **commit**
  7. *(No transaction)* Enqueue `photo-jobs` to Redis Streams
- DB-level dedup: catch `UniqueConstraintViolationException` → 409
- This order ensures: (a) the row lock is held for milliseconds not seconds, (b) a DB row always exists before any MinIO object, so orphan reconciliation can always find and clean up on failure

**Step 5: Run tests, verify pass**

**Step 6: Commit**

```bash
git commit -m "feat: upload endpoint with dedup, quota enforcement, email gate, Redis job enqueue"
```

---

### Task 3.3: Worker — Spring Boot Application Scaffold

**Files:**
- Create: `worker/src/main/java/org/jphototagger/worker/JptWorkerApplication.java`
- Create: `worker/src/main/resources/application.yml`

**Step 1: Write failing test — worker context loads**

**Step 2: Implement worker application class**

**Step 3: Configure `application.yml`**

```yaml
worker:
  streams:
    claim-idle-time-ms: 1800000   # 30 minutes — must exceed worst-case RAW processing time
    max-retries: 3                 # Dead-letter after this many delivery attempts
jpt:
  trash:
    retention-days: 30
```

Also configure restricted DB user and HikariCP pool size 5.

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: worker Spring Boot scaffold"
```

---

### Task 3.4: Worker — Redis Streams Consumer

**Files:**
- Create: `worker/src/main/java/org/jphototagger/worker/consumer/PhotoJobConsumer.java`
- Create: `worker/src/main/java/org/jphototagger/worker/consumer/DeleteJobConsumer.java`

**Consumer group and consumer name:**
- `photo-jobs` stream group: `photo-processors`
- `delete-jobs` stream group: `delete-processors`
- Consumer name: `InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid()` — stable across restarts, unique per instance, logged at startup
- Groups created with `XGROUP CREATE ... MKSTREAM` on startup if they don't exist

**`delete-jobs` stream message schema:**
```
photo_id      — UUID string
original_key  — e.g., {user_id}/originals/{photo_id}.{ext}
thumbnail_sm  — e.g., {user_id}/thumbnails/{photo_id}_sm.jpg
thumbnail_md  — e.g., {user_id}/thumbnails/{photo_id}_md.jpg
```
Producer (TrashPurgeScheduler and UnverifiedAccountPurgeScheduler) must populate all four fields. Consumer deletes all three MinIO keys per message.

**Step 1: Write failing tests**

```java
@Test
void photoJobConsumer_updatesStatusToProcessing() {
    // assert processing_status=PROCESSING set before pipeline starts
}

@Test
void photoJobConsumer_validatesPhotoExistsBeforeProcessing() {
    // assert job is discarded without processing when photo not found
}

@Test
void photoJobConsumer_discardsJobForNonExistentPhoto() {
    // assert XACK called and no processing attempted for unknown photo_id
}

@Test
void photoJobConsumer_setsStatusFailedAfterMaxRetries() {
    // assert processing_status=FAILED and XACK called after delivery count >= MAX_RETRIES
}

@Test
void deleteJobConsumer_deletesOriginalAndAllThumbnails() {
    // assert all three MinIO keys (original_key, thumbnail_sm, thumbnail_md) are deleted
}

@Test
void xautoclaim_doesNotReclaimRecentlyProcessedMessages() {
    // assert messages idle < claim-idle-time-ms are not claimed
}

@Test
void startupRecovery_onlyOneInstanceReenqueuesWhenLockContested() {
    // assert only the Redis lock holder performs recovery; others skip
}

@Test
void consumer_usesStableConsumerNameAcrossRestarts() {
    // assert consumer name is hostname+PID and consistent within a process lifecycle
}
```

**Step 2: Implement PhotoJobConsumer**

- `XREADGROUP` on `photo-jobs` stream using group `photo-processors` and stable consumer name
- Validate `photo_id` exists and status is `PENDING`
- Update status to `PROCESSING`
- Call processing pipeline (Task 3.5)
- Update status to `DONE` or `FAILED`
- `XACK` on success
- **Retry / dead-letter policy:** On failure, check the message delivery count from `XPENDING`. If `deliveryCount >= MAX_RETRIES` (default 3, from `worker.streams.max-retries`): set `processing_status = FAILED` in DB, XACK the message (removes from PEL), optionally XADD to `dead-letter` stream for inspection. If under the retry limit, leave unacknowledged — XAUTOCLAIM will retry after the idle window.

**Step 3: Implement DeleteJobConsumer**

- `XREADGROUP` on `delete-jobs` stream using group `delete-processors`
- Parse all four message fields: `photo_id`, `original_key`, `thumbnail_sm`, `thumbnail_md`
- Delete all three MinIO keys per message
- `XACK`

**Step 4: Implement XAUTOCLAIM recovery (scheduled every 5 min)**

- `min-idle-time` = `${worker.streams.claim-idle-time-ms:1800000}` (default 30 minutes)
- **Rationale:** 30 minutes safely exceeds the worst-case RAW processing time (Tika → libraw → libvips → metadata-extractor). A message idle longer than this indicates a hung or crashed worker — reclaiming it is correct. If the idle time were shorter than processing time, a slow but healthy worker would have its in-progress message stolen, causing duplicate concurrent processing.

**Step 5: Implement startup re-enqueue recovery**

- **Acquire distributed lock first:** `SET worker:startup-recovery-lock {instanceId} NX PX 60000`
- Only the instance that acquires the lock performs the recovery scan
- Instances that do not acquire the lock skip recovery and log accordingly
- Lock holder: query all `pending`/`processing` rows where `deleted_at IS NULL`, re-enqueue to Redis Streams

**Step 6: Run tests, verify pass**

**Step 7: Commit**

```bash
git commit -m "feat: worker Redis Streams consumers with retry, dead-letter, and recovery"
```

---

### Task 3.5: Worker — Image Processing Pipeline

**Files:**
- Create: `worker/src/main/java/org/jphototagger/worker/pipeline/ImageProcessor.java`
- Create: `worker/src/main/java/org/jphototagger/worker/pipeline/TikaValidator.java`
- Create: `worker/src/main/java/org/jphototagger/worker/pipeline/ThumbnailGenerator.java`
- Create: `worker/src/main/java/org/jphototagger/worker/pipeline/MetadataExtractor.java`

**Step 1: Write failing tests**

```java
@Test
void tikaValidator_rejectsNonImageFile() {
    // assert ProcessingException thrown for non-image MIME type
}

@Test
void tikaValidator_acceptsJpeg() {
    // assert no exception for image/jpeg
}

@Test
void thumbnailGenerator_createsSmAndMdThumbnails() {
    // assert two thumbnail objects uploaded to MinIO with _sm and _md suffixes
}

@Test
void thumbnailGenerator_failsCleanlyOnProcessTimeout() {
    // assert ProcessTimeoutException thrown and process destroyed when tool exceeds timeout
}

@Test
void metadataExtractor_extractsExifData() {
    // assert photo_metadata row contains expected EXIF fields from test fixture
}

@Test
void metadataExtractor_upsertSucceedsOnReprocessing() {
    // assert no exception when photo_metadata row already exists; row is updated not duplicated
}
```

**Step 2: Implement TikaValidator**

Apache Tika content-type check. Reject non-image files before any processing.

**Step 3: Implement ThumbnailGenerator**

- Download original from MinIO to tmpfs (`/tmp` — see docker-compose tmpfs note below)
- For RAW: `libraw` CLI via ProcessBuilder (extract embedded JPEG)
- `libvips` CLI via ProcessBuilder (resize to sm/md thumbnails)
- Upload thumbnails to MinIO
- All CLI calls use explicit argument arrays — never shell strings
- Files referenced by UUID storage keys only
- **ProcessBuilder timeout:** All tool invocations use `process.waitFor(5, TimeUnit.MINUTES)`. On timeout: call `process.destroyForcibly()`, throw `ProcessTimeoutException`. The exception propagates to `PhotoJobConsumer` which applies the retry/dead-letter policy (MI-4). Per-tool timeout is configurable (e.g., `worker.process.timeout-minutes: 5`).
- **tmpfs sizing (docker-compose.yml):** The worker service must declare:
  ```yaml
  tmpfs:
    - /tmp:size=1g,mode=1777
  ```
  Rationale: up to 5 concurrent workers (HikariCP pool 5) × 120 MB RAW = ~600 MB peak; 1 GB provides adequate headroom.

**Step 4: Implement MetadataExtractor**

- `metadata-extractor` (Java) as primary
- ExifTool `-fast2` as fallback via ProcessBuilder (same timeout policy as above)
- Write extracted EXIF/IPTC/XMP to `photo_metadata` table as JSONB using **upsert**:
  ```sql
  INSERT INTO photo_metadata (photo_id, exif_data, extracted_at)
  VALUES (?, ?, now())
  ON CONFLICT (photo_id) DO UPDATE SET
      exif_data = EXCLUDED.exif_data,
      extracted_at = EXCLUDED.extracted_at;
  ```
  This ensures re-processed photos (after a failed first run) overwrite partial metadata without PK violations.
- Populate `caption`, `title`, `description` on `photos` from IPTC/XMP

**Step 5: Wire into ImageProcessor pipeline**

Orchestrate: Tika → libraw → libvips → metadata-extractor → ExifTool fallback → update DB

**Step 6: Run tests, verify pass**

**Step 7: Commit**

```bash
git commit -m "feat: worker image processing pipeline — Tika, libraw, libvips, metadata"
```

---

### Task 3.6: Worker — Scheduled Tasks

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`
- Create: `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`
- Create: `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java`

**ShedLock dependency (add to `api/build.gradle`):**
```groovy
implementation 'net.javacrumbs.shedlock:shedlock-spring:6.x'
implementation 'net.javacrumbs.shedlock:shedlock-provider-redis-spring:6.x'
```
All three schedulers are annotated with `@SchedulerLock` to prevent concurrent execution across API instances (rolling deploys, horizontal scale). This is the primary guard against duplicate purge operations.

**Step 1: Write failing tests**

```java
@Test
void trashPurge_deletesPhotosOlderThanRetentionWindow() {
    // assert photos with deleted_at < now() - retention-days are removed
}

@Test
void trashPurge_enqueuesMinioDeleteJob() {
    // assert delete-job enqueued in Redis for each purged photo with all four message fields
}

@Test
void trashPurge_doesNotRunConcurrentlyAcrossInstances() {
    // assert ShedLock prevents second scheduler execution while first holds lock
}

@Test
void orphanReconciliation_detectsOrphanedMinioObjects() {
    // assert MinIO objects without a matching storage_key in DB are enqueued for deletion
}

@Test
void unverifiedPurge_deletesAccountsOlderThan7Days() {
    // assert users with email_verified=false and created_at < now() - 7 days are deleted
}

@Test
void unverifiedPurge_keepsVerifiedAccounts() {
    // assert users with email_verified=true are not deleted regardless of age
}

@Test
void unverifiedPurge_enqueuesMinioDeletesBeforeDeletingDbRecords() {
    // assert delete-jobs are present in Redis before photo rows are removed from DB
}
```

**Step 2: Implement TrashPurgeScheduler**

- `@Scheduled(cron = "0 0 3 * * *")` — daily at 3 AM
- `@SchedulerLock(name = "trashPurge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")`
- Retention window from `@Value("${jpt.trash.retention-days:30}")` — per-user configurability is out of scope for Phase 3
- Delete photos where `deleted_at < now() - (retentionDays || ' days')::interval`
- Enqueue `delete-job` for each purged photo using **Lettuce pipeline** (single round-trip, not N individual `XADD` calls):
  ```java
  redisTemplate.executePipelined((RedisCallback<?>) connection -> {
      for (Photo photo : purgedPhotos) {
          connection.xAdd("delete-jobs", buildDeleteMessage(photo));
      }
      return null;
  });
  ```
- Cascade deletes `photo_keywords`, `album_photos`, `shares`

**Step 3: Implement OrphanReconciliationScheduler**

- `@Scheduled(cron = "0 0 4 * * SUN")` — weekly
- `@SchedulerLock(name = "orphanReconciliation", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")`
- **To avoid OOM, stream both sides:**
  - MinIO side: iterate by user prefix (`{user_id}/`) using paginated `listObjects` — never load all objects at once
  - DB side: `@Query("SELECT storage_key FROM photos WHERE user_id = :userId AND deleted_at IS NULL")` returning `Stream<String>`, annotated `@Transactional(readOnly = true)`, consumed inside a try-with-resources block
- Enqueue unreferenced MinIO objects for deletion

**Step 4: Implement UnverifiedAccountPurgeScheduler**

- `@Scheduled(cron = "0 30 3 * * *")` — daily at 3:30 AM (after trash purge)
- `@SchedulerLock(name = "unverifiedAccountPurge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")`
- Delete users where `email_verified = false` AND `created_at < now() - INTERVAL '7 days'`
- **Strict deletion order to prevent permanent orphans:**
  1. Query `storage_key` values for all photos belonging to the user
  2. Enqueue MinIO delete-jobs (pipeline) — jobs are in Redis before any DB row is removed
  3. Delete DB records (cascade: photos, keywords, albums, saved searches)
  - If the service crashes between steps 2 and 3, delete-jobs are already queued and MinIO cleanup completes on the next worker cycle. The DB rows still exist and will be caught on the next purge run.
- Uses the `authDataSource` (BYPASSRLS) since unverified user cleanup must access the users table directly
- Implements design doc requirement (v4.0, [CR#5]): "unverified accounts soft-gated (no uploads) with 7-day auto-purge"

**Step 5: Run tests, verify pass**

**Step 6: Commit**

```bash
git commit -m "feat: scheduled tasks — trash purge, orphan reconciliation, unverified account purge"
```

---

**Next Phase:** [Phase 4: React Frontend](2026-02-25-saas-conversion-phase-4.md)
