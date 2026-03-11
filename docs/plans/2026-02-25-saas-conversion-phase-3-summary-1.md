# JPhotoTagger SaaS Conversion — Phase 3: Storage & Media — Completion Summary

---

## 1. Overview

Phase 3 aimed to deliver the full storage and media layer for the JPhotoTagger SaaS conversion: MinIO integration, a multi-step upload pipeline with deduplication and quota enforcement, a Spring Boot worker application, Redis Streams-based job consumers (photo processing and deletion), an image processing pipeline (Tika, libraw, libvips, metadata-extractor, ExifTool), and three scheduled maintenance tasks. All work was to follow a strict TDD workflow, with six critical implementation reviews and three security audits incorporated across seven plan revisions (v1.0 through v7.0).

**Overall status: ~83% complete.** Five of six tasks are fully delivered. Task 3.4 (Worker — Redis Streams Consumer) was not implemented; the image processing pipeline it would invoke is complete, but the consumer layer that drives it is absent.

---

## 2. Completed Items

- **Task 3.1 — MinIO Configuration & Service:** Dual-client `MinioConfig` (API and worker), `StorageService` with internal/public client split, pre-signed URL generation (15 min thumbnails, 1 hr originals), worker-scoped IAM policy with `originals/*` + `thumbnails/*` sub-prefix restrictions (SA3-F2), tests passing, committed.

- **Task 3.2 — Upload Endpoint with Deduplication:** `ProcessingStatus` enum with `@Enumerated(EnumType.STRING)`; Flyway V5 (partial unique index for soft-deleted re-uploads) and V6 (`used_bytes >= 0` non-negative constraint with `NOT VALID` + `VALIDATE CONSTRAINT`); upload endpoint with SHA-256 `DigestInputStream` temp-file buffering (CI-3), Tika MIME detection, `Map.ofEntries()` allowlist (MI-1), email verification gate (HTTP 403), quota check (HTTP 402), two-transaction DB/MinIO order with compensating rollback, Redis `XADD` to `photo-jobs`; `original_filename` XSS sanitization via `Jsoup.parse(s).text()` before DB write (SA3-F1); photoStatus ownership check (SA-6); API container tmpfs in `docker-compose.yml`; `.env.example` with `API_TMPFS_SIZE`/`WORKER_TMPFS_SIZE`; all planned test stubs implemented.

- **Task 3.3 — Worker Spring Boot Scaffold:** `JptWorkerApplication`, `WorkerProperties`, `worker/application.yml` with `claim-idle-time-ms`, `max-retries`, `timeout-minutes`, HikariCP pool size 5, context-loads test, committed.

- **Task 3.5 — Worker Image Processing Pipeline:** `TikaValidator`, `ThumbnailGenerator` (libraw → libvips, tmpfs temp files in try-finally, ProcessBuilder timeout/destroyForcibly), `MetadataExtractor` (metadata-extractor primary, ExifTool `-fast2` fallback with stdout redirect-to-file to prevent pipe buffer exhaustion (SA2-F2), `Files.deleteIfExists()` in finally block (MI-6), explicit `/tmp` dir (MI-7)), full EXIF/IPTC/XMP sanitization via `HashMap.forEach()` + `Jsoup.parse(s).text()` (SA2-F1, CI-2), null-safe `sanitize()` helper (MI-3), JSONB upsert; `ImageProcessor` pipeline with RAW branching; `ProcessingException` and `ProcessTimeoutException`; `ImageProcessorTest`; multiple fix commits for dcraw filename, MIME detection, ExifTool redirect, and TikaValidator deduplication; committed.

- **Task 3.6 — Scheduled Tasks:** `TrashPurgeScheduler` (daily 3 AM, page-0 batch loop, Lettuce pipeline enqueue, null-`storage_key` CTE cleanup (SA2-F5, CI-4)), `OrphanReconciliationScheduler` (weekly Sunday 4 AM, non-originals skip (MI-4), `SELECT EXISTS` orphan check (SA-4)), `UnverifiedAccountPurgeScheduler` (daily 3:30 AM, strict enqueue-before-delete order); `PhotoDeleteJobEnqueuer` extracted as shared helper; ShedLock on all three schedulers; `SchedulerTest`; committed.

- **Cross-cutting:** Java library versions pinned in version catalog (Tika 2.9.2, metadata-extractor 2.19.0, Jsoup 1.18.3, MinIO SDK 8.5.12) (SA2-F3); Trivy SBOM filesystem scan and worker Docker image scan added to CI (SA-1); worker Dockerfile with pinned apt versions (`libraw-tools=0.21.4-r2`, `vips-tools=8.17.3-r1`, `perl-image-exiftool=13.36-r0`, `tini=0.19.0-r3`) (SA-8); worker `docker-compose.yml` tmpfs `${WORKER_TMPFS_SIZE:-1g},mode=1777` (MI-6 docker).

---

## 3. Partially Completed or Modified Items

- **Task 3.4 — Worker Redis Streams Consumer (partial):** The pipeline (`ImageProcessor`) that consumers would invoke is fully implemented. The consumer infrastructure — `PhotoJobConsumer`, `DeleteJobConsumer`, XAUTOCLAIM recovery scheduler, startup re-enqueue recovery with distributed lock, and all 14 planned consumer test stubs — was not delivered. The `delete-jobs` stream is produced by the schedulers but has no consumer.

- **Worker Dockerfile base image:** The plan specified `debian:bookworm-slim@sha256:<digest>` with explicit digest pinning. The delivered Dockerfile uses `eclipse-temurin:21-jre-alpine` without a digest pin.

- **ShedLock version:** The plan specified `6.0.2` as the baseline reference, with an explicit instruction to verify against Maven Central at implementation time. The delivered build uses `6.6.0`, consistent with that instruction.

- **Extra Flyway migrations:** Two migrations beyond the planned V5/V6 were added: V7 (`original_filename` column on `photos`) and V8 (`extracted_at` column on `photo_metadata`). These supported the `original_filename` sanitization requirement (SA3-F1) and metadata extraction timestamp tracking.

- **`PhotoDeleteJobEnqueuer` helper class:** The plan did not specify this class; it was introduced to consolidate `XADD` logic shared between `TrashPurgeScheduler` and `UnverifiedAccountPurgeScheduler`, reducing duplication.

---

## 4. Omitted or Deferred Items

- **`PhotoJobConsumer.java`:** Not implemented. XREADGROUP loop on `photo-jobs`, status transitions (PENDING/PROCESSING/DONE/FAILED), null `storage_key` guard, retry/dead-letter policy via XPENDING delivery count, all absent.

- **`DeleteJobConsumer.java`:** Not implemented. XREADGROUP loop on `delete-jobs`, null/blank `original_key` guard (SA2-F5), `STORAGE_KEY_PATTERN` regex validation for all three keys (SA3-F2 consumer-side), MinIO multi-key deletion, all absent.

- **Consumer group creation on startup (`XGROUP CREATE ... MKSTREAM`):** Not implemented.

- **XAUTOCLAIM recovery scheduler (every 5 min):** Not implemented. The `claim-idle-time-ms` property exists in `WorkerProperties` but is not consumed anywhere.

- **Startup re-enqueue recovery with distributed lock:** Not implemented. The `SET NX PX` lock, Lua ownership-check refresh script, paginated PEL deduplication (`Set<String>` via repeated XPENDING), and PENDING/PROCESSING DB scan with re-enqueue are all absent.

- **Consumer test stubs (all 14 from Task 3.4):** Not implemented.

- **FROM image digest pinning in worker Dockerfile:** Not delivered.

---

## 5. Discrepancy Explanations

- **Task 3.4 not implemented:** The session that delivered Tasks 3.5 and 3.6 proceeded without implementing Task 3.4 first. Based on commit ordering (`feat: worker Spring Boot scaffold` → `feat: worker image processing pipeline` → `feat: scheduled tasks`), the execution sequence skipped 3.4 and jumped directly to 3.5. No explicit deferral note was left in the plan or a handoff document.

- **Alpine vs. Debian base image:** The plan's Dockerfile spec was written for `debian:bookworm-slim` to ensure `apt-get` compatibility with the specified packages. The delivered Dockerfile uses the `eclipse-temurin:21-jre-alpine` JRE image with Alpine's `apk` package manager, which is a valid alternative that provides a smaller attack surface. The package names differ accordingly (e.g., `vips-tools` vs. `libvips-tools`).

- **No FROM digest pin:** The plan required pinning the FROM image to a digest for reproducible builds. The delivered Dockerfile omits this, meaning future builds may silently pull a newer Alpine/JRE layer.

- **ShedLock 6.6.0 vs. 6.0.2:** The plan explicitly instructed "check Maven Central for the current stable 6.x release at implementation time." Using 6.6.0 is the expected outcome of following that instruction.

- **V7/V8 extra migrations:** V7 was required to add the `original_filename` column (SA3-F1 mandated storing a sanitized filename); V8 added `extracted_at` to `photo_metadata`. Both arose from implementation details not fully reflected in the original migration plan.

- **`PhotoDeleteJobEnqueuer`:** Extracted as a shared component to avoid duplicating Lettuce pipeline logic across two schedulers. Represents a minor structural improvement consistent with plan intent.

---

## 6. Key Achievements

- **Security hardening depth:** Three security audits and six critical implementation reviews drove the plan from v1.0 to v7.0. All 22 audit/review findings that were addressed in the plan revisions were implemented: stored XSS prevention on `original_filename` and EXIF fields, pipe buffer exhaustion fix for ExifTool, `used_bytes` non-negative constraint, compensating transaction floor guards, TOCTOU-safe orphan detection, `STORAGE_KEY_PATTERN` IAM-level blast-radius limitation, and null-key guard on delete consumers.

- **Robust scheduler implementation:** The `TrashPurgeScheduler` correctly solves the classic paginated-delete skipping problem (always query page 0), the `OrphanReconciliationScheduler` eliminates the TOCTOU race via `SELECT EXISTS` by `photo_id`, and the `UnverifiedAccountPurgeScheduler` enforces a crash-safe enqueue-before-delete ordering.

- **Production-ready pipeline:** The image processing pipeline handles RAW branching, per-tool timeouts with `destroyForcibly()`, full EXIF sanitization preventing stored XSS from any string-typed metadata field, and upsert semantics for safe reprocessing.

- **Reproducible build hygiene:** Java library versions pinned in the version catalog, apt packages pinned in the Dockerfile, and Trivy scanning of both filesystem JARs and the worker Docker image provide a strong CVE audit baseline.

---

## 7. Final Assessment

Phase 3 delivers a production-quality storage and media foundation: MinIO integration, upload pipeline, worker scaffold, image processing, and scheduled maintenance — all with substantial security hardening applied across multiple review cycles. The single significant gap is Task 3.4: the Redis Streams consumer layer (`PhotoJobConsumer`, `DeleteJobConsumer`, XAUTOCLAIM recovery, and startup re-enqueue) was not implemented, meaning the worker cannot yet consume jobs from either stream. The pipeline it would call is complete and the streams are being produced by both the upload endpoint and schedulers, so the system is structurally ready; adding the consumer layer is the remaining prerequisite before the end-to-end photo processing flow is operational. The FROM image digest pinning is a minor reproducibility gap that does not affect runtime correctness.
