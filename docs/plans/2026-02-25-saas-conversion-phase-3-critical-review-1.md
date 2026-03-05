# Critical Implementation Review — Phase 3: Storage & Media
**Source plan:** `2026-02-25-saas-conversion-phase-3.md`
**Review version:** 1
**Reviewer:** Senior Staff Engineer
**Date:** 2026-03-04

---

## 1. Overall Assessment

The plan is well-structured and reflects the approved design doc (v4.0) faithfully — TDD scaffolding is present, security concerns from prior audits are addressed (ProcessBuilder with explicit arrays, UUID-only storage keys, Tika validation, soft-gate on email verification). However, five issues are correctness-breaking and must be fixed before implementation begins: a Flyway version collision that will crash the app at startup, a transaction-boundary defect that can create quota drift, a pre-signed URL misconfiguration that will produce inaccessible URLs for browsers, a startup recovery race condition that causes duplicate job processing, and an XAUTOCLAIM idle-time that will claim jobs still in progress. Several secondary issues also need resolution.

---

## 2. Critical Issues

### CI-1 — Flyway Version Collision (Task 3.2)
**Description:** The plan names the migration `V4__fix_content_hash_unique_constraint.sql`. `V4__create_jpt_auth_role.sql` already exists in the codebase.

**Impact:** Flyway will fail at startup with a "Found more than one migration with version 4" error, rendering the application undeployable. All tests that boot a Spring context will also fail.

**Fix:** Name the migration `V5__fix_content_hash_unique_constraint.sql`.

---

### CI-2 — Pre-signed URLs Generated Against Internal MinIO Hostname (Task 3.1)
**Description:** The design doc v2.0 [2.1] explicitly requires pre-signed URLs to be generated against the **public Nginx domain** so that browsers can resolve them. If `MinioConfig` initialises the MinIO client with the internal Docker hostname (e.g., `http://minio:9000`), the generated URLs will contain that hostname, which is unreachable from browsers.

**Impact:** Every thumbnail and original download will fail with a connection refused / DNS error on the client side. This is a silent misconfiguration — tests using Testcontainers or the internal hostname will pass but production will break.

**Fix:** The `MinioClient` must be constructed with the external public base URL (e.g., `https://example.com`). In `MinioConfig`, expose a `minio.public-url` property that is set to the Nginx-proxied public URL. The `StorageService` must pass this URL (not the internal endpoint) when calling `GetPresignedObjectUrlArgs`. Add a test that asserts the generated URL begins with the configured public URL.

---

### CI-3 — Row Lock Held Across MinIO I/O (Task 3.2)
**Description:** The plan orders operations as: quota `SELECT FOR UPDATE` → stream to MinIO → DB INSERT. The `SELECT FOR UPDATE` row lock on the `users` row is acquired inside a transaction that stays open while an arbitrarily large file (up to 200 MB) is streamed to MinIO over the network. This can take seconds to minutes.

**Impact:** Every concurrent upload from the same user will block waiting to acquire the same row lock, effectively serialising all uploads per user. A single slow or stalled upload will lock out all other uploads from that user. Under load this becomes a thundering-herd problem.

**Fix:** Keep the locking transaction as narrow as possible. Recommended order:
1. Compute SHA-256 hash (streaming, before any transaction).
2. Fast-path dedup check (read-only query, no lock).
3. Open transaction: `SELECT FOR UPDATE` on user row → validate quota → `INSERT INTO photos` (status=`pending`, no `storage_key` yet) → increment `used_bytes` → **commit**.
4. Upload to MinIO (outside any transaction).
5. If MinIO upload fails: open a compensating transaction to delete the photo row and decrement `used_bytes`, return 500.
6. Open transaction: `UPDATE photos SET storage_key = ?, processing_status = 'pending' WHERE id = ?` → **commit**.
7. Enqueue to Redis Streams.

This keeps the DB lock held for milliseconds, not seconds, and makes the quota accounting correct in both success and failure paths.

---

### CI-4 — Startup Recovery Race Condition (Task 3.4, Step 5)
**Description:** The plan says: "Query all `pending`/`processing` rows where `deleted_at IS NULL`, re-enqueue to Redis Streams." This runs unconditionally at startup. If two worker instances start simultaneously (rolling deploy, crash-restart), both query and re-enqueue the same rows, doubling every job in the stream.

**Impact:** Duplicate processing of the same photos — generates duplicate thumbnails in MinIO, runs duplicate DB updates (benign in isolation but wasteful), and may cause `UNIQUE` constraint violations on `photo_metadata`. At scale this turns a deploy into a thundering herd.

**Fix:** Options in order of preference:
- Acquire a distributed startup lock in Redis (`SET nx px`) before the recovery scan; only the instance holding the lock performs recovery.
- Alternatively, before re-enqueuing, check whether a pending entry for each `photo_id` already exists in the stream (use `XRANGE photo-jobs - + COUNT 1` with a filter, or maintain a Redis Set of in-flight IDs). Re-enqueue only those not already present.
- At minimum, add a comment acknowledging this and deferring to single-instance deployment.

---

### CI-5 — XAUTOCLAIM Idle-Time Must Exceed Max Processing Time (Task 3.4, Step 4)
**Description:** The plan says "XAUTOCLAIM recovery scheduled every 5 min" but does not specify the `min-idle-time` argument. If this defaults to the schedule interval (5 min), a worker processing a 200 MB RAW file through Tika → libraw → libvips → metadata-extractor (which can take longer than 5 minutes for exotic RAW formats) will have its message claimed by another worker, causing concurrent duplicate processing of the same file.

**Impact:** Two workers simultaneously download and process the same file, create duplicate MinIO thumbnails, and race on DB updates.

**Fix:** Set `min-idle-time` to a value safely larger than the worst-case processing time. Empirically, 15–30 minutes is a reasonable starting point for RAW processing. Document the reasoning. Expose this as a configurable property (`worker.streams.claim-idle-time-ms`).

---

## 3. Minor Issues & Improvements

### MI-1 — MinIO Upload Before DB Insert Creates Orphans (Task 3.2)
The plan's original order (quota check → MinIO upload → DB insert) means a DB failure after a successful MinIO upload orphans the object. CI-3's fix above resolves this by inserting the DB row first. Make sure the orphan reconciliation scheduler (Task 3.6) is acknowledged as the fallback safety net, not the primary strategy.

### MI-2 — Empty Test Method Bodies (Tasks 3.1, 3.2)
All listed test methods have empty bodies `{ }`. The plan intends TDD but empty stubs won't drive design. At minimum, add a one-line comment inside each stub describing what the test should assert (expected HTTP status, expected DB state, expected MinIO interaction via mock). This makes the TDD intent executable.

### MI-3 — `processing_status` as Raw Strings
The string literals `'pending'`, `'processing'`, `'done'`, `'failed'` appear across the API, worker, and migration. A typo anywhere causes a silent failure or CHECK constraint violation. Define a shared `ProcessingStatus` enum or constants class and use it in both the JPA entity and the worker. The schema already has the CHECK constraint — the Java layer should enforce the same vocabulary.

### MI-4 — No Retry / Dead-Letter Policy for Worker Job Failures (Task 3.4)
The plan says XACK on success and leaves failed jobs unacknowledged indefinitely until XAUTOCLAIM reclaims them. There is no max-retry count and no dead-letter stream. A permanently failing job (e.g., corrupted RAW file that always crashes libraw) will loop forever: claim → process → fail → re-claim.

**Fix:** Track a retry count per message (use the delivery count from XPENDING, or store it in the message payload). After N failures (suggest 3), XACK the message to remove it from the PEL and write the photo's `processing_status` to `'failed'` in the DB. Optionally publish to a `dead-letter` stream for inspection.

### MI-5 — ProcessBuilder Calls Have No Timeout (Task 3.5)
`libraw`, `libvips`, and ExifTool are invoked via `ProcessBuilder` with no specified timeout. A hung child process will block the calling thread indefinitely.

**Fix:** Use `process.waitFor(timeout, TimeUnit.SECONDS)` and call `process.destroyForcibly()` on timeout. Set a generous but finite limit (e.g., 5 minutes per tool invocation). Log the timeout as an error and fail the job cleanly.

### MI-6 — tmpfs Size Not Specified (Task 3.5)
RAW files from professional cameras can be 50–120 MB. With HikariCP pool size 5, up to 5 workers can each download a file simultaneously, requiring up to ~600 MB of tmpfs. The Docker Compose worker service definition must explicitly size the tmpfs mount (e.g., `tmpfs: - /tmp:size=1g`). Without this, the default (often half of RAM) may be too small or too large depending on the VPS.

### MI-7 — `photo_metadata` Must Use Upsert (Task 3.5)
The worker writes to `photo_metadata` via INSERT. If a photo is re-processed (e.g., after a failed first run), the INSERT will fail with a primary-key violation because the row already exists from the first (partial) run.

**Fix:** Use `INSERT INTO photo_metadata ... ON CONFLICT (photo_id) DO UPDATE SET exif_data = EXCLUDED.exif_data, ...`.

### MI-8 — TrashPurgeScheduler N+1 Redis Enqueues (Task 3.6)
The plan iterates over each deleted photo and enqueues a Redis delete-job individually. For a user with thousands of photos in the trash, this is N round-trips to Redis.

**Fix:** Use `XADD` in a Redis pipeline (Lettuce `async` / `pipeline` API) or batch into a Lua script. Alternatively, enqueue a single "delete-batch" job containing a list of `storage_key` values.

### MI-9 — Scheduled Tasks Need Distributed Lock Guard (Task 3.6)
`TrashPurgeScheduler`, `OrphanReconciliationScheduler`, and `UnverifiedAccountPurgeScheduler` run in the API container. If the API is ever scaled to multiple instances (even temporarily for a rolling deploy), all instances trigger the same cron jobs simultaneously, causing duplicate purge/delete operations.

**Fix:** Add [ShedLock](https://github.com/lukas-krecan/ShedLock) with the Redis backend (already available). Annotate each scheduler with `@SchedulerLock(name = "...", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")`. This is a one-dependency add and prevents the issue entirely.

### MI-10 — UnverifiedAccountPurgeScheduler Must Enqueue MinIO Deletes Before DB Deletion (Task 3.6)
The plan says "cascade: delete all photos (enqueue MinIO delete jobs)". The sequencing must be: (1) query photo `storage_key` values for the user, (2) enqueue MinIO delete jobs, (3) delete DB records. If the DB records are deleted first and the service crashes, the MinIO objects become permanently orphaned (the reconciliation scheduler is the only recovery, and it compares DB `storage_key` columns — but if the rows are gone, it won't find orphans either).

### MI-11 — Orphan Reconciliation Full-Listing Risk (Task 3.6)
The plan compares "MinIO object listing vs DB `storage_key` values". For an account with thousands of photos, listing all MinIO objects is a potentially expensive paginated API call, and loading all `storage_key` values from the DB must be streamed (not loaded into a `List`). The plan should specify:
- Iterate MinIO listing by user-prefix (`{user_id}/`) to bound scope.
- Use a DB cursor / Spring Data `Stream<String>` for the DB side to avoid OOM.

### MI-12 — Task 3.6 Duplicates the Files Section (Copy-Paste Error)
The `Files:` list in Task 3.6 is duplicated twice verbatim (lines 241–244 and 242–245 in the plan). Remove the duplicate block before implementation to avoid confusion.

### MI-13 — Retention Days Not Configured
The plan says `deleted_at < now() - retention_days` but `retention_days` is never defined. Is it a global constant? A per-user setting? A Spring config property? Specify the source (suggest `@Value("${jpt.trash.retention-days:30}")`) and ensure it is tested.

### MI-14 — Consumer Group and Consumer Name Not Specified (Task 3.4)
`XREADGROUP` requires a consumer group name and a consumer name. Neither is specified. The consumer name should be stable across restarts (e.g., hostname + PID) to allow proper XPENDING tracking. The group name should be documented (e.g., `photo-processors`). Specify both in the plan.

### MI-15 — DeleteJobConsumer Message Schema Not Specified (Task 3.4)
The plan says "Delete MinIO objects by storage key" but doesn't define the Redis Stream message schema for `delete-jobs`. The original path is `{user_id}/originals/{photo_id}.{ext}` and there are two thumbnail sizes — the message must carry all keys to delete. Define the message fields.

---

## 4. Questions for Clarification

1. **Pre-signed URL caching strategy:** Pre-signed URLs expire in 15 minutes (thumbnails) and 1 hour (originals). Does the frontend refresh these from the API before expiry? Or does the client re-fetch the photo detail endpoint? This needs to be designed before the frontend (Phase 4) to avoid displaying broken image links.

2. **ExifTool daemon mode:** ExifTool `-fast2` as a per-invocation fallback has significant JVM startup overhead. Has starting ExifTool in daemon mode (`exiftool -stay_open True -@ -`) been considered for the worker, given that it will handle many files?

3. **Multiple thumbnail sizes:** The plan mentions `sm` and `md` thumbnails. Are exact pixel dimensions and format (JPEG quality, progressive?) specified in the design doc? The `ThumbnailGenerator` tests reference "sm and md thumbnails" but the exact spec should be locked before implementation.

4. **`upload_concurrentDuplicatesHandledByDbConstraint` test:** This test requires true concurrent execution to be meaningful. Will it be implemented as a multi-threaded integration test, or accepted as a unit test that only verifies exception handling when the constraint is violated (not concurrency itself)?

5. **Retention days per-user vs global:** Is the trash retention window configurable per user (future feature), or is it global config only for this phase?

---

## 5. Final Recommendation

**Major revisions needed** before implementation begins.

**Must fix before writing any code:**
- CI-1: Rename migration to V5
- CI-2: Configure MinIO client with public Nginx URL for pre-signed URL generation
- CI-3: Restructure upload transaction order (lock → insert → commit → upload to MinIO)
- CI-4: Add distributed startup lock for re-enqueue recovery
- CI-5: Set XAUTOCLAIM min-idle-time to ≥ 15 min

**Should fix during implementation:**
- MI-4: Dead-letter policy for failed worker jobs
- MI-5: ProcessBuilder timeouts
- MI-7: `photo_metadata` upsert
- MI-9: ShedLock for all three schedulers
- MI-10: Sequence delete-job enqueue before DB deletion in unverified account purge

**Low risk, fix opportunistically:**
- MI-2 through MI-3, MI-6, MI-8, MI-11 through MI-15
