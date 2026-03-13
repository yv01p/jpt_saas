package org.jphototagger.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.jphototagger.api.entity.Share;
import org.jphototagger.api.repository.AlbumRepository;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.api.repository.ShareLookupRepository;
import org.jphototagger.api.repository.ShareRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Manages share token lifecycle: creation, lookup, deletion, and listing.
 *
 * <p>Token format: 43-char URL-safe Base64 string (256-bit SecureRandom).
 * Storage: SHA-256 hex of plaintext token (never stored in plaintext).
 * Unauthenticated lookups use ShareLookupRepository (BYPASSRLS DataSource).
 */
@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    /** Pattern for valid share tokens: 43 URL-safe base64 chars (no padding). */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** Pattern to identify GPS-related EXIF keys (case-insensitive prefix match). */
    private static final Pattern GPS_KEY_PATTERN = Pattern.compile("(?i)gps.*");

    private final ShareRepository shareRepository;
    private final ShareLookupRepository shareLookupRepository;
    private final PhotoRepository photoRepository;
    private final AlbumRepository albumRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int defaultShareDays;

    public ShareService(
            ShareRepository shareRepository,
            ShareLookupRepository shareLookupRepository,
            PhotoRepository photoRepository,
            AlbumRepository albumRepository,
            ObjectMapper objectMapper,
            @Value("${app.default-share-days:30}") int defaultShareDays) {
        this.shareRepository = shareRepository;
        this.shareLookupRepository = shareLookupRepository;
        this.photoRepository = photoRepository;
        this.albumRepository = albumRepository;
        this.objectMapper = objectMapper;
        this.defaultShareDays = defaultShareDays;
    }

    /**
     * Creates a new share for a resource. Returns the share entity and the plaintext token
     * (which must be returned to the caller — it will never be stored or retrievable again).
     */
    @Transactional
    public CreateShareResult createShare(UUID userId, String resourceType, UUID resourceId, boolean includeGps) {
        // Validate resource exists and is not deleted
        validateResourceExists(userId, resourceType, resourceId);

        byte[] randomBytes = new byte[32]; // 256 bits
        secureRandom.nextBytes(randomBytes);
        String plaintextToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String tokenHash = RefreshTokenService.sha256(plaintextToken);

        Share share = new Share();
        share.setUserId(userId);
        share.setResourceType(resourceType);
        share.setResourceId(resourceId);
        share.setTokenHash(tokenHash);
        share.setIncludeGps(includeGps);
        share.setExpiresAt(Instant.now().plus(defaultShareDays, ChronoUnit.DAYS));
        share.setPermissions("view");

        Share saved = shareRepository.save(share);
        return new CreateShareResult(saved, plaintextToken);
    }

    /**
     * Looks up a share by the plaintext token (provided by the public user).
     * Returns the share metadata. All failures (not found, expired, invalid format,
     * deleted resource) return the same EntityNotFoundException — single code path.
     */
    public ShareLookupResult lookupShare(String plaintextToken) {
        // Validate token format BEFORE querying — return 404 (not 400) to avoid leaking expected format
        if (!TOKEN_PATTERN.matcher(plaintextToken).matches()) {
            throw new EntityNotFoundException("Share not found");
        }

        String tokenHash = RefreshTokenService.sha256(plaintextToken);
        Optional<Map<String, Object>> shareOpt = shareLookupRepository.findShareByTokenHash(tokenHash);

        if (shareOpt.isEmpty()) {
            throw new EntityNotFoundException("Share not found");
        }

        Map<String, Object> shareData = shareOpt.get();
        return new ShareLookupResult(shareData);
    }

    /**
     * Deletes a share by ID. Returns 404 if the share does not exist or is not owned by userId.
     * RLS on the primary DataSource enforces ownership.
     */
    @Transactional
    public void deleteShare(UUID shareId, UUID userId) {
        Share share = shareRepository.findById(shareId)
                .orElseThrow(() -> new EntityNotFoundException("Share not found"));
        // Secondary check: RLS should enforce this but we verify explicitly for clarity
        if (!share.getUserId().equals(userId)) {
            throw new EntityNotFoundException("Share not found");
        }
        shareRepository.delete(share);
    }

    /**
     * Lists all shares for a user (paginated). Uses primary DataSource (RLS active).
     */
    @Transactional(readOnly = true)
    public Page<Share> listShares(UUID userId, Pageable pageable) {
        return shareRepository.findByUserId(userId, pageable);
    }

    /**
     * Strips GPS-related fields from EXIF JSON data.
     * Removes all keys matching the GPS prefix pattern.
     */
    public String stripGpsFromExif(String exifJson) {
        if (exifJson == null) {
            return null;
        }
        try {
            Map<String, Object> exifMap = objectMapper.readValue(exifJson,
                    new TypeReference<Map<String, Object>>() {});
            Map<String, Object> stripped = new TreeMap<>();
            for (Map.Entry<String, Object> entry : exifMap.entrySet()) {
                if (!GPS_KEY_PATTERN.matcher(entry.getKey()).matches()) {
                    stripped.put(entry.getKey(), entry.getValue());
                }
            }
            return objectMapper.writeValueAsString(stripped);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse EXIF JSON for GPS stripping, returning as-is", e);
            return exifJson;
        }
    }

    private void validateResourceExists(UUID userId, String resourceType, UUID resourceId) {
        if ("photo".equals(resourceType)) {
            photoRepository.findById(resourceId)
                    .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null)
                    .orElseThrow(() -> new EntityNotFoundException("Photo not found or deleted"));
        } else if ("album".equals(resourceType)) {
            albumRepository.findById(resourceId)
                    .filter(a -> a.getUserId().equals(userId))
                    .orElseThrow(() -> new EntityNotFoundException("Album not found"));
        } else {
            throw new EntityNotFoundException("Unknown resource type: " + resourceType);
        }
    }

    public record CreateShareResult(Share share, String plaintextToken) {}

    public record ShareLookupResult(Map<String, Object> shareData) {}
}
