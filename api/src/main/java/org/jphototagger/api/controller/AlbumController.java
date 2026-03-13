package org.jphototagger.api.controller;

import jakarta.validation.Valid;
import org.jphototagger.api.dto.AlbumRequest;
import org.jphototagger.api.dto.PhotoResponse;
import org.jphototagger.api.entity.Album;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.service.AlbumService;
import org.jphototagger.api.service.StorageService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/albums")
public class AlbumController {

    private final AlbumService albumService;
    private final StorageService storageService;

    public AlbumController(AlbumService albumService, StorageService storageService) {
        this.albumService = albumService;
        this.storageService = storageService;
    }

    @GetMapping
    public ResponseEntity<Page<Album>> listAlbums(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(albumService.listAlbums(userId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Album> getAlbum(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(albumService.getAlbum(userId, id));
    }

    @PostMapping
    public ResponseEntity<Album> createAlbum(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody AlbumRequest body) {
        return ResponseEntity.status(201).body(albumService.createAlbum(userId, body.name()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Album> updateAlbum(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id,
            @Valid @RequestBody AlbumRequest body) {
        return ResponseEntity.ok(albumService.updateAlbum(userId, id, body.name()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAlbum(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        albumService.deleteAlbum(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{albumId}/photos/{photoId}")
    public ResponseEntity<Void> addPhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID albumId,
            @PathVariable UUID photoId) {
        albumService.addPhoto(userId, albumId, photoId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{albumId}/photos")
    public ResponseEntity<List<PhotoResponse>> getAlbumPhotos(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID albumId) {
        List<PhotoResponse> photos = albumService.getAlbumPhotos(userId, albumId).stream()
                .map(this::toPhotoResponse)
                .toList();
        return ResponseEntity.ok(photos);
    }

    @DeleteMapping("/{albumId}/photos/{photoId}")
    public ResponseEntity<Void> removePhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID albumId,
            @PathVariable UUID photoId) {
        albumService.removePhoto(userId, albumId, photoId);
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
