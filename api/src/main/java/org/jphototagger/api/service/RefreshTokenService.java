package org.jphototagger.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final String REFRESH_PREFIX = "refresh:";
    private static final String USER_REFRESH_PREFIX = "user_refresh:";
    private static final String FAMILY_PREFIX = "refresh_family:";
    private static final String TOKEN_FAMILY_PREFIX = "token_family:";

    private final StringRedisTemplate redis;
    private final Duration tokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            StringRedisTemplate redis,
            @Value("${app.refresh-token-expiry-days:30}") int expiryDays) {
        this.redis = redis;
        this.tokenTtl = Duration.ofDays(expiryDays);
    }

    /**
     * Creates a new refresh token for a user, starting a new token family.
     */
    public String createToken(UUID userId) {
        String familyId = UUID.randomUUID().toString();
        return createTokenInFamily(userId, familyId);
    }

    /**
     * Rotates a refresh token: validates the old token, deletes it, issues a new one
     * in the same family.
     *
     * @return a record containing the new raw token and the userId
     * @throws InvalidRefreshTokenException if token is invalid or replay detected
     */
    public RotationResult rotate(String rawToken) {
        String hash = sha256(rawToken);
        String key = REFRESH_PREFIX + hash;

        String data = redis.opsForValue().get(key);
        if (data == null) {
            // Token not found — check if it belongs to a known family (replay detection)
            detectReplay(hash);
            throw new InvalidRefreshTokenException("Invalid refresh token");
        }

        // Parse stored JSON: {"userId":"...","issuedAt":"...","family":"..."}
        UUID userId = UUID.fromString(parseJsonField(data, "userId"));
        String familyId = parseJsonField(data, "family");

        // Delete old token
        redis.delete(key);
        redis.opsForSet().remove(USER_REFRESH_PREFIX + userId, hash);

        // Issue new token in same family
        String newRawToken = createTokenInFamily(userId, familyId);
        return new RotationResult(newRawToken, userId);
    }

    /**
     * Revoke a single refresh token (logout).
     */
    public void revoke(String rawToken) {
        String hash = sha256(rawToken);
        String key = REFRESH_PREFIX + hash;
        String data = redis.opsForValue().get(key);
        if (data != null) {
            UUID userId = UUID.fromString(parseJsonField(data, "userId"));
            redis.delete(key);
            redis.opsForSet().remove(USER_REFRESH_PREFIX + userId, hash);
        }
    }

    /**
     * Revoke all refresh tokens for a user (password change).
     */
    public void revokeAllForUser(UUID userId) {
        String userSetKey = USER_REFRESH_PREFIX + userId;
        Set<String> hashes = redis.opsForSet().members(userSetKey);
        if (hashes != null) {
            for (String hash : hashes) {
                redis.delete(REFRESH_PREFIX + hash);
            }
        }
        redis.delete(userSetKey);
    }

    /**
     * Extract the userId from a raw refresh token without rotating.
     */
    public UUID getUserId(String rawToken) {
        String hash = sha256(rawToken);
        String data = redis.opsForValue().get(REFRESH_PREFIX + hash);
        if (data == null) {
            return null;
        }
        return UUID.fromString(parseJsonField(data, "userId"));
    }

    private String createTokenInFamily(UUID userId, String familyId) {
        byte[] randomBytes = new byte[32]; // 256 bits
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String hash = sha256(rawToken);
        String issuedAt = Instant.now().toString();
        String value = "{\"userId\":\"" + userId + "\",\"issuedAt\":\"" + issuedAt + "\",\"family\":\"" + familyId + "\"}";

        redis.opsForValue().set(REFRESH_PREFIX + hash, value, tokenTtl);
        redis.opsForSet().add(USER_REFRESH_PREFIX + userId, hash);
        redis.opsForSet().add(FAMILY_PREFIX + familyId, hash);
        // Reverse index: token hash → familyId (for O(1) replay detection)
        redis.opsForValue().set(TOKEN_FAMILY_PREFIX + hash, familyId, tokenTtl);
        // Set TTL on family set too (cleanup)
        redis.expire(FAMILY_PREFIX + familyId, tokenTtl.plusDays(1));
        redis.expire(USER_REFRESH_PREFIX + userId, tokenTtl.plusDays(1));

        return rawToken;
    }

    private void detectReplay(String hash) {
        // O(1) lookup via reverse index: token hash → familyId
        String familyId = redis.opsForValue().get(TOKEN_FAMILY_PREFIX + hash);
        if (familyId != null) {
            log.warn("SECURITY: Refresh token replay detected in family {}", familyId);
            revokeFamily(familyId);
            throw new InvalidRefreshTokenException("Refresh token replay detected");
        }
    }

    private void revokeFamily(String familyId) {
        String familyKey = FAMILY_PREFIX + familyId;
        Set<String> hashes = redis.opsForSet().members(familyKey);
        if (hashes != null) {
            for (String hash : hashes) {
                String key = REFRESH_PREFIX + hash;
                String data = redis.opsForValue().get(key);
                if (data != null) {
                    UUID userId = UUID.fromString(parseJsonField(data, "userId"));
                    redis.delete(key);
                    redis.opsForSet().remove(USER_REFRESH_PREFIX + userId, hash);
                }
            }
        }
        // Don't delete the family set — keep it for future replay detection
    }

    /**
     * Simple JSON field extractor for flat JSON objects with string values.
     */
    static String parseJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            throw new IllegalArgumentException("Field '" + field + "' not found in JSON: " + json);
        }
        start += key.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public record RotationResult(String rawToken, UUID userId) {}

    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String message) {
            super(message);
        }
    }
}
