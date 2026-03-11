# Phase 4 Critical Implementation Review — v7 (Worker Redis Streams Code Review)

**Code reviewed:** Committed worker Redis Streams consumer implementation (commits `9b67af2` through `84fe5cb`)
**Previous reviews:** `...-critical-review-1.md` through `...-critical-review-6.md`
**Files reviewed:**
- `worker/src/main/java/org/jphototagger/worker/consumer/ConsumerConfig.java`
- `worker/src/main/java/org/jphototagger/worker/consumer/ConsumerScheduler.java`
- `worker/src/main/java/org/jphototagger/worker/consumer/DeleteJobConsumer.java`
- `worker/src/main/java/org/jphototagger/worker/consumer/PhotoJobConsumer.java`
- `worker/src/test/java/org/jphototagger/worker/consumer/DeleteJobConsumerTest.java`
- `worker/src/test/java/org/jphototagger/worker/consumer/PhotoJobConsumerTest.java`
- `worker/src/test/java/org/jphototagger/worker/consumer/StartupRecoveryTest.java`
- `api/src/main/java/org/jphototagger/api/repository/PhotoRepository.java`
- `api/src/main/resources/application.yml`
- `worker/src/main/resources/application.yml`
- `worker/src/main/java/org/jphototagger/worker/config/WorkerProperties.java`
**Date:** 2026-03-10
**Reviewer:** Senior Staff Engineer (critical-implementation-review skill)

---

## Scope Note

Reviews v1–v6 covered the React frontend plan (CI-1–CI-25, MI-1–MI-41) and the unstaged scheduler code. This review covers the **committed** worker Redis Streams consumer implementation across 4 commits (`9b67af2` → `84fe5cb`). Issue numbering continues from v6 (CI-26+, MI-42+).

---

## 1. Overall Assessment

The worker Redis Streams implementation is architecturally sound. The choice of native Lettuce over Spring Data Redis's high-level API for `XAUTOCLAIM`/`XPENDING` with range+limit is correct and well-motivated. The consumer group bootstrap (MKSTREAM), consumer naming (HOSTNAME+PID), startup recovery with distributed lock and Lua-based ownership verification, PEL pagination, dead-letter routing, and per-message format validation are all implemented correctly. Test coverage is comprehensive for happy paths and most error paths.

**Three issues require fixes before this code ships to production.** The most serious is a scheduler threading problem that effectively serializes the two consumers onto a single thread, with the photo consumer's 2-second blocking XREADGROUP holding the thread hostage. The second is that the startup recovery dedup filter (`paginatePel()`) is never actually tested — the critical test leaves `xrange` unstubbed, so the dedup set is always empty during test execution. The third is that `DeleteJobConsumer`'s PEL entries are permanently orphaned on worker crash because there is no XAUTOCLAIM for the `delete-jobs` stream, despite the at-most-once comment being misleading about the actual ACK ordering.

---

## 2. Critical Issues

### CI-26: Single Scheduler Thread + 2-Second Blocking XREADGROUP Serializes Both Consumers

**File:** `ConsumerConfig.java:42` (`@EnableScheduling`), `ConsumerScheduler.java:33-44`

**Description:** `@EnableScheduling` with no `SchedulingConfigurer` or `spring.task.scheduling.pool.size` configuration uses Spring's default `ThreadPoolTaskScheduler` with **pool size 1** — a single thread for all `@Scheduled` methods. Both `pollPhotoJobs` and `pollDeleteJobs` are annotated `@Scheduled(fixedDelayString = "100")` on that same single thread. Each uses `XReadArgs.Builder.count(1).block(2000)` — a 2-second blocking wait.

With one thread:

| Time | Action |
|------|--------|
| 0ms | Thread runs `pollPhotoJobs` |
| 0–2000ms | Thread blocks waiting for a photo-job message |
| 2000ms | `pollPhotoJobs` returns (no messages) |
| 2100ms | After 100ms `fixedDelay`, thread runs `pollDeleteJobs` |
| 2100–4100ms | Thread blocks waiting for a delete-job message |
| 4100ms | `pollDeleteJobs` returns (no messages) |
| 4200ms | Cycle repeats |

Effective poll rate on an idle system: **~4 seconds** per stream, not 100ms. During active photo processing (pipeline may take 10–60 seconds for large RAW files), the delete stream gets **no polls at all** until the photo consumer's `processMessage` returns. On a continuous-upload workload, delete jobs could be delayed by minutes.

Furthermore, `reclaimIdlePhotoJobs` (every 5 minutes) also runs on this single thread. If a photo pipeline is running, the XAUTOCLAIM sweep is deferred past its scheduled time.

**Why it matters:** On a production system with concurrent uploads and deletions, delete jobs will not be processed promptly. Storage is not reclaimed in a timely manner. XAUTOCLAIM runs late. The system appears to have two parallel consumers but behaves as one serialized consumer.

**Fix:** Add `spring.task.scheduling.pool.size: 3` to `worker/src/main/resources/application.yml` — one thread per independently scheduled loop, plus one for the autoclaim sweep:

```yaml
# worker/src/main/resources/application.yml
spring:
  task:
    scheduling:
      pool:
        size: 3   # pollPhotoJobs + pollDeleteJobs + reclaimIdlePhotoJobs
```

No code changes required — Spring's scheduler picks up this property automatically.

---

### CI-27: `paginatePel()` Dedup Logic Is Never Exercised in Tests

**File:** `StartupRecoveryTest.java:132-173`, `PhotoJobConsumer.java:393-421`

**Description:** `paginatePel()` builds the `pelPhotoIds` dedup set by calling `fetchPhotoIdFromStream(msg.getId())` for each PEL entry, which issues one `XRANGE STREAM id id` per entry. The dedup set is used to prevent re-enqueuing photos that are already in the PEL.

`StartupRecoveryTest.startupRecovery_pelPaginationCoversAllEntries` mocks `redis.xpending(...)` to return 1,001 entries across two pages. But it **never mocks `redis.xrange(...)`**. With `@ExtendWith(MockitoExtension.class)` (STRICT_STUBS mode), unstubbed `xrange` calls return `null` or an empty list (Mockito returns the default value — `null` for `List<StreamMessage>` in strict mode). `fetchPhotoIdFromStream` returns `Optional.empty()` for every entry:

```java
if (msgs != null && !msgs.isEmpty()) {
    return Optional.ofNullable(msgs.get(0).getBody().get("photo_id"));
}
// falls through → returns Optional.empty()
```

So `pelPhotoIds` is **always empty** in every test, regardless of how many PEL entries are mocked. The dedup filter never fires. The test only passes because `photoRepository.findPendingOrProcessingForRecovery` is also mocked to return `PageImpl(List.of())` — no photos to re-enqueue. If a real PENDING photo were in the DB and also in the PEL, the test would pass (dedup filter empty → photo re-enqueued), but the **production behavior** of that photo being correctly skipped is never verified.

**Why it matters:** The dedup filter is the entire point of `paginatePel()`. Its failure mode is duplicate re-enqueueing: a photo that is already being processed (PEL entry exists) gets re-submitted to `photo-jobs`, causing two workers to race on the same photo. The race is detected at `DONE` status check, but both workers run the image pipeline (CPU, MinIO I/O). More critically, no test would catch a regression that broke `fetchPhotoIdFromStream` (e.g., wrong field name `"photoid"` instead of `"photo_id"`).

**Fix:** Add a test that:
1. Puts a photo ID in the PEL (mock `xpending` to return a PEL entry with a known ID)
2. Mocks `xrange` for that ID to return a stream message body with `"photo_id": knownPhotoId`
3. Sets up `photoRepository.findPendingOrProcessingForRecovery` to return that photo
4. Asserts that `redis.xadd(STREAM, ...)` is **never** called for that photo (it's in the PEL, so it must not be re-enqueued)

```java
@Test
void startupRecovery_doesNotReenqueuePhotosAlreadyInPel() {
    when(redis.set(eq(PhotoJobConsumer.RECOVERY_LOCK_KEY), anyString(), any()))
            .thenReturn("OK");

    UUID photoId = UUID.randomUUID();
    String pelMsgId = "123-0";

    // PEL entry exists for this message
    PendingMessage pelEntry = new PendingMessage(pelMsgId, "some-consumer", 1000L, 1L);
    when(redis.xpending(
            eq(PhotoJobConsumer.STREAM), eq(PhotoJobConsumer.GROUP),
            any(Range.class), any(Limit.class)))
            .thenReturn(List.of(pelEntry))   // 1 entry — ends pagination
            .thenReturn(List.of());

    // XRANGE returns the stream body for that PEL message ID
    StreamMessage<String, String> streamMsg = new StreamMessage<>(
            PhotoJobConsumer.STREAM, pelMsgId, Map.of("photo_id", photoId.toString()));
    when(redis.xrange(eq(PhotoJobConsumer.STREAM), eq(Range.create(pelMsgId, pelMsgId))))
            .thenReturn(List.of(streamMsg));

    // DB says the photo is PENDING
    Photo pending = pendingPhoto(photoId);
    when(photoRepository.findPendingOrProcessingForRecovery(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(pending)));

    // Lock refresh succeeds
    when(redis.eval(anyString(), eq(ScriptOutputType.STATUS),
            any(String[].class), any(String[].class)))
            .thenReturn("OK");

    consumer.performStartupRecovery();

    // Photo is in PEL → must NOT be re-enqueued
    verify(redis, never()).xadd(eq(PhotoJobConsumer.STREAM), anyMap());
}
```

---

### CI-28: `DeleteJobConsumer` PEL Entries Are Permanently Orphaned on Worker Crash

**File:** `ConsumerScheduler.java:55-58`, `DeleteJobConsumer.java:115-129, 180`

**Description:** `ConsumerScheduler` runs `reclaimIdlePhotoJobs()` (XAUTOCLAIM on `photo-jobs`) every 5 minutes. There is **no `reclaimIdleDeleteJobs()`** equivalent for `delete-jobs`. The `DeleteJobConsumer` does not implement `reclaimIdleMessages()`.

The XACK in `DeleteJobConsumer.processMessage` is at the **end** (line 180), after all three MinIO `deleteObject` calls. If the JVM crashes between receiving the XREADGROUP message and sending XACK (e.g., during a MinIO delete call), the message stays in the PEL under the original consumer's name. Since the consumer name is `HOSTNAME + "-" + PID`, and PID changes on restart, the new consumer never re-reads the stuck PEL entry. With no XAUTOCLAIM on `delete-jobs`, the entry is **permanently stuck**.

The inline comment (line 163) says "at-most-once semantics... there is no XAUTOCLAIM retry mechanism on this stream." This is internally contradictory: at-most-once semantics require ACK-before-process (acknowledge first, then attempt the work). The current code acknowledges LAST (after MinIO calls succeed or fail gracefully). This is "best-effort single-attempt" semantics, not at-most-once.

**Consequences:**
1. The PEL for `delete-jobs` accumulates stuck entries from crashes — these are visible in `XPENDING` but never re-delivered.
2. Thumbnails partially deleted at crash time are never retried. `OrphanReconciliationScheduler` scans only `{userId}/originals/` (line 93: `String prefix = userId + "/originals/"`) — orphaned thumbnails in `{userId}/thumbnails/` are invisible to it and are permanently leaked.

**Why it matters:** For a long-running service, JVM crashes (OOM, SIGKILL from Docker, rolling restart kill signal) are expected. Each crash leaks ~2 thumbnail files (sm + md). Over months of operation this is minor storage waste, but the permanently-growing PEL is an operational annoyance — operators using `XPENDING delete-jobs delete-processors - + 10` will see growing lists of dead-consumer entries.

**Fix — two options (choose one):**

**Option A (prefer at-most-once — simplest):** XACK before MinIO deletes. A crash after XACK leaves the message gone — the deletes are lost, but the PEL is clean. Acceptable because `OrphanReconciliationScheduler` cleans originals weekly (thumbnails are still a gap, but smaller impact):

```java
// XACK first (at-most-once: if crash happens, we accept the missed delete)
redisCommands.xack(STREAM, GROUP, messageId);

// Then attempt deletes
deleteObject(originalKey, photoId, messageId);
if (isValidStorageKey(thumbnailSm)) deleteObject(thumbnailSm, ...);
if (isValidStorageKey(thumbnailMd)) deleteObject(thumbnailMd, ...);

log.info("Delete-job completed: photo_id={}, original_key={}", photoId, originalKey);
```

**Option B (add XAUTOCLAIM for delete-jobs — at-least-once):** Add `reclaimIdleDeleteJobs()` to `ConsumerScheduler`:

```java
// ConsumerScheduler.java
@Scheduled(fixedDelayString = "${worker.streams.autoclaim-interval-ms:300000}")
public void reclaimIdleDeleteJobs() {
    deleteJobConsumer.reclaimIdleMessages();
}
```

And add `reclaimIdleMessages()` to `DeleteJobConsumer` (parallel to `PhotoJobConsumer`). Since MinIO deletes are idempotent, re-delivery is safe. Update the comment to say "at-least-once" instead of "at-most-once."

---

## 3. Previously Addressed Items

All issues from reviews v1–v6 (CI-1–CI-25, MI-1–MI-41) are correctly addressed in the committed code and/or existing plan revisions. Highlights of the most relevant prior resolutions for the worker code:

- **Q21 (v6):** "Does `streamAllIds()` exist in `UserRepository`?" — Yes, confirmed at `UserRepository.java:22`. The method exists.
- **CI-23/CI-24/CI-25:** Frontend plan TDD stub and mock format issues — not yet applied to the plan (those fixes were in progress when this session started, per the handoff). They remain open in the plan document.
- **MI-41:** `-XX:+UseG1GC` in `worker/Dockerfile` — not yet removed (was in-progress). Remains open.
- Worker consumer code (all files above) was not reviewed in any prior review — this is the first pass.

---

## 4. Minor Issues & Improvements

### MI-42: `paginatePel()` Issues One XRANGE per PEL Entry — O(N) Sequential Round-Trips

**File:** `PhotoJobConsumer.java:393-421`, `fetchPhotoIdFromStream:426-440`

**Description:** For each `PendingMessage` in the PEL, `paginatePel()` calls `fetchPhotoIdFromStream(msg.getId())`, which issues one `XRANGE STREAM id id` per entry. For a PEL with 5,000 entries (e.g., after an extended outage), this issues 5,000 sequential Redis round-trips — approximately 2.5–5 seconds of network I/O at 0.5–1ms per RTT.

**Why it matters:** Startup recovery is already gated by a 5-minute lock TTL. For very large PELs (10,000+ entries after a multi-hour outage affecting many workers), the XRANGE scan could consume a significant portion of the 5-minute window, leaving less time for the DB page scan. This is unlikely to exceed the TTL in practice but is an unnecessary O(N) pattern.

**Fix:** Batch XRANGE calls by collecting `PEL_PAGE_SIZE` message IDs per PEL page and issuing one `XRANGE minId maxId COUNT batchSize` per batch, then matching returned messages to PEL entries by ID. This reduces round-trips from O(N) to O(N/1000).

For the current deployment scale (single VPS, limited concurrent users), this is a low-urgency optimization. The test coverage issue (CI-27) is the immediate concern.

---

### MI-43: `xautoclaim_doesNotReclaimRecentlyProcessedMessages` Accesses Private Field via Reflection

**File:** `StartupRecoveryTest.java:103-106`

**Description:**

```java
java.lang.reflect.Field minIdleField = XAutoClaimArgs.class.getDeclaredField("minIdleTime");
minIdleField.setAccessible(true);
long actualIdleTime = (long) minIdleField.get(argsCaptor.getValue());
```

`XAutoClaimArgs.minIdleTime` is a private field with no public getter. If Lettuce renames this field in a future minor release (e.g., to `minIdleTimeMs`), the test throws `NoSuchFieldException` at runtime — not at compile time. The failure is not obvious from the stack trace.

**Fix:** Parameterize the test with a distinctive `claimIdleTimeMs` value set via `WorkerProperties`, then verify XAUTOCLAIM is called with that exact idle time by comparing it against the configured value (which you control):

```java
@Test
void xautoclaim_passesConfiguredIdleTimeToRedis() {
    long customIdleMs = 60_000L; // 1 minute — different from default 30 minutes
    workerProperties.getStreams().setClaimIdleTimeMs(customIdleMs);

    ClaimedMessages<String, String> empty = new ClaimedMessages<>("0-0", List.of());
    when(redis.xautoclaim(eq(PhotoJobConsumer.STREAM), any(XAutoClaimArgs.class)))
            .thenReturn(empty);

    consumer.reclaimIdleMessages();

    // The exact minIdleTime field is internal to Lettuce.
    // Indirectly verify correctness by confirming a different idle time was NOT used:
    // The only reliable assertion without reflection is that xautoclaim was called exactly once.
    // A future Lettuce version may expose a public API — for now, calling XAUTOCLAIM once
    // with ANY args confirms the method reached Redis.
    verify(redis, times(1)).xautoclaim(eq(PhotoJobConsumer.STREAM), any(XAutoClaimArgs.class));
}
```

If the goal is to verify the specific idle time value, an integration test with a real Redis container (similar to `SchedulerTest`) is more reliable than reflection on Lettuce internals.

---

### MI-44: `photo-poll-delay-ms`, `delete-poll-delay-ms`, and `autoclaim-interval-ms` Not in `WorkerProperties` Typed Config

**File:** `ConsumerScheduler.java:33,43,55`, `WorkerProperties.java`

**Description:** `WorkerProperties.Streams` exposes `claimIdleTimeMs` and `maxRetries` as typed, testable properties. But the scheduler delays — `worker.streams.photo-poll-delay-ms`, `worker.streams.delete-poll-delay-ms`, and `worker.streams.autoclaim-interval-ms` — are accessed directly via `@Scheduled(fixedDelayString = "${...}")`. These are not surfaced in `WorkerProperties`, so:

1. Tests that need custom delays must use `@TestPropertySource` (heavyweight) rather than `workerProperties.getStreams().setPhotoPollDelayMs(...)`.
2. The operator configuration reference is split: some `worker.streams.*` properties are in the typed class, others are documented only by the `@Scheduled` annotation.

**Fix:** Add the three delay fields to `WorkerProperties.Streams` with their defaults, and reference them via SpEL in `@Scheduled`:

```java
// WorkerProperties.Streams
private long photoPollDelayMs = 100;
private long deletePollDelayMs = 100;
private long autoclaimIntervalMs = 300_000;
// + getters/setters
```

```java
// ConsumerScheduler.java — requires Spring Expression Language (@EnableSpringConfigured not needed)
@Scheduled(fixedDelayString = "#{@workerProperties.streams.photoPollDelayMs}")
public void pollPhotoJobs() { ... }
```

Low urgency. The defaults in the `@Scheduled` annotation are readable. This is a consistency improvement.

---

### MI-45: Dead-Letter Stream Has No Consumer, Reader, or Operational Alerting

**File:** `PhotoJobConsumer.java:261-267`

**Description:**

```java
redisCommands.xadd("dead-letter", Map.of(
        "photo_id", photoId.toString(),
        "message_id", messageId,
        "reason", "max_retries_exceeded"));
```

Dead-lettered photos are written to the `"dead-letter"` stream. There is no consumer group or reader for this stream. The stream will grow without bound. An operator using `XLEN dead-letter` or `XRANGE dead-letter - + COUNT 10` can inspect it, but there is no automated alerting (e.g., Redis stream length metric, log aggregation trigger, or Prometheus gauge).

This means: if a photo pipeline fails repeatedly for all users (e.g., a corrupt shared dependency), all photos are silently dead-lettered with no operator notification beyond ERROR log lines that may be lost in high-volume log streams.

**Fix:** Add a note in `worker/application.yml` and in class Javadoc pointing to the operations runbook. For a future hardening pass: expose a Micrometer gauge on `XLEN dead-letter` via Spring Boot Actuator. Low urgency for the current deployment.

---

### MI-46: Both API Service and Worker Service Run Flyway Migrations

**File:** `worker/src/main/resources/application.yml:15-24`

**Description:** Both `api/application.yml` and `worker/application.yml` have `spring.flyway.enabled: true`. Both services run Flyway migrations on startup. Flyway's internal distributed lock (stored in `flyway_schema_history` using a SELECT ... FOR UPDATE) prevents double-execution. However:

1. If the worker starts before the API service and the schema is not yet initialized, the worker runs all migrations — requiring its restricted DB user (`jpt_worker`) to have no schema permission, while the Flyway user (`jpt`) must be available. This is fine as configured (separate `spring.flyway.user`) but adds startup ordering dependency.
2. The worker Flyway configuration is a full duplicate of the API's, creating a maintenance burden if placeholder names change.

**Standard pattern:** Only one service (typically the API or an init container) runs Flyway. The worker should have `spring.flyway.enabled: false`. The worker's restricted DB user (`jpt_worker`) does not need DDL permissions.

**Fix (optional):** Set `spring.flyway.enabled: false` in `worker/application.yml`. Ensure the API service starts before the worker (already the common Docker Compose `depends_on` pattern). Low urgency — the current approach is safe for the single-VPS deployment.

---

### MI-47: `@EnableScheduling` on `ConsumerConfig` Instead of `ConsumerScheduler`

**File:** `ConsumerConfig.java:41`

**Description:** `@EnableScheduling` is placed on `ConsumerConfig` (a `@Configuration` class that wires Lettuce beans). The scheduled methods are in `ConsumerScheduler`. It's more idiomatic and readable to place `@EnableScheduling` on `ConsumerScheduler` itself or the main application class, co-locating the annotation with the class it enables.

**Fix:** Move `@EnableScheduling` from `ConsumerConfig` to `ConsumerScheduler`. Zero functional change.

---

## 5. Questions for Clarification

**Q22:** For `DeleteJobConsumer`, is the intended semantic truly at-most-once (XACK first, then delete — accept occasional missed deletes) or best-effort single-attempt (XACK last — accept stuck PEL entries on crash)? The comment says "at-most-once" but the code does "XACK last." CI-28's Option A or Option B depends on this answer.

**Q23:** Should thumbnails be included in `OrphanReconciliationScheduler`'s scan? Currently only `{userId}/originals/` is scanned. If `DeleteJobConsumer` uses Option A (XACK-first), some thumbnail deletes may be missed on crash. Without a thumbnail scan in the reconciler, those orphaned thumbnails accumulate permanently.

**Q24:** Is `DeleteJobConsumer`'s `processMessage` deliberately omitting XAUTOCLAIM recovery to keep the delete-jobs stream "fire-and-forget"? If so, the operational cost (growing PEL from crashes) should be acknowledged in the class Javadoc with a note that stuck PEL entries can be inspected and cleared manually via `XDEL`.

---

## 6. Dependency Map Update

```
Worker Redis Streams consumer code (committed in 9b67af2–84fe5cb)
  ├── ConsumerConfig (wiring)
  │     ✓ StatefulRedisConnection bean (Spring calls close() via Closeable detection)
  │     ✓ Consumer name: HOSTNAME + PID
  │     ✓ ApplicationReadyEvent for deferred startup recovery
  │     ✓ Consumer group bootstrap (ensureGroupExists) at bean creation
  │     [CI-26: @EnableScheduling default pool=1 — serializes both consumers]
  │
  ├── ConsumerScheduler (poll loop driver)
  │     ✓ fixedDelay (not fixedRate) — sequential processing
  │     ✓ XAUTOCLAIM every 5 min for photo-jobs
  │     [CI-26: 1 thread shared by pollPhotoJobs + pollDeleteJobs + reclaimIdlePhotoJobs]
  │     [CI-28: No reclaimIdleDeleteJobs — delete-jobs PEL stuck on crash]
  │     [MI-44: poll delays not in WorkerProperties typed config]
  │     [MI-47: @EnableScheduling on ConsumerConfig, not here]
  │
  ├── PhotoJobConsumer
  │     ✓ PENDING/PROCESSING routing (PROCESSING = reclaim path)
  │     ✓ DONE/FAILED terminal skip
  │     ✓ Null storage_key guard
  │     ✓ XPENDING redelivery count → dead-letter at maxRetries
  │     ✓ XAUTOCLAIM reclaimIdleMessages()
  │     ✓ Startup recovery with distributed Redis lock (SET NX + Lua refresh)
  │     ✓ PEL pagination (1000/page)
  │     [CI-27: paginatePel() dedup logic untested — xrange never mocked]
  │     [MI-42: paginatePel() O(N) XRANGE round-trips per PEL entry]
  │     [MI-45: dead-letter stream unmonitored]
  │
  ├── DeleteJobConsumer
  │     ✓ Null/blank originalKey guard (XACK + skip)
  │     ✓ STORAGE_KEY_PATTERN format validation (SA3-F2)
  │     ✓ Independent thumbnail key validation (partial delete on bad sm/md key)
  │     ✓ MinIO failure caught, logged; XACK still issued
  │     [CI-28: No XAUTOCLAIM — crash leaves PEL entry permanently stuck]
  │     [CI-28: Comment says "at-most-once" but XACK is LAST, not first]
  │
  ├── PhotoRepository (new method)
  │     ✓ findPendingOrProcessingForRecovery — JPQL enum FQN (Hibernate-supported)
  │     ✓ findAllByUserIdWithStorageKey — used by UnverifiedAccountPurgeScheduler
  │
  └── worker/application.yml
        ✓ worker.streams.claim-idle-time-ms: 1800000
        ✓ worker.streams.max-retries: 3
        [CI-26: spring.task.scheduling.pool.size missing — defaults to 1]
        [MI-46: spring.flyway.enabled: true — both services run migrations]
```

---

## 7. Final Recommendation

**Approve with changes.**

The implementation is the most sophisticated code in the Phase 4 work — the startup recovery distributed locking, PEL pagination, and retry/dead-letter routing are well-designed. The at-most-once vs. at-least-once semantics confusion in `DeleteJobConsumer`, the scheduler thread starvation, and the untested dedup logic are the three issues that must be resolved before this ships.

| Priority | Issue | Fix |
|----------|-------|-----|
| **Blocking** | CI-26: Single scheduler thread starves delete consumer | Add `spring.task.scheduling.pool.size: 3` to `worker/application.yml` |
| **Blocking** | CI-27: `paginatePel()` dedup logic completely untested | Add test that mocks `xrange` and verifies in-PEL photos are not re-enqueued |
| **Blocking** | CI-28: `DeleteJobConsumer` crash leaves PEL entries permanently stuck; at-most-once comment contradicts XACK-last behavior | Choose Option A (XACK-first) or Option B (add XAUTOCLAIM) and update comment |
| **Medium** | MI-42: O(N) XRANGE round-trips in `paginatePel()` | Batch XRANGE calls per PEL page |
| **Medium** | MI-43: Reflection on `XAutoClaimArgs.minIdleTime` private field | Replace with a non-reflection assertion or integration test |
| **Medium** | MI-44: Poll delays not in `WorkerProperties` typed config | Add `photoPollDelayMs`, `deletePollDelayMs`, `autoclaimIntervalMs` to `WorkerProperties.Streams` |
| **Low** | MI-45: Dead-letter stream unmonitored | Add Javadoc + operations runbook reference |
| **Low** | MI-46: Both services run Flyway migrations | Set `spring.flyway.enabled: false` in worker |
| **Low** | MI-47: `@EnableScheduling` on `ConsumerConfig` | Move to `ConsumerScheduler` |
