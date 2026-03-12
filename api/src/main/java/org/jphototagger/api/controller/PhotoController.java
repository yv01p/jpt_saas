package org.jphototagger.api.controller;

import org.jphototagger.api.dto.PhotoResponse;
import org.jphototagger.api.entity.Keyword;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.entity.PhotoKeyword;
import org.jphototagger.api.repository.KeywordRepository;
import org.jphototagger.api.repository.PhotoKeywordRepository;
import org.jphototagger.api.service.PhotoService;
import org.jphototagger.api.service.StorageService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/photos")
public class PhotoController {

    private final PhotoService photoService;
    private final StorageService storageService;
    private final PhotoKeywordRepository photoKeywordRepository;
    private final KeywordRepository keywordRepository;

    public PhotoController(PhotoService photoService, StorageService storageService,
                           PhotoKeywordRepository photoKeywordRepository,
                           KeywordRepository keywordRepository) {
        this.photoService = photoService;
        this.storageService = storageService;
        this.photoKeywordRepository = photoKeywordRepository;
        this.keywordRepository = keywordRepository;
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
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
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
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(photoService.listTrash(userId, page, size).map(this::toPhotoResponse));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restorePhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        photoService.restore(userId, id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/keywords")
    public ResponseEntity<List<Keyword>> listKeywordsForPhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        // Validate photo ownership
        photoService.getPhoto(userId, id);
        List<PhotoKeyword> assignments = photoKeywordRepository.findByPhotoIdAndUserId(id, userId);
        List<Keyword> keywords = assignments.stream()
                .map(pk -> keywordRepository.findById(pk.getKeywordId())
                        .orElse(null))
                .filter(k -> k != null && k.getUserId().equals(userId))
                .toList();
        return ResponseEntity.ok(keywords);
    }

    @PostMapping("/{id}/keywords/{keywordId}")
    @Transactional
    public ResponseEntity<Void> addKeywordToPhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id,
            @PathVariable UUID keywordId) {
        // Validate photo ownership
        photoService.getPhoto(userId, id);
        // Validate keyword ownership
        Keyword keyword = keywordRepository.findById(keywordId)
                .orElseThrow(() -> new EntityNotFoundException("Keyword not found"));
        if (!keyword.getUserId().equals(userId)) {
            throw new EntityNotFoundException("Keyword not found");
        }
        PhotoKeyword pk = new PhotoKeyword();
        pk.setPhotoId(id);
        pk.setKeywordId(keywordId);
        pk.setUserId(userId);
        photoKeywordRepository.save(pk);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/keywords/{keywordId}")
    @Transactional
    public ResponseEntity<Void> removeKeywordFromPhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id,
            @PathVariable UUID keywordId) {
        // Validate photo ownership
        photoService.getPhoto(userId, id);
        photoKeywordRepository.deleteByPhotoIdAndKeywordIdAndUserId(id, keywordId, userId);
        return ResponseEntity.noContent().build();
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
