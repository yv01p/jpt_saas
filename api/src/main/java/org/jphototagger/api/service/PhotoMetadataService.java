package org.jphototagger.api.service;

import jakarta.persistence.EntityNotFoundException;
import org.jphototagger.api.dto.PhotoMetadataResponse;
import org.jphototagger.api.entity.User;
import org.jphototagger.api.repository.PhotoMetadataRepository;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PhotoMetadataService {

    private final PhotoMetadataRepository photoMetadataRepository;
    private final PhotoRepository photoRepository;
    private final UserRepository userRepository;

    public PhotoMetadataService(PhotoMetadataRepository photoMetadataRepository,
                                PhotoRepository photoRepository,
                                UserRepository userRepository) {
        this.photoMetadataRepository = photoMetadataRepository;
        this.photoRepository = photoRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PhotoMetadataResponse getMetadata(UUID userId, UUID photoId) {
        // Verify the photo exists and belongs to the user
        photoRepository.findById(photoId)
                .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));

        // Retrieve the authenticated user's GPS preference
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        // Retrieve metadata (may not exist yet if processing is still in progress)
        var metadata = photoMetadataRepository.findById(photoId)
                .orElseThrow(() -> new EntityNotFoundException("Metadata not available"));

        // Build metadata response
        PhotoMetadataResponse response = PhotoMetadataResponse.from(metadata);

        // Strip GPS fields if the user has disabled GPS display (SA4-F1)
        if (!user.isShowGps()) {
            response = response.withoutGps();
        }
        return response;
    }
}
