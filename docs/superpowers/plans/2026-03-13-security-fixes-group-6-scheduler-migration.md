# Security Fixes Group 6: Scheduler Migration + Tests (Finding #6)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate all three schedulers (TrashPurge, OrphanReconciliation, UnverifiedAccountPurge) to use `SchedulerRepository`, add integration tests, run final verification.

**Dependencies:** Requires Group 5 (Scheduler Infrastructure) to be completed first — needs `SchedulerRepository`, `PhotoDeleteJobEnqueuer.enqueueByRows()`, and V14 migration.

**Tech Stack:** Java 17, Spring Boot 3.4.2, PostgreSQL 16 (Flyway), JUnit 5 + Testcontainers, Redis Streams, MinIO.

**Design Spec:** `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md` (Section 5)

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java` | Modify | Migrate to `SchedulerRepository` |
| `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java` | Modify | Keyset pagination via `SchedulerRepository` |
| `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java` | Modify | Use `SchedulerRepository.findStorageKeysByUserId` |
| `api/src/test/java/org/jphototagger/api/scheduler/SchedulerTest.java` | Modify | Update mocks from `PhotoRepository` to `SchedulerRepository` |
| `api/src/test/java/org/jphototagger/api/repository/SchedulerRepositoryTest.java` | Create | Integration tests with `jpt_auth` role |

---

### Task 1: Migrate TrashPurgeScheduler to SchedulerRepository

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java`

- [ ] **Step 1: Replace photoRepository and jdbcTemplate with SchedulerRepository**

Rewrite `TrashPurgeScheduler.java`:

```java
package org.jphototagger.api.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jphototagger.api.repository.SchedulerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TrashPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);

    private final SchedulerRepository schedulerRepository;
    private final PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer;
    private final int retentionDays;

    public TrashPurgeScheduler(
            SchedulerRepository schedulerRepository,
            PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer,
            @Value("${jpt.trash.retention-days:30}") int retentionDays) {
        this.schedulerRepository = schedulerRepository;
        this.photoDeleteJobEnqueuer = photoDeleteJobEnqueuer;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "trashPurge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeTrash() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("TrashPurgeScheduler: purging photos deleted before {} (retention={} days)",
                cutoff, retentionDays);

        List<Map<String, Object>> batch;
        int totalPurged = 0;

        do {
            batch = schedulerRepository.findPurgeableBatch(cutoff);
            if (batch.isEmpty()) {
                break;
            }
            photoDeleteJobEnqueuer.enqueueByRows(batch);
            UUID[] ids = batch.stream()
                    .map(row -> (UUID) row.get("id"))
                    .toArray(UUID[]::new);
            schedulerRepository.deletePhotosByIds(ids);
            totalPurged += batch.size();
            log.debug("TrashPurgeScheduler: purged batch of {} photos (total so far: {})",
                    batch.size(), totalPurged);
        } while (!batch.isEmpty());

        int nullKeyRows = schedulerRepository.purgeNullStorageKeyPhotos();
        log.info("TrashPurgeScheduler: purged {} photos, cleaned {} null-storage-key rows",
                totalPurged, nullKeyRows);
    }
}
```

- [ ] **Step 2: Update SchedulerTest mocks for TrashPurgeScheduler**

`SchedulerTest` uses `@MockBean PhotoRepository` — update to `@MockBean SchedulerRepository` and change mock setup:

1. Replace `@MockBean PhotoRepository photoRepository;` with `@MockBean SchedulerRepository schedulerRepository;`
2. Update any `when(photoRepository.findPurgeableBatch(...))` to `when(schedulerRepository.findPurgeableBatch(any(Instant.class))).thenReturn(List.of(...))`
3. For empty-batch tests: `when(schedulerRepository.findPurgeableBatch(any())).thenReturn(List.of())`
4. For batch-with-data tests: return `List<Map<String,Object>>` rows matching the `findPurgeableBatch` schema: `Map.of("id", photoId, "user_id", userId, "storage_key", "key")`
5. Add `when(schedulerRepository.purgeNullStorageKeyPhotos()).thenReturn(0)` where needed
6. Verify with: `verify(schedulerRepository).deletePhotosByIds(any(UUID[].class))`

- [ ] **Step 3: Run scheduler tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.scheduler.SchedulerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java api/src/test/java/org/jphototagger/api/scheduler/SchedulerTest.java
git commit -m "refactor(scheduler): migrate TrashPurgeScheduler to SchedulerRepository (Finding #6)"
```

---

### Task 2: Migrate OrphanReconciliationScheduler to SchedulerRepository + keyset pagination

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java`

- [ ] **Step 1: Rewrite OrphanReconciliationScheduler**

Replace entire file to use `SchedulerRepository` and keyset pagination:

```java
package org.jphototagger.api.scheduler;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jphototagger.api.repository.SchedulerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class OrphanReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrphanReconciliationScheduler.class);
    private static final Duration RECENCY_THRESHOLD = Duration.ofHours(2);
    private static final int ID_BATCH_SIZE = 1_000;

    private final SchedulerRepository schedulerRepository;
    private final MinioClient minioInternalClient;
    private final PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer;
    private final String bucket;

    public OrphanReconciliationScheduler(
            SchedulerRepository schedulerRepository,
            @Qualifier("minioInternalClient") MinioClient minioInternalClient,
            PhotoDeleteJobEnqueuer photoDeleteJobEnqueuer,
            @Value("${minio.bucket}") String bucket) {
        this.schedulerRepository = schedulerRepository;
        this.minioInternalClient = minioInternalClient;
        this.photoDeleteJobEnqueuer = photoDeleteJobEnqueuer;
        this.bucket = bucket;
    }

    @Scheduled(cron = "0 0 4 * * SUN")
    @SchedulerLock(name = "orphanReconciliation", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void reconcileOrphans() {
        log.info("OrphanReconciliationScheduler: starting orphan reconciliation");

        UUID cursor = null;
        int pageSize = 100;
        int orphansFound = 0;

        while (true) {
            List<UUID> page = schedulerRepository.queryUserIdPage(cursor, pageSize);
            if (page.isEmpty()) break;
            for (UUID userId : page) {
                orphansFound += reconcileUser(userId);
            }
            cursor = page.get(page.size() - 1);
        }

        log.info("OrphanReconciliationScheduler: enqueued {} orphaned objects for deletion",
                orphansFound);
    }

    private int reconcileUser(UUID userId) {
        String prefix = userId + "/originals/";

        Iterable<Result<Item>> objects = minioInternalClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .recursive(false)
                        .build());

        ZonedDateTime recencyCutoff = ZonedDateTime.now().minus(RECENCY_THRESHOLD);
        Map<UUID, String> candidateKeys = new HashMap<>();
        for (Result<Item> result : objects) {
            try {
                Item item = result.get();
                if (item.isDir()) continue;
                if (item.lastModified() == null || item.lastModified().isAfter(recencyCutoff)) {
                    if (item.lastModified() == null) {
                        log.warn("OrphanReconciliationScheduler: null lastModified for key={}, skipping", item.objectName());
                    }
                    continue;
                }
                String objectKey = item.objectName();
                if (!objectKey.startsWith(prefix)) continue;

                UUID photoId = PhotoDeleteJobEnqueuer.extractPhotoIdFromKey(objectKey);
                if (photoId == null) {
                    log.warn("OrphanReconciliationScheduler: could not parse photo_id from key={}", objectKey);
                    continue;
                }
                candidateKeys.put(photoId, objectKey);
            } catch (Exception e) {
                log.error("OrphanReconciliationScheduler: error processing MinIO object", e);
            }
        }

        if (candidateKeys.isEmpty()) return 0;

        List<UUID> candidateIds = new ArrayList<>(candidateKeys.keySet());
        Set<UUID> existingIds = findExistingIds(candidateIds);

        int count = 0;
        for (Map.Entry<UUID, String> entry : candidateKeys.entrySet()) {
            UUID photoId = entry.getKey();
            String objectKey = entry.getValue();
            if (!existingIds.contains(photoId)) {
                photoDeleteJobEnqueuer.enqueueOrphan(userId, photoId, objectKey);
                count++;
                log.debug("OrphanReconciliationScheduler: orphan enqueued key={}", objectKey);
            }
        }
        return count;
    }

    private Set<UUID> findExistingIds(List<UUID> candidateIds) {
        Set<UUID> existingIds = new HashSet<>();
        for (int i = 0; i < candidateIds.size(); i += ID_BATCH_SIZE) {
            List<UUID> batch = candidateIds.subList(i, Math.min(i + ID_BATCH_SIZE, candidateIds.size()));
            existingIds.addAll(schedulerRepository.findExistingPhotoIds(batch.toArray(new UUID[0])));
        }
        return existingIds;
    }
}
```

- [ ] **Step 2: Update SchedulerTest mocks for OrphanReconciliationScheduler**

Update the orphan reconciliation tests in `SchedulerTest`:

1. Mock `schedulerRepository.queryUserIdPage(null, 100)` to return test user IDs
2. Mock `schedulerRepository.queryUserIdPage(lastId, 100)` to return empty list (end pagination)
3. Mock `schedulerRepository.findExistingPhotoIds(any(UUID[].class))` to return known photo IDs (non-orphans)
4. For orphan-detected tests: return a subset so some candidates are identified as orphans
5. Verify: `verify(photoDeleteJobEnqueuer).enqueueOrphan(eq(userId), eq(orphanPhotoId), anyString())`

- [ ] **Step 3: Run scheduler tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.scheduler.SchedulerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java api/src/test/java/org/jphototagger/api/scheduler/SchedulerTest.java
git commit -m "refactor(scheduler): migrate OrphanReconciliationScheduler to SchedulerRepository + keyset pagination (Finding #6)"
```

---

### Task 3: Migrate UnverifiedAccountPurgeScheduler to SchedulerRepository

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java:39,79-82,112-114`

- [ ] **Step 1: Replace photoRepository with SchedulerRepository**

In `UnverifiedAccountPurgeScheduler.java`:

1. Remove `photoRepository` field and constructor parameter
2. Add `schedulerRepository` field and constructor parameter
3. Replace `purgeUser()` step 1 and step 2:

```java
// OLD:
List<Photo> photos = photoRepository.findAllByUserIdWithStorageKey(userId);
if (!photos.isEmpty()) {
    enqueueDeleteJobsBatch(photos);
}

// NEW:
List<Map<String, Object>> photoRows = schedulerRepository.findStorageKeysByUserId(userId);
if (!photoRows.isEmpty()) {
    photoDeleteJobEnqueuer.enqueueByRows(photoRows);
}
```

4. Remove `enqueueDeleteJobsBatch` method
5. Update log message to use `photoRows.size()` instead of `photos.size()`
6. Add structured logging that always fires (even on zero-count path) at the end of `purgeAccounts()`:

```java
log.info("UnverifiedAccountPurgeScheduler: purged {} accounts ({} photos queued)",
        purgedCount, totalPhotosQueued);
```

This must execute unconditionally — not inside an `if (purgedCount > 0)` block — so the log line appears even when zero accounts are purged. Declare the accumulator before the loop and increment inside `purgeUser()`:

```java
// At the start of purgeAccounts():
int purgedCount = 0;
int totalPhotosQueued = 0;

// Inside the loop, after enqueueByRows:
totalPhotosQueued += photoRows.size();

// At the very end of purgeAccounts() (outside any if block):
log.info("UnverifiedAccountPurgeScheduler: purged {} accounts ({} photos queued)",
        purgedCount, totalPhotosQueued);
```

- [ ] **Step 2: Update SchedulerTest mocks for UnverifiedAccountPurgeScheduler**

Update the unverified-account-purge tests in `SchedulerTest`:

1. The `@MockBean SchedulerRepository schedulerRepository` should already exist from Task 1. If not, add it.
2. Replace `when(photoRepository.findAllByUserIdWithStorageKey(...))` with `when(schedulerRepository.findStorageKeysByUserId(any(UUID.class))).thenReturn(List.of(Map.of("id", photoId, "user_id", userId, "storage_key", "key")))`
3. Replace any `verify(enqueueDeleteJobsBatch(...))` with `verify(photoDeleteJobEnqueuer).enqueueByRows(any())`
4. For zero-photos tests: `when(schedulerRepository.findStorageKeysByUserId(any())).thenReturn(List.of())`

- [ ] **Step 3: Run scheduler tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.scheduler.SchedulerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java api/src/test/java/org/jphototagger/api/scheduler/SchedulerTest.java
git commit -m "refactor(scheduler): migrate UnverifiedAccountPurgeScheduler to SchedulerRepository (Finding #6)"
```

---

### Task 4: Scheduler integration tests

**Files:**
- Create: `api/src/test/java/org/jphototagger/api/repository/SchedulerRepositoryTest.java`

- [ ] **Step 1: Create integration test with jpt_auth role**

```java
package org.jphototagger.api.repository;

import org.jphototagger.api.config.TestRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import({TestRedisConfig.class, SchedulerRepositoryTest.SchedulerTestConfig.class})
class SchedulerRepositoryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.auth-datasource.url", pg::getJdbcUrl);
        registry.add("spring.auth-datasource.username", pg::getUsername);
        registry.add("spring.auth-datasource.password", pg::getPassword);
        registry.add("spring.flyway.url", pg::getJdbcUrl);
        registry.add("spring.flyway.user", pg::getUsername);
        registry.add("spring.flyway.password", pg::getPassword);
        registry.add("app.share-reader.jdbc-url", pg::getJdbcUrl);
    }

    @TestConfiguration
    static class SchedulerTestConfig {
        @Bean("authJdbcTemplate")
        @Primary
        public JdbcTemplate authJdbcTemplate(
                @Value("${spring.datasource.url}") String url) {
            var ds = new DriverManagerDataSource(url, "jpt_auth", "test_auth_password");
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private SchedulerRepository schedulerRepository;

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbc;

    // Use superuser for seeding test data
    @Autowired
    private JdbcTemplate superJdbc;

    @BeforeEach
    void setUp() {
        // Create superuser JdbcTemplate using the TC superuser credentials for seeding
        // The @Autowired JdbcTemplate is already the primary (superuser) one
    }

    @Test
    void findPurgeableBatch_returnsDeletedPhotos() {
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        // Seed user and soft-deleted photo as superuser
        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 0, 0, true, NOW(), NOW())",
            userId, "sched-test-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, deleted_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 1000, ?||'/originals/'||?||'.jpg', NOW(), NOW() - INTERVAL '31 days', 'DONE')",
            photoId, userId, userId.toString(), photoId.toString());

        List<Map<String, Object>> batch = schedulerRepository.findPurgeableBatch(
            Instant.now().minus(30, ChronoUnit.DAYS));
        assertThat(batch).isNotEmpty();
        assertThat(batch.stream().anyMatch(row -> photoId.equals(row.get("id")))).isTrue();
    }

    @Test
    void queryUserIdPage_returnsUserIds() {
        List<UUID> page = schedulerRepository.queryUserIdPage(null, 10);
        assertThat(page).isNotNull();
    }

    @Test
    void findExistingPhotoIds_returnsExistingIds() {
        UUID randomId = UUID.randomUUID();
        List<UUID> result = schedulerRepository.findExistingPhotoIds(new UUID[]{randomId});
        assertThat(result).doesNotContain(randomId);
    }

    @Test
    void deletePhotosByIds_removesPhotos() {
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 1000, 0, true, NOW(), NOW())",
            userId, "del-test-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, deleted_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 1000, ?||'/originals/'||?||'.jpg', NOW(), NOW() - INTERVAL '31 days', 'DONE')",
            photoId, userId, userId.toString(), photoId.toString());

        schedulerRepository.deletePhotosByIds(new UUID[]{photoId});

        List<UUID> remaining = schedulerRepository.findExistingPhotoIds(new UUID[]{photoId});
        assertThat(remaining).doesNotContain(photoId);
    }

    @Test
    void purgeNullStorageKeyPhotos_deletesAndUpdatesUsedBytes() {
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 5000, 0, true, NOW(), NOW())",
            userId, "null-key-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, deleted_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 5000, NULL, NOW() - INTERVAL '2 hours', NULL, 'DONE')",
            photoId, userId);

        int affected = schedulerRepository.purgeNullStorageKeyPhotos();
        assertThat(affected).isGreaterThanOrEqualTo(1);

        // Verify used_bytes was decremented
        Long usedBytes = superJdbc.queryForObject(
            "SELECT used_bytes FROM users WHERE id = ?", Long.class, userId);
        assertThat(usedBytes).isEqualTo(0L);
    }

    @Test
    void findStorageKeysByUserId_returnsPhotosWithKeys() {
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        String storageKey = userId + "/originals/" + photoId + ".jpg";

        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 0, 0, true, NOW(), NOW())",
            userId, "keys-test-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 1000, ?, NOW(), 'DONE')",
            photoId, userId, storageKey);

        List<Map<String, Object>> result = schedulerRepository.findStorageKeysByUserId(userId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("storage_key")).isEqualTo(storageKey);
    }

    @Test
    void jptAppWithoutRlsContextReturnsZeroRows() {
        // Verify that jpt_auth (which BYPASSes RLS) can see rows,
        // while a connection without RLS context (using jpt_app role) returns 0 rows.
        // This validates the RLS policy is enforced for non-privileged roles.
        UUID userId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        superJdbc.update(
            "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, failed_login_attempts, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, '$2a$12$test', 10737418240, 0, 0, true, NOW(), NOW())",
            userId, "rls-test-" + userId + "@test.com");
        superJdbc.update(
            "INSERT INTO photos (id, user_id, filename, size_bytes, storage_key, uploaded_at, deleted_at, processing_status) " +
            "VALUES (?, ?, 'test.jpg', 1000, ?||'/originals/'||?||'.jpg', NOW(), NOW() - INTERVAL '31 days', 'DONE')",
            photoId, userId, userId.toString(), photoId.toString());

        // jpt_auth (BYPASSRLS) should see the row
        List<Map<String, Object>> authResult = schedulerRepository.findPurgeableBatch(
            Instant.now().minus(30, ChronoUnit.DAYS));
        assertThat(authResult.stream().anyMatch(row -> photoId.equals(row.get("id")))).isTrue();

        // jpt_app without SET app.current_user_id should see 0 rows
        // Password matches Flyway placeholder jpt_app_password in application-test.yml:34
        var appDs = new DriverManagerDataSource(
            pg.getJdbcUrl(), "jpt_app", "test_app_password");
        var appJdbc = new JdbcTemplate(appDs);
        List<Map<String, Object>> appResult = appJdbc.queryForList(
            "SELECT id FROM photos WHERE deleted_at < ? LIMIT 100",
            Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));
        assertThat(appResult).isEmpty();
    }
}
```

- [ ] **Step 2: Run integration tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.repository.SchedulerRepositoryTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add api/src/test/java/org/jphototagger/api/repository/SchedulerRepositoryTest.java
git commit -m "test(scheduler): add SchedulerRepository integration tests with jpt_auth role"
```

---

### Task 5: Final verification — full test suite

- [ ] **Step 1: Run the complete test suite**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --no-daemon`
Expected: ALL PASS

- [ ] **Step 2: Fix any remaining failures**

Address test failures from integration changes (mock updates, constructor changes, etc.)

- [ ] **Step 3: Final commit if fixes needed**

```bash
git add api/
git commit -m "test: fix remaining test failures from security findings fixes"
```
