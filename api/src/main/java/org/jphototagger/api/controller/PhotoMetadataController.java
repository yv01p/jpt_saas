package org.jphototagger.api.controller;

import org.jphototagger.api.dto.PhotoMetadataResponse;
import org.jphototagger.api.service.PhotoMetadataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/photos")
public class PhotoMetadataController {

    private final PhotoMetadataService photoMetadataService;

    public PhotoMetadataController(PhotoMetadataService photoMetadataService) {
        this.photoMetadataService = photoMetadataService;
    }

    @GetMapping("/{id}/metadata")
    public ResponseEntity<PhotoMetadataResponse> getPhotoMetadata(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(photoMetadataService.getMetadata(userId, id));
    }
}
