package org.jphototagger.api.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jphototagger.api.config.RateLimitConfig;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-user rate limiting filter backed by Bucket4j + Redis.
 * Applies separate limits for upload endpoints (POST/PUT to /photos)
 * and general API requests.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitConfig rateLimitConfig;
    private final LettuceConnectionFactory lettuceConnectionFactory;

    private ProxyManager<String> proxyManager;
    private StatefulRedisConnection<String, byte[]> connection;

    public RateLimitFilter(RateLimitConfig rateLimitConfig,
                           LettuceConnectionFactory lettuceConnectionFactory) {
        this.rateLimitConfig = rateLimitConfig;
        this.lettuceConnectionFactory = lettuceConnectionFactory;
    }

    @PostConstruct
    void init() {
        RedisClient nativeClient = (RedisClient) lettuceConnectionFactory.getNativeClient();
        connection = nativeClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                        Duration.ofHours(1)))
                .build();
    }

    @PreDestroy
    public void destroy() {
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UUID userId)) {
            if (isAuthEndpoint(request)) {
                String clientIp = getClientIp(request);
                ConsumptionProbe probe = proxyManager.builder()
                        .build("rate:auth:" + clientIp, this::authBucketConfig)
                        .tryConsumeAndReturnRemaining(1);
                if (!probe.isConsumed()) {
                    long retryAfterSeconds = Math.max(1,
                            (probe.getNanosToWaitForRefill() + 999_999_999) / 1_000_000_000);
                    response.setContentType("application/json");
                    response.setStatus(429);
                    response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
                    response.getWriter().write("{\"error\":\"Too Many Requests\",\"status\":429}");
                    return;
                }
            }
            filterChain.doFilter(request, response);
            return;
        }

        boolean isUpload = isUploadRequest(request);
        String bucketKey = isUpload
                ? "rate:upload:" + userId
                : "rate:general:" + userId;

        Supplier<BucketConfiguration> configSupplier = isUpload
                ? this::uploadBucketConfig
                : this::generalBucketConfig;

        ConsumptionProbe probe = proxyManager.builder()
                .build(bucketKey, configSupplier)
                .tryConsumeAndReturnRemaining(1);

        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1,
                    (probe.getNanosToWaitForRefill() + 999_999_999) / 1_000_000_000);
            response.setContentType("application/json");
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write("{\"error\":\"Too Many Requests\",\"status\":429}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthEndpoint(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/auth/");
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private BucketConfiguration authBucketConfig() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rateLimitConfig.getAuth())
                        .refillGreedy(rateLimitConfig.getAuth(), Duration.ofHours(1))
                        .build())
                .build();
    }

    private boolean isUploadRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return "POST".equals(method) && path.matches(".*/photos/?$");
    }

    private BucketConfiguration uploadBucketConfig() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rateLimitConfig.getUpload())
                        .refillGreedy(rateLimitConfig.getUpload(), Duration.ofHours(1))
                        .build())
                .build();
    }

    private BucketConfiguration generalBucketConfig() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rateLimitConfig.getGeneral())
                        .refillGreedy(rateLimitConfig.getGeneral(), Duration.ofHours(1))
                        .build())
                .build();
    }
}
