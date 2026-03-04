package org.jphototagger.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jphototagger.api.service.RefreshTokenService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final JdbcTemplate authJdbc;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final long jwtExpiryMinutes;
    private final int refreshExpiryDays;
    private final String redirectUri;

    public OAuth2SuccessHandler(
            @Qualifier("authJdbcTemplate") JdbcTemplate authJdbc,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            @Value("${app.jwt-expiry-minutes}") long jwtExpiryMinutes,
            @Value("${app.refresh-token-expiry-days:30}") int refreshExpiryDays,
            @Value("${app.oauth2.redirect-uri:/}") String redirectUri) {
        this.authJdbc = authJdbc;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.jwtExpiryMinutes = jwtExpiryMinutes;
        this.refreshExpiryDays = refreshExpiryDays;
        this.redirectUri = redirectUri;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        String provider = oauthToken.getAuthorizedClientRegistrationId();

        OidcUser oidcUser = (OidcUser) oauthToken.getPrincipal();
        String email = oidcUser.getEmail();
        String oauthId = oidcUser.getSubject();

        // Handle missing email
        if (email == null || email.isBlank()) {
            response.sendRedirect(redirectUri + "login?error=no_email");
            return;
        }

        // Check if user exists
        List<Map<String, Object>> existing = authJdbc.queryForList(
                "SELECT id, email, password_hash, oauth_provider, oauth_id FROM users WHERE email = ?", email);

        if (existing.isEmpty()) {
            // New user — create with OAuth credentials
            UUID userId = UUID.randomUUID();
            authJdbc.update(
                    "INSERT INTO users (id, email, oauth_provider, oauth_id, quota_bytes, used_bytes, " +
                            "failed_login_attempts, email_verified, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, 10737418240, 0, 0, true, NOW(), NOW())",
                    userId, email, provider, oauthId);

            issueTokens(response, userId, email);
            return;
        }

        Map<String, Object> user = existing.get(0);
        String existingPasswordHash = (String) user.get("password_hash");
        String existingOAuthProvider = (String) user.get("oauth_provider");

        // If user has a password (non-OAuth account), block login — no auto-merge
        if (existingPasswordHash != null && !existingPasswordHash.isBlank()) {
            response.sendRedirect(redirectUri + "login?error=email_conflict");
            return;
        }

        // Verify provider and id match
        String existingOAuthId = (String) user.get("oauth_id");
        if (!provider.equals(existingOAuthProvider) || !oauthId.equals(existingOAuthId)) {
            response.sendRedirect(redirectUri + "login?error=provider_mismatch");
            return;
        }

        // Existing OAuth user with matching provider+id — issue tokens
        UUID userId = (UUID) user.get("id");
        issueTokens(response, userId, email);
    }

    private void issueTokens(HttpServletResponse response, UUID userId, String email) throws IOException {
        String jwt = jwtService.generateToken(userId, email);
        String refreshToken = refreshTokenService.createToken(userId);

        ResponseCookie jwtCookie = ResponseCookie.from("jwt", jwt)
                .httpOnly(true).secure(true).sameSite("Lax")
                .path("/").maxAge(Duration.ofMinutes(jwtExpiryMinutes)).build();

        ResponseCookie refreshCookie = ResponseCookie.from("refresh", refreshToken)
                .httpOnly(true).secure(true).sameSite("Lax")
                .path("/auth").maxAge(Duration.ofDays(refreshExpiryDays)).build();

        response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        response.sendRedirect(redirectUri);
    }
}
