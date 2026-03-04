package org.jphototagger.api.controller;

import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.service.PhotoService;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/photos")
public class PhotoController {

    private final PhotoService photoService;

    public PhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @GetMapping
    public ResponseEntity<Page<Photo>> listPhotos(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(photoService.listPhotos(userId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Photo> getPhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(photoService.getPhoto(userId, id));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getPhotoStatus(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        Photo photo = photoService.getPhotoStatus(userId, id);
        return ResponseEntity.ok(Map.of(
                "id", photo.getId().toString(),
                "processing_status", photo.getProcessingStatus()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        photoService.softDelete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trash")
    public ResponseEntity<Page<Photo>> listTrash(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(photoService.listTrash(userId, page, size));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restorePhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        photoService.restore(userId, id);
        return ResponseEntity.ok().build();
    }
}
