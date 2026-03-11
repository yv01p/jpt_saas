package org.jphototagger.api.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures two MinioClient instances:
 * <ul>
 *   <li>{@code minioInternalClient} — connects to the internal Docker hostname.
 *       Used for all I/O: upload, download, delete.</li>
 *   <li>{@code minioPublicClient} — connects to the Nginx-proxied public URL.
 *       Used <em>only</em> for pre-signed URL generation so that URLs returned
 *       to browsers begin with the public hostname, not the internal one.
 *       Carries a presign-only IAM user with an empty policy (no S3 permissions).
 *       MUST NEVER be used for actual data I/O operations.</li>
 * </ul>
 *
 * The MinIO Java SDK generates pre-signed URLs against whatever endpoint the
 * MinioClient was constructed with, so two separate clients are required.
 */
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.public-url}")
    private String publicUrl;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    /** Credentials for the presign-only IAM user — empty policy, no S3 data access. */
    @Value("${minio.presign-access-key}")
    private String presignAccessKey;

    @Value("${minio.presign-secret-key}")
    private String presignSecretKey;

    @Bean("minioInternalClient")
    public MinioClient minioInternalClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /**
     * Pre-sign-only client. Carries an empty-policy IAM user — no GetObject/PutObject/DeleteObject
     * permission. Use only for {@code getPresignedObjectUrl()} (pure HMAC computation).
     * Do not pass this bean to any code path that calls I/O methods.
     */
    @Bean("minioPublicClient")
    public MinioClient minioPublicClient() {
        return MinioClient.builder()
                .endpoint(publicUrl)
                .credentials(presignAccessKey, presignSecretKey)
                .build();
    }
}
