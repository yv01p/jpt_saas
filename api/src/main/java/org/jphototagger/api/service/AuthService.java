package org.jphototagger.api.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.jphototagger.api.exception.EmailVerificationRequiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Async;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
     * Register a new user. Silently no-ops on duplicate email (with timing equalization)
     * so callers cannot enumerate registered addresses via response body or timing.
     */
    public void register(String email, String password) {
        var existing = authJdbc.queryForList("SELECT id FROM users WHERE email = ?", email);
        if (!existing.isEmpty()) {
            // Equalize timing with the happy path (bcrypt at cost 12 ≈ 250ms).
            // Do not throw — caller always receives the same 202 response.
            passwordEncoder.encode(password);
            return;
        }

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
        String tokenHash = RefreshTokenService.sha256(plainToken);

        authJdbc.update(
                "INSERT INTO email_tokens (id, user_id, token_hash, purpose, expires_at, created_at) " +
                        "VALUES (?, ?, ?, 'verify', NOW() + INTERVAL '24 hours', NOW())",
                UUID.randomUUID(), userId, tokenHash);

        sendVerificationEmailAsync(email, plainToken);
    }

    @Async
    void sendVerificationEmailAsync(String email, String token) {
        emailService.sendVerificationEmail(email, token);
    }

    /**
     * Authenticate a user. Returns a map with userId and email on success.
     * Always performs bcrypt check to prevent timing side-channel.
     *
     * @throws BadCredentialsException for any failure (wrong password, locked, not found)
     */
    public Map<String, Object> authenticate(String email, String password) {
        var rows = authJdbc.queryForList(
                "SELECT id, email, password_hash, failed_login_attempts, locked_until, email_verified FROM users WHERE email = ?",
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

        // Reject unverified email — checked after bcrypt to preserve timing side-channel protection
        Boolean emailVerified = (Boolean) user.get("email_verified");
        if (emailVerified == null || !emailVerified) {
            throw new EmailVerificationRequiredException("Please verify your email before logging in");
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
        if (oldPassword.length() > 128 || newPassword.length() > 128) {
            throw new BadCredentialsException("Invalid credentials");
        }
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

    /**
     * Verify email using the token sent during registration.
     * Uses constant-time hash comparison via database lookup on the SHA-256 hash.
     * Deletes all verification tokens for the user after success.
     *
     * @return true if verification succeeded, false if token is invalid or expired
     */
    public boolean verifyEmail(String plainToken) {
        String tokenHash = RefreshTokenService.sha256(plainToken);

        var rows = authJdbc.queryForList(
                "SELECT user_id FROM email_tokens WHERE token_hash = ? AND purpose = 'verify' AND expires_at > NOW()",
                tokenHash);

        if (rows.isEmpty()) {
            return false;
        }

        UUID userId = (UUID) rows.get(0).get("user_id");

        authJdbc.update("UPDATE users SET email_verified = true, updated_at = NOW() WHERE id = ?", userId);
        authJdbc.update("DELETE FROM email_tokens WHERE user_id = ? AND purpose = 'verify'", userId);

        return true;
    }

    public String getUserEmail(UUID userId) {
        var rows = authJdbc.queryForList("SELECT email FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) return null;
        return (String) rows.get(0).get("email");
    }

}
