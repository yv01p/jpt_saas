package org.jphototagger.api.controller;

import jakarta.validation.Valid;
import org.jphototagger.api.dto.CreateShareRequest;
import org.jphototagger.api.dto.ShareResponse;
import org.jphototagger.api.entity.Share;
import org.jphototagger.api.repository.ShareLookupRepository;
import org.jphototagger.api.service.ShareService;
import org.jphototagger.api.service.StorageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles share token creation, lookup, deletion, and listing.
 *
 * <p>Authenticated endpoints: POST /shares, DELETE /shares/{id}, GET /shares
 * Unauthenticated endpoints: GET /share/{token}, GET /share/{token}/photos
 */
@RestController
public class ShareController {

    private final ShareService shareService;
    private final ShareLookupRepository shareLookupRepository;
    private final StorageService storageService;

    public ShareController(ShareService shareService,
                           ShareLookupRepository shareLookupRepository,
                           StorageService storageService) {
        this.shareService = shareService;
        this.shareLookupRepository = shareLookupRepository;
        this.storageService = storageService;
    }

    /**
     * POST /shares — create a new share (authenticated).
     * Returns ShareResponse including the plaintext token (only time it is returned).
     */
    @PostMapping("/shares")
    public ResponseEntity<ShareResponse> createShare(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CreateShareRequest request) {
        var result = shareService.createShare(
                userId,
                request.getResourceType(),
                request.getResourceId(),
                request.isIncludeGps());

        Share share = result.share();
        ShareResponse response = new ShareResponse(
                share.getId(),
                share.getResourceType(),
                share.getResourceId(),
                result.plaintextToken(), // Only returned here
                share.getExpiresAt(),
                share.getPermissions(),
                share.isIncludeGps(),
                share.getCreatedAt());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /share/{token} — look up a share by token (unauthenticated).
     * Returns share metadata plus resource data (photo or album).
     */
    @GetMapping("/share/{token}")
    public ResponseEntity<?> getShare(@PathVariable String token) {
        var lookupResult = shareService.lookupShare(token);
        Map<String, Object> shareData = lookupResult.shareData();

        String resourceType = (String) shareData.get("resource_type");
        UUID resourceId = UUID.fromString(shareData.get("resource_id").toString());
        boolean includeGps = Boolean.TRUE.equals(shareData.get("include_gps"));

        Map<String, Object> response = new HashMap<>();
        response.put("share", shareData);

        if ("photo".equals(resourceType)) {
            var photoOpt = shareLookupRepository.findPhotoById(resourceId);
            if (photoOpt.isEmpty()) {
                throw new jakarta.persistence.EntityNotFoundException("Share not found");
            }
            Map<String, Object> photo = new HashMap<>(photoOpt.get());

            // Strip GPS fields from EXIF data when includeGps=false
            if (!includeGps) {
                Object exifData = photo.get("exif_data");
                if (exifData != null) {
                    String strippedExif = shareService.stripGpsFromExif(exifData.toString());
                    photo.put("exif_data", strippedExif);
                }
            }

            // Generate presigned URLs if storage_key is available
            Object storageKey = photo.get("storage_key");
            if (storageKey != null) {
                String key = storageKey.toString();
                // Extract userId from storage key (format: {userId}/originals/{photoId}.{ext})
                String[] parts = key.split("/");
                if (parts.length >= 2) {
                    UUID photoOwnerId = UUID.fromString(parts[0]);
                    UUID photoId = UUID.fromString(parts[2].replaceAll("\\.[^.]+$", ""));
                    photo.put("thumbnailUrl", storageService.generateThumbnailPresignedUrl(
                            storageService.thumbnailSmKey(photoOwnerId, photoId)));
                    photo.put("originalUrl", storageService.generateOriginalPresignedUrl(key));
                }
            }

            response.put("photo", photo);

        } else if ("album".equals(resourceType)) {
            // For album shares, just return album basic info
            Map<String, Object> album = new HashMap<>();
            album.put("id", resourceId.toString());
            response.put("album", album);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET /share/{token}/photos — paginated album photos (unauthenticated).
     * Only valid for album shares. Page size is capped at 50.
     */
    @GetMapping("/share/{token}/photos")
    public Page<Map<String, Object>> getSharedAlbumPhotos(
            @PathVariable String token,
            @PageableDefault(size = 20) Pageable pageable) {
        int cappedSize = Math.min(pageable.getPageSize(), 50);
        Pageable capped = PageRequest.of(pageable.getPageNumber(), cappedSize, pageable.getSort());

        var lookupResult = shareService.lookupShare(token);
        Map<String, Object> shareData = lookupResult.shareData();

        String resourceType = (String) shareData.get("resource_type");
        if (!"album".equals(resourceType)) {
            throw new jakarta.persistence.EntityNotFoundException("Share not found");
        }

        UUID albumId = UUID.fromString(shareData.get("resource_id").toString());
        return shareLookupRepository.findAlbumPhotos(albumId, capped);
    }

    /**
     * DELETE /shares/{id} — revoke a share (authenticated).
     */
    @DeleteMapping("/shares/{id}")
    public ResponseEntity<Void> deleteShare(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        shareService.deleteShare(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /shares — list the authenticated user's shares (paginated).
     */
    @GetMapping("/shares")
    public Page<ShareResponse> listShares(
            @AuthenticationPrincipal UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return shareService.listShares(userId, pageable)
                .map(share -> new ShareResponse(
                        share.getId(),
                        share.getResourceType(),
                        share.getResourceId(),
                        null, // token NOT included in list response
                        share.getExpiresAt(),
                        share.getPermissions(),
                        share.isIncludeGps(),
                        share.getCreatedAt()));
    }
}
