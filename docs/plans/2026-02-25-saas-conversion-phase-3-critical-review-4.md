# Critical Implementation Review — Phase 3: Storage & Media (v4.0)
**Source plan:** `2026-02-25-saas-conversion-phase-3.md` (v4.0)
**Review version:** 4
**Reviewer:** Senior Staff Engineer
**Date:** 2026-03-04
**Prior reviews:** `critical-review-1.md` (v1.0 → v2.0), `critical-review-2.md` (v2.0 → v3.0), `critical-review-3.md` (v3.0 → v4.0), `security-audit-1.md` (v3.0 → v4.0)

---

## 1. Overall Assessment

The v4.0 plan resolves all two critical issues and all two minor issues from review 3, and all eight security audit findings, correctly and in sufficient detail. The Lua ownership-checking lock refresh, paginated PEL deduplication, Tika MIME allowlist, worker MinIO least privilege, `Jsoup.parse().text()` sanitization, and `SELECT EXISTS(photo_id)` orphan check are all well-specified. The plan is close to implementation-ready.

One new critical issue is identified: the `TrashPurgeScheduler` batch loop uses offset-based pagination while simultaneously deleting rows from the result set, which causes purgeable photos to be silently skipped when there are more than 500 purgeable photos in a single run. Five minor issues round out the review — none are architectural, all are targeted fixes.

No prior critical or minor issues have been inadvertently reopened.

---

## 2. Critical Issues

### CI-1 — TrashPurgeScheduler Offset Pagination While Deleting: Purgeable Photos Silently Skipped (Task 3.6, Step 2)

**Description:** The batch purge loop reads:

```java
Pageable page = PageRequest.of(0, 500);
Slice<Photo> slice;
do {
    slice = photoRepo.findPurgeableBatch(cutoff, page);
    enqueueDeleteJobsBatch(slice.getContent());
    deletePhotosBatch(slice.getContent());
    page = slice.nextPageable();     // ← offset advances to 500, then 1000, etc.
} while (slice.hasNext());
```

`findPurgeableBatch` is an offset-based query of the form `SELECT ... WHERE deleted_at < :cutoff ORDER BY id LIMIT 500 OFFSET :offset`. After `deletePhotosBatch` removes batch 1 (IDs 1–500), `page = slice.nextPageable()` advances the offset to 500. The next query runs `OFFSET 500` against a result set that now has only the original IDs 501–1000 (which are now at offsets 0–499). The query skips all of them and returns 0 rows. `slice.hasNext()` is false. The loop exits, and IDs 501–1000 are left unpurged.

Concretely: if there are 600 purgeable photos, only the first 500 are purged in the run. The remaining 100 are caught the following day. In the worst case (thousands of photos past retention on first production run, or during catch-up after a downtime), the scheduler permanently lags by one batch per day, never clearing the backlog.

**Impact:** Purgeable photos accumulate in the trash. User quota is not decremented on schedule. MinIO objects are not deleted on schedule. The scheduler always reports success (no exception thrown), making the bug invisible in logs.

**Fix:** Since rows are being deleted each iteration, always query page 0 — the next set of purgeable rows comes to position 0 after each deletion. Replace:

```java
page = slice.nextPageable();
```

with:

```java
// Do NOT advance the offset — deleted rows vacate position 0 for the next batch
// page remains PageRequest.of(0, 500)
```

The `slice.hasNext()` check must also change: after deletion, `hasNext()` reflects the state of the current slice (before deletion), not the remaining rows. The correct termination condition is whether the current batch is non-empty:

```java
Pageable page = PageRequest.of(0, 500);
List<Photo> batch;
do {
    batch = photoRepo.findPurgeableBatch(cutoff, page);   // always page 0
    if (batch.isEmpty()) break;
    enqueueDeleteJobsBatch(batch);                         // Lettuce pipeline
    deletePhotosBatch(batch);
} while (!batch.isEmpty());
```

Apply the same fix to any other scheduler that paginates-while-deleting. `UnverifiedAccountPurgeScheduler` should be reviewed for the same pattern.

---

## 3. Minor Issues & Improvements

### MI-1 — `Map.of()` Limited to 10 Entries: MIME Allowlist Will Exceed the Limit (Task 3.2, Step 4)

**Description:** The MIME-to-extension allowlist uses `Map.of()`:

```java
private static final Map<String, String> MIME_TO_EXT = Map.of(
    "image/jpeg", "jpg",
    "image/png", "png",
    "image/tiff", "tiff",
    "image/x-canon-cr2", "cr2",
    "image/x-nikon-nef", "nef",
    "image/x-sony-arw", "arw",
    "image/x-adobe-dng", "dng"
    // define full RAW list at implementation time
);
```

`Map.of()` has no overload for more than 10 key-value pairs. Tika recognises at least 15–20 common RAW formats (RAF, ORF, RW2, PEF, SRF, MRW, X3F, etc.). Extending the `Map.of()` call beyond 10 entries is a compile error.

**Impact:** The naive "fill in the full RAW list" instruction at implementation time produces a build failure. The developer must either truncate the RAW support list or restructure the map.

**Fix:** Replace `Map.of()` with `Map.ofEntries()`:

```java
private static final Map<String, String> MIME_TO_EXT = Map.ofEntries(
    Map.entry("image/jpeg",         "jpg"),
    Map.entry("image/png",          "png"),
    Map.entry("image/tiff",         "tiff"),
    Map.entry("image/x-canon-cr2",  "cr2"),
    Map.entry("image/x-nikon-nef",  "nef"),
    Map.entry("image/x-sony-arw",   "arw"),
    Map.entry("image/x-adobe-dng",  "dng")
    // add full RAW list here — no limit
);
```

`Map.ofEntries()` supports any number of entries. Update the code example in Task 3.2, Step 4 accordingly.

---

### MI-2 — `ProcessingStatus` Enum: `toDbValue()` Returns Lowercase but `@Enumerated(EnumType.STRING)` Stores Uppercase (Task 3.2, Step 1)

**Description:** The plan defines:

```java
public enum ProcessingStatus {
    PENDING, PROCESSING, DONE, FAILED;

    public String toDbValue() {
        return name().toLowerCase();   // returns "pending", "processing", etc.
    }
}
```

And instructs: `@Enumerated(EnumType.STRING)` on the JPA entity. With `@Enumerated(EnumType.STRING)`, JPA calls `name()` — not `toDbValue()` — when persisting, storing `"PENDING"` (uppercase). Any native SQL query or DB-level check constraint using lowercase values (`WHERE processing_status = 'pending'`) returns no results. If the `photos.processing_status` column has a check constraint from Phase 1/2 that accepts lowercase only, all JPA writes fail at runtime.

**Impact:** Either silent query mismatch (queries return 0 rows when using lowercase literals) or constraint violation on every JPA write, depending on the DB schema.

**Fix:** Choose one approach and document it explicitly:

- **Option A (Recommended): JPA `AttributeConverter` to store lowercase.** Remove `@Enumerated(EnumType.STRING)` and add a converter that calls `toDbValue()` on write and maps lowercase string back on read. This is the standard pattern for non-default enum storage.
- **Option B: Store uppercase.** Remove `toDbValue()` (it's unused in practice), use `@Enumerated(EnumType.STRING)`, and ensure all native SQL uses uppercase literals (`'PENDING'`, `'FAILED'`, etc.).

Either is correct; the plan must pick one and call it out so the DB check constraint (if any) and all native SQL in schedulers and workers use the same case.

---

### MI-3 — Null-`storage_key` Cleanup Enqueues Delete-Jobs Without a Storage Key (Task 3.6, Step 2)

**Description:** The null-`storage_key` recovery section instructs:

```
For each batch:
1. Enqueue Redis delete-jobs (Lettuce pipeline) — jobs are in Redis before any DB row is removed
2. Execute a single SQL CTE ...
```

However, for null-`storage_key` rows the `original_key`, `thumbnail_sm`, and `thumbnail_md` fields are unknown — the MinIO upload either never completed (upload failed before MinIO step) or completed but `storage_key` was never persisted (Tx 2 failure). The delete-job schema requires all four fields (`photo_id`, `original_key`, `thumbnail_sm`, `thumbnail_md`). `DeleteJobConsumer` will receive `null` for all three key fields and either throw an NPE or perform no-op deletes.

The OrphanReconciliationScheduler (weekly) already handles any truly orphaned MinIO objects — objects without a DB row — so there is no need to enqueue a delete-job for these rows.

**Impact:** NPE or no-op in `DeleteJobConsumer` for delete-jobs produced by null-`storage_key` cleanup. If the consumer is not null-safe, this creates a dead-letter entry and blocks further processing.

**Fix:** Clarify in the plan that null-`storage_key` rows skip the delete-job enqueue step entirely. The CTE handles the quota+row cleanup atomically. Any orphaned MinIO object (from a Tx 2 failure) is caught by `OrphanReconciliationScheduler` on its weekly run. Update the description:

```
For each null-storage_key batch:
1. (No delete-job enqueue — storage_key unknown; OrphanReconciliation handles any MinIO objects)
2. Execute the SQL CTE to atomically delete the row and decrement used_bytes
```

---

### MI-4 — `Jsoup.parse(rawValue).text()` Missing Null Guard: NPE on Absent IPTC Fields (Task 3.5, Step 4)

**Description:** The sanitization code:

```java
String safeCaption = Jsoup.parse(rawCaption).text();
String safeTitle   = Jsoup.parse(rawTitle).text();
String safeDesc    = Jsoup.parse(rawDescription).text();
```

If any of `rawCaption`, `rawTitle`, or `rawDescription` is `null` (absent from the photo's IPTC/XMP metadata — the common case for photos without embedded metadata), `Jsoup.parse(null)` throws `NullPointerException`.

**Impact:** `MetadataExtractor` crashes on any photo without IPTC caption/title/description, producing `processing_status = FAILED` for the majority of real-world photos.

**Fix:** Add null guards before parsing:

```java
String safeCaption = rawCaption != null ? Jsoup.parse(rawCaption).text() : null;
String safeTitle   = rawTitle   != null ? Jsoup.parse(rawTitle).text()   : null;
String safeDesc    = rawDesc    != null ? Jsoup.parse(rawDesc).text()    : null;
```

Or use a helper: `private String sanitize(String raw) { return raw != null ? Jsoup.parse(raw).text() : null; }`. Update Task 3.5, Step 4 with the null-safe form.

---

### MI-5 — `OrphanReconciliationScheduler` Does Not Address Thumbnail Orphans (Task 3.6, Step 3)

**Description:** The orphan check iterates `{user_id}/originals/{photo_id}.{ext}` paths and checks `SELECT EXISTS(SELECT 1 FROM photos WHERE id = :photo_id)`. For true orphans (no DB row), it enqueues a delete-job. The delete-job schema includes `thumbnail_sm` and `thumbnail_md` fields.

However, the scheduler only iterates originals paths. MinIO `listObjects` returns all objects under `{user_id}/` — including `{user_id}/thumbnails/{photo_id}_sm.jpg` and `{user_id}/thumbnails/{photo_id}_md.jpg`. For non-orphan photos these are handled by the normal delete-job pipeline. But if a photo is a true orphan (no DB row), the scheduler:

1. Detects the original via the originals path ✓
2. Enqueues a delete-job with `original_key` set to the originals path

But the delete-job needs `thumbnail_sm` and `thumbnail_md` to be explicitly set. The plan doesn't specify how these are constructed for orphaned originals found via MinIO listing. If not constructed, only the original is deleted; the orphaned thumbnail objects accumulate indefinitely.

Additionally, if the listing returns a thumbnail path (`thumbnails/…`) before its corresponding original, the scheduler would try to parse a `photo_id` from the thumbnail filename pattern (which differs: `{photo_id}_sm.jpg` vs `{photo_id}.{ext}`). The plan's parsing logic (`between last '/' and '.{ext}'`) would extract `{photo_id}_sm` as the UUID, which is invalid, potentially causing a DB query with a malformed UUID.

**Impact:** Orphaned thumbnail objects never cleaned up. Potential UUID parse error for thumbnail paths in the listing.

**Fix — specify two things:**
1. When an orphaned original is found, construct the expected thumbnail keys from the parsed `photo_id` and populate all four delete-job fields:
   ```
   original_key  = the MinIO key from the listing
   thumbnail_sm  = {user_id}/thumbnails/{photo_id}_sm.jpg
   thumbnail_md  = {user_id}/thumbnails/{photo_id}_md.jpg
   ```
2. Skip any MinIO object path that does not match the `originals/` prefix pattern during the listing scan (thumbnails are handled transitively via the original's delete-job).

---

## 4. Questions for Clarification

1. **TrashPurgeScheduler fix scope (CI-1):** Does `UnverifiedAccountPurgeScheduler` also paginate-while-deleting? The plan says "Delete users where `email_verified = false`" without explicit pagination. If the user count is always small this may be fine, but worth confirming whether a page-0 cursor loop should be added for consistency.

2. **ProcessingStatus storage case (MI-2):** What case does the existing `photos.processing_status` column use? If Phase 1/2 migrations defined it as `VARCHAR` with no check constraint, Option B (uppercase, no converter) is simpler. If a check constraint or existing data uses lowercase, Option A (converter) is required.

3. **Delete-job enqueue for null-`storage_key` rows (MI-3):** Confirm the intent: the paragraph "1. Enqueue Redis delete-jobs (Lettuce pipeline)" in the null-`storage_key` cleanup section — does it apply only to the main trash purge above it, or is it also intended for the null-`storage_key` rows? If the latter, how should the consumer handle null keys?

---

## 5. Final Recommendation

**Approve with changes** — one targeted fix required before implementation begins; four minor fixes during implementation.

**Must fix before writing any code:**
- CI-1: Replace offset-advancing pagination with a stable page-0 cursor loop in `TrashPurgeScheduler`; verify `UnverifiedAccountPurgeScheduler` for the same pattern

**Fix during implementation (low blast radius, no architectural impact):**
- MI-1: Replace `Map.of()` with `Map.ofEntries()` in `MIME_TO_EXT` allowlist
- MI-2: Choose and document enum storage case; add JPA `AttributeConverter` or confirm uppercase-only convention
- MI-3: Clarify/remove delete-job enqueue for null-`storage_key` rows
- MI-4: Add null guards before `Jsoup.parse()` calls in `MetadataExtractor`
- MI-5: Specify thumbnail key construction in `OrphanReconciliationScheduler`; skip non-originals paths in listing scan
