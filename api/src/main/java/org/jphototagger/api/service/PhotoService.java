package org.jphototagger.api.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.entity.User;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.api.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public PhotoService(PhotoRepository photoRepository, UserRepository userRepository,
                        EntityManager entityManager) {
        this.photoRepository = photoRepository;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
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
        return getPhoto(userId, photoId);
    }

    @Transactional
    public void softDelete(UUID userId, UUID photoId) {
        Photo photo = getPhoto(userId, photoId);

        User user = entityManager.createQuery(
                "SELECT u FROM User u WHERE u.id = :userId", User.class)
                .setParameter("userId", userId)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();

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
}
