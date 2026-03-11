# Security Audit — SaaS Conversion Phase 4
**Audit ID:** 2026-03-10-saas-conversion-phase-4-security-audit-2
**Audited artefact:** `docs/plans/2026-02-25-saas-conversion-phase-4.md` (v8.0) and all associated implementation files
**Auditor:** Lead Cyber-Security Auditor (LCSA)
**Date:** 2026-03-10
**Scope:** Phase 4 backend prerequisites (Task 4.0) and prior-phase implementation under review — scheduler layer, Redis Streams consumer/worker layer, image processing pipeline, Dockerfile, application configuration.

---

## Materials Received

| File | Role |
|------|------|
| `api/src/main/java/.../scheduler/TrashPurgeScheduler.java` | Soft-delete purge scheduler |
| `api/src/main/java/.../scheduler/OrphanReconciliationScheduler.java` | MinIO orphan sweep |
| `api/src/main/java/.../scheduler/UnverifiedAccountPurgeScheduler.java` | Unverified account deletion |
| `api/src/main/java/.../scheduler/PhotoDeleteJobEnqueuer.java` | Shared Redis XADD helper |
| `api/src/main/resources/application.yml` | API service runtime config |
| `api/src/test/java/.../scheduler/SchedulerTest.java` | Scheduler integration tests |
| `worker/src/main/java/.../consumer/ConsumerConfig.java` | Worker Spring wiring |
| `worker/src/main/java/.../consumer/ConsumerScheduler.java` | Worker scheduled poll loops |
| `worker/src/main/java/.../consumer/PhotoJobConsumer.java` | Photo-jobs stream consumer |
| `worker/src/main/java/.../consumer/DeleteJobConsumer.java` | Delete-jobs stream consumer |
| `worker/src/main/java/.../pipeline/ImageProcessor.java` | Pipeline orchestrator |
| `worker/src/main/java/.../pipeline/TikaValidator.java` | MIME type validation |
| `worker/src/main/java/.../pipeline/ThumbnailGenerator.java` | libvips/dcraw_emu subprocess runner |
| `worker/src/main/java/.../pipeline/MetadataExtractor.java` | exiftool / metadata-extractor |
| `worker/src/main/java/.../config/WorkerProperties.java` | Typed configuration |
| `worker/src/main/resources/application.yml` | Worker runtime config |
| `worker/src/test/resources/application-test.yml` | Worker test configuration |
| `worker/Dockerfile` | Worker container image |
| `worker/src/test/java/.../consumer/DeleteJobConsumerTest.java` | Delete consumer unit tests |
| `worker/src/test/java/.../consumer/StartupRecoveryTest.java` | Recovery unit tests |
| `docs/plans/2026-02-25-saas-conversion-phase-4.md` | Phase 4 plan (v8.0) |

**Scope assumptions:**
- This is an independent second audit. Prior audit findings are not referenced; all findings stand on their own evidence.
- Frontend implementation (Tasks 4.1–4.n) is not yet written; plan-level design decisions affecting frontend security are evaluated against the plan text only.
- The reviewed code is the implementation of scheduled jobs and the worker Redis Streams pipeline (phases 3–4 overlap). Task 4.0 backend additions (UserController, GPS filtering, pre-signed URLs) are plan-specified but not yet written; they are reviewed as design intent.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

### Entry Points
| Category | Entry Point | Trust Level |
|----------|-------------|-------------|
| Scheduled job | `TrashPurgeScheduler.purgeTrash()` (daily, 03:00) | Internal, ShedLock-guarded |
| Scheduled job | `OrphanReconciliationScheduler.reconcileOrphans()` (weekly, Sun 04:00) | Internal, ShedLock-guarded |
| Scheduled job | `UnverifiedAccountPurgeScheduler.purgeUnverifiedAccounts()` (daily, 03:30) | Internal, ShedLock-guarded |
| Stream consumer | `PhotoJobConsumer.pollOnce()` (100 ms fixed-delay) | Reads from Redis `photo-jobs` |
| Stream consumer | `DeleteJobConsumer.pollOnce()` (100 ms fixed-delay) | Reads from Redis `delete-jobs` |
| Stream consumer | `PhotoJobConsumer.reclaimIdleMessages()` (5 min XAUTOCLAIM) | Internal reclaim |
| Startup hook | `PhotoJobConsumer.performStartupRecovery()` (once at boot) | Internal, distributed Redis lock |
| File processor | `ThumbnailGenerator.generate()` | Processes user-uploaded binary files |
| File processor | `MetadataExtractor.extract()` | Processes user-uploaded binary files |

### Trust Boundaries
```
[User upload via API] → [MinIO (internal)] → [DB storage_key]
                                ↓
[API schedulers] → [Redis Streams] → [Worker consumers]
                                            ↓
                              [CLI subprocess: exiftool, dcraw_emu, vipsthumbnail]
                                            ↓
                              [DB write: photo_metadata, photos rows]
```

- Redis is semi-trusted: internal, password-protected, but not an authenticated principal per message
- MinIO internal endpoint: trusted for reads, internal network
- DB: trusted; scheduler operations bypass RLS via dedicated `jpt_auth` role

### Authentication/Authorization Architecture
- Schedulers: no user-facing auth; ShedLock mutual exclusion via Redis keys
- Worker consumers: no per-message authentication; message integrity relies on Redis access control
- Distributed recovery lock: Redis `SET NX PX` with ownership-verified Lua release script

### Sensitive Data Flows
- Photo storage keys (MinIO paths): DB → Redis streams → Worker → MinIO operations
- User IDs and photo IDs: DB → Redis streams → Worker logs
- EXIF/XMP metadata (potentially containing GPS, titles, descriptions): User files → MetadataExtractor → Jsoup sanitize → DB
- Credentials: all via `${ENV_VAR}` with no defaults except test config

---

## Pass 2: Systematic Vulnerability Hunting

---

### Finding #1: DeleteJobConsumer Validates Key Format But Not Ownership — Compromised Redis Enables Targeted Photo Deletion

**Vulnerability:** Broken Access Control / Insecure Direct Object Reference — OWASP A01
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Medium

**Location:**
- File: `worker/src/main/java/org/jphototagger/worker/consumer/DeleteJobConsumer.java`, Lines 153–165

**Risk & Exploit Path:**

The `STORAGE_KEY_PATTERN` regex (SA3-F2) prevents injection of arbitrary MinIO paths such as `../etc/passwd` or `admin/config/secrets`. This is the documented defence. However, the validation is format-only: it confirms the key looks like `{uuid}/originals/{uuid}.{ext}` — it does not verify that the referenced `photo_id` exists in the database, nor that it belongs to the user associated with the first UUID segment.

Precondition: attacker gains write access to Redis (requires network compromise + credential theft, or Redis misconfiguration). This is a meaningful barrier given password authentication, but Redis is an internal service whose credentials can be exposed through misconfigured environment files, leaked logs, or lateral movement.

**Exploit path:**
1. Attacker writes to the `delete-jobs` stream: `XADD delete-jobs * photo_id <any-valid-uuid> original_key <user-uuid>/originals/<photo-uuid>.jpg thumbnail_sm <user-uuid>/thumbnails/<photo-uuid>_sm.jpg thumbnail_md <user-uuid>/thumbnails/<photo-uuid>_md.jpg`
2. `isValidStorageKey()` passes for any well-formed UUID path
3. Worker immediately XACKs and issues `removeObject()` calls against MinIO
4. Target user's photos are silently deleted with no DB record of the delete-job's source

**Evidence / Trace:**
```java
// DeleteJobConsumer.java:153–165
if (!isValidStorageKey(originalKey)) {
    // ← Only format check; no DB ownership verification
    redisCommands.xack(STREAM, GROUP, messageId);
    return;
}

redisCommands.xack(STREAM, GROUP, messageId);  // ← XACK before verification

deleteObject(originalKey, photoId, messageId);  // ← MinIO delete, no auth check
```

**Remediation:**
- **Primary fix:** Before issuing `removeObject()`, cross-check that `photo_id` exists in the DB and that `original_key` matches its stored `storage_key`. The worker already has `photoRepository` available:
  ```java
  // After format validation, before XACK:
  Optional<Photo> photo = photoRepository.findById(UUID.fromString(photoId));
  if (photo.isEmpty() || !originalKey.equals(photo.get().getStorageKey())) {
      log.error("delete-job key does not match DB record — potential injection, XACK and skip");
      redisCommands.xack(STREAM, GROUP, messageId);
      return;
  }
  ```
  Note: this converts delete-jobs to at-least-once semantics for the DB check, which is acceptable since `removeObject` is idempotent.
- **Architectural improvement:** Consider HMAC-signing delete-job messages at enqueue time using a shared secret, allowing the consumer to verify message authenticity independent of Redis trust.
- **Defense-in-depth:** Enable Redis ACLs restricting `XADD` to `delete-jobs` to the API service's Redis user only, preventing lateral movement from compromising the stream.

**References:**
- OWASP A01:2021 — Broken Access Control
- CWE-639: Authorization Bypass Through User-Controlled Key

---

### Finding #2: Dead-Letter Stream Has No MAXLEN — Unbounded Redis Memory Growth

**Vulnerability:** Security Misconfiguration / Denial of Service — OWASP A05
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low (requires only repeated processing failures)

**Location:**
- File: `worker/src/main/java/org/jphototagger/worker/consumer/PhotoJobConsumer.java`, Lines 268–275

**Risk & Exploit Path:**

Every photo that exhausts its retry budget is written to the `dead-letter` Redis stream with no MAXLEN limit. Under normal operation this stream grows slowly. However, if a batch of malformed images or a sustained processing bug causes many photos to fail, each failure appends to `dead-letter` without trimming. Redis holds all stream entries in memory. On a constrained VPS with a shared Redis instance serving both API and worker, stream bloat can consume available memory and trigger Redis OOM errors, degrading or halting all real-time stream processing.

**Evidence / Trace:**
```java
// PhotoJobConsumer.java:268–275
try {
    redisCommands.xadd("dead-letter", Map.of(  // ← No MAXLEN parameter
            "photo_id", photoId.toString(),
            "message_id", messageId,
            "reason", "max_retries_exceeded"));
} catch (Exception ex) {
    log.warn("Failed to write to dead-letter stream for photo {}", photoId, ex);
}
```

The code's own comment acknowledges the issue: *"For production alerting, expose XLEN as a Micrometer gauge and set a threshold-based alert."* No MAXLEN is applied on the XADD.

**Remediation:**
- **Primary fix:** Apply approximate MAXLEN trimming on every append:
  ```java
  // Use MAXLEN ~ with approximate trimming (XADD MAXLEN ~ 10000 ...)
  // Lettuce: XAddArgs.Builder.maxlen(10_000).approximateTrimming()
  redisCommands.xadd("dead-letter",
      XAddArgs.Builder.maxlen(10_000).approximateTrimming(),
      Map.of(...));
  ```
  This bounds memory at ~10 000 dead-letter entries with minimal performance overhead.
- **Architectural improvement:** Add a Micrometer `XLEN dead-letter` gauge and a threshold alert (e.g., > 100 entries triggers PagerDuty/Slack) as noted in the code comment.
- **Defense-in-depth:** Set `maxmemory-policy allkeys-lru` in Redis config so Redis degrades gracefully under memory pressure rather than halting writes.

---

### Finding #3: UnverifiedAccountPurgeScheduler Loads All Stale Users Into Memory Without Pagination

**Vulnerability:** Insecure Design / Resource Exhaustion — OWASP A04
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low (self-inflicted; attacker triggers by registering many unverified accounts)

**Location:**
- File: `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java`, Lines 59–62

**Risk & Exploit Path:**

The scheduler queries all stale unverified users into a single `List<UUID>` with no `LIMIT` or pagination. An attacker who registers thousands of accounts via the public sign-up endpoint (even with rate limiting per IP, this is feasible over time via proxy rotation) could cause the list to be large enough to exhaust JVM heap on the API server during the daily purge window. At minimum, a long-running query would hold the DB connection for the ShedLock duration (PT10M), blocking other schedulers from using the connection pool.

**Evidence / Trace:**
```java
// UnverifiedAccountPurgeScheduler.java:59–62
List<UUID> staleUserIds = authJdbcTemplate.query(
        "SELECT id FROM users WHERE email_verified = false "
                + "AND created_at < now() - INTERVAL '7 days'",  // ← No LIMIT
        (rs, rowNum) -> UUID.fromString(rs.getString("id")));
```

**Remediation:**
- **Primary fix:** Add pagination with a fixed batch size:
  ```java
  // Add LIMIT and OFFSET, or use a cursor-based approach:
  authJdbcTemplate.query(
      "SELECT id FROM users WHERE email_verified = false "
      + "AND created_at < now() - INTERVAL '7 days' "
      + "ORDER BY created_at LIMIT 1000",  // ← Process 1000 per run; next run picks up more
      ...);
  ```
  Alternatively, use a `do/while` loop until the query returns an empty batch (same pattern as `TrashPurgeScheduler`).
- **Architectural improvement:** Add an index on `(email_verified, created_at)` to make this query fast as the `users` table grows.
- **Defense-in-depth:** Add rate-limiting at the sign-up endpoint that is stricter for unverified accounts (e.g., max 3 registrations per IP per hour) to limit the attacker-controlled growth rate.

---

### Finding #4: Startup Recovery Not Fault-Tolerant to Redis Connection Failures

**Vulnerability:** Security Misconfiguration / Insecure Design — OWASP A05
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low (transient Redis unavailability at startup)

**Location:**
- File: `worker/src/main/java/org/jphototagger/worker/consumer/ConsumerConfig.java`, Lines 131–136

**Risk & Exploit Path:**

`runStartupRecovery()` is called from an `ApplicationReadyEvent` listener. If Redis is unavailable or returns a connection error when `redisCommands.set(RECOVERY_LOCK_KEY, ...)` is called, the exception propagates out of `performStartupRecovery()` and is absorbed by Spring's event-listener exception handler. The application continues starting and begins polling — but the startup recovery is silently skipped. Any photos stuck in `PENDING` or `PROCESSING` state (from a prior crash) will remain stuck indefinitely until a future XAUTOCLAIM sweep reclaims them (if they were in the PEL) or until a manual operator intervention.

In a deployment scenario where the worker restarts rapidly (e.g., crash loop) during a Redis brownout, all worker instances could skip recovery, causing prolonged photo processing failures visible to users.

**Evidence / Trace:**
```java
// ConsumerConfig.java:131–136
@EventListener(ApplicationReadyEvent.class)
public void runStartupRecovery(ApplicationReadyEvent event) {
    PhotoJobConsumer consumer = event.getApplicationContext()
            .getBean(PhotoJobConsumer.class);
    consumer.performStartupRecovery();  // ← Exception from Redis propagates up; no catch here
}
```

Inside `performStartupRecovery()`:
```java
// PhotoJobConsumer.java:329
String lockResult = redisCommands.set(
        RECOVERY_LOCK_KEY, instanceId,
        SetArgs.Builder.nx().px(RECOVERY_LOCK_TTL_MS));
// ← If Redis is down, this throws; no try/catch here
```

**Remediation:**
- **Primary fix:** Wrap the `runStartupRecovery` call in a try/catch that logs the failure at ERROR and optionally schedules a delayed retry:
  ```java
  @EventListener(ApplicationReadyEvent.class)
  public void runStartupRecovery(ApplicationReadyEvent event) {
      try {
          event.getApplicationContext().getBean(PhotoJobConsumer.class)
               .performStartupRecovery();
      } catch (Exception e) {
          log.error("Startup recovery failed — PENDING/PROCESSING photos may need manual re-enqueue", e);
          // Optionally: schedule a one-shot retry after 60 seconds
      }
  }
  ```
- **Architectural improvement:** Expose a `/api/admin/recovery` actuator endpoint (protected behind ADMIN role) that operators can trigger to re-run recovery manually after a Redis outage.
- **Defense-in-depth:** Add a monitoring alert on `photos WHERE processing_status IN ('PENDING','PROCESSING') AND uploaded_at < now() - INTERVAL '30 minutes'` — indicates stuck processing that recovery should have cleared.

---

### Finding #5: Test Configuration Commits MinIO Default Credentials to Repository

**Vulnerability:** Cryptographic Failures / Credential Exposure — OWASP A02
**Severity:** Low
**Confidence:** Confirmed
**Attack Complexity:** Low

**Location:**
- File: `worker/src/test/resources/application-test.yml`, Lines 22–25

**Risk & Exploit Path:**

The test configuration hard-codes the MinIO default credentials (`minioadmin`/`minioadmin`). While this file is under `test/resources` and should never reach production, committing known-default credentials to version control creates risk if:
1. A developer accidentally activates the `test` profile in a staging/production environment
2. The repository becomes public or is accessed by an unauthorized party
3. CI/CD pipelines run against a shared MinIO instance without overriding these values

**Evidence / Trace:**
```yaml
# worker/src/test/resources/application-test.yml:22–25
minio:
  endpoint: http://localhost:9000
  access-key: minioadmin    # ← MinIO's shipped default; widely known
  secret-key: minioadmin    # ← MinIO's shipped default; widely known
  bucket: jpt-photos
```

**Remediation:**
- **Primary fix:** These values are acceptable for ephemeral Testcontainers MinIO. Add a clear header comment:
  ```yaml
  # TESTCONTAINERS ONLY — these credentials are only used against the ephemeral
  # MinIO Testcontainer. Never activate the 'test' profile in non-test environments.
  ```
- **Architectural improvement:** Where Testcontainers MinIO is used, prefer injecting credentials via `@DynamicPropertySource` from the container object rather than hard-coding in YAML, making it impossible to accidentally apply them to a real MinIO instance.
- **Defense-in-depth:** Add a CI check (`grep -r "minioadmin" src/main/`) that fails the build if default MinIO creds are found in main sources.

---

### Finding #6: Object Key Values Logged Without Newline Sanitization — Log Injection Risk

**Vulnerability:** Injection / Log Injection — OWASP A03
**Severity:** Low
**Confidence:** Low (internal services; modern loggers largely mitigate)
**Attack Complexity:** High (requires compromised MinIO or Redis)

**Location:**
- `OrphanReconciliationScheduler.java`, Lines 123, 139, 167
- `DeleteJobConsumer.java`, Lines 154, 156

**Risk & Exploit Path:**

Several log statements embed values that originate from MinIO object listings or Redis stream message bodies without first stripping CR/LF characters. A compromised MinIO instance could return object keys containing `\n` or `\r\n` sequences, allowing a threat actor to forge additional log lines that appear to be legitimate log entries (e.g., simulating a successful authentication event or suppressing an error message).

```java
// OrphanReconciliationScheduler.java:139
log.warn("OrphanReconciliationScheduler: could not parse photo_id from key={}", objectKey);

// DeleteJobConsumer.java:154
log.error("delete-job originalKey failed format validation — XACK and skip, key={}, photo_id={}",
          originalKey, photoId);
```

Modern SLF4J/Logback implementations typically do not interpret embedded newlines as record separators in standard pattern layouts — the control character is escaped or rendered literally. Severity is Low because: (a) exploitation requires MinIO or Redis compromise, (b) modern log aggregators (ELK, Loki) parse structured fields rather than raw text, and (c) Logback's `%msg` escaping often prevents forged entries from being mistaken for legitimate log records.

**Remediation:**
- **Primary fix:** Strip `\n`, `\r`, and other control characters from external-origin strings before logging:
  ```java
  private static String sanitizeForLog(String s) {
      return s == null ? null : s.replaceAll("[\r\n\t]", "_");
  }
  log.warn("...key={}", sanitizeForLog(objectKey));
  ```
- **Architectural improvement:** Use structured logging (JSON/logfmt format) with a field for the key. Structured log processors are immune to line-based injection.
- **Defense-in-depth:** Enable Logback's `PatternLayout` control character masking if available in the version in use.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attack: Redis Compromise → Targeted User Photo Deletion (Finding #1 amplified)

Redis is protected by password auth and is an internal service. However, if an attacker achieves Redis write access (via leaked `REDIS_PASSWORD`, misconfigured ACLs, or internal network pivot), they can combine Finding #1 with knowledge of user UUIDs (obtainable from a prior DB read or API recon) to silently delete any user's photos. The format validation in SA3-F2 provides defence-in-depth against path traversal but does not prevent on-format targeted deletion. The OrphanReconciliationScheduler would not recover these files (they had DB rows which were not purged — only MinIO objects are deleted). The blast radius is permanent data loss for targeted users with no automated recovery.

**Recommendation:** Implement the DB cross-check described in Finding #1. This is the most impactful single fix in this audit.

### Chained Attack: Malformed Image Flood → Dead-Letter OOM → Redis Failure (Findings #3 + #2)

An attacker who registers many accounts (exploiting Finding #3's no-LIMIT flaw) could also upload many intentionally malformed images — each processed, retried, and dead-lettered. This triggers Finding #2's unbounded stream growth. Combined, these could degrade the Redis service affecting all users. Each finding is Low individually; the chain produces a meaningful availability degradation scenario.

### GPS Filtering Gap in Plan (Task 4.0, SA4-F1)

The plan's `withoutGps()` requirement specifies a case-insensitive key prefix check for `GPS:*` / `Gps:*` variants. This must be rigorously implemented in `PhotoMetadataService`. If only exact-case matching is used, a metadata key variant like `gps:GPSLatitude` (all-lowercase prefix) would bypass the filter and leak location data to users who have disabled GPS display. This is a design-time guidance note; the implementation does not yet exist.

**Recommendation:** The plan correctly mandates case-insensitive matching. Ensure the implementation uses `key.toLowerCase().startsWith("gps:")` or a compiled case-insensitive `Pattern`.

### CSRF / Session Cookie Hardening (Plan SA4-F4) — Not Yet Implemented

The plan mandates `SameSite=Strict` and `Secure` on both the CSRF cookie and `JSESSIONID` session cookie, plus `ForwardedHeaderFilter` for reverse-proxy TLS awareness. None of these appear in the reviewed Spring Security configuration (which is not in scope for this specific phase). This MUST be verified before the frontend launches in Task 4.0. Failure to enforce `SameSite=Strict` on the CSRF cookie could allow cross-site request forgery attacks on PATCH/DELETE endpoints once the frontend is live.

**Recommendation:** Add a dedicated security configuration test: `assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Strict")` and `contains("Secure")`.

### Implicit Trust: MinIO Internal Endpoint

`OrphanReconciliationScheduler` uses `minioInternalClient` which communicates with MinIO via internal network (not through the public URL). This correctly avoids exposing object listings to the public network. The internal endpoint should be configured to require authentication (which it does via access/secret key) and ideally should be on a private network segment inaccessible from the public internet. This is a deployment concern outside code scope.

---

## 1. Executive Summary

The Phase 4 backend implementation demonstrates consistently high engineering discipline: explicit ProcessBuilder argument arrays throughout the pipeline prevent shell injection; content-based MIME validation via Apache Tika avoids extension-spoofing; metadata values are sanitized with Jsoup before DB storage; Redis credentials have no default fallbacks; stack traces are suppressed from API responses; and the Docker container follows container hardening best practices (non-root user, SHA-pinned base image, tini init, pinned Alpine package versions).

The primary security concern is a **medium-severity authorization gap in `DeleteJobConsumer`**: the storage key format validation (SA3-F2) correctly prevents arbitrary path injection but does not cross-verify against the database that a delete-job's referenced photo actually belongs to the claimed user and that the key matches the stored `storage_key`. A compromised Redis instance would allow an attacker to issue targeted photo deletions without leaving a DB audit trail. This is the most impactful finding and should be remediated before production launch.

Three low-severity findings — an unbounded dead-letter stream, pagination missing from the unverified-account purge query, and silent startup recovery failure on Redis downtime — represent reliability risks with secondary security implications (DoS potential, stuck processing states). One low finding (committed MinIO default test credentials) is a hygiene issue without direct exploitability. Collectively, these findings describe a codebase that is close to production-ready but benefits from targeted hardening before the frontend goes live and attack surface expands.

---

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | DeleteJobConsumer: format-only key validation — no ownership check | A01 Broken Access Control | Medium | High | 0 | REMEDIATE BEFORE LAUNCH |
| 2 | Dead-letter stream: no MAXLEN — unbounded Redis memory growth | A05 Misconfiguration | Low | Confirmed | 0 | REMEDIATE |
| 3 | UnverifiedAccountPurge: no LIMIT on stale user query | A04 Insecure Design | Low | Confirmed | 0 | REMEDIATE |
| 4 | Startup recovery silently skipped on Redis unavailability | A05 Misconfiguration | Low | High | 0 | REMEDIATE |
| 5 | Test configuration: MinIO default credentials in repository | A02 Crypto Failures | Low | Confirmed | 0 | HYGIENE |
| 6 | Log injection via MinIO/Redis object key values | A03 Injection | Low | Low | 3 | MONITOR |

---

## 3. Security Quality Score (SQS)

**Deductions:**

| Finding | Severity | Deduction |
|---------|----------|-----------|
| #1 | Medium | −8 |
| #2 | Low | −2 |
| #3 | Low | −2 |
| #4 | Low | −2 |
| #5 | Low | −2 |
| #6 | Low | −2 |
| **Total** | | **−18** |

**Final SQS: 82 / 100**

**Hard gates triggered:** No
- No unremediated Critical findings
- No Critical/High CVEs with EPSS ≥ 0.2 or CISA KEV entries identified
- No hardcoded secrets in production source paths (test credentials are test-scope only)

**Posture: Acceptable**
Deploy only with a remediation commitment and timeline. Finding #1 should be resolved before the frontend launches (expands attack surface). Findings #2–#4 should be resolved in the next sprint.

---

## 4. Positive Security Observations

1. **ProcessBuilder with explicit argument arrays throughout the pipeline.** `ThumbnailGenerator`, `MetadataExtractor`, and `ImageProcessor` all use `new ProcessBuilder("tool", arg1, arg2, ...)` — never shell string interpolation. This is the correct pattern and completely eliminates shell injection risk regardless of what user-controlled data ends up in arguments (UUIDs as temp file paths, etc.).

2. **Content-based MIME validation precedes all processing.** `TikaValidator` reads actual file bytes via Apache Tika before any thumbnail generation or metadata extraction. Extension spoofing (uploading a `.jpg`-named executable) is detected and the photo is permanently marked `FAILED` before any processing tool is invoked.

3. **Lua-based atomic lock ownership verification in startup recovery.** The `REFRESH_LOCK_SCRIPT` and release script both use `GET` then `SET`/`DEL` in a single Lua script, preventing TOCTOU races where a GC pause could cause a worker to act on a lock it no longer owns. This is the industry-correct pattern for Redis distributed locks.

4. **Redis secrets have no default fallbacks.** Both `api/src/main/resources/application.yml` and `worker/src/main/resources/application.yml` use `${REDIS_PASSWORD}` with no `:default` clause. A misconfigured deployment fails at startup with a clear error rather than silently running with an empty password. The same pattern applies to MinIO and JWT secrets.

5. **Dockerfile follows container hardening best practices.** SHA-256 pinned base image (`eclipse-temurin:21-jre-alpine@sha256:...`), all Alpine packages pinned to exact versions, tini as PID 1 for correct signal handling and zombie reaping, and a dedicated non-root `worker` user. This meaningfully limits the blast radius of a container escape or compromised dependency.

---

## 5. Prioritized Remediation Roadmap

### 1. Finding #1 — DeleteJobConsumer DB Cross-Check
**Why prioritized:** Medium severity × high exploitability (once Redis is compromised) × high blast radius (permanent photo deletion). The OrphanReconciliationScheduler will not recover these objects since they had valid DB rows at deletion time.
**Estimated effort:** Quick Win — add `photoRepository.findById()` check before XACK in `processMessage()`. The worker already holds a `photoRepository` reference.
**Owner:** Backend / Worker team

### 2. Finding #3 — UnverifiedAccountPurge Pagination
**Why prioritized:** The purge runs daily; an attacker registration campaign (using proxy rotation to bypass rate limits) could cause a large stale-user list that OOMs the API JVM during the nightly window. Failure in this scheduler also holds a DB connection for up to PT10M (ShedLock lockAtMostFor).
**Estimated effort:** Quick Win — add `ORDER BY created_at LIMIT 1000` to the query and wrap in a `do/while` loop (same pattern as `TrashPurgeScheduler`).
**Owner:** Backend team

### 3. Finding #2 — Dead-Letter Stream MAXLEN
**Why prioritized:** Redis memory exhaustion would halt all stream processing (photo upload pipeline, delete pipeline) for all users simultaneously. Low probability but high blast radius.
**Estimated effort:** Quick Win — add `XAddArgs.Builder.maxlen(10_000).approximateTrimming()` to the `xadd` call. Add `XLEN dead-letter` Micrometer gauge for alerting.
**Owner:** Backend / Worker team

### 4. Finding #4 — Startup Recovery Error Handling
**Why prioritized:** Unhandled exceptions in `ApplicationReadyEvent` listeners are silently swallowed by Spring; this creates an invisible failure mode where photo processing recovery silently never runs after a Redis-induced restart.
**Estimated effort:** Quick Win — wrap `performStartupRecovery()` call in try/catch with ERROR log. Optionally schedule a delayed retry.
**Owner:** Worker team

### 5. Finding #5 — Test Credential Comment + CI Guard
**Why prioritized:** Low exploitability, but hygiene matters especially if the repository ever becomes public or is accessed by contractors.
**Estimated effort:** Quick Win — add comment to `application-test.yml`; add `grep -r "minioadmin" src/main/` to CI pipeline that fails on match.
**Owner:** DevOps / any team

---

*End of audit report.*
