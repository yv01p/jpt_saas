# Critical Implementation Review — Phase 3: Storage & Media (v2.0)
**Source plan:** `2026-02-25-saas-conversion-phase-3.md` (v2.0)
**Review version:** 2
**Reviewer:** Senior Staff Engineer
**Date:** 2026-03-04
**Prior review:** `2026-02-25-saas-conversion-phase-3-critical-review-1.md`

---

## 1. Overall Assessment

The v2.0 plan is a substantial improvement over v1.0. All five critical issues from the first review are addressed in principle, and the fifteen minor issues are acknowledged. However, the fixes for CI-2, CI-4, and CI-5 introduce new implementation-level gaps: the MinIO public URL substitution mechanism is unspecified at the SDK level (the Java MinIO SDK does not work the way the plan implies), the startup recovery lock TTL is too short for large recovery scans, and the distributed lock does not prevent re-enqueueing jobs already present in the stream across restart cycles. Two additional correctness issues are new: compensating transaction failure permanently corrupts user quota with no recovery path, and delivery count is not accessible from the `XREADGROUP` response in Spring Data Redis. Several minor issues also remain.

---

## 2. Critical Issues

### CI-1 — MinIO Public URL Substitution Mechanism Unspecified (Task 3.1)

**Description:** The plan says "`StorageService` substitutes `minio.public-url` into `GetPresignedObjectUrlArgs` before returning URLs to callers." The MinIO Java SDK's `GetPresignedObjectUrlArgs` does not accept a base URL override — it generates the URL against the endpoint configured in the `MinioClient` at construction time. There is no "substitute before returning" API. An implementer reading this plan will either fail to build or produce a broken implementation.

**Impact:** Correctness-breaking. The plan's wording implies an API that does not exist. Two valid implementation approaches exist but each has significant trade-offs that must be chosen before implementation begins.

**Fix — choose one and document it:**
- **Option A (Recommended): Dual-client approach.** Construct two `MinioClient` instances: `minioInternalClient` (configured with `minio.url`) for server-to-server upload/download/delete, and `minioPublicClient` (configured with `minio.public-url`) solely for pre-signed URL generation. The public client never performs I/O. This is the cleanest approach with no string manipulation.
- **Option B: Post-generation host substitution.** Generate the URL with the internal client, then replace the internal hostname and port with `minio.public-url` using `URI` parsing. Fragile — breaks if the MinIO SDK changes URL format or adds a path prefix. Not recommended.

Specify Option A in the plan and add a test that asserts the `minioPublicClient` is never used for upload/download operations.

---

### CI-2 — Compensating Transaction Failure Causes Permanent Quota Drift (Task 3.2)

**Description:** The upload flow's step 5 states: "On MinIO failure: compensating Tx — delete the photo row, decrement `used_bytes`, return 500." If this compensating transaction itself fails (e.g., DB connection lost in the window between MinIO upload failure and the compensating commit), the user's `used_bytes` is permanently over-debited and a dangling photo row exists with no `storage_key`. Orphan reconciliation won't fix this — it scans MinIO for objects with no matching DB row, not DB rows with no MinIO object. There is no recovery path specified.

**Impact:** Silent quota corruption. A user could eventually be locked out of uploads despite having available quota. Under pathological network conditions (brief DB outage during MinIO I/O) this could accumulate.

**Fix:** Two complementary defenses:
1. In `TrashPurgeScheduler` or a dedicated reconciliation job, periodically query for photo rows where `storage_key IS NULL` and `created_at < now() - INTERVAL '1 hour'` — these are compensating-Tx failures. Enqueue them for deletion and decrement quota.
2. Log a critical-severity alert whenever the compensating Tx itself fails, so the condition is never silent.

---

### CI-3 — Startup Recovery Lock TTL Too Short for Large Recovery Scans (Task 3.4, Step 5)

**Description:** The plan sets the startup recovery lock TTL to 60 seconds (`PX 60000`). For a deployment with thousands of `pending`/`processing` rows (e.g., after an extended outage), querying all rows and re-enqueueing them via Redis `XADD` could exceed 60 seconds. If the lock expires mid-scan, a second worker instance starting during a rolling deploy acquires the lock and begins a concurrent re-enqueue while the first instance is still running, defeating the lock's purpose.

**Impact:** Duplicate job enqueuing under the exact recovery scenario the lock was designed to prevent — large-scale outage recovery followed by rolling deploy restart.

**Fix:** Increase the TTL to 5 minutes (`PX 300000`). Use `SET ... NX PX 300000` and also implement a lock-refresh (watchdog) pattern: extend the lock TTL by another 5 minutes every 60 seconds while recovery is in progress. Alternatively, use a two-phase approach: acquire lock → write recovery-in-progress flag to Redis → scan in pages → release lock at the end, refreshing TTL on each page.

---

### CI-4 — Startup Recovery Re-enqueues Jobs Already Present in the Stream (Task 3.4, Step 5)

**Description:** The distributed lock prevents two instances from running recovery concurrently, but does not prevent re-enqueueing messages that are already present in the `photo-jobs` stream from a prior boot cycle. Scenario: worker starts, enqueues 100 recovery jobs, crashes before any are consumed, restarts, and enqueues the same 100 jobs again. After N restarts, each photo has N entries in the stream. The dead-letter policy (max-retries=3) counts delivery attempts, not enqueue attempts — fresh entries from re-enqueue reset the delivery counter, so a chronically failing job will loop indefinitely across restarts.

**Impact:** Unbounded duplicate processing across restart cycles. Compounds with CI-3's TTL issue.

**Fix:** Before re-enqueueing a `photo_id` during recovery, check whether an unacknowledged entry for that `photo_id` already exists in the PEL (Pending Entry List) using `XPENDING photo-jobs photo-processors - + COUNT 100`. Re-enqueue only photos whose `photo_id` is absent from both the PEL and the stream. Alternatively, use a Redis Set (`photo-jobs:in-flight`) as an idempotency guard: check Set membership before enqueuing; add to Set on enqueue; remove on XACK.

---

### CI-5 — Delivery Count Not Available from XREADGROUP Response (Task 3.4, Step 2)

**Description:** The plan says "On failure, check the message delivery count from `XPENDING`." In Spring Data Redis (Lettuce), `XREADGROUP` returns `MapRecord<String, Object, Object>` — the delivery count is **not** included in this response. To get the delivery count, you must call `XPENDING photo-jobs photo-processors - + COUNT 1 {consumerName}` or `XPENDING photo-jobs photo-processors {id} {id} 1` as a separate command. This adds a Redis round-trip per failed message and requires the consumer to issue a second command before deciding whether to dead-letter. The plan implies this is a simple field access; it is not.

**Impact:** Correctness-breaking implementation if the developer assumes delivery count is in the `XREADGROUP` response. The retry logic will not work as written.

**Fix:** Document explicitly in Task 3.4, Step 2:
1. On failure, call `XPENDING photo-jobs photo-processors {messageId} {messageId} 1` to get the delivery count for the specific message ID.
2. Compare against `worker.streams.max-retries`.
3. Branch to dead-letter or leave unacknowledged accordingly.
Note: delivery count via XPENDING counts cumulative deliveries across all consumers in the group, which is the correct semantic for cross-instance retry tracking.

---

## 3. Minor Issues & Improvements

### MI-1 — Temp File Cleanup Not Specified (Task 3.5)

`ThumbnailGenerator` downloads the original to tmpfs but no explicit cleanup is mentioned. On pipeline failure (timeout, crash), the temp file remains on tmpfs indefinitely. With pool size 5 and 1 GB tmpfs, leaked files from five concurrent failures consume the full budget.

**Fix:** Wrap the temp file lifecycle in a try-finally or use `Files.createTempFile` + try-with-resources pattern:
```java
Path tmp = Files.createTempFile("/tmp", photoId.toString(), "." + ext);
try {
    // download, process
} finally {
    Files.deleteIfExists(tmp);
}
```

---

### MI-2 — `jpt.trash.retention-days` Placed in Worker's `application.yml` (Task 3.3)

`TrashPurgeScheduler` lives in the API module. `jpt.trash.retention-days` is specified in the worker's `application.yml`. The API module will not read this property from the worker's config. The implementer will add it to the wrong file.

**Fix:** Move `jpt.trash.retention-days: 30` to `api/src/main/resources/application.yml`. Remove it from the worker's YAML.

---

### MI-3 — `InetAddress.getLocalHost()` Unreliable in Docker Containers (Task 3.4)

In Docker containers, `InetAddress.getLocalHost().getHostName()` can return `localhost` (useless for disambiguation) or throw `UnknownHostException` if `/etc/hosts` is not configured. This is a known container networking issue.

**Fix:**
```java
String hostname = Optional.ofNullable(System.getenv("HOSTNAME"))
    .filter(s -> !s.isBlank())
    .orElseGet(() -> {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (UnknownHostException e) { return UUID.randomUUID().toString(); }
    });
String consumerName = hostname + "-" + ProcessHandle.current().pid();
```

---

### MI-4 — `photo-jobs` Stream Message Schema Not Defined (Task 3.4)

The `delete-jobs` message schema is fully defined (four fields). The `photo-jobs` message schema is not defined anywhere. `PhotoJobConsumer` reads from this stream and extracts at minimum a `photo_id` — but the field name and any other fields (e.g., `user_id`, `storage_key`, file extension for temp file naming) are unspecified.

**Fix:** Define the `photo-jobs` message schema in Task 3.4 alongside the `delete-jobs` schema:
```
photo_id  — UUID string of the photo to process
```
Confirm whether additional fields (user_id, ext) are included or whether the consumer fetches them from DB using `photo_id`.

---

### MI-5 — No Maximum Upload Size Reference in Task 3.2 (Task 3.2)

The design doc (v3.0, [M4]) specifies `spring.servlet.multipart.max-file-size=200MB` and Nginx `client_max_body_size 250m`. Neither is referenced in Task 3.2. An implementer could omit these, causing Spring to reject large legitimate uploads with an opaque `MaxUploadSizeExceededException`.

**Fix:** Add a note to Task 3.2, Step 4: "Confirm `spring.servlet.multipart.max-file-size=200MB` is set per design doc [M4]. Return HTTP 413 with a user-readable message when exceeded."

---

### MI-6 — Wrong HTTP Status for Quota Exceeded (Task 3.2)

The test comment reads "assert HTTP 403 when used_bytes + file_size > quota_bytes." HTTP 403 means "Forbidden" (authorization). Quota exhaustion is not an authorization failure — the user has permission to upload but has hit a resource limit.

**Fix:** Use HTTP 402 Payment Required (conventional for quota/billing limits) or HTTP 507 Insufficient Storage (RFC 4918, semantically precise). Update the test comment and the implementation accordingly. Pick one and document it as the API contract.

---

### MI-7 — ShedLock Version Unpinned (Task 3.6)

`shedlock-spring:6.x` and `shedlock-provider-redis-spring:6.x` use a wildcard version. Gradle will resolve `6.x` to the latest `6.*` release, making builds non-reproducible and vulnerable to unexpected breaking changes.

**Fix:** Pin to an exact version (e.g., `6.0.2`). Check Maven Central for the current stable `6.x` release at implementation time and lock it.

---

### MI-8 — `purgedPhotos` Loaded as Full List in TrashPurgeScheduler (Task 3.6)

The Lettuce pipeline example iterates `for (Photo photo : purgedPhotos)`, implying `purgedPhotos` is a `List<Photo>`. For a user with thousands of trashed photos past the retention window, loading all into a Java heap `List` risks OOM in the API container.

**Fix:** Page through purged photos in batches:
```java
Pageable page = PageRequest.of(0, 500);
Slice<Photo> slice;
do {
    slice = photoRepo.findPurgeableBatch(cutoff, page);
    enqueueDeleteJobsBatch(slice.getContent()); // pipeline per batch
    deletePhotosBatch(slice.getContent());
    page = slice.nextPageable();
} while (slice.hasNext());
```

---

### MI-9 — OrphanReconciliationScheduler User ID Source Unspecified (Task 3.6)

The plan says "iterate by user prefix (`{user_id}/`)" but doesn't specify where the list of user UUIDs comes from. The obvious source is a DB query, but if loaded into a `List<UUID>` for millions of users, it could be large.

**Fix:** Add to Task 3.6, Step 3: "Stream user IDs from DB using a read-only cursor query: `SELECT id FROM users` with `Stream<UUID>`; for each user, perform the MinIO prefix listing + DB key comparison."

---

### MI-10 — libraw Step Not Conditional on File Type (Task 3.5)

The pipeline description reads "Tika → libraw → libvips → metadata-extractor." `libraw` is only applicable to RAW image formats (CR2, CR3, NEF, ARW, etc.). Running it on JPEG or PNG inputs will either fail or produce nonsense output. The plan does not document that the libraw step is conditional.

**Fix:** Update Task 3.5, Step 5 to document the branching logic:
```
if (mimeType is RAW format):
    libraw → extract embedded JPEG
libvips → resize to sm/md thumbnails
metadata-extractor → ...
```
Define the set of MIME types that trigger the libraw path (or the Tika `MediaType` predicates to check).

---

### MI-11 — Partial Pipeline Failure Causes Full Reprocessing on Retry (Task 3.5)

If thumbnail generation succeeds but metadata extraction fails, the job will be retried. On retry, thumbnail generation runs again unnecessarily — libvips re-downloads the original from MinIO, re-processes, and re-uploads thumbnails that already exist (the MinIO PUT will silently overwrite). This is wasteful but not incorrect, assuming idempotent MinIO PUTs. The plan should acknowledge this as accepted behavior to avoid implementers adding complex partial-state tracking.

**Fix:** Add a note to Task 3.5, Step 5: "Pipeline steps are not individually idempotent-guarded. On retry, all steps re-execute. MinIO PUT overwrites are idempotent; DB upsert on `photo_metadata` is idempotent (MI-7). This is acceptable for Phase 3."

---

### MI-12 — `ProcessingException` Type Undefined (Task 3.5)

The test `tikaValidator_rejectsNonImageFile()` references `ProcessingException` in its comment. This type is not defined or referenced anywhere else in the plan.

**Fix:** Add a note to Task 3.5: "Define `ProcessingException` (checked or unchecked) in the worker module. `ProcessTimeoutException` should extend it. `PhotoJobConsumer` catches `ProcessingException` as the failure signal for the retry/dead-letter policy."

---

## 4. Questions for Clarification

1. **MinIO dual-client approach:** Is Option A (dual `MinioClient` instances) acceptable, or is there a constraint that prevents constructing two clients (e.g., connection pool budget)?

2. **`photo-jobs` message schema:** Does `PhotoJobConsumer` fetch photo metadata (user_id, storage_key, file extension) from the DB using `photo_id`, or should these be included in the stream message to avoid a DB round-trip at job pickup time?

3. **Worker consumer thread model:** `XREADGROUP` runs on some thread. Is the consumer single-threaded (one in-flight job at a time per consumer instance), or does it use a thread pool? If a thread pool, what bounds it — HikariCP pool size, or a separate `@Async` executor? This affects the tmpfs sizing calculation and the XAUTOCLAIM idle-time assumption.

4. **Quota exceeded HTTP status:** 402 Payment Required or 507 Insufficient Storage? Needs to be locked before Phase 4 frontend work begins.

5. **Compensating transaction recovery:** Is a periodic "DB-row-with-no-storage_key" cleanup query acceptable for Phase 3, or should it be deferred to a later phase with a JIRA ticket?

---

## 5. Final Recommendation

**Major revisions needed** on the five critical issues before implementation begins.

**Must fix before writing any code:**
- CI-1: Specify dual-client MinIO approach for public URL generation
- CI-2: Add recovery path for compensating transaction failure (quota drift)
- CI-3: Increase startup lock TTL to 5 min and add lock-refresh watchdog
- CI-4: Add PEL/in-flight Set check before re-enqueueing during startup recovery
- CI-5: Document that delivery count requires a separate `XPENDING` call; update retry logic accordingly

**Should fix during implementation:**
- MI-1: Temp file cleanup in try-finally
- MI-2: Move `jpt.trash.retention-days` to API's `application.yml`
- MI-6: Correct quota exceeded HTTP status to 402 or 507
- MI-8: Page/stream `purgedPhotos` rather than loading full List
- MI-10: Document libraw conditional branching on RAW MIME types

**Low risk, fix opportunistically:**
- MI-3, MI-4, MI-5, MI-7, MI-9, MI-11, MI-12
