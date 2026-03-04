package org.jphototagger.api.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final JdbcTemplate authJdbc;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            @Qualifier("authJdbcTemplate") JdbcTemplate authJdbc,
            PasswordEncoder passwordEncoder,
            EmailService emailService) {
        this.authJdbc = authJdbc;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    /**
     * Register a new user. Returns the user ID.
     */
    public UUID register(String email, String password) {
        String hash = passwordEncoder.encode(password);
        UUID userId = UUID.randomUUID();

        authJdbc.update(
                "INSERT INTO users (id, email, password_hash, quota_bytes, used_bytes, " +
                        "failed_login_attempts, email_verified, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 10737418240, 0, 0, false, NOW(), NOW())",
                userId, email, hash);

        // Generate email verification token
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String plainToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String tokenHash = sha256Hex(plainToken);

        authJdbc.update(
                "INSERT INTO email_tokens (id, user_id, token_hash, purpose, expires_at, created_at) " +
                        "VALUES (?, ?, ?, 'verify', NOW() + INTERVAL '24 hours', NOW())",
                UUID.randomUUID(), userId, tokenHash);

        emailService.sendVerificationEmail(email, plainToken);

        return userId;
    }

    /**
     * Authenticate a user. Returns a map with userId and email on success.
     * Always performs bcrypt check to prevent timing side-channel.
     *
     * @throws BadCredentialsException for any failure (wrong password, locked, not found)
     */
    public Map<String, Object> authenticate(String email, String password) {
        var rows = authJdbc.queryForList(
                "SELECT id, email, password_hash, failed_login_attempts, locked_until FROM users WHERE email = ?",
                email);

        if (rows.isEmpty()) {
            // Perform dummy bcrypt to prevent timing side-channel
            passwordEncoder.matches(password, "$2a$12$dummy.hash.to.prevent.timing.side.channel.attacks.00000000");
            throw new BadCredentialsException("Invalid credentials");
        }

        Map<String, Object> user = rows.get(0);
        UUID userId = (UUID) user.get("id");
        String storedHash = (String) user.get("password_hash");
        int failedAttempts = (int) user.get("failed_login_attempts");
        Instant lockedUntil = user.get("locked_until") != null
                ? ((java.sql.Timestamp) user.get("locked_until")).toInstant()
                : null;

        // Always check password first (timing side-channel mitigation)
        boolean passwordCorrect = passwordEncoder.matches(password, storedHash);

        // After comparison, check if account is locked
        boolean isLocked = failedAttempts >= MAX_FAILED_ATTEMPTS
                && lockedUntil != null
                && lockedUntil.isAfter(Instant.now());

        if (!passwordCorrect) {
            // Increment failed attempts
            int newAttempts = failedAttempts + 1;
            if (newAttempts >= MAX_FAILED_ATTEMPTS) {
                authJdbc.update(
                        "UPDATE users SET failed_login_attempts = ?, locked_until = ? WHERE id = ?",
                        newAttempts, java.sql.Timestamp.from(Instant.now().plus(LOCKOUT_DURATION)), userId);
            } else {
                authJdbc.update(
                        "UPDATE users SET failed_login_attempts = ? WHERE id = ?",
                        newAttempts, userId);
            }
            throw new BadCredentialsException("Invalid credentials");
        }

        if (isLocked) {
            // Password was correct but account is locked — still return generic error
            throw new BadCredentialsException("Invalid credentials");
        }

        // Successful login — reset counter
        authJdbc.update(
                "UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?",
                userId);

        return Map.of("userId", userId, "email", (String) user.get("email"));
    }

    /**
     * Change password and return userId for token revocation.
     */
    public UUID changePassword(UUID userId, String oldPassword, String newPassword) {
        var rows = authJdbc.queryForList(
                "SELECT password_hash FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String storedHash = (String) rows.get(0).get("password_hash");
        if (!passwordEncoder.matches(oldPassword, storedHash)) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String newHash = passwordEncoder.encode(newPassword);
        authJdbc.update("UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ?",
                newHash, userId);

        return userId;
    }

    public String getUserEmail(UUID userId) {
        var rows = authJdbc.queryForList("SELECT email FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) return null;
        return (String) rows.get(0).get("email");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
