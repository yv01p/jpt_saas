package org.jphototagger.api.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jphototagger.api.dto.ErrorResponse;
import org.jphototagger.api.dto.LoginRequest;
import org.jphototagger.api.dto.RegisterRequest;
import org.jphototagger.api.security.JwtService;
import org.jphototagger.api.service.AuthService;
import org.jphototagger.api.service.RefreshTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final long jwtExpiryMinutes;
    private final int refreshExpiryDays;
    private final boolean cookieSecure;

    public AuthController(
            AuthService authService,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            @Value("${app.jwt-expiry-minutes}") long jwtExpiryMinutes,
            @Value("${app.refresh-token-expiry-days:30}") int refreshExpiryDays,
            @Value("${app.cookie-secure:true}") boolean cookieSecure) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.jwtExpiryMinutes = jwtExpiryMinutes;
        this.refreshExpiryDays = refreshExpiryDays;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "If this email is not registered, a verification email has been sent."));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            Map<String, Object> result = authService.authenticate(request.email(), request.password());
            UUID userId = (UUID) result.get("userId");
            String email = (String) result.get("email");

            String jwt = jwtService.generateToken(userId, email);
            String refreshToken = refreshTokenService.createToken(userId);

            ResponseCookie jwtCookie = buildJwtCookie(jwt);
            ResponseCookie refreshCookie = buildRefreshCookie(refreshToken);

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                    .body(Map.of("message", "Login successful"));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Invalid credentials", 401));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        String refreshToken = extractCookie(request, "refresh");
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Missing refresh token", 401));
        }

        try {
            RefreshTokenService.RotationResult result = refreshTokenService.rotate(refreshToken);
            String email = authService.getUserEmail(result.userId());
            if (email == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Invalid credentials", 401));
            }

            String jwt = jwtService.generateToken(result.userId(), email);
            ResponseCookie jwtCookie = buildJwtCookie(jwt);
            ResponseCookie newRefreshCookie = buildRefreshCookie(result.rawToken());

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
                    .header(HttpHeaders.SET_COOKIE, newRefreshCookie.toString())
                    .body(Map.of("message", "Token refreshed"));
        } catch (RefreshTokenService.InvalidRefreshTokenException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Invalid credentials", 401));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String refreshToken = extractCookie(request, "refresh");
        if (refreshToken != null) {
            refreshTokenService.revoke(refreshToken);
        }

        ResponseCookie clearJwt = ResponseCookie.from("jwt", "")
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(0).build();
        ResponseCookie clearRefresh = ResponseCookie.from("refresh", "")
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/auth").maxAge(0).build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearJwt.toString())
                .header(HttpHeaders.SET_COOKIE, clearRefresh.toString())
                .body(Map.of("message", "Logged out"));
    }

    private ResponseCookie buildJwtCookie(String token) {
        return ResponseCookie.from("jwt", token)
                .httpOnly(true).secure(cookieSecure).sameSite("Lax")
                .path("/").maxAge(Duration.ofMinutes(jwtExpiryMinutes)).build();
    }

    private ResponseCookie buildRefreshCookie(String refreshToken) {
        return ResponseCookie.from("refresh", refreshToken)
                .httpOnly(true).secure(cookieSecure).sameSite("Lax")
                .path("/auth").maxAge(Duration.ofDays(refreshExpiryDays)).build();
    }

    private String extractCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
