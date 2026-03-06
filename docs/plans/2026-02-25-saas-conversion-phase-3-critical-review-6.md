# Critical Implementation Review — Phase 3: Storage & Media (v5.0)
**Source plan:** `2026-02-25-saas-conversion-phase-3.md` (v5.0)
**Review version:** 6
**Reviewer:** Senior Staff Engineer
**Date:** 2026-03-05
**Prior reviews:** `critical-review-1.md` through `critical-review-5.md`, `security-audit-1.md`, `security-audit-2.md`

---

## 1. Overall Assessment

The v5.0 plan correctly integrates the security audit 2 findings (SA2-F1 through SA2-F5) and all prior architectural decisions remain sound. However, the seven issues raised in review 5 (two critical, five minor) were **not addressed** and remain present verbatim in the plan. Additionally, this review identifies two new critical issues and four new minor issues not present in any prior review.

The most severe new finding is a fundamental body-stream handling gap in the upload endpoint: the plan specifies SHA-256 hashing, Tika detection, and MinIO upload as sequential steps on the same HTTP request body without specifying how the body is buffered to allow all three operations. Without explicit guidance, the implementer cannot correctly read the multipart stream. The second new critical issue is that `PhotoJobConsumer` accepts only `PENDING` status, which causes a photo to become permanently stuck in `PROCESSING` state after an XAUTOCLAIM reclaim (the most common recovery path).

---

## 2. Critical Issues

### CI-1 — TrashPurgeScheduler Offset Pagination While Deleting: STILL PRESENT (Task 3.6, Step 2)

**Description:** Unresolved from review 5 (CI-1). The buggy loop is still in the plan:

```java
Pageable page = PageRequest.of(0, 500);
Slice<Photo> slice;
do {
    slice = photoRepo.findPurgeableBatch(cutoff, page);
    enqueueDeleteJobsBatch(slice.getContent());
    deletePhotosBatch(slice.getContent());
    page = slice.nextPageable();      // ← offset advances after rows are deleted
} while (slice.hasNext());
```

After deleting batch 1 (rows 0–499), `OFFSET 500` skips what are now rows 0–499. All photos beyond the first batch are silently skipped.

**Fix:** Always query page 0; break on empty result:

```java
Pageable page = PageRequest.of(0, 500);
List<Photo> batch;
do {
    batch = photoRepo.findPurgeableBatch(cutoff, page);
    if (batch.isEmpty()) break;
    enqueueDeleteJobsBatch(batch);
    deletePhotosBatch(batch);
} while (!batch.isEmpty());
```

---

### CI-2 — `Collectors.toMap()` NPE on Null EXIF Values: STILL PRESENT (Task 3.5, Step 4)

**Description:** Unresolved from review 5 (CI-2). The SA2-F1 stream sanitization is unchanged:

```java
Map<String, Object> sanitizedExifData = rawExifData.entrySet().stream()
    .collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> e.getValue() instanceof String s ? Jsoup.parse(s).text() : e.getValue()
    ));
```

When `e.getValue()` is `null`, the ternary returns `null`. `Collectors.toMap()` calls `HashMap.merge()` which calls `Objects.requireNonNull(value)` — NPE. Any photo whose EXIF map contains a null-description tag permanently fails with `processing_status = FAILED`.

**Fix:** Use `HashMap` directly to allow null values:

```java
Map<String, Object> sanitizedExifData = new HashMap<>();
rawExifData.forEach((k, v) ->
    sanitizedExifData.put(k, v instanceof String s ? Jsoup.parse(s).text() : v));
```

---

### CI-3 — Upload Endpoint: Request Body Can Only Be Read Once; SHA-256 + Tika + MinIO Upload All Require It (Task 3.2, Step 4) — **NEW**

**Description:** The transaction order specifies three operations that each consume the multipart body:

1. Step 1 — compute SHA-256 hash (needs full body)
2. Step 2 — run Tika on magic bytes (needs first ~4 KB)
3. Step 5 — upload to MinIO (needs full body)

An HTTP request body (`multipartFile.getInputStream()`) is a non-resettable `InputStream` — it can only be consumed once. Spring's multipart parser does write the file to a server-side temp file (under `spring.servlet.multipart.location`), so `getInputStream()` reads from that file, and `getSize()` is available immediately. However, calling `getInputStream()` a second time returns a new stream from the beginning only for `CommonsMultipartFile`; `StandardMultipartFile` (the default in Spring Boot 3 without commons-fileupload) does not reset. Even if it did, calling `getInputStream()` three times is not a specified or guaranteed contract.

**Impact:** Without explicit buffering guidance, implementations will either (a) fail at runtime with "stream closed" / "stream already consumed" errors, or (b) load 200 MB into heap (`getBytes()`) causing OOM on large RAW uploads. Neither is acceptable.

**Fix:** Add an explicit buffering step between body receipt and processing. The correct pattern for a 200 MB upload is a single-pass temp-file approach:

```java
// Step 0 (new): stream body to tmpfs temp file, computing hash simultaneously
Path uploadTemp = Files.createTempFile(Path.of("/tmp"), "upload-", ".tmp");
try {
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    try (DigestInputStream dis =
             new DigestInputStream(multipartFile.getInputStream(), sha256)) {
        Files.copy(dis, uploadTemp, StandardCopyOption.REPLACE_EXISTING);
    }
    String contentHash = HexFormat.of().formatHex(sha256.digest());
    long fileSize = Files.size(uploadTemp);

    // Step 2: Tika reads magic bytes from temp file (not the original stream)
    String mimeType = new Tika().detect(uploadTemp.toFile());

    // ... dedup, DB insert ...

    // Step 5: MinIO upload reads from temp file
    storageService.upload(uploadTemp, storageKey, mimeType, fileSize);
} finally {
    Files.deleteIfExists(uploadTemp);
}
```

Add this buffering step to Task 3.2 Step 4. Note that the API container also needs a tmpfs or a fast-disk temp directory for upload handling — the plan currently only documents tmpfs for the worker. Add a note that `spring.servlet.multipart.location` should point to a tmpfs-backed path (or the system `/tmp`, which should be fast SSD on a typical VPS). The `try-finally` guarantees cleanup even if Tika, DB, or MinIO fails.

---

### CI-4 — `PhotoJobConsumer` Validates `PENDING` Only; XAUTOCLAIM Reclaim Leaves Photos Permanently in `PROCESSING` (Task 3.4, Step 2) — **NEW**

**Description:** The plan specifies:

> Validate `photo_id` exists and status is `PENDING`

The XAUTOCLAIM recovery flow (Step 4, every 5 min) reclaims messages idle > 30 min. The most common reason a message is idle that long is a worker crash mid-processing. When a worker crashes after setting `processing_status = PROCESSING` but before setting `DONE` or `FAILED`, the message remains unacknowledged in the PEL. XAUTOCLAIM correctly reclaims it and delivers it to a new consumer. That consumer reads the photo row and finds `status = PROCESSING` — not `PENDING`. The plan's validation rejects `PROCESSING` as an invalid initial state, XACKs the message (or discards it without XACK), and the photo is permanently stuck in `PROCESSING` with no path to `DONE` or `FAILED`.

**Impact:** Every worker crash during active processing produces a photo permanently in `PROCESSING` state. This is the primary failure mode that XAUTOCLAIM is designed to recover from; the plan's own recovery mechanism breaks on the most common failure path.

**Fix:** Accept both `PENDING` and `PROCESSING` as valid states for a newly claimed job:

```java
// In PhotoJobConsumer, after fetching photo from DB:
if (photo.getProcessingStatus() == ProcessingStatus.DONE) {
    log.info("photo {} already DONE — XACK and skip", photoId);
    redisCommands.xack(STREAM, GROUP, messageId);
    return;
}
if (photo.getProcessingStatus() == ProcessingStatus.FAILED) {
    log.warn("photo {} already FAILED — XACK and skip (was re-enqueued?)", photoId);
    redisCommands.xack(STREAM, GROUP, GROUP, messageId);
    return;
}
// PENDING or PROCESSING: proceed with processing
updateStatus(photoId, ProcessingStatus.PROCESSING);
```

Add a test: `photoJobConsumer_reprocessesPhotoWithProcessingStatus` — assert that when a photo has `processing_status = PROCESSING` (simulating a prior worker crash), the consumer re-processes it and correctly sets `DONE` or `FAILED`.

---

## 3. Minor Issues & Improvements

### MI-1 — `Map.of()` 10-Entry Limit in `MIME_TO_EXT` Allowlist: STILL PRESENT (Task 3.2, Step 4)

Unresolved from review 5. The full RAW MIME list exceeds 10 entries; `Map.of()` has no 11-argument overload. Compile error at implementation time.

**Fix:** Replace `Map.of(...)` with `Map.ofEntries(Map.entry(...), ...)`.

---

### MI-2 — `ProcessingStatus` Enum: `toDbValue()` and `@Enumerated(EnumType.STRING)` Store Different Cases: STILL PRESENT (Task 3.2, Step 1)

Unresolved from review 5. `toDbValue()` returns lowercase; `@Enumerated(EnumType.STRING)` stores uppercase. Native SQL using one case returns 0 rows against the other.

**Fix:** Drop `toDbValue()` and use uppercase-only SQL literals, or replace `@Enumerated(EnumType.STRING)` with a JPA `AttributeConverter` that calls `toDbValue()`.

---

### MI-3 — Missing Null Guards Before `Jsoup.parse()` for Named IPTC Fields: STILL PRESENT (Task 3.5, Step 4)

Unresolved from review 5. `Jsoup.parse(null)` throws NPE. `rawCaption`, `rawTitle`, and `rawDescription` are absent from most photos.

**Fix:** `String safeCaption = rawCaption != null ? Jsoup.parse(rawCaption).text() : null;` (and same for title, description). Or extract a `private String sanitize(String s)` helper.

---

### MI-4 — `OrphanReconciliationScheduler`: Thumbnail Orphans Not Cleaned; Non-Originals Paths Not Skipped: STILL PRESENT (Task 3.6, Step 3)

Unresolved from review 5. Orphaned originals are detected but their corresponding thumbnail keys are not specified in the enqueued delete-job. Non-originals paths (`thumbnails/`) are listed but their UUID parsing produces invalid UUIDs (`{photo_id}_sm`), causing DB errors and log noise.

**Fix:** When an orphan original is found, construct thumbnail keys from the parsed `photo_id` and populate all four delete-job fields. Skip any object path not matching the `originals/` prefix.

---

### MI-5 — V6 Migration `ADD CONSTRAINT CHECK` Without `NOT VALID`: May Block on Existing Data (Task 3.2, Step 2)

Unresolved from review 5. `ALTER TABLE users ADD CONSTRAINT ... CHECK (used_bytes >= 0)` validates all existing rows by default. Any row with `used_bytes < 0` from prior bugs blocks migration.

**Fix:** Add `NOT VALID` to defer row validation, or add a pre-migration cleanup step zeroing out any negative `used_bytes`.

---

### MI-6 — ExifTool Output File Uses `File.delete()` Instead of `Files.deleteIfExists()` (Task 3.5, Step 4) — **NEW**

**Description:** The ExifTool output file cleanup in the `finally` block uses `outputFile.delete()`:

```java
} finally {
    outputFile.delete();
}
```

`File.delete()` returns `false` silently on failure (file in use, permissions error, etc.). The established pattern from Step 3 (`ThumbnailGenerator`) uses `Files.deleteIfExists(tmp)` for consistent failure visibility.

**Fix:** Replace `outputFile.delete()` with `Files.deleteIfExists(outputFile.toPath())` in the ExifTool finally block. Consider also wrapping in a try-catch with ERROR logging to surface filesystem issues.

---

### MI-7 — `File.createTempFile()` Without Explicit Directory for ExifTool Output (Task 3.5, Step 4) — **NEW**

**Description:** `File.createTempFile("exiftool-", ".json")` uses the JVM's `java.io.tmpdir` default, which is typically `/tmp`. However, unlike `Files.createTempFile(Path.of("/tmp"), ...)` used in Step 3 (ThumbnailGenerator), this implicit dependency on `java.io.tmpdir` deviates from the established pattern and could write outside the tmpfs mount if `java.io.tmpdir` is overridden.

**Fix:** Use `File.createTempFile("exiftool-", ".json", new File("/tmp"))` for explicit tmpfs targeting, consistent with the established `Path.of("/tmp")` pattern.

---

### MI-8 — `PhotoJobConsumer` Does Not Guard Against Null `storage_key` Before MinIO Download (Task 3.4, Step 2) — **NEW**

**Description:** Startup recovery re-enqueues photos with `processing_status IN ('pending', 'processing') AND deleted_at IS NULL`. This set may include photos that are stuck between Tx 1 and Tx 2 of the upload (i.e., `storage_key IS NULL` — the compensating-Tx failed and the null-key cleanup hasn't run yet, since it runs hourly). The consumer fetches the photo, proceeds to download from MinIO using the storage key, and calls `storageService.download(null, ...)` — producing a NullPointerException or a malformed MinIO request.

The null-key guard exists in `DeleteJobConsumer` (SA2-F5) but not in `PhotoJobConsumer`.

**Fix:** Add a null guard in `PhotoJobConsumer` after fetching the photo from DB:

```java
if (photo.getStorageKey() == null) {
    log.error("photo {} has null storage_key — XACK and skip; " +
              "TrashPurgeScheduler will clean up", photoId);
    redisCommands.xack(STREAM, GROUP, messageId);
    return;
}
```

Add test: `photoJobConsumer_xacksAndSkipsMessageWithNullStorageKey` — assert XACK is called and no MinIO download is attempted when `storage_key` is null.

---

## 4. Questions for Clarification

1. **CI-1 / CI-4 deferral:** Were the review 5 findings (CI-1, MI-1 through MI-5) intentionally deferred to a v6.0 revision, or inadvertently omitted? The v5.0 changelog references only SA2-F1 through SA2-F5.

2. **Upload temp file location (CI-3):** Does the API container mount a tmpfs at `/tmp`? If not, upload temp files for 200 MB RAW uploads will write to the container's overlay filesystem, which is significantly slower and may have limited space. Should the API's `docker-compose.yml` also declare `tmpfs: - /tmp:size=512m` (or similar)?

3. **`StandardMultipartFile` reset behaviour (CI-3):** Is the project using Spring Boot 3's default multipart resolver (`StandardServletMultipartResolver`) or Apache Commons (`CommonsMultipartResolver`)? The safe buffering approach in the fix is correct for both, but if Commons is already in use, `getInputStream()` is resettable and a lighter approach may be acceptable.

4. **`PROCESSING` on reclaim (CI-4):** Should XAUTOCLAIM reclaim of a `PROCESSING` photo reset the delivery counter in the plan's retry logic? The delivery count is stored in the Redis PEL across all deliveries, so the retry limit still applies — confirm this is the intended behaviour for reclaimed jobs.

---

## 5. Final Recommendation

**Major revisions needed** — four critical issues (two carried from review 5, two new), seven minor issues (five carried, two new).

**Must fix before writing any code:**
- **CI-1:** Replace offset-advancing `Slice<Photo>` pagination in `TrashPurgeScheduler` with stable page-0 `List<Photo>` loop; verify `UnverifiedAccountPurgeScheduler` for the same pattern
- **CI-2:** Replace `Collectors.toMap()` with `HashMap.forEach()` in SA2-F1 sanitization to prevent NPE on null-description EXIF tags
- **CI-3:** Add an explicit upload body buffering step (DigestInputStream → tmpfs temp file) before Tika detection and MinIO upload; document API container tmpfs requirement
- **CI-4:** Accept `PENDING | PROCESSING` as valid initial states in `PhotoJobConsumer`; add null-`storage_key` guard (MI-8); add corresponding tests

**Fix during implementation (low blast radius):**
- MI-1: Replace `Map.of()` with `Map.ofEntries()` in `MIME_TO_EXT`
- MI-2: Choose and document enum storage case; add `AttributeConverter` or drop `toDbValue()`
- MI-3: Add null guards before `Jsoup.parse()` for `rawCaption`, `rawTitle`, `rawDescription`
- MI-4: Specify thumbnail key construction in `OrphanReconciliationScheduler`; skip non-originals paths
- MI-5: Add `NOT VALID` to V6 migration or add pre-migration negative-value cleanup step
- MI-6: Replace `outputFile.delete()` with `Files.deleteIfExists(outputFile.toPath())` in ExifTool finally block
- MI-7: Use `File.createTempFile("exiftool-", ".json", new File("/tmp"))` for explicit tmpfs targeting
