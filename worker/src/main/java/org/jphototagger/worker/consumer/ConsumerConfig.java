package org.jphototagger.worker.consumer;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.minio.MinioClient;
import org.jphototagger.api.repository.PhotoRepository;
import org.jphototagger.worker.config.WorkerProperties;
import org.jphototagger.worker.pipeline.ImageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.UUID;

/**
 * Wires up the Redis Streams consumers using native Lettuce commands.
 *
 * <p>Spring Data Redis's {@code StringRedisTemplate} does not expose
 * {@code XAUTOCLAIM} or granular {@code XPENDING} with range/limit, so we
 * obtain a native Lettuce {@link StatefulRedisConnection} from the
 * {@link LettuceConnectionFactory} provided by Spring Boot autoconfiguration.
 *
 * <p>A single {@link StatefulRedisConnection} is opened at startup and reused
 * for the lifetime of the process (thread-safe for single-thread use).
 *
 * <p>The scheduled poll loops live in {@link ConsumerScheduler}, which injects
 * the consumer beans produced here. Keeping them separate avoids a circular
 * dependency (Spring cannot inject beans from a {@code @Configuration} class
 * into that same class's constructor).
 */
@Configuration
@EnableScheduling
public class ConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(ConsumerConfig.class);

    /**
     * Opens a native Lettuce {@link StatefulRedisConnection} for String/String codec.
     *
     * <p>We cast the Spring-managed {@link LettuceConnectionFactory}'s native client
     * to {@link RedisClient} and open a dedicated connection for stream commands.
     * This avoids interfering with Spring Data Redis's shared connection pool.
     */
    @Bean
    public StatefulRedisConnection<String, String> lettuceStreamConnection(
            LettuceConnectionFactory lettuceConnectionFactory) {
        // getNativeClient() returns the AbstractRedisClient; cast to RedisClient
        // (safe for standalone Redis — all environments in this project use standalone).
        RedisClient redisClient = (RedisClient) lettuceConnectionFactory.getNativeClient();
        return redisClient.connect();
    }

    /**
     * Exposes the synchronous {@link RedisCommands} interface for use by consumers.
     */
    @Bean
    public RedisCommands<String, String> lettuceRedisCommands(
            StatefulRedisConnection<String, String> lettuceStreamConnection) {
        return lettuceStreamConnection.sync();
    }

    /**
     * Builds a stable consumer name from {@code HOSTNAME} env var + PID.
     * Falls back to {@link InetAddress}, then a random UUID.
     *
     * <p>Shared by both {@link PhotoJobConsumer} and {@link DeleteJobConsumer}
     * so that they use a consistent, process-unique identity.
     */
    @Bean
    public String consumerName() {
        String hostname = Optional.ofNullable(System.getenv("HOSTNAME"))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> {
                    try {
                        return InetAddress.getLocalHost().getHostName();
                    } catch (UnknownHostException e) {
                        return UUID.randomUUID().toString();
                    }
                });
        return hostname + "-" + ProcessHandle.current().pid();
    }

    // -------------------------------------------------------------------------
    // Consumer beans
    // -------------------------------------------------------------------------

    @Bean
    public PhotoJobConsumer photoJobConsumer(
            RedisCommands<String, String> lettuceRedisCommands,
            PhotoRepository photoRepository,
            ImageProcessor imageProcessor,
            WorkerProperties workerProperties,
            String consumerName) {
        log.info("Registering PhotoJobConsumer with consumerName={}", consumerName);
        PhotoJobConsumer consumer = new PhotoJobConsumer(
                lettuceRedisCommands, photoRepository, imageProcessor,
                workerProperties, consumerName);
        consumer.ensureGroupExists();
        return consumer;
    }

    @Bean
    public DeleteJobConsumer deleteJobConsumer(
            RedisCommands<String, String> lettuceRedisCommands,
            MinioClient minioClient,
            WorkerProperties workerProperties,
            String consumerName,
            @Value("${minio.bucket}") String bucket) {
        DeleteJobConsumer consumer = new DeleteJobConsumer(
                lettuceRedisCommands, minioClient, workerProperties, bucket, consumerName);
        consumer.ensureGroupExists();
        return consumer;
    }

    // -------------------------------------------------------------------------
    // Startup recovery — deferred until application context is fully ready
    // -------------------------------------------------------------------------

    /**
     * Runs startup recovery after the Spring application context is fully
     * initialised. Deferring to {@link ApplicationReadyEvent} ensures Redis
     * unavailability during context startup does not abort the whole context.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void runStartupRecovery(ApplicationReadyEvent event) {
        PhotoJobConsumer consumer = event.getApplicationContext()
                .getBean(PhotoJobConsumer.class);
        consumer.performStartupRecovery();
    }
}
