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
 *       Never used for actual data I/O.</li>
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

    @Bean("minioInternalClient")
    public MinioClient minioInternalClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean("minioPublicClient")
    public MinioClient minioPublicClient() {
        return MinioClient.builder()
                .endpoint(publicUrl)
                .credentials(accessKey, secretKey)
                .build();
    }
}
