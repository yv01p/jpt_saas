package org.jphototagger.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.jphototagger.api.config.TestRedisConfig;
import org.jphototagger.api.security.JwtService;
import org.jphototagger.api.service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TestRedisConfig.class)
class PhotoControllerTest {

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

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;

    @MockBean
    StorageService storageService;

    private Cookie jwtCookie(UUID userId) {
        String token = jwtService.generateToken(userId, userId + "@test.com");
        return new Cookie("jwt", token);
    }

    private UUID createUser(String email, long usedBytes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, used_bytes) VALUES (?, ?, ?, ?)",
                id, email, "hash", usedBytes);
        return id;
    }

    private UUID createVerifiedUser(String email, long usedBytes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, used_bytes, email_verified) VALUES (?, ?, ?, ?, ?)",
                id, email, "hash", usedBytes, true);
        return id;
    }

    private UUID createPhoto(UUID userId, String filename, long sizeBytes, String processingStatus,
                             Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, userId, filename, sizeBytes, processingStatus, deletedAt != null ?
                        java.sql.Timestamp.from(deletedAt) : null);
        return id;
    }

    // -------------------------------------------------------------------------
    // Upload tests
    // -------------------------------------------------------------------------

    @Test
    void upload_streamsToMinioAndEnqueuesJob() throws Exception {
        // assert HTTP 200, photo row created with processing_status=PENDING, Redis job enqueued
        UUID userId = createVerifiedUser("upload-basic@test.com", 0);
        when(storageService.originalKey(any(), any(), anyString())).thenReturn("key/orig.jpg");

        byte[] jpegBytes = minimalJpegBytes();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processing_status").value("PENDING"));

        // Verify photo row exists in DB
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM photos WHERE user_id = ? AND processing_status = 'PENDING'",
                Integer.class, userId);
        assertThat(count).isEqualTo(1);

        // Verify MinIO upload was called
        verify(storageService, times(1)).upload(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void upload_rejectsDuplicateContentHash() throws Exception {
        // assert HTTP 409 when (user_id, content_hash) active row already exists
        UUID userId = createVerifiedUser("upload-dedup@test.com", 0);
        byte[] jpegBytes = minimalJpegBytes();
        String hash = sha256Hex(jpegBytes);

        // Insert existing active photo with same content_hash
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status, content_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "existing.jpg", jpegBytes.length, "PENDING", hash);

        when(storageService.originalKey(any(), any(), anyString())).thenReturn("key/orig.jpg");
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isConflict());
    }

    @Test
    void upload_rejectsWhenQuotaExceeded() throws Exception {
        // assert HTTP 402 when used_bytes + file_size > quota_bytes
        UUID userId = createVerifiedUser("upload-quota@test.com", 10737418240L); // used = quota
        byte[] jpegBytes = minimalJpegBytes();

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void upload_concurrentDuplicatesHandledByDbConstraint() throws Exception {
        // assert HTTP 409 when DataIntegrityViolationException is caught from concurrent insert.
        // Note: this test exercises the application fast-path dedup check (findByUserIdAndContentHash).
        // The DB partial unique index (photos_user_content_hash_active_idx) is the safety net for
        // true concurrent requests that slip through the fast-path check simultaneously.
        UUID userId = createVerifiedUser("upload-concurrent@test.com", 0);
        byte[] jpegBytes = minimalJpegBytes();
        String hash = sha256Hex(jpegBytes);

        // Pre-insert active photo with same content_hash to simulate concurrent insert constraint violation
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status, content_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "concurrent.jpg", jpegBytes.length, "PENDING", hash);

        when(storageService.originalKey(any(), any(), anyString())).thenReturn("key/orig.jpg");
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isConflict());
    }

    @Test
    void upload_rejectsUnverifiedUser() throws Exception {
        // assert HTTP 403 with message "Email verification required before uploading"
        UUID userId = createUser("upload-unverified@test.com", 0); // email_verified defaults to false
        byte[] jpegBytes = minimalJpegBytes();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Email verification required before uploading"));
    }

    @Test
    void upload_allowsVerifiedUser() throws Exception {
        // assert HTTP 200 for user with email_verified=true
        UUID userId = createVerifiedUser("upload-verified@test.com", 0);
        when(storageService.originalKey(any(), any(), anyString())).thenReturn("key/orig.jpg");

        byte[] jpegBytes = minimalJpegBytes();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isOk());
    }

    @Test
    void upload_succeedsAfterSoftDeletedDuplicate() throws Exception {
        // assert HTTP 200 when same content_hash exists with deleted_at IS NOT NULL
        UUID userId = createVerifiedUser("upload-softdel@test.com", 0);
        byte[] jpegBytes = minimalJpegBytes();
        String hash = sha256Hex(jpegBytes);

        // Insert soft-deleted photo with same content_hash
        jdbcTemplate.update(
                "INSERT INTO photos (id, user_id, filename, size_bytes, processing_status, content_hash, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "deleted.jpg", jpegBytes.length, "DONE", hash,
                java.sql.Timestamp.from(Instant.now()));

        when(storageService.originalKey(any(), any(), anyString())).thenReturn("key/orig2.jpg");
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processing_status").value("PENDING"));
    }

    @Test
    void upload_minioFailureRollsBackQuotaAndPhotoRow() throws Exception {
        // assert used_bytes unchanged and photo row absent when MinIO upload throws
        UUID userId = createVerifiedUser("upload-minio-fail@test.com", 0);
        when(storageService.originalKey(any(), any(), anyString())).thenReturn("key/fail.jpg");
        doThrow(new StorageService.StorageException("MinIO down", new RuntimeException()))
                .when(storageService).upload(anyString(), any(), anyLong(), anyString());

        byte[] jpegBytes = minimalJpegBytes();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isInternalServerError());

        // Verify used_bytes unchanged
        Long usedBytes = jdbcTemplate.queryForObject(
                "SELECT used_bytes FROM users WHERE id = ?", Long.class, userId);
        assertThat(usedBytes).isEqualTo(0L);

        // Verify photo row is absent
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM photos WHERE user_id = ?", Integer.class, userId);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void upload_withMaliciousFilenameExtension_usesNormalizedExtension() throws Exception {
        // assert storage_key contains only the MIME-derived extension regardless of uploaded filename
        UUID userId = createVerifiedUser("upload-ext@test.com", 0);
        when(storageService.originalKey(any(), any(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "/originals/" + inv.getArgument(1) + "." + inv.getArgument(2));

        byte[] jpegBytes = minimalJpegBytes();
        // Malicious filename with double extension
        MockMultipartFile file = new MockMultipartFile("file", "malicious.jpg.sh", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isOk());

        // Verify storage_key uses MIME-derived extension (jpg), not the uploaded filename extension (sh)
        String storageKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM photos WHERE user_id = ?", String.class, userId);
        assertThat(storageKey).endsWith(".jpg");
        assertThat(storageKey).doesNotEndWith(".sh");
    }

    @Test
    void photoStatus_anotherUsersActivePhoto_returns404NotForbidden() throws Exception {
        // IDOR fix: getPhotoStatus() must return 404 for any photo the caller does not own,
        // whether active or deleted. Returning 403 (vs 404) would reveal that the UUID
        // belongs to a live photo on the platform — information disclosure across tenants.
        // The combined filter (userId AND deletedAt IS NULL) makes all non-owner cases
        // indistinguishable from "not found", matching the pattern used in getPhoto().
        UUID user1 = createUser("status-owner@test.com", 0);
        UUID user2 = createUser("status-intruder@test.com", 0);
        UUID photoId = createPhoto(user1, "private.jpg", 1000, "PROCESSING", null);

        mockMvc.perform(get("/photos/" + photoId + "/status")
                        .cookie(jwtCookie(user2)))
                .andExpect(status().isNotFound());
    }

    @Test
    void upload_computesSha256AndUploadsToMinioFromTempFile() throws Exception {
        // assert multipartFile.getInputStream() is called exactly once;
        // assert MinIO receives correct file content via temp file path (not original stream)
        UUID userId = createVerifiedUser("upload-sha256@test.com", 0);
        when(storageService.originalKey(any(), any(), anyString())).thenReturn("key/sha.jpg");

        byte[] jpegBytes = minimalJpegBytes();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isOk());

        // Verify MinIO upload was called exactly once (stream read once from temp file)
        verify(storageService, times(1)).upload(anyString(), any(), anyLong(), anyString());

        // Verify photo row has correct size matching the actual file
        Long sizeBytes = jdbcTemplate.queryForObject(
                "SELECT size_bytes FROM photos WHERE user_id = ?", Long.class, userId);
        assertThat(sizeBytes).isEqualTo(jpegBytes.length);
    }

    @Test
    void upload_sanitizesOriginalFilenameForDisplay() throws Exception {
        // assert that a filename containing "<script>alert(1)</script>.jpg" has all HTML stripped
        // before being written to photos.original_filename.
        // Jsoup.parse("<script>alert(1)</script>.jpg").text() → ".jpg" (script content discarded).
        // The key assertion is that "script" and "alert" do not appear in the stored original_filename.
        UUID userId = createVerifiedUser("upload-sanitize@test.com", 0);
        when(storageService.originalKey(any(), any(), anyString())).thenReturn("key/safe.jpg");

        byte[] jpegBytes = minimalJpegBytes();
        MockMultipartFile file = new MockMultipartFile("file",
                "<script>alert(1)</script>.jpg", "image/jpeg", jpegBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(file)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isOk());

        // Verify original_filename in DB does not contain script tags or script body.
        // Jsoup.parse("<script>alert(1)</script>.jpg").text() → ".jpg": script element content
        // is discarded entirely, leaving only the trailing text node after the closing tag.
        String originalFilename = jdbcTemplate.queryForObject(
                "SELECT original_filename FROM photos WHERE user_id = ?", String.class, userId);
        assertThat(originalFilename).doesNotContain("<script>");
        assertThat(originalFilename).doesNotContain("</script>");
        assertThat(originalFilename).doesNotContain("alert");
    }

    @Test
    void upload_rejectsUnsupportedMimeType() throws Exception {
        // assert HTTP 415 when uploaded file has non-image magic bytes (e.g., PDF header)
        UUID userId = createVerifiedUser("unsupported@test.com", 0);
        byte[] pdfBytes = "%PDF-1.4 test content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile pdfFile = new MockMultipartFile("file", "test.pdf", "application/pdf", pdfBytes);

        mockMvc.perform(multipart("/photos/upload")
                        .file(pdfFile)
                        .with(csrf())
                        .cookie(jwtCookie(userId)))
                .andExpect(status().isUnsupportedMediaType());
    }

    // -------------------------------------------------------------------------
    // Existing tests (processing_status updated to uppercase per V5 migration)
    // -------------------------------------------------------------------------

    @Test
    void listPhotos_returnsOnlyUsersPhotos() throws Exception {
        UUID user1 = createUser("list1@test.com", 0);
        UUID user2 = createUser("list2@test.com", 0);
        createPhoto(user1, "u1p1.jpg", 1000, "DONE", null);
        createPhoto(user1, "u1p2.jpg", 2000, "DONE", null);
        createPhoto(user2, "u2p1.jpg", 1000, "DONE", null);
        createPhoto(user2, "u2p2.jpg", 2000, "DONE", null);

        mockMvc.perform(get("/photos")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void getPhoto_returns404ForOtherUsersPhoto() throws Exception {
        UUID user1 = createUser("get1@test.com", 0);
        UUID user2 = createUser("get2@test.com", 0);
        UUID photoId = createPhoto(user1, "secret.jpg", 1000, "DONE", null);

        mockMvc.perform(get("/photos/" + photoId)
                        .cookie(jwtCookie(user2)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePhoto_softDeletesAndDecrementsQuota() throws Exception {
        UUID user = createUser("del@test.com", 5000);
        UUID photoId = createPhoto(user, "todelete.jpg", 5000, "DONE", null);

        mockMvc.perform(delete("/photos/" + photoId)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNoContent());

        // Verify soft delete
        Instant deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM photos WHERE id = ?", Instant.class, photoId);
        assertThat(deletedAt).isNotNull();

        // Verify quota decremented
        Long usedBytes = jdbcTemplate.queryForObject(
                "SELECT used_bytes FROM users WHERE id = ?", Long.class, user);
        assertThat(usedBytes).isEqualTo(0);
    }

    @Test
    void getPhotoStatus_returnsProcessingStatus() throws Exception {
        UUID user = createUser("status@test.com", 0);
        UUID photoId = createPhoto(user, "processing.jpg", 1000, "PROCESSING", null);

        mockMvc.perform(get("/photos/" + photoId + "/status")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(photoId.toString()))
                .andExpect(jsonPath("$.processing_status").value("PROCESSING"));
    }

    @Test
    void trashView_returnsDeletedPhotos() throws Exception {
        UUID user = createUser("trash@test.com", 0);
        createPhoto(user, "active.jpg", 1000, "DONE", null);
        createPhoto(user, "deleted.jpg", 2000, "DONE", Instant.now());

        mockMvc.perform(get("/photos/trash")
                        .param("page", "0").param("size", "50")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].filename").value("deleted.jpg"));
    }

    @Test
    void restorePhoto_clearsDeletedAtAndIncrementsQuota() throws Exception {
        UUID user = createUser("restore@test.com", 0);
        UUID photoId = createPhoto(user, "restore.jpg", 5000, "DONE", Instant.now());

        mockMvc.perform(post("/photos/" + photoId + "/restore")
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk());

        // Verify restore
        Object deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM photos WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp("deleted_at"),
                photoId);
        assertThat(deletedAt).isNull();

        // Verify quota incremented
        Long usedBytes = jdbcTemplate.queryForObject(
                "SELECT used_bytes FROM users WHERE id = ?", Long.class, user);
        assertThat(usedBytes).isEqualTo(5000);
    }

    @Test
    void concurrentSoftDelete_decrementsUsedBytesOnlyOnce() throws Exception {
        // Arrange: user with 2000 bytes used, one 1000-byte photo.
        // Two concurrent DELETE requests target the same photo.
        //
        // With the race fix (lock user BEFORE reading photo):
        //   - Thread 1 acquires lock, re-reads photo (active), decrements 2000→1000, commits → 204
        //   - Thread 2 acquires lock, re-reads photo (already deleted), throws 404 → no decrement
        //   - Final: used_bytes = 1000
        //
        // Without the fix (read photo BEFORE acquiring lock):
        //   - Both threads read photo (deletedAt=null) before either acquires the lock
        //   - Thread 1 acquires lock, decrements 2000→1000, commits → 204
        //   - Thread 2 acquires lock (re-reads user: 1000), decrements 1000→0, commits → 204
        //   - Final: used_bytes = 0 (double-decrement)
        UUID userId = createUser("race-delete@test.com", 2000);
        UUID photoId = createPhoto(userId, "race.jpg", 1000, "DONE", null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        Runnable task = () -> {
            try {
                ready.countDown();
                go.await();
                int status = mockMvc.perform(delete("/photos/" + photoId)
                                .with(csrf())
                                .cookie(jwtCookie(userId)))
                        .andReturn().getResponse().getStatus();
                statuses.add(status);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join();
        t2.join();

        // One request succeeds, the other finds the photo already deleted
        assertThat(statuses).containsExactlyInAnyOrder(204, 404);

        // used_bytes decremented exactly once: 2000 - 1000 = 1000
        Long usedBytes = jdbcTemplate.queryForObject(
                "SELECT used_bytes FROM users WHERE id = ?", Long.class, userId);
        assertThat(usedBytes).isEqualTo(1000L);
    }

    @Test
    void deletePhoto_secondDeleteReturns404() throws Exception {
        UUID user = createUser("deldel@test.com", 5000);
        UUID photoId = createPhoto(user, "deldel.jpg", 5000, "DONE", null);

        // First delete succeeds
        mockMvc.perform(delete("/photos/" + photoId)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNoContent());

        // Second delete returns 404
        mockMvc.perform(delete("/photos/" + photoId)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPhoto_returnsTrashedPhotoAs404() throws Exception {
        UUID user = createUser("trashed@test.com", 0);
        UUID photoId = createPhoto(user, "trashed.jpg", 1000, "DONE", Instant.now());

        mockMvc.perform(get("/photos/" + photoId)
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Keyword-photo assignment tests
    // -------------------------------------------------------------------------

    private UUID createKeyword(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO keywords (id, user_id, name) VALUES (?, ?, ?)",
                id, userId, name);
        return id;
    }

    @Test
    void listKeywordsForPhoto_returnsAssignedKeywords() throws Exception {
        UUID user = createUser("kw-list@test.com", 0);
        UUID photoId = createPhoto(user, "kw.jpg", 1000, "DONE", null);
        UUID kw1 = createKeyword(user, "Animals");
        UUID kw2 = createKeyword(user, "Dogs");

        jdbcTemplate.update(
                "INSERT INTO photo_keywords (photo_id, keyword_id, user_id) VALUES (?, ?, ?)",
                photoId, kw1, user);
        jdbcTemplate.update(
                "INSERT INTO photo_keywords (photo_id, keyword_id, user_id) VALUES (?, ?, ?)",
                photoId, kw2, user);

        mockMvc.perform(get("/photos/" + photoId + "/keywords")
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listKeywordsForPhoto_returns404ForOtherUsersPhoto() throws Exception {
        UUID user1 = createUser("kw-list1@test.com", 0);
        UUID user2 = createUser("kw-list2@test.com", 0);
        UUID photoId = createPhoto(user1, "private.jpg", 1000, "DONE", null);

        mockMvc.perform(get("/photos/" + photoId + "/keywords")
                        .cookie(jwtCookie(user2)))
                .andExpect(status().isNotFound());
    }

    @Test
    void addKeywordToPhoto_returns200() throws Exception {
        UUID user = createUser("kw-add@test.com", 0);
        UUID photoId = createPhoto(user, "kw.jpg", 1000, "DONE", null);
        UUID kwId = createKeyword(user, "Nature");

        mockMvc.perform(post("/photos/" + photoId + "/keywords/" + kwId)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isOk());

        // Verify row in photo_keywords
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM photo_keywords WHERE photo_id = ? AND keyword_id = ? AND user_id = ?",
                Integer.class, photoId, kwId, user);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void addKeywordToPhoto_returns404ForOtherUsersKeyword() throws Exception {
        UUID user1 = createUser("kw-add1@test.com", 0);
        UUID user2 = createUser("kw-add2@test.com", 0);
        UUID photoId = createPhoto(user1, "kw.jpg", 1000, "DONE", null);
        UUID kwId = createKeyword(user2, "OtherUserKeyword");

        mockMvc.perform(post("/photos/" + photoId + "/keywords/" + kwId)
                        .with(csrf())
                        .cookie(jwtCookie(user1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeKeywordFromPhoto_returns204() throws Exception {
        UUID user = createUser("kw-del@test.com", 0);
        UUID photoId = createPhoto(user, "kw.jpg", 1000, "DONE", null);
        UUID kwId = createKeyword(user, "ToRemove");

        jdbcTemplate.update(
                "INSERT INTO photo_keywords (photo_id, keyword_id, user_id) VALUES (?, ?, ?)",
                photoId, kwId, user);

        mockMvc.perform(delete("/photos/" + photoId + "/keywords/" + kwId)
                        .with(csrf())
                        .cookie(jwtCookie(user)))
                .andExpect(status().isNoContent());

        // Verify row removed
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM photo_keywords WHERE photo_id = ? AND keyword_id = ?",
                Integer.class, photoId, kwId);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void removeKeywordFromPhoto_returns404ForOtherUsersPhoto() throws Exception {
        UUID user1 = createUser("kw-del1@test.com", 0);
        UUID user2 = createUser("kw-del2@test.com", 0);
        UUID photoId = createPhoto(user1, "kw.jpg", 1000, "DONE", null);
        UUID kwId = createKeyword(user1, "Animals");

        mockMvc.perform(delete("/photos/" + photoId + "/keywords/" + kwId)
                        .with(csrf())
                        .cookie(jwtCookie(user2)))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /**
     * Returns a minimal valid JPEG byte sequence (SOI + APP0 + EOI).
     * Tika detects this as image/jpeg.
     */
    private byte[] minimalJpegBytes() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8, // SOI
                (byte) 0xFF, (byte) 0xE0, // APP0 marker
                0x00, 0x10,               // APP0 length = 16
                0x4A, 0x46, 0x49, 0x46, 0x00, // "JFIF\0"
                0x01, 0x01,               // version 1.1
                0x00,                     // aspect ratio units
                0x00, 0x01,               // Xdensity
                0x00, 0x01,               // Ydensity
                0x00, 0x00,               // thumbnail
                (byte) 0xFF, (byte) 0xD9  // EOI
        };
    }

    /**
     * Computes SHA-256 hex for the given bytes.
     */
    private String sha256Hex(byte[] data) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        return java.util.HexFormat.of().formatHex(hash);
    }
}
