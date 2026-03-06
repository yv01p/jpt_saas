# Critical Implementation Review — Phase 3: Storage & Media (v3.0)
**Source plan:** `2026-02-25-saas-conversion-phase-3.md` (v3.0)
**Review version:** 3
**Reviewer:** Senior Staff Engineer
**Date:** 2026-03-04
**Prior reviews:** `critical-review-1.md` (v1.0 → v2.0), `critical-review-2.md` (v2.0 → v3.0)

---

## 1. Overall Assessment

The v3.0 plan is a high-quality, production-conscious implementation guide. All five critical issues and all twelve minor issues from the second review are addressed correctly and in sufficient detail. The dual-client MinIO pattern, delivery-count-via-XPENDING, lock TTL increase, PEL-check guard, and compensating-Tx recovery are all present and coherent.

Two new critical-severity issues remain: the PEL check is bounded to COUNT 100 and is not paginated — in the large-outage recovery scenario it was designed to protect, it silently fails to exclude most already-queued messages. The lock-refresh step uses `SET ... XX` without verifying ownership, creating a race window where two instances can both believe they hold the lock and run recovery concurrently. Two minor code-correctness issues are also present.

No prior critical or minor issues have been inadvertently reopened.

---

## 2. Critical Issues

### CI-1 — PEL Deduplication Check Bounded to COUNT 100: Silent Failure Under Large Recovery (Task 3.4, Step 5)

**Description:** The startup recovery step reads the Pending Entry List with:

```
XPENDING photo-jobs photo-processors - + COUNT 100
```

This call returns at most 100 PEL entries. The recovery loop then iterates all `pending`/`processing` DB rows (potentially thousands after an extended outage) and filters out any `photo_id` present in the PEL. However, if there are more than 100 entries in the PEL, all entries beyond position 100 are invisible to the check. Any photo whose PEL entry falls at position 101+ will appear "absent from the PEL" and be re-enqueued, creating a duplicate stream entry. This is exactly the failure scenario that introduced the PEL check in the first place: a large-scale outage where the worker crashed mid-recovery and is now restarting.

**Impact:** The deduplication guard silently provides no protection when it matters most. A sufficiently large outage (>100 in-flight jobs at crash time) results in the same unbounded re-enqueueing as v2.0, resetting delivery counters and causing failed jobs to retry indefinitely.

**Fix — choose one:**

- **Option A (Recommended): Paginate the PEL into a Set before scanning.** Before the DB scan begins, iterate the full PEL using repeated `XPENDING ... - + COUNT 1000` calls (advancing the cursor on each call) until the result set is empty. Collect all `photo_id` field values into a `Set<String>`. The DB recovery scan filters against this in-memory Set. This is O(PEL-size) in Redis round-trips with 1000-entry pages, which is fast and bounded.

- **Option B: Use a Redis Set as idempotency guard.** On every `XADD photo-jobs` enqueue (both startup recovery and normal upload), atomically `SADD photo-jobs:in-flight {photo_id}` in a pipeline. On `XACK`, atomically `SREM photo-jobs:in-flight {photo_id}`. Recovery checks `SISMEMBER` instead of iterating the PEL. This has O(1) per check but requires modifying the normal upload path. The Set must have an expiry to prevent unbounded growth on abnormal termination without XACK.

Document the chosen option explicitly and add a corresponding test.

---

### CI-2 — Lock Refresh Does Not Verify Ownership: Two Instances Can Both Believe They Hold the Lock (Task 3.4, Step 5)

**Description:** The lock-refresh step is:

```java
SET worker:startup-recovery-lock {instanceId} XX PX 300000
```

The Redis `XX` flag sets the key only if it already exists — it does not check the current value. If the lock expires between two page batches (e.g., a GC pause longer than 5 minutes on the holding instance) and a second instance acquires the lock with its own `instanceId` before the first instance issues the refresh, the refresh command succeeds — the key exists, so `XX` does not block it — but it overwrites the second instance's `instanceId` with the first instance's `instanceId`. The second instance, which now holds the refreshed lock under a value it never set, still believes it is the lock holder (its local variable `lockAcquired == true`). Both instances proceed with recovery concurrently, defeating the entire distributed-lock design.

**Impact:** Under a GC pause or I/O stall longer than the 5-minute TTL, two worker instances execute concurrent startup recovery and both re-enqueue the same photos, duplicating stream entries and resetting delivery counters for any in-flight jobs.

**Fix:** Use a Lua script for the refresh to atomically check ownership before extending TTL:

```lua
-- refresh-lock.lua
if redis.call("GET", KEYS[1]) == ARGV[1] then
  return redis.call("SET", KEYS[1], ARGV[1], "XX", "PX", ARGV[2])
else
  return nil
end
```

If the Lua script returns nil, the instance has lost the lock and must abort recovery immediately (log at ERROR; do not continue scanning). In Spring Data Redis with Lettuce, execute via `RedisTemplate.execute(RedisScript<Boolean> script, ...)`. Add a test asserting that a lock-holder whose lock has expired does not continue recovery after a failed refresh.

---

## 3. Minor Issues & Improvements

### MI-1 — `Files.createTempFile` Called With String Directory: Compile Error (Task 3.5, Step 3)

**Description:** The code fragment in the temp-file cleanup fix reads:

```java
Path tmp = Files.createTempFile("/tmp", photoId.toString(), "." + ext);
```

`Files.createTempFile` has two overloads:
- `createTempFile(String prefix, String suffix)` — two arguments, uses system temp dir
- `createTempFile(Path dir, String prefix, String suffix)` — three arguments, `Path` first

Passing a `String` as the first argument in the three-argument form does not compile; there is no overload accepting `(String, String, String)`. This is not a runtime error; it is a compile error that will prevent the worker module from building.

**Fix:**
```java
Path tmp = Files.createTempFile(Path.of("/tmp"), photoId.toString(), "." + ext);
```

---

### MI-2 — null-`storage_key` Cleanup: Quota Decrement Not Atomic With Row Delete (Task 3.6, Step 2)

**Description:** The compensating-Tx recovery cleanup added in v3.0 states: "For each [null-`storage_key` row]: enqueue delete-job (no-op if no MinIO object exists), delete the DB row, decrement `used_bytes`." These two DB mutations — delete row and decrement `used_bytes` — are not specified as a single transaction. If the scheduler thread is interrupted (JVM crash, OOM kill, container restart) after the `DELETE photos WHERE id = ?` commits but before the `UPDATE users SET used_bytes = used_bytes - ?` commits, the photo row is gone and the quota cannot be recovered. This is precisely the silent quota drift that CI-2 (review 2) was designed to eliminate. The recovery path re-introduces the same class of failure it was created to fix.

**Impact:** Pathological (double-crash required: MinIO upload fails, compensating Tx fails, then the recovery cleanup also crashes mid-execution), but the failure mode is permanent and silent — same as the original CI-2 scenario.

**Fix:** Wrap the delete and quota decrement in a single `@Transactional` method:

```java
@Transactional
void purgeNullStorageKeyRow(Photo photo) {
    photoRepo.delete(photo);
    userRepo.decrementUsedBytes(photo.getUserId(), photo.getFileSize());
}
```

Because `fileSize` may be 0 or unknown for these rows (the upload never completed), document what value to decrement by: either the value stored in `photos.file_size` (populated at step 3 of the upload transaction before MinIO), or 0 if the column is null. Clarify which is correct.

---

## 4. Questions for Clarification

1. **PEL deduplication approach (CI-1):** Does Option A (paginated PEL Set) or Option B (Redis `in-flight` Set) better fit the implementation constraints? Option A requires no changes to the normal upload path. Option B requires coordinated changes in both API and worker but scales better if the PEL is expected to be very large (>10k entries).

2. **Lock-refresh Lua script availability:** Is a Spring Data Redis `RedisScript` abstraction already used in the codebase (e.g., from Phase 2 rate limiting), or does this introduce a new pattern that needs to be wired up?

3. **null-`storage_key` row `file_size`:** Is `file_size` populated on the `photos` row during Tx 1 of the upload (step 3, before MinIO upload), or is it populated only after MinIO succeeds? The compensating-Tx recovery path needs to know the correct value to decrement from `used_bytes`.

---

## 5. Final Recommendation

**Approve with changes** — two targeted fixes required before implementation begins.

**Must fix before writing any code:**
- CI-1: Paginate the PEL check into a `Set<String>` before the recovery scan, or use a Redis `in-flight` Set
- CI-2: Replace the lock-refresh `SET XX` with an ownership-checking Lua script; add test for expired-lock abort

**Fix during implementation (low blast radius, no architectural impact):**
- MI-1: `Files.createTempFile(Path.of("/tmp"), ...)` — compile error, will be caught immediately
- MI-2: Wrap null-`storage_key` delete + quota decrement in a single `@Transactional` method; clarify `file_size` value for decrement
