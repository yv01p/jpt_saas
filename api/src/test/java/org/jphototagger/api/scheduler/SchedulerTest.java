package org.jphototagger.api.scheduler;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jphototagger.api.config.TestRedisConfig;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.entity.User;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class SchedulerTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.flyway.url", pg::getJdbcUrl);
        registry.add("spring.flyway.user", pg::getUsername);
        registry.add("spring.flyway.password", pg::getPassword);
        registry.add("spring.auth-datasource.url", pg::getJdbcUrl);
        registry.add("spring.auth-datasource.username", pg::getUsername);
        registry.add("spring.auth-datasource.password", pg::getPassword);
    }

    @Autowired
    private TrashPurgeScheduler trashPurgeScheduler;

    @Autowired
    private OrphanReconciliationScheduler orphanReconciliationScheduler;

    @Autowired
    private UnverifiedAccountPurgeScheduler unverifiedAccountPurgeScheduler;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockBean(name = "minioInternalClient")
    private MinioClient minioInternalClient;

    @MockBean(name = "minioPublicClient")
    private MinioClient minioPublicClient;

    @BeforeEach
    void cleanDb() {
        photoRepository.deleteAll();
        userRepository.deleteAll();
        // Clear the delete-jobs stream
        redisTemplate.delete("delete-jobs");
        // Clear ShedLock Redis keys so each test can acquire the lock independently.
        // RedisLockProvider key format: {keyPrefix}:{environment}:{lockName}
        // Default keyPrefix="job-lock", default environment="default".
        redisTemplate.delete("job-lock:default:trashPurge");
        redisTemplate.delete("job-lock:default:orphanReconciliation");
        redisTemplate.delete("job-lock:default:unverifiedAccountPurge");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private User createVerifiedUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$12$hashedpassword");
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private User createUnverifiedUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$12$hashedpassword");
        user.setEmailVerified(false);
        return userRepository.save(user);
    }

    private Photo createDeletedPhoto(User user, int daysAgo) {
        Photo photo = new Photo();
        photo.setUserId(user.getId());
        photo.setFilename("photo.jpg");
        photo.setStorageKey(user.getId() + "/originals/" + UUID.randomUUID() + ".jpg");
        photo.setSizeBytes(1024L);
        photo.setDeletedAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        return photoRepository.save(photo);
    }

    private Photo createActivePhoto(User user) {
        Photo photo = new Photo();
        photo.setUserId(user.getId());
        photo.setFilename("active.jpg");
        photo.setStorageKey(user.getId() + "/originals/" + UUID.randomUUID() + ".jpg");
        photo.setSizeBytes(2048L);
        return photoRepository.save(photo);
    }

    /** Read all messages from the delete-jobs Redis stream. */
    private List<MapRecord<String, Object, Object>> readDeleteJobs() {
        List<MapRecord<String, Object, Object>> msgs =
                redisTemplate.opsForStream().range("delete-jobs", Range.unbounded());
        return msgs == null ? List.of() : msgs;
    }

    private void setCreatedAtDaysAgo(UUID userId, int days) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             var ps = conn.prepareStatement(
                     "UPDATE users SET created_at = NOW() - (? * INTERVAL '1 day') WHERE id = ?")) {
            ps.setInt(1, days);
            ps.setObject(2, userId);
            ps.execute();
        }
    }

    // -------------------------------------------------------------------------
    // TrashPurgeScheduler tests
    // -------------------------------------------------------------------------

    @Test
    void trashPurge_deletesPhotosOlderThanRetentionWindow() {
        User user = createVerifiedUser("trash-purge-delete@example.com");

        // deleted 31 days ago — beyond 30-day retention
        Photo old = createDeletedPhoto(user, 31);
        // deleted 1 day ago — within retention window
        Photo recent = createDeletedPhoto(user, 1);

        trashPurgeScheduler.purgeTrash();

        assertThat(photoRepository.findById(old.getId())).isEmpty();
        assertThat(photoRepository.findById(recent.getId())).isPresent();
    }

    @Test
    void trashPurge_enqueuesMinioDeleteJob() {
        User user = createVerifiedUser("trash-purge-enqueue@example.com");

        Photo old = createDeletedPhoto(user, 31);
        UUID photoId = old.getId();

        trashPurgeScheduler.purgeTrash();

        List<MapRecord<String, Object, Object>> messages = readDeleteJobs();
        assertThat(messages).isNotEmpty();

        boolean found = messages.stream().anyMatch(msg -> {
            String msgPhotoId = (String) msg.getValue().get("photo_id");
            String msgOrigKey  = (String) msg.getValue().get("original_key");
            String msgSmKey    = (String) msg.getValue().get("thumbnail_sm");
            String msgMdKey    = (String) msg.getValue().get("thumbnail_md");
            return photoId.toString().equals(msgPhotoId)
                    && msgOrigKey  != null
                    && msgSmKey    != null && msgSmKey.contains("_sm.jpg")
                    && msgMdKey    != null && msgMdKey.contains("_md.jpg");
        });
        assertThat(found).as("delete-job with all four fields must be enqueued").isTrue();
    }

    @Test
    void trashPurge_doesNotRunConcurrentlyAcrossInstances() throws Exception {
        // Verify @SchedulerLock annotation is present on purgeTrash()
        Method method = TrashPurgeScheduler.class.getMethod("purgeTrash");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("@SchedulerLock must be on purgeTrash()").isNotNull();
        assertThat(lock.name()).isEqualTo("trashPurge");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT10M");
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT1M");
    }

    // -------------------------------------------------------------------------
    // OrphanReconciliationScheduler tests
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void orphanReconciliation_detectsOrphanedMinioObjects() throws Exception {
        User user = createVerifiedUser("orphan-detect@example.com");
        UUID userId = user.getId();
        UUID orphanPhotoId = UUID.randomUUID(); // no DB row for this photo_id

        String orphanKey = userId + "/originals/" + orphanPhotoId + ".jpg";

        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(orphanKey);
        when(item.isDir()).thenReturn(false);
        when(item.lastModified()).thenReturn(ZonedDateTime.now().minusHours(3));

        Result<Item> result = mock(Result.class);
        when(result.get()).thenReturn(item);

        when(minioInternalClient.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(result));

        orphanReconciliationScheduler.reconcileOrphans();

        List<MapRecord<String, Object, Object>> messages = readDeleteJobs();
        assertThat(messages).isNotEmpty();

        boolean found = messages.stream().anyMatch(msg -> {
            String msgPhotoId  = (String) msg.getValue().get("photo_id");
            String msgOrigKey  = (String) msg.getValue().get("original_key");
            return orphanPhotoId.toString().equals(msgPhotoId)
                    && orphanKey.equals(msgOrigKey);
        });
        assertThat(found).as("orphaned object must be enqueued for deletion").isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void orphanReconciliation_doesNotDeleteObjectWherePhotoRowExists() throws Exception {
        User user = createVerifiedUser("orphan-skip@example.com");
        UUID userId = user.getId();

        Photo activePhoto = createActivePhoto(user);
        UUID photoId = activePhoto.getId();
        String objectKey = userId + "/originals/" + photoId + ".jpg";

        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(objectKey);
        when(item.isDir()).thenReturn(false);
        when(item.lastModified()).thenReturn(ZonedDateTime.now().minusHours(3));

        Result<Item> result = mock(Result.class);
        when(result.get()).thenReturn(item);

        when(minioInternalClient.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(result));

        orphanReconciliationScheduler.reconcileOrphans();

        List<MapRecord<String, Object, Object>> messages = readDeleteJobs();
        boolean found = messages.stream().anyMatch(msg ->
                photoId.toString().equals(msg.getValue().get("photo_id")));
        assertThat(found).as("existing photo must not be enqueued for deletion").isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void orphanReconciliation_skipsRecentlyCreatedObjects() throws Exception {
        // SA1-F4: Objects younger than the recency threshold must be skipped
        // to avoid racing with in-progress uploads between Tx 1 and Tx 2.
        User user = createVerifiedUser("orphan-recent@example.com");
        UUID userId = user.getId();
        UUID recentPhotoId = UUID.randomUUID(); // no DB row — but object is too new to classify

        String recentKey = userId + "/originals/" + recentPhotoId + ".jpg";

        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(recentKey);
        when(item.isDir()).thenReturn(false);
        when(item.lastModified()).thenReturn(ZonedDateTime.now().minusMinutes(30));

        Result<Item> result = mock(Result.class);
        when(result.get()).thenReturn(item);

        when(minioInternalClient.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(result));

        orphanReconciliationScheduler.reconcileOrphans();

        List<MapRecord<String, Object, Object>> messages = readDeleteJobs();
        boolean found = messages.stream().anyMatch(msg ->
                recentPhotoId.toString().equals(msg.getValue().get("photo_id")));
        assertThat(found).as("recently-created object must not be treated as orphan").isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void orphanReconciliation_continuesAfterItemEnumerationError() throws Exception {
        User user = createVerifiedUser("orphan-error@example.com");
        UUID goodPhotoId = UUID.randomUUID();
        String goodKey = user.getId() + "/originals/" + goodPhotoId + ".jpg";

        Result<Item> badResult = mock(Result.class);
        when(badResult.get()).thenThrow(new RuntimeException("MinIO transient error"));

        Item goodItem = mock(Item.class);
        when(goodItem.objectName()).thenReturn(goodKey);
        when(goodItem.isDir()).thenReturn(false);
        when(goodItem.lastModified()).thenReturn(ZonedDateTime.now().minusHours(3));
        Result<Item> goodResult = mock(Result.class);
        when(goodResult.get()).thenReturn(goodItem);

        when(minioInternalClient.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(badResult, goodResult));

        orphanReconciliationScheduler.reconcileOrphans();

        boolean found = readDeleteJobs().stream().anyMatch(msg ->
                goodPhotoId.toString().equals(msg.getValue().get("photo_id")));
        assertThat(found).as("good orphan must be processed despite earlier error").isTrue();
    }

    // -------------------------------------------------------------------------
    // UnverifiedAccountPurgeScheduler tests
    // -------------------------------------------------------------------------

    @Test
    void unverifiedPurge_deletesAccountsOlderThan7Days() throws Exception {
        User old = createUnverifiedUser("unverified-old@example.com");
        setCreatedAtDaysAgo(old.getId(), 8);

        User recent = createUnverifiedUser("unverified-recent@example.com");

        unverifiedAccountPurgeScheduler.purgeUnverifiedAccounts();

        assertThat(userRepository.findById(old.getId())).isEmpty();
        assertThat(userRepository.findById(recent.getId())).isPresent();
    }

    @Test
    void unverifiedPurge_keepsVerifiedAccounts() throws Exception {
        User verified = createVerifiedUser("verified-keep@example.com");
        setCreatedAtDaysAgo(verified.getId(), 8);

        unverifiedAccountPurgeScheduler.purgeUnverifiedAccounts();

        assertThat(userRepository.findById(verified.getId())).isPresent();
    }

    @Test
    void unverifiedPurge_enqueuesMinioDeletesBeforeDeletingDbRecords() throws Exception {
        User old = createUnverifiedUser("unverified-order@example.com");

        Photo photo = new Photo();
        photo.setUserId(old.getId());
        photo.setFilename("photo.jpg");
        photo.setStorageKey(old.getId() + "/originals/" + UUID.randomUUID() + ".jpg");
        photo.setSizeBytes(512L);
        photoRepository.save(photo);

        setCreatedAtDaysAgo(old.getId(), 8);

        unverifiedAccountPurgeScheduler.purgeUnverifiedAccounts();

        // Both user and photo must be deleted
        assertThat(userRepository.findById(old.getId())).isEmpty();
        assertThat(photoRepository.findById(photo.getId())).isEmpty();

        // Delete-jobs must have been enqueued (before DB delete)
        List<MapRecord<String, Object, Object>> messages = readDeleteJobs();
        assertThat(messages).isNotEmpty();

        boolean jobFound = messages.stream().anyMatch(msg ->
                photo.getStorageKey().equals(msg.getValue().get("original_key")));
        assertThat(jobFound).as("delete-job for photo must be present in Redis").isTrue();
    }
}
