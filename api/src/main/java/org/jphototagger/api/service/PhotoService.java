package org.jphototagger.api.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.apache.tika.Tika;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.entity.User;
import org.jphototagger.api.enums.ProcessingStatus;
import org.jphototagger.api.exception.EmailVerificationRequiredException;
import org.jphototagger.api.exception.QuotaExceededException;
import org.jphototagger.api.exception.UnsupportedMediaTypeException;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.api.repository.UserRepository;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private static final Map<String, String> MIME_TO_EXT = Map.ofEntries(
            Map.entry("image/jpeg",         "jpg"),
            Map.entry("image/png",          "png"),
            Map.entry("image/tiff",         "tiff"),
            Map.entry("image/x-canon-cr2",  "cr2"),
            Map.entry("image/x-nikon-nef",  "nef"),
            Map.entry("image/x-sony-arw",   "arw"),
            Map.entry("image/x-adobe-dng",  "dng"),
            Map.entry("image/heic",         "heic"),
            Map.entry("image/webp",         "webp")
    );

    private final PhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;
    private final Tika tika;

    // Self-injection for @Transactional methods (avoids Spring AOP self-invocation limitation)
    @Autowired @Lazy
    private PhotoService self;

    public PhotoService(PhotoRepository photoRepository, UserRepository userRepository,
                        EntityManager entityManager, StorageService storageService,
                        StringRedisTemplate redisTemplate) {
        this.photoRepository = photoRepository;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.storageService = storageService;
        this.redisTemplate = redisTemplate;
        this.tika = new Tika();
    }

    /**
     * Upload a photo for the given user.
     *
     * <p>Transaction order:
     * <ol>
     *   <li>No tx: buffer request body to tmpfs temp file; compute SHA-256 simultaneously.</li>
     *   <li>No tx: Run Tika on temp file to detect MIME; map to extension; 415 if unsupported.</li>
     *   <li>No tx: Fast-path dedup check by (user_id, content_hash) WHERE deleted_at IS NULL.</li>
     *   <li>Tx 1: SELECT FOR UPDATE user; validate quota; INSERT photo row; increment used_bytes; COMMIT.</li>
     *   <li>No tx: Upload to MinIO from temp file input stream.</li>
     *   <li>On MinIO failure: compensating Tx — delete photo row, decrement used_bytes; return 500.</li>
     *   <li>Tx 2: UPDATE photos SET storage_key, processing_status=PENDING WHERE id = ?.</li>
     *   <li>No tx: XADD photo-jobs to Redis Streams.</li>
     * </ol>
     */
    public Photo uploadPhoto(UUID userId, MultipartFile file) {
        // Step 0 (no tx): Buffer to temp file and compute SHA-256 in a single pass
        Path uploadTemp;
        try {
            uploadTemp = Files.createTempFile(Path.of("/tmp"), "upload-", ".tmp");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp file", e);
        }

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(file.getInputStream(), sha256)) {
                Files.copy(dis, uploadTemp, StandardCopyOption.REPLACE_EXISTING);
            }
            String contentHash = HexFormat.of().formatHex(sha256.digest());
            long fileSize = Files.size(uploadTemp);

            return doUpload(userId, file, uploadTemp, contentHash, fileSize);
        } catch (Exception e) {
            // Re-throw known exception types without wrapping
            if (e instanceof EmailVerificationRequiredException
                    || e instanceof QuotaExceededException
                    || e instanceof DataIntegrityViolationException
                    || e instanceof UnsupportedMediaTypeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Failed to process upload", e);
        } finally {
            silentlyDelete(uploadTemp);
        }
    }

    private Photo doUpload(UUID userId, MultipartFile file, Path uploadTemp,
                           String contentHash, long fileSize) {
        // Step 1 (no tx): Detect MIME type using Tika; map to extension
        String mimeType;
        try {
            mimeType = tika.detect(uploadTemp.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to detect MIME type", e);
        }
        String ext = MIME_TO_EXT.get(mimeType);
        if (ext == null) {
            throw new UnsupportedMediaTypeException("Unsupported media type: " + mimeType);
        }

        // Sanitize original filename (SA3-F1)
        String rawFilename = file.getOriginalFilename();
        String safeOriginalFilename = rawFilename != null ? Jsoup.parse(rawFilename).text() : null;
        // Use sanitized filename as the display filename (fallback to a generic name)
        String displayFilename = (safeOriginalFilename != null && !safeOriginalFilename.isBlank())
                ? safeOriginalFilename : "upload." + ext;

        // Step 2 (no tx): Fast-path dedup — check if active photo with same hash exists
        photoRepository.findByUserIdAndContentHashAndDeletedAtIsNull(userId, contentHash)
                .ifPresent(existing -> {
                    throw new DataIntegrityViolationException("Duplicate content hash for user");
                });

        // Step 3 (Tx 1): Lock user row, validate quota, insert photo, increment used_bytes
        Photo savedPhoto = self.insertPhotoWithQuotaCheck(userId, contentHash, fileSize,
                displayFilename, safeOriginalFilename);

        // Step 4 (no tx): Upload to MinIO
        String objectKey = storageService.originalKey(userId, savedPhoto.getId(), ext);
        try (InputStream in = Files.newInputStream(uploadTemp)) {
            storageService.upload(objectKey, in, fileSize, mimeType);
        } catch (Exception e) {
            // Step 5 (on MinIO failure): Compensating Tx
            try {
                self.compensate(userId, savedPhoto.getId(), fileSize);
            } catch (Exception compEx) {
                log.error("CRITICAL: Compensating transaction failed for photo {} user {}",
                        savedPhoto.getId(), userId, compEx);
            }
            throw new RuntimeException("Failed to upload to storage", e);
        }

        // Step 6 (Tx 2): Update storage_key on the photo row
        Photo finalPhoto = self.updateStorageKey(savedPhoto.getId(), objectKey);

        // Step 7 (no tx): Enqueue Redis job
        try {
            Map<String, String> message = Map.of("photo_id", finalPhoto.getId().toString());
            redisTemplate.opsForStream().add("photo-jobs", message);
        } catch (Exception e) {
            log.error("CRITICAL: Redis XADD failed for photo_id={}. Photo is uploaded and quota " +
                      "charged but worker job was not enqueued. Manual intervention required.",
                      finalPhoto.getId(), e);
            // Do NOT rethrow — the photo was successfully saved; return it to the client.
            // The startup recovery scan can re-enqueue by scanning PENDING photos with storage_key set.
        }

        return finalPhoto;
    }

    @Transactional
    public Photo insertPhotoWithQuotaCheck(UUID userId, String contentHash, long fileSize,
                                           String displayFilename, String originalFilename) {
        // SELECT FOR UPDATE user row
        User user = entityManager.createQuery(
                "SELECT u FROM User u WHERE u.id = :userId", User.class)
                .setParameter("userId", userId)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();

        // Email verification gate
        if (!user.isEmailVerified()) {
            throw new EmailVerificationRequiredException("Email verification required before uploading");
        }

        // Quota check
        if (user.getUsedBytes() + fileSize > user.getQuotaBytes()) {
            throw new QuotaExceededException("Storage quota exceeded");
        }

        // Insert photo row (no storage_key yet)
        Photo photo = new Photo();
        photo.setUserId(userId);
        photo.setFilename(displayFilename);
        photo.setOriginalFilename(originalFilename);
        photo.setContentHash(contentHash);
        photo.setSizeBytes(fileSize);
        photo.setProcessingStatus(ProcessingStatus.PENDING);

        Photo saved = photoRepository.save(photo);

        // Increment used_bytes
        user.setUsedBytes(user.getUsedBytes() + fileSize);
        userRepository.save(user);

        return saved;
    }

    @Transactional
    public Photo updateStorageKey(UUID photoId, String objectKey) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found: " + photoId));
        photo.setStorageKey(objectKey);
        photo.setProcessingStatus(ProcessingStatus.PENDING);
        return photoRepository.save(photo);
    }

    @Transactional
    public void compensate(UUID userId, UUID photoId, long fileSize) {
        photoRepository.deleteById(photoId);
        // Use native SQL for GREATEST since JPQL doesn't support it
        entityManager.createNativeQuery(
                "UPDATE users SET used_bytes = GREATEST(0, used_bytes - :fileSize) WHERE id = :userId")
                .setParameter("fileSize", fileSize)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public Page<Photo> listPhotos(UUID userId, int page, int size) {
        return photoRepository.findByUserIdAndDeletedAtIsNullOrderByUploadedAtDesc(
                userId, PageRequest.of(page, Math.min(size, 100)));
    }

    @Transactional(readOnly = true)
    public Photo getPhoto(UUID userId, UUID photoId) {
        return photoRepository.findById(photoId)
                .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
    }

    @Transactional(readOnly = true)
    public Photo getPhotoStatus(UUID userId, UUID photoId) {
        return photoRepository.findById(photoId)
                .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
    }

    @Transactional
    public void softDelete(UUID userId, UUID photoId) {
        // Lock user row FIRST to serialize concurrent soft-deletes for this user.
        // Re-reading the photo inside the lock ensures a second concurrent request
        // sees the already-deleted state and throws EntityNotFoundException rather
        // than decrementing used_bytes a second time.
        User user = entityManager.createQuery(
                "SELECT u FROM User u WHERE u.id = :userId", User.class)
                .setParameter("userId", userId)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();

        Photo photo = photoRepository.findById(photoId)
                .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));

        photo.setDeletedAt(Instant.now());
        photoRepository.save(photo);

        user.setUsedBytes(Math.max(0, user.getUsedBytes() - photo.getSizeBytes()));
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public Page<Photo> listTrash(UUID userId, int page, int size) {
        return photoRepository.findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                userId, PageRequest.of(page, Math.min(size, 100)));
    }

    @Transactional
    public void restore(UUID userId, UUID photoId) {
        Photo photo = photoRepository.findById(photoId)
                .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() != null)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));

        User user = entityManager.createQuery(
                "SELECT u FROM User u WHERE u.id = :userId", User.class)
                .setParameter("userId", userId)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();

        long newUsed = user.getUsedBytes() + photo.getSizeBytes();
        if (newUsed > user.getQuotaBytes()) {
            throw new IllegalStateException("Restoring this photo would exceed your storage quota");
        }

        photo.setDeletedAt(null);
        photoRepository.save(photo);

        user.setUsedBytes(newUsed);
        userRepository.save(user);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void silentlyDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
