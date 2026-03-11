package org.jphototagger.api.controller;

import org.jphototagger.api.dto.PhotoResponse;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.service.PhotoService;
import org.jphototagger.api.service.StorageService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/photos")
public class PhotoController {

    private final PhotoService photoService;
    private final StorageService storageService;

    public PhotoController(PhotoService photoService, StorageService storageService) {
        this.photoService = photoService;
        this.storageService = storageService;
    }

    @PostMapping("/upload")
    public ResponseEntity<PhotoResponse> uploadPhoto(
            @AuthenticationPrincipal UUID userId,
            @RequestParam("file") MultipartFile file) {
        Photo photo = photoService.uploadPhoto(userId, file);
        return ResponseEntity.ok(toPhotoResponse(photo));
    }

    @GetMapping
    public ResponseEntity<Page<PhotoResponse>> listPhotos(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(photoService.listPhotos(userId, page, size).map(this::toPhotoResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PhotoResponse> getPhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(toPhotoResponse(photoService.getPhoto(userId, id)));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getPhotoStatus(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        Photo photo = photoService.getPhotoStatus(userId, id);
        return ResponseEntity.ok(Map.of(
                "id", photo.getId().toString(),
                "processing_status", photo.getProcessingStatus().name()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        photoService.softDelete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trash")
    public ResponseEntity<Page<PhotoResponse>> listTrash(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(photoService.listTrash(userId, page, size).map(this::toPhotoResponse));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restorePhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        photoService.restore(userId, id);
        return ResponseEntity.ok().build();
    }

    private PhotoResponse toPhotoResponse(Photo photo) {
        if (photo.getStorageKey() == null) {
            return PhotoResponse.from(photo);
        }
        String thumbnailUrl = storageService.generateThumbnailPresignedUrl(
                storageService.thumbnailSmKey(photo.getUserId(), photo.getId()));
        String originalUrl = storageService.generateOriginalPresignedUrl(photo.getStorageKey());
        return PhotoResponse.from(photo, thumbnailUrl, originalUrl);
    }
}
