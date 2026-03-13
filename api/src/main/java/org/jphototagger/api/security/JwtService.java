package org.jphototagger.api.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final String jwtSecret;
    private final long expiryMinutes;
    private final Environment environment;
    private final SecretKey signingKey;

    public JwtService(
            @Value("${app.jwt-secret}") String jwtSecret,
            @Value("${app.jwt-expiry-minutes}") long expiryMinutes,
            Environment environment) {
        this.jwtSecret = jwtSecret;
        this.expiryMinutes = expiryMinutes;
        this.environment = environment;
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @PostConstruct
    void validateSecret() {
        if (environment == null || !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            if (jwtSecret.length() < 43) {
                throw new IllegalStateException(
                        "JWT_SECRET must be >= 256 bits (43+ base64 chars)");
            }
            if (jwtSecret.contains("change-me")) {
                throw new IllegalStateException(
                        "Default JWT_SECRET detected in non-dev profile");
            }
            // Guard against deploying with .env.ci values. Primary secret strength validation (256-bit minimum) enforced above.
            if (jwtSecret.startsWith("ci_test")) {
                throw new IllegalStateException("CI test JWT secret detected — do not use .env.ci in production");
            }
        }
    }

    public String generateToken(UUID userId, String email) {
        return generateToken(userId, email, expiryMinutes);
    }

    String generateToken(UUID userId, String email, long expiryMinutes) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiryMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID extractUserId(String token) {
        String subject = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return UUID.fromString(subject);
    }
}
