# Critical Implementation Review — Phase 3: Storage & Media (v5.0)
**Source plan:** `2026-02-25-saas-conversion-phase-3.md` (v5.0)
**Review version:** 5
**Reviewer:** Senior Staff Engineer
**Date:** 2026-03-04
**Prior reviews:** `critical-review-1.md` (v1.0 → v2.0), `critical-review-2.md` (v2.0 → v3.0), `critical-review-3.md` (v3.0 → v4.0), `security-audit-1.md` (v3.0 → v4.0), `critical-review-4.md` (v4.0), `security-audit-2.md` (v4.0 → v5.0)

---

## 1. Overall Assessment

v5.0 correctly addresses all five security audit 2 findings (SA2-F1 through SA2-F5). The broad EXIF sanitization, ExifTool stdout redirect, library pinning, `GREATEST(0,...)` floor guard, and null-key skip in `DeleteJobConsumer` are all present, coherent, and well-specified.

However, the v5.0 revision addressed only the security audit 2 findings. The six issues raised in review 4 against v4.0 — one critical and five minor — were **not incorporated**. The most severe is the TrashPurgeScheduler offset-advancing pagination bug (CI-1 from review 4), which remains in the plan unchanged. Additionally, the SA2-F1 broad sanitization introduces a new critical NPE risk via `Collectors.toMap()`.

No prior critical or minor issues from reviews 1–3 or security audit 1 have been inadvertently reopened.

---

## 2. Critical Issues

### CI-1 — TrashPurgeScheduler Offset Pagination While Deleting: STILL PRESENT (Task 3.6, Step 2)

**Description:** This issue was raised in review 4 (against v4.0) and was not addressed in v5.0. The plan still contains the buggy loop verbatim:

```java
Pageable page = PageRequest.of(0, 500);
Slice<Photo> slice;
do {
    slice = photoRepo.findPurgeableBatch(cutoff, page);
    enqueueDeleteJobsBatch(slice.getContent());
    deletePhotosBatch(slice.getContent());
    page = slice.nextPageable();      // ← offset advances to 500, 1000, etc.
} while (slice.hasNext());
```

After `deletePhotosBatch` removes batch 1 (rows at offsets 0–499), those rows are gone. The next query runs `OFFSET 500` against a result set that is now 500 rows shorter — IDs 501–1000 are now at offsets 0–499 and are skipped entirely. `slice.hasNext()` is `false` after the second query returns 0 rows, and the loop exits. All photos beyond the first 500 are left unpurged. Quota is not decremented and MinIO objects are not deleted for those photos.

**Impact:** Silent backlog accumulation. Quota drift grows unbounded on deployments with large trash windows. The scheduler always reports success (no exception), making the bug invisible in logs and metrics.

**Fix (from review 4, reproduced here for completeness):** Always query page 0 — deleted rows vacate position 0 for the next batch. Terminate on an empty batch, not on `hasNext()`:

```java
Pageable page = PageRequest.of(0, 500);
List<Photo> batch;
do {
    batch = photoRepo.findPurgeableBatch(cutoff, page);   // always page 0
    if (batch.isEmpty()) break;
    enqueueDeleteJobsBatch(batch);
    deletePhotosBatch(batch);
} while (!batch.isEmpty());
```

Change the return type of `findPurgeableBatch` from `Slice<Photo>` to `List<Photo>` since `hasNext()` is no longer used. Also verify `UnverifiedAccountPurgeScheduler` for the same offset-pagination-while-deleting pattern.

---

### CI-2 — `Collectors.toMap()` NPE on Null EXIF Values: New Issue from SA2-F1 (Task 3.5, Step 4)

**Description:** The SA2-F1 broad sanitization (new in v5.0) assembles the sanitized EXIF map using:

```java
Map<String, Object> sanitizedExifData = rawExifData.entrySet().stream()
    .collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> e.getValue() instanceof String s ? Jsoup.parse(s).text() : e.getValue()
    ));
```

When `e.getValue()` is `null` (a tag that exists in the raw binary but cannot be decoded or formatted — returned by `metadata-extractor`'s `tag.getDescription()` in several cases, including unrecognised proprietary vendor tags, GPS sub-fields with no data, and formatting errors), the `instanceof String s` pattern match evaluates to `false` and the ternary returns `e.getValue()` — i.e., `null`. `Collectors.toMap()` is implemented via `HashMap.merge()`, which calls `Objects.requireNonNull(value)` and throws `NullPointerException` on a null value (confirmed in Java 11+ source; applies to Java 21). The stream operation aborts with NPE, `MetadataExtractor` throws, and `PhotoJobConsumer` marks the photo `FAILED`.

**Impact:** Any photo whose EXIF metadata contains a null-valued tag fails metadata extraction permanently (until manually reprocessed). This is not a rare corner case: `metadata-extractor` includes tags in directories even when their formatted description is null. The failure produces `processing_status = FAILED` with no meaningful log message differentiating this from a real processing error.

**Fix:** Either filter null values before collecting:

```java
Map<String, Object> sanitizedExifData = rawExifData.entrySet().stream()
    .filter(e -> e.getValue() != null)
    .collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> e.getValue() instanceof String s ? Jsoup.parse(s).text() : e.getValue()
    ));
```

Or use a `HashMap` directly (which allows null values):

```java
Map<String, Object> sanitizedExifData = new HashMap<>();
rawExifData.forEach((k, v) ->
    sanitizedExifData.put(k, v instanceof String s ? Jsoup.parse(s).text() : v));
```

The `HashMap` approach is preferable — it preserves null-valued tags in the JSONB payload (matching the original data structure) rather than silently dropping them. Add a test with a fixture that includes a null-description tag to confirm no NPE.

---

## 3. Minor Issues & Improvements

### MI-1 — `Map.of()` 10-Entry Limit in MIME_TO_EXT Allowlist: STILL PRESENT (Task 3.2, Step 4)

**Description:** Unresolved from review 4. The `MIME_TO_EXT` map still uses `Map.of()` with a note to add the full RAW list at implementation time. `Map.of()` has no overload accepting more than 10 key-value pairs; the full RAW list (RAF, ORF, RW2, PEF, SRF, MRW, X3F, etc.) exceeds 10 entries and produces a compile error.

**Fix:** Replace `Map.of(...)` with `Map.ofEntries(Map.entry(...), ...)`. No limit on entries.

---

### MI-2 — `ProcessingStatus` Enum: `toDbValue()` and `@Enumerated(EnumType.STRING)` Store Different Cases: STILL PRESENT (Task 3.2, Step 1)

**Description:** Unresolved from review 4. `toDbValue()` returns lowercase (`"pending"`, `"failed"`) but `@Enumerated(EnumType.STRING)` calls `name()` — storing uppercase (`"PENDING"`, `"FAILED"`). Any native SQL query or check constraint using lowercase literals returns 0 rows or fails at write time.

**Fix:** Choose one of:
- **Option A (Recommended):** Remove `@Enumerated(EnumType.STRING)`; add a JPA `AttributeConverter` that calls `toDbValue()` on write and parses lowercase on read.
- **Option B:** Remove `toDbValue()` (dead code); use `@Enumerated(EnumType.STRING)` with uppercase-only native SQL literals.

Document the chosen case and confirm the DB check constraint (if any) uses the same case.

---

### MI-3 — Missing Null Guards Before `Jsoup.parse()` for Named IPTC Fields: STILL PRESENT (Task 3.5, Step 4)

**Description:** Unresolved from review 4 (was MI-4 in that review). The per-field sanitization in Step 2 of the MetadataExtractor:

```java
String safeCaption = Jsoup.parse(rawCaption).text();
String safeTitle   = Jsoup.parse(rawTitle).text();
String safeDesc    = Jsoup.parse(rawDescription).text();
```

`Jsoup.parse(null)` throws `NullPointerException`. `rawCaption`, `rawTitle`, and `rawDescription` are absent from most photos (IPTC is rarely embedded). Note that SA2-F1's stream operation (CI-2 fix above) handles null via `instanceof` pattern match, but these three explicit calls do not. Every photo without IPTC metadata fails with NPE at this step.

**Fix:**
```java
String safeCaption = rawCaption != null ? Jsoup.parse(rawCaption).text() : null;
String safeTitle   = rawTitle   != null ? Jsoup.parse(rawTitle).text()   : null;
String safeDesc    = rawDesc    != null ? Jsoup.parse(rawDesc).text()    : null;
```

Or extract a helper: `private String sanitize(String s) { return s != null ? Jsoup.parse(s).text() : null; }`.

---

### MI-4 — `OrphanReconciliationScheduler`: Thumbnail Orphans Not Cleaned; Non-Originals Paths Not Skipped: STILL PRESENT (Task 3.6, Step 3)

**Description:** Unresolved from review 4 (was MI-5 in that review). Two sub-issues:

1. The scheduler iterates `{user_id}/originals/{photo_id}.{ext}` objects. When an orphaned original is detected (no DB row), it enqueues a delete-job. The delete-job schema requires `thumbnail_sm` and `thumbnail_md` fields. The plan does not specify how to construct these keys. If left unset, the corresponding thumbnail objects accumulate indefinitely.

2. MinIO `listObjects` on prefix `{user_id}/` returns ALL objects including `{user_id}/thumbnails/{photo_id}_sm.jpg`. The plan's `photo_id` parsing logic (`between last '/' and '.{ext}'`) extracts `{photo_id}_sm` from thumbnail paths — an invalid UUID — potentially causing DB query errors and log noise.

**Fix (two-part):**
1. When an orphaned original is found, construct thumbnail keys from the parsed `photo_id`:
   ```
   thumbnail_sm = {user_id}/thumbnails/{photo_id}_sm.jpg
   thumbnail_md = {user_id}/thumbnails/{photo_id}_md.jpg
   ```
   Populate all four fields of the delete-job message.
2. Skip any object path that does not match the `originals/` prefix pattern during the listing scan. Thumbnails are handled transitively via the original's delete-job.

---

### MI-5 — V6 Migration `ADD CONSTRAINT CHECK` Without `NOT VALID`: May Block on Existing Data (Task 3.2, Step 2)

**Description:** New issue from SA2-F4.

```sql
ALTER TABLE users ADD CONSTRAINT users_used_bytes_non_negative CHECK (used_bytes >= 0);
```

`ALTER TABLE ADD CONSTRAINT CHECK` in PostgreSQL validates **all existing rows** by default. If the dev/test `users` table contains any row with `used_bytes < 0` — possible on any environment where prior upload bugs produced negative quota values — this migration fails with a constraint violation and blocks deployment.

**Impact:** Migration failure on dev/test environments that have been running since Phase 1/2 with the earlier quota bugs (which motivated the GREATEST fix in the first place). Production is unaffected on a fresh deployment.

**Fix:** Add `NOT VALID` to defer row validation:

```sql
ALTER TABLE users ADD CONSTRAINT users_used_bytes_non_negative
    CHECK (used_bytes >= 0) NOT VALID;
```

After confirming no existing negative values (or after a cleanup step), run `ALTER TABLE users VALIDATE CONSTRAINT users_used_bytes_non_negative` as a separate migration or manual step. Alternatively, note in the plan that a pre-migration cleanup query should zero out any negative `used_bytes` values before V6 runs.

---

## 4. Questions for Clarification

1. **CI-1 deferral intent:** Was the TrashPurgeScheduler pagination fix (CI-1, review 4) intentionally deferred to a future revision, or was it inadvertently omitted from the v5.0 changelog? The v5.0 changelog lists only SA2-F1 through SA2-F5; none of the review 4 findings (CI-1, MI-1 through MI-5) appear.

2. **V6 migration safety (MI-5):** Do any dev/test `users` rows currently have `used_bytes < 0`? If so, the `NOT VALID` approach or a pre-migration cleanup is required.

3. **`rawExifData` null values (CI-2):** Does the `metadata-extractor` map-building code (not shown in the plan) already filter out null-description tags before populating `rawExifData`? If so, CI-2 is lower risk than assessed — but the fix should still be applied defensively.

---

## 5. Final Recommendation

**Major revisions needed** — two critical issues, four unresolved minor issues from review 4, and one new minor issue.

**Must fix before writing any code:**
- **CI-1:** Replace offset-advancing pagination in `TrashPurgeScheduler` with stable page-0 cursor loop; verify `UnverifiedAccountPurgeScheduler` for the same pattern
- **CI-2:** Use `HashMap` directly (or add `.filter(e -> e.getValue() != null)`) in the SA2-F1 stream sanitization to prevent `Collectors.toMap()` NPE on null EXIF values

**Fix during implementation (low blast radius):**
- MI-1: Replace `Map.of()` with `Map.ofEntries()` in `MIME_TO_EXT`
- MI-2: Choose and document enum storage case; add `AttributeConverter` or drop `toDbValue()`
- MI-3: Add null guards before `Jsoup.parse()` for `rawCaption`, `rawTitle`, `rawDescription`
- MI-4: Specify thumbnail key construction in `OrphanReconciliationScheduler`; skip non-originals paths
- MI-5: Add `NOT VALID` to V6 migration or add pre-migration negative-value cleanup step
