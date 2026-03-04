package org.jphototagger.api.controller;

import org.jphototagger.api.entity.Album;
import org.jphototagger.api.service.AlbumService;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/albums")
public class AlbumController {

    private final AlbumService albumService;

    public AlbumController(AlbumService albumService) {
        this.albumService = albumService;
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
            @RequestBody Map<String, String> body) {
        return ResponseEntity.status(201).body(albumService.createAlbum(userId, body.get("name")));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Album> updateAlbum(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(albumService.updateAlbum(userId, id, body.get("name")));
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

    @DeleteMapping("/{albumId}/photos/{photoId}")
    public ResponseEntity<Void> removePhoto(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID albumId,
            @PathVariable UUID photoId) {
        albumService.removePhoto(userId, albumId, photoId);
        return ResponseEntity.noContent().build();
    }
}
