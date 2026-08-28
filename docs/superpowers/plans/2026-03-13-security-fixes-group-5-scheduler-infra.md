# Security Fixes Group 5: Scheduler Infrastructure (Finding #6)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create V14 Flyway migration for scheduler permissions, create `SchedulerRepository` to encapsulate scheduler SQL, refactor `PhotoDeleteJobEnqueuer` with shared helpers.

**Dependencies:** None — fully independent. (Group 6 depends on this group.)

**Tech Stack:** Java 17, Spring Boot 3.4.2, PostgreSQL 16 (Flyway), JUnit 5 + Testcontainers, Redis Streams, MinIO.

**Design Spec:** `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md` (Section 5)

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `api/src/main/resources/db/migration/V14__grant_scheduler_permissions_to_jpt_auth.sql` | Create | Comprehensive scheduler grants |
| `api/src/main/java/org/jphototagger/api/repository/SchedulerRepository.java` | Create | Encapsulate all scheduler raw SQL |
| `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java` | Modify | Add `buildDeleteJobMessage`, `enqueueByRows`, `extractPhotoIdFromKey` |

---

### Task 1: Create V14 migration for scheduler permissions

**Files:**
- Create: `api/src/main/resources/db/migration/V14__grant_scheduler_permissions_to_jpt_auth.sql`

- [ ] **Step 1: Create migration file**

```sql
-- V14__grant_scheduler_permissions_to_jpt_auth.sql
-- Comprehensive grants for all tables accessed by schedulers via authJdbcTemplate.
-- This migration supersedes any out-of-band grants previously applied to jpt_auth
-- during environment provisioning. All scheduler-required permissions are now
-- version-controlled in Flyway — the single source of truth for scheduler permissions.

-- TrashPurgeScheduler: SELECT purgeable batches, DELETE purged rows
GRANT SELECT, DELETE ON photos TO jpt_auth;

-- TrashPurgeScheduler.purgeNullStorageKeyPhotos(): CTE updates users.used_bytes
GRANT UPDATE (used_bytes) ON users TO jpt_auth;

-- UnverifiedAccountPurgeScheduler: full user cascade delete
-- NOTE: DELETE ON users is the highest-privilege grant to jpt_auth.
-- Required by UnverifiedAccountPurgeScheduler for purging unverified accounts.
-- Application-layer WHERE clause restricts to email_verified = false AND created_at < cutoff.
-- No database-level restriction is possible — audit any new DELETE usage against users table.
GRANT DELETE ON users TO jpt_auth;
GRANT SELECT, DELETE ON album_photos TO jpt_auth;
GRANT DELETE ON albums TO jpt_auth;
GRANT DELETE ON saved_searches TO jpt_auth;
GRANT DELETE ON shares TO jpt_auth;
GRANT SELECT, UPDATE, DELETE ON keywords TO jpt_auth;

-- OrphanReconciliationScheduler: query user IDs, check photo existence
-- (SELECT ON users already granted in V4; SELECT ON photos granted above)
```

- [ ] **Step 2: Run Flyway migration via test**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.JptSaasApplicationTest" --no-daemon`
Expected: PASS (migration applies cleanly)

- [ ] **Step 3: Commit**

```bash
git add api/src/main/resources/db/migration/V14__grant_scheduler_permissions_to_jpt_auth.sql
git commit -m "infra: V14 migration — comprehensive scheduler permissions for jpt_auth (Finding #6)"
```

---

### Task 2: Create SchedulerRepository

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/repository/SchedulerRepository.java`

- [ ] **Step 1: Implement SchedulerRepository**

```java
package org.jphototagger.api.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates all raw SQL used by schedulers via authJdbcTemplate (BYPASSRLS).
 * Scheduler classes call repository methods instead of inlining SQL.
 */
@Repository
public class SchedulerRepository {

    private final JdbcTemplate authJdbc;

    public SchedulerRepository(@Qualifier("authJdbcTemplate") JdbcTemplate authJdbc) {
        this.authJdbc = authJdbc;
    }

    public List<Map<String, Object>> findPurgeableBatch(Instant cutoff) {
        return authJdbc.queryForList(
            "SELECT id, user_id, storage_key FROM photos " +
            "WHERE deleted_at < ? " +
            "LIMIT 100",
            Timestamp.from(cutoff));
    }

    public void deletePhotosByIds(UUID[] ids) {
        authJdbc.update("DELETE FROM photos WHERE id = ANY(?)",
            (PreparedStatement ps) -> ps.setArray(1,
                ps.getConnection().createArrayOf("uuid", ids)));
    }

    public int purgeNullStorageKeyPhotos() {
        return authJdbc.update(
            "WITH deleted AS (" +
            "    DELETE FROM photos" +
            "    WHERE storage_key IS NULL" +
            "    AND deleted_at IS NULL" +
            "    AND uploaded_at < now() - INTERVAL '1 hour'" +
            "    RETURNING user_id, COALESCE(size_bytes, 0) AS size_bytes" +
            ")" +
            "UPDATE users u" +
            "  SET used_bytes = GREATEST(0, u.used_bytes - d.size_bytes)" +
            "  FROM deleted d" +
            "  WHERE u.id = d.user_id"
        );
    }

    public List<UUID> queryUserIdPage(UUID afterId, int pageSize) {
        if (afterId == null) {
            return authJdbc.queryForList(
                "SELECT id FROM users ORDER BY id LIMIT ?", UUID.class, pageSize);
        }
        return authJdbc.queryForList(
            "SELECT id FROM users WHERE id > ? ORDER BY id LIMIT ?",
            UUID.class, afterId, pageSize);
    }

    public List<UUID> findExistingPhotoIds(UUID[] batch) {
        return authJdbc.query(
            con -> {
                PreparedStatement ps = con.prepareStatement(
                    "SELECT id FROM photos WHERE id = ANY(?)");
                ps.setArray(1, con.createArrayOf("uuid", batch));
                return ps;
            },
            (rs, rowNum) -> UUID.fromString(rs.getString("id")));
    }

    public List<Map<String, Object>> findStorageKeysByUserId(UUID userId) {
        return authJdbc.queryForList(
            "SELECT id, user_id, storage_key FROM photos " +
            "WHERE user_id = ? AND storage_key IS NOT NULL",
            userId);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:compileJava --no-daemon`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/repository/SchedulerRepository.java
git commit -m "feat(scheduler): create SchedulerRepository to encapsulate scheduler SQL"
```

---

### Task 3: Add `buildDeleteJobMessage`, `enqueueByRows`, `extractPhotoIdFromKey` to PhotoDeleteJobEnqueuer

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java`

- [ ] **Step 1: Extract `buildDeleteJobMessage()` helper and add new methods**

In `PhotoDeleteJobEnqueuer.java`:

1. Add `buildDeleteJobMessage()` private helper
2. Refactor `enqueue(List<Photo>)` to use it
3. Refactor `enqueueOrphan()` to use it
4. Add `enqueueByRows(List<Map<String,Object>>)`
5. Add static `extractPhotoIdFromKey(String)`

```java
private Map<String, String> buildDeleteJobMessage(UUID userId, UUID photoId, String originalKey) {
    return Map.of(
        "photo_id",     photoId.toString(),
        "original_key", originalKey,
        "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
        "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
    );
}

public void enqueueByRows(List<Map<String, Object>> rows) {
    redisTemplate.executePipelined(new SessionCallback<Object>() {
        @SuppressWarnings("unchecked")
        @Override
        public Object execute(RedisOperations operations) {
            for (Map<String, Object> row : rows) {
                UUID photoId   = (UUID) row.get("id");
                UUID userId    = (UUID) row.get("user_id");
                String origKey = (String) row.get("storage_key");
                if (origKey == null) {
                    log.warn("Skipping delete-job for photo {} — null storage_key", photoId);
                    continue;
                }
                operations.opsForStream().add("delete-jobs",
                    buildDeleteJobMessage(userId, photoId, origKey));
            }
            return null;
        }
    });
}

/** Parses UUID from "{userId}/originals/{photoId}.{ext}"; returns null on failure. */
static UUID extractPhotoIdFromKey(String key) {
    try {
        int lastSlash = key.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String filename = key.substring(lastSlash + 1);
        int dot = filename.lastIndexOf('.');
        String uuidStr = dot >= 0 ? filename.substring(0, dot) : filename;
        return UUID.fromString(uuidStr);
    } catch (IllegalArgumentException e) {
        return null;
    }
}
```

Update `enqueue(List<Photo>)` to use `buildDeleteJobMessage`:

```java
// Inside the loop, replace Map.of(...) with:
Map<String, String> msg = buildDeleteJobMessage(userId, photoId, photo.getStorageKey());
```

Update `enqueueOrphan()` to use `buildDeleteJobMessage`:

```java
public void enqueueOrphan(UUID userId, UUID photoId, String originalKey) {
    redisTemplate.opsForStream().add("delete-jobs",
        buildDeleteJobMessage(userId, photoId, originalKey));
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:compileJava --no-daemon`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java
git commit -m "refactor(scheduler): extract buildDeleteJobMessage, add enqueueByRows and extractPhotoIdFromKey"
```
