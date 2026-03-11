# Security Audit 4 — Phase 3: Storage & Media Implementation

**Auditor:** Lead Cyber-Security Auditor (LCSA)
**Date:** 2026-03-10
**Audit scope:** Implemented code only. Plan text is referenced only to verify security requirements were met.
**Prior audits:** SA1 (2026-03-04), SA2 (2026-03-04), SA3 (2026-03-05) — findings SA-1 through SA3-F3 addressed in plan v4.0–v7.0.

---

## Materials Audited

| File | Role |
|------|------|
| `api/.../config/MinioConfig.java` | Dual-client MinIO configuration |
| `api/.../service/StorageService.java` | MinIO I/O + presigned URL generation |
| `api/.../service/PhotoService.java` | Upload pipeline, quota, dedup |
| `api/.../controller/PhotoController.java` | HTTP endpoints |
| `api/.../scheduler/TrashPurgeScheduler.java` | Trash purge + null-key cleanup |
| `api/.../scheduler/OrphanReconciliationScheduler.java` | MinIO orphan detection |
| `api/.../scheduler/UnverifiedAccountPurgeScheduler.java` | Unverified account cleanup |
| `api/.../scheduler/PhotoDeleteJobEnqueuer.java` | Delete-job Redis publisher |
| `worker/.../consumer/ConsumerConfig.java` | Lettuce connection + consumer beans |
| `worker/.../consumer/ConsumerScheduler.java` | Scheduled poll loops |
| `worker/.../consumer/PhotoJobConsumer.java` | Photo processing consumer |
| `worker/.../consumer/DeleteJobConsumer.java` | MinIO delete consumer |
| `worker/.../pipeline/ImageProcessor.java` | Pipeline orchestrator |
| `worker/.../pipeline/TikaValidator.java` | MIME validation |
| `worker/.../pipeline/ThumbnailGenerator.java` | libraw + libvips thumbnails |
| `worker/.../pipeline/MetadataExtractor.java` | EXIF/IPTC/XMP extraction |
| `worker/.../config/WorkerProperties.java` | Typed config |
| `worker/.../config/MinioConfig.java` | Worker MinIO client |
| `api/src/main/resources/application.yml` | API config |
| `worker/src/main/resources/application.yml` | Worker config |
| `worker/Dockerfile` | Worker container definition |
| `docker-compose.yml` | Full deployment topology |
| `db/migration/V1–V8__*.sql` | Schema |
| `.github/workflows/ci.yml` | CI pipeline |

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points
- **HTTP API** (`PhotoController`): `POST /photos/upload`, `GET /photos/{id}`, `GET /photos/{id}/status`, `DELETE /photos/{id}`, `POST /photos/{id}/restore`, `GET /photos/trash`
- **Redis Streams** (`photo-jobs`): consumed by `PhotoJobConsumer`
- **Redis Streams** (`delete-jobs`): consumed by `DeleteJobConsumer`
- **Scheduled Jobs**: `TrashPurgeScheduler` (daily 3 AM), `OrphanReconciliationScheduler` (weekly), `UnverifiedAccountPurgeScheduler` (daily 3:30 AM)
- **MinIO object listing** (`OrphanReconciliationScheduler`): object keys from MinIO treated as partially untrusted (key structure parsed)

### Trust Boundaries
- Internet → nginx → API (JWT-authenticated)
- API → Redis Streams → Worker (internal Docker network)
- Worker → PostgreSQL (restricted `jpt_worker` role)
- Worker → MinIO (scoped worker credentials: originals/\* + thumbnails/\* only)
- Scheduled jobs → MinIO (full API credentials via `minioInternalClient`)

### Technology Stack
Java 21, Spring Boot 3, JPA/Hibernate, Lettuce, Apache Tika, metadata-extractor, Jsoup, MinIO SDK, ExifTool (subprocess), libraw (subprocess), libvips (subprocess), PostgreSQL 16, Redis 7, Alpine Linux worker container.

---

## Pass 2 & 3: Findings

---

### Finding #1: `softDelete()` Race Condition Enables Quota Double-Decrement

**Vulnerability:** Race Condition / TOCTOU — OWASP A08 (Software and Data Integrity Failures) / Business Logic
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: `api/src/main/java/org/jphototagger/api/service/PhotoService.java`, Lines 271–286

**Risk & Exploit Path:**
An authenticated user can issue two concurrent `DELETE /photos/{id}` requests for the same photo. Both requests pass through `getPhoto()` before the user row lock is acquired. When both threads reach `SELECT FOR UPDATE` on the user row, they serialize on the lock, but both already hold an in-memory `Photo` object with `deletedAt == null`. After Thread 1 commits (sets `deletedAt`, decrements `used_bytes`), Thread 2 acquires the lock, re-reads the user row with Thread 1's updated `used_bytes`, then decrements again based on the stale in-memory photo. The result is a double-decrement of `used_bytes`.

**Evidence / Trace:**
```java
// getPhoto() — no lock on the photo row
@Transactional(readOnly = true)
public Photo getPhoto(UUID userId, UUID photoId) {
    return photoRepository.findById(photoId)           // ← photo loaded, no lock
            .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null)
            .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
}

// softDelete() — user lock acquired AFTER photo is fetched
@Transactional
public void softDelete(UUID userId, UUID photoId) {
    Photo photo = getPhoto(userId, photoId);           // ← stale photo; no row lock

    User user = entityManager.createQuery(...)
            .setLockMode(PESSIMISTIC_WRITE)            // ← serializes here, but too late
            .getSingleResult();

    photo.setDeletedAt(Instant.now());
    photoRepository.save(photo);                       // ← idempotent (sets deletedAt again)
    user.setUsedBytes(Math.max(0, user.getUsedBytes() - photo.getSizeBytes())); // ← ← SECOND DECREMENT
    userRepository.save(user);
}
```

**Race timeline:**
1. Thread 1: `findById(photoId)` → photo.deletedAt = null, photo.sizeBytes = 500 MB
2. Thread 2: `findById(photoId)` → photo.deletedAt = null (before Thread 1 commits)
3. Thread 1: acquires user lock, decrements used_bytes from 1 GB → 500 MB, commits
4. Thread 2: acquires user lock (fresh read: used_bytes = 500 MB), decrements again → 0 MB
5. Net: used_bytes = 0 MB; MinIO retains the file; quota appears exhausted = 0

**Impact:** Attacker can drive `used_bytes` toward 0 across multiple race cycles, making their storage appear nearly empty. Combined with V6's non-negative constraint, `used_bytes` is floored at 0 rather than going negative, but the attacker gains effectively free quota. No cascade to other users; requires authentication.

**Remediation:**
- Primary fix: Acquire the user lock before reading the photo, then re-validate photo ownership and `deletedAt` inside the same transaction after the lock is held:
  ```java
  @Transactional
  public void softDelete(UUID userId, UUID photoId) {
      // Lock user row first — serializes all concurrent soft-deletes for this user
      User user = entityManager.createQuery("SELECT u FROM User u WHERE u.id = :uid", User.class)
              .setParameter("uid", userId)
              .setLockMode(PESSIMISTIC_WRITE)
              .getSingleResult();
      // Re-read photo inside the locked transaction
      Photo photo = photoRepository.findById(photoId)
              .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null)
              .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
      photo.setDeletedAt(Instant.now());
      photoRepository.save(photo);
      user.setUsedBytes(Math.max(0, user.getUsedBytes() - photo.getSizeBytes()));
      userRepository.save(user);
  }
  ```
- Architectural improvement: Add a `@Version` column to `Photo` for optimistic locking as a defense-in-depth backstop against concurrent writes.
- Defense-in-depth: The same pattern should be verified in `restore()` — that method also loads the photo before locking the user, though the quota overflow guard (`newUsed > user.getQuotaBytes()`) provides a partial backstop in that direction.

**References:**
- CWE-362 (Concurrent Execution Using Shared Resource with Improper Synchronization)

---

### Finding #2: `getPhotoStatus()` Ordering Leaks Photo Existence for Cross-Tenant IDs

**Vulnerability:** IDOR Information Disclosure — OWASP A01 (Broken Access Control)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low (once a UUID is known)

**Location:**
- File: `api/src/main/java/org/jphototagger/api/service/PhotoService.java`, Lines 260–268

**Risk & Exploit Path:**
`getPhotoStatus()` filters on `deletedAt == null` before checking ownership. This creates an asymmetric response:
- UUID is an active photo belonging to another user → `AccessDeniedException` → HTTP 403
- UUID doesn't exist, or belongs to the caller but is deleted → `EntityNotFoundException` → HTTP 404

An attacker who possesses a specific UUID (from a revoked share link, a previous data context, or a bug report) can determine whether that photo is currently active (non-deleted) on the platform, regardless of ownership. Contrast with `getPhoto()`, which uses a combined filter: `p.getUserId().equals(userId) && p.getDeletedAt() == null` — both conditions are evaluated atomically, making all failure cases indistinguishable (always 404).

**Evidence / Trace:**
```java
// VULNERABLE — two-phase check
public Photo getPhotoStatus(UUID userId, UUID photoId) {
    Photo photo = photoRepository.findById(photoId)
            .filter(p -> p.getDeletedAt() == null)    // ← phase 1: existence + liveness check
            .orElseThrow(() -> new EntityNotFoundException("Photo not found")); // 404
    if (!photo.getUserId().equals(userId)) {           // ← phase 2: ownership check
        throw new AccessDeniedException("Access denied"); // ← VULNERABLE: 403 reveals liveness
    }
    return photo;
}

// CORRECT pattern used in getPhoto()
public Photo getPhoto(UUID userId, UUID photoId) {
    return photoRepository.findById(photoId)
            .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null) // ← atomic
            .orElseThrow(() -> new EntityNotFoundException("Photo not found"));    // always 404
}
```

**Practical exploitability:** Low — photo IDs are UUIDs (128-bit random), making blind enumeration infeasible. Exploitation requires prior knowledge of a specific UUID. Impact is limited to confirming a single photo's liveness; no data exfiltration possible.

**Remediation:**
- Primary fix: Apply the same combined filter used in `getPhoto()`:
  ```java
  public Photo getPhotoStatus(UUID userId, UUID photoId) {
      return photoRepository.findById(photoId)
              .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null)
              .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
  }
  ```

**References:**
- CWE-200 (Exposure of Sensitive Information to an Unauthorized Actor)

---

### Finding #3: API `minioPublicClient` Carries Full `readwrite` Credentials on a Public-Facing Endpoint

**Vulnerability:** Excessive Credential Scope — OWASP A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High (requires secondary credential leak)

**Location:**
- File: `api/src/main/java/org/jphototagger/api/config/MinioConfig.java`, Lines 45–51
- Related: `docker-compose.yml`, Line 171 (`mc admin policy attach minio readwrite --user $$MINIO_API_ACCESS_KEY`)

**Risk & Exploit Path:**
`minioPublicClient` is configured with the API's full `readwrite`-scoped MinIO credentials and connects to `${MINIO_PUBLIC_URL}` — the internet-accessible MinIO endpoint (MinIO must be reachable from browsers for presigned URLs to function). The credential bound to this client can `GetObject`, `PutObject`, and `DeleteObject` on the entire `jpt-photos` bucket.

The public client is used exclusively for `getPresignedObjectUrl()`, which is a pure HMAC computation requiring no network call. The credentials are never transmitted in presigned URLs themselves (only the access key ID and a time-limited signature are embedded). However, the `minioPublicClient` bean is now a live Java object with full-bucket write/delete access against an internet-accessible endpoint. If:
1. Any future code accidentally calls `minioPublicClient.removeObject()` (instead of `minioInternalClient`), it succeeds silently and destructively against the public endpoint.
2. The credentials are leaked from any other vector (environment variable dump, misconfigured logging, container escape), an attacker can make full-bucket destructive calls via the public MinIO URL.

**Evidence / Trace:**
```java
// MinioConfig.java
@Bean("minioPublicClient")
public MinioClient minioPublicClient() {
    return MinioClient.builder()
            .endpoint(publicUrl)               // ← internet-accessible MinIO URL
            .credentials(accessKey, secretKey) // ← same full readwrite credentials as internalClient
            .build();
}

// docker-compose.yml — API access key has full readwrite policy
(mc admin policy attach minio readwrite --user $$MINIO_API_ACCESS_KEY || true) // ← readwrite = GetObject+PutObject+DeleteObject on all keys
```

The worker, by contrast, correctly uses a scoped policy (`originals/*` + `thumbnails/*` only). The API's public client has no such scoping despite connecting to the public endpoint.

**Remediation:**
- Primary fix: Create a dedicated `presign-only` IAM policy for the public client that has no actual data access — presigned URL generation is a pure computation and requires no MinIO permission at generation time:
  ```bash
  # Empty policy — presigned URL signing is client-side HMAC; no MinIO permission needed
  echo '{"Version":"2012-10-17","Statement":[]}' > /tmp/presign-only-policy.json
  mc admin policy create minio presign-only /tmp/presign-only-policy.json
  mc admin user add minio api-presign-access <strong-secret>
  mc admin policy attach minio presign-only --user api-presign-access
  ```
  Use `MINIO_API_PRESIGN_ACCESS_KEY` / `MINIO_API_PRESIGN_SECRET_KEY` in `MinioConfig` for the public client.
- Architectural improvement: Annotate `minioPublicClient` with a `@Qualifier` and add a `@SuppressWarnings` / enforcement comment making it explicit that this client must never be used for I/O operations. Consider a wrapper class `PresignOnlyMinioClient` that overrides all I/O methods to throw `UnsupportedOperationException`, enforcing correct usage at the type level.

---

### Finding #4: Worker Container Healthcheck References a Heartbeat File That No Code Creates

**Vulnerability:** Broken Health Monitoring — OWASP A05 (Security Misconfiguration)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** N/A (deployment defect)

**Location:**
- File: `docker-compose.yml`, Lines 85–89

**Risk & Exploit Path:**
The `worker` service healthcheck is:
```yaml
healthcheck:
  test: ["CMD-SHELL", "find /tmp/worker-heartbeat -mmin -1 | grep -q ."]
  interval: 15s
  timeout: 3s
  retries: 3
  start_period: 30s
```
This check passes only if `/tmp/worker-heartbeat` was modified within the last 1 minute. However, none of the worker application code (`ConsumerScheduler`, `ConsumerConfig`, `PhotoJobConsumer`, `DeleteJobConsumer`, `ImageProcessor`, or any other class) creates or updates this file.

After `start_period` (30s) expires, the healthcheck runs every 15s, fails every time, and marks the container permanently `unhealthy` after 3 consecutive failures (75s post-start). In production:
- Monitoring systems that alert on container health will generate constant false-positive alerts for the worker
- Any future `depends_on: worker: condition: service_healthy` dependency would block indefinitely
- Automated restart policies triggered by unhealthy state would cause continuous restart loops

Security impact: a continuously restarting worker could create windows during which in-flight photo processing jobs are interrupted without cleanup, increasing load on startup recovery paths and potentially degrading delete-job processing availability.

**Evidence / Trace:**
```yaml
# docker-compose.yml:85-89
healthcheck:
  test: ["CMD-SHELL", "find /tmp/worker-heartbeat -mmin -1 | grep -q ."]  # ← file never created
```
Searched across all worker Java source files — no reference to `worker-heartbeat` or any `/tmp/` file creation in a scheduled heartbeat context.

**Remediation:**
- Primary fix: Add a heartbeat task to `ConsumerScheduler` that touches the file on a 30-second interval:
  ```java
  @Scheduled(fixedDelay = 30_000)
  public void writeHeartbeat() {
      try {
          Files.write(Path.of("/tmp/worker-heartbeat"), new byte[0],
                  java.nio.file.StandardOpenOption.CREATE,
                  java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
      } catch (IOException e) {
          log.warn("Failed to write worker heartbeat file", e);
      }
  }
  ```
  The worker container has `read_only: true` with `/tmp` as a tmpfs mount, so this write is permitted.
- Alternative: Use a Spring Actuator health endpoint instead:
  ```yaml
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health | grep -q 'UP'"]
  ```

---

### Finding #5: Trivy SBOM CI Scan Runs Without Building JARs; CI Actions Are Not Pinned to Commit SHAs

**Vulnerability:** Supply Chain — OWASP A06 (Vulnerable and Outdated Components)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Medium (requires malicious action update or build artifact manipulation)

**Location:**
- File: `.github/workflows/ci.yml`, Lines 1–38

**Risk & Exploit Path:**

**Part A — SBOM scan without build step:**
The `trivy-scan` job runs `scan-type: fs` on the checkout, but without first running `./gradlew build`, no compiled JAR files are present. Trivy's filesystem scan can detect CVEs in `build.gradle` dependency declarations, but it cannot detect vulnerabilities in JAR bytecode or transitive dependencies not declared in the top-level manifest. The plan explicitly requires "extend the Trivy scan to cover JARs in the build output" (SA2-F3). This control is partially unimplemented: source-level dependency CVEs may be caught, but runtime JAR-level CVEs in shade-bundled or version-resolved transitive dependencies are not detected.

**Part B — Unpinned GitHub Actions:**
```yaml
uses: aquasecurity/trivy-action@master         # ← floating branch ref — VULNERABLE
uses: github/codeql-action/upload-sarif@v3     # ← major-version tag — not pinned to SHA
uses: actions/checkout@v4                       # ← major-version tag — not pinned to SHA
uses: docker/setup-buildx-action@v3            # ← major-version tag — not pinned to SHA
```
`@master` is the highest-risk pattern: any push to the `aquasecurity/trivy-action` repository's master branch is automatically used in the next workflow run. A compromised upstream maintainer could inject malicious code that exfiltrates secrets (`GITHUB_TOKEN`, Docker credentials, environment variables), modifies build artifacts, or bypasses the security scan itself. The `@v3`/`@v4` tags are slightly better (major version is typically immutable by convention) but are not cryptographically pinned.

**Remediation:**
- Primary fix (Part A): Add a build step before the Trivy scan:
  ```yaml
  trivy-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      - name: Build project
        run: ./gradlew :api:build :worker:build -x test
      - name: Run Trivy SBOM vulnerability scan
        uses: aquasecurity/trivy-action@<sha>
        with:
          scan-type: fs
          scanners: vuln
          exit-code: '1'          # ← fail the build on findings
          severity: HIGH,CRITICAL
  ```
- Primary fix (Part B): Pin all GitHub Actions to specific commit SHAs:
  ```yaml
  uses: aquasecurity/trivy-action@18f2510ee396bbf400402e8f6e2dc35db4b45b08  # v0.28.0
  uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683         # v4.2.2
  ```
  Use `Dependabot` or `Renovate` with `pinDigests: true` to keep SHA pins current.
- Defense-in-depth: Add `permissions: contents: read` to each CI job to restrict `GITHUB_TOKEN` scope.

---

### Finding #6: PostgreSQL and Redis Container Images Use Floating Tags

**Vulnerability:** Supply Chain — OWASP A06 (Vulnerable and Outdated Components)
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** High (requires registry compromise or accidental breaking update)

**Location:**
- File: `docker-compose.yml`, Lines 101 (`postgres:16`), 181 (`redis:7-alpine`)

**Risk & Exploit Path:**
```yaml
postgres:
  image: postgres:16          # ← floating tag; re-pulled on each `docker compose pull`

redis:
  image: redis:7-alpine       # ← floating tag
```
`postgres:16` and `redis:7-alpine` are mutable tags. While Docker Hub does not typically receive malicious updates to official images, floating tags can silently introduce:
- Minor version upgrades with behavioral changes affecting data integrity
- New CVEs before operators are aware and can respond
- Compatibility breaks with application-level SQL or Redis protocol expectations

Contrast with the correctly pinned images: `minio/minio:RELEASE.2025-09-07T16-13-09Z-cpuv1`, `minio/mc:RELEASE.2025-08-13T08-35-41Z-cpuv1`, `eclipse-temurin:21-jre-alpine@sha256:...`.

**Remediation:**
- Primary fix: Pin both images to immutable digest references at next update cycle:
  ```yaml
  postgres:
    image: postgres:16@sha256:<digest>   # retrieve with: docker pull postgres:16 && docker inspect postgres:16 --format '{{.RepoDigests}}'

  redis:
    image: redis:7-alpine@sha256:<digest>
  ```
- Defense-in-depth: Add Renovate/Dependabot configuration to automate digest pin updates with a changelog review step.

---

### Finding #7: Consumer Log Statements Include Unescaped Redis Stream Values (Log Injection)

**Vulnerability:** Log Injection — OWASP A03 (Injection) — Informational
**Severity:** Informational
**Confidence:** Medium (requires Redis compromise to trigger)
**Attack Complexity:** High

**Location:**
- File: `worker/.../consumer/PhotoJobConsumer.java`, Lines 184–186
- File: `worker/.../consumer/DeleteJobConsumer.java`, Lines 154–156

**Risk & Exploit Path:**
Redis stream message values (e.g., `photoIdStr`, `originalKey`) are logged directly without escaping or sanitizing control characters:
```java
log.error("Received message {} with invalid photo_id '{}' — XACK and skip",
        messageId, photoIdStr);  // ← photoIdStr from Redis, unescaped
```
SLF4J/Logback does not sanitize newlines or ANSI escape codes in log arguments. A compromised Redis instance that injects `\n[CRITICAL] Authentication bypass succeeded for user admin\n` as a `photo_id` value would produce a forged log line, potentially misleading security monitoring tools or contaminating SIEM event streams.

**Exploitation prerequisite:** Redis must be compromised first (requirepass enabled, backend network isolated). In the current deployment, Redis is on the `backend` network with `requirepass`, making this a theoretical concern. Included for defense-in-depth completeness.

**Remediation:**
- Primary fix: Sanitize untrusted values before logging by stripping control characters:
  ```java
  private static String sanitizeForLog(String s) {
      return s == null ? "<null>" : s.replaceAll("[\r\n\t]", "_").replaceAll("[\u001B\\p{Cntrl}]", "?");
  }
  ```
- Alternative: Configure a Logback layout encoder that strips ANSI/control codes at the appender level, applying protection uniformly without code changes.

---

## Executive Summary

The Phase 3 implementation represents a significantly hardened codebase. Prior audits (SA1–SA3) addressed eight substantive findings including ExifTool RCE (CVE-2021-22204), command injection via filename extension, EXIF-stored XSS via unsanitized JSONB, pipe buffer exhaustion in ExifTool, and storage key injection via compromised Redis. All eight are correctly remediated in the reviewed code.

This audit found no critical or high severity vulnerabilities. The most significant finding is a **race condition in `softDelete()`** (Finding #1) where two concurrent soft-delete requests on the same photo can decrement `used_bytes` twice, allowing an authenticated user to manipulate their quota accounting. This is exploitable with minimal effort (two concurrent HTTP requests) and should be prioritized. The fix — acquiring the user lock before the photo read, then re-validating the photo inside the locked transaction — is straightforward and directly analogous to the correct locking pattern already used in `uploadPhoto()`.

The remaining findings are low-severity: an information disclosure in `getPhotoStatus()` that reveals photo liveness to unauthorized callers (mitigated by UUID unguessability), an overly-permissive MinIO credential for the presigning client, a broken worker healthcheck, a partial gap in the Trivy SBOM scan, floating base image tags, and a theoretical log injection path. The codebase demonstrates strong security hygiene in the areas most often audited: no hardcoded secrets, parameterized SQL throughout, explicit `ProcessBuilder` argument arrays (no shell strings), Jsoup sanitization on all user-controlled text written to the database, UUID-based storage key validation in the delete consumer, and correct `SELECT FOR UPDATE` locking in the upload quota path.

The codebase is suitable for production deployment after Finding #1 (softDelete race) and Finding #4 (broken healthcheck) are resolved. The remaining findings should be tracked in the backlog with remediation before the next scaling or exposure increase.

---

## Findings Summary Table

| # | Title | Category | Severity | Confidence | Status |
|---|-------|----------|----------|------------|--------|
| 1 | `softDelete()` race → quota double-decrement | A08 Business Logic | Medium | High | REMEDIATE BEFORE DEPLOY |
| 2 | `getPhotoStatus()` IDOR information disclosure | A01 Access Control | Low | Confirmed | BACKLOG |
| 3 | API `minioPublicClient` has full readwrite credentials | A05 Misconfiguration | Low | Confirmed | BACKLOG |
| 4 | Worker healthcheck references non-existent heartbeat file | A05 Misconfiguration | Low | Confirmed | REMEDIATE BEFORE DEPLOY |
| 5 | Trivy SBOM scan without build + unpinned actions | A06 Supply Chain | Low | Confirmed | BACKLOG |
| 6 | PostgreSQL and Redis use floating image tags | A06 Supply Chain | Low | Confirmed | BACKLOG |
| 7 | Log injection via unescaped Redis stream values | A03 Injection | Informational | Medium | INFORMATIONAL |

---

## Security Quality Score (SQS)

**Calculation:**

| Severity | Count | Deduction |
|----------|-------|-----------|
| Critical | 0 | 0 |
| High | 0 | 0 |
| Medium | 1 | −8 |
| Low | 5 | −10 (5 × −2; distinct categories, no grouping) |
| Informational | 1 | −1 |

**Final SQS:** 81/100
**Hard gates triggered:** No
**Posture:** Acceptable — deploy only with remediation commitment and timeline

Recommended minimum before deployment: resolve Finding #1 (softDelete race) and Finding #4 (broken healthcheck). Remaining findings should have remediation tickets filed.

---

## Positive Security Observations

1. **ExifTool pinned to 13.36**: Satisfies the ≥ 12.24 requirement (CVE-2021-22204 mitigated). Version pinned at the `apk` layer with an exact version string.

2. **All ProcessBuilder calls use explicit argument arrays**: `dcraw_emu`, `vipsthumbnail`, and `exiftool` are invoked without shell expansion (`ProcessBuilder("cmd", "arg1", "arg2")`), eliminating command injection from all file path arguments.

3. **DeleteJobConsumer STORAGE_KEY_PATTERN validation**: The UUID-based regex applied to all three MinIO keys before any delete operation provides effective defense against Redis-compromise injection. The pattern correctly requires UUID structure in both the user and photo ID segments, and restricts the sub-path to `originals` or `thumbnails`.

4. **Comprehensive EXIF sanitization with null-safe HashMap traversal**: All EXIF/IPTC/XMP string values sanitized using `Jsoup.parse(s).text()` before JSONB assembly. Null-valued EXIF entries handled correctly via `HashMap.forEach()` (not `Collectors.toMap()`, which would NPE on null values). This was a prior-audit finding (SA2-F1) that is correctly and completely implemented.

5. **Upload transaction sequencing with compensating transaction**: The six-step upload pipeline (temp file → Tika → dedup → Tx1 quota → MinIO → Tx2 storage_key) correctly sequences the DB row insert before the MinIO PUT, ensuring orphan reconciliation can always find and clean up failed uploads. The `GREATEST(0, used_bytes - :fileSize)` floor guard in the compensating transaction, backed by the V6 `CHECK (used_bytes >= 0)` database constraint, provides two independent layers of non-negative quota enforcement.

6. **Startup recovery lock with Lua ownership verification**: The distributed recovery lock correctly uses a Lua script to atomically verify ownership before refreshing TTL, preventing the split-brain scenario where two instances both believe they hold the lock after a GC pause. The PEL pagination at 1000-entry batches correctly handles large-scale outage scenarios where a single 100-entry page would miss entries.

7. **Worker container hardening**: Non-root `worker` user, `cap_drop: ALL`, `no-new-privileges`, `read_only: true` filesystem (writes only to tmpfs), dedicated scoped MinIO credentials (`originals/*` + `thumbnails/*` only), and separate restricted PostgreSQL role. Base image pinned to SHA digest.

---

## Prioritized Remediation Roadmap

| Priority | Finding | Reason | Effort | Owner |
|----------|---------|--------|--------|-------|
| 1 | #1 — softDelete race → quota double-decrement | Auth user can manipulate quota; exploitable with two concurrent requests; fix is one-transaction restructure | Quick Win | Backend |
| 2 | #4 — Worker healthcheck broken | Container permanently marked unhealthy in production; causes restart loops and false alerts | Quick Win | DevOps |
| 3 | #2 — getPhotoStatus IDOR | 403/404 divergence reveals photo liveness; one-line filter fix matches existing `getPhoto()` pattern | Quick Win | Backend |
| 4 | #5 — Trivy SBOM scan gaps | Add build step before fs scan; pin action SHAs; add `exit-code: 1` to fail on HIGH/CRITICAL | Moderate | DevOps/Security |
| 5 | #3 — Public MinIO client over-credentialed | Create presign-only IAM user; two env vars + minio-init policy change | Moderate | DevOps |
