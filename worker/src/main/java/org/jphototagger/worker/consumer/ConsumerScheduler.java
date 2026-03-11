package org.jphototagger.worker.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Drives the Redis Streams consumer poll loops on a fixed-delay schedule.
 *
 * <p>Each consumer is single-threaded — one in-flight job at a time.
 * Spring's {@code @Scheduled} methods must be no-arg, so the consumers are
 * injected here and held as fields.
 *
 * <p>Fixed-delay (not fixed-rate) ensures sequential processing: the next
 * poll does not start until the previous one finishes.
 *
 * <p>All schedule intervals are configurable via {@code worker.streams.*} properties
 * and are also exposed as typed fields in {@link org.jphototagger.worker.config.WorkerProperties.Streams}.
 */
@Component
@EnableScheduling
public class ConsumerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConsumerScheduler.class);
    private static final Path HEARTBEAT_PATH = Path.of("/tmp/worker-heartbeat");

    private final PhotoJobConsumer photoJobConsumer;
    private final DeleteJobConsumer deleteJobConsumer;

    public ConsumerScheduler(PhotoJobConsumer photoJobConsumer,
                             DeleteJobConsumer deleteJobConsumer) {
        this.photoJobConsumer  = photoJobConsumer;
        this.deleteJobConsumer = deleteJobConsumer;
    }

    /**
     * Drives the photo-jobs consumer poll loop.
     *
     * <p>Default delay: 100 ms. Override with {@code worker.streams.photo-poll-delay-ms}.
     */
    @Scheduled(fixedDelayString = "${worker.streams.photo-poll-delay-ms:100}")
    public void pollPhotoJobs() {
        photoJobConsumer.pollOnce();
    }

    /**
     * Drives the delete-jobs consumer poll loop.
     *
     * <p>Default delay: 100 ms. Override with {@code worker.streams.delete-poll-delay-ms}.
     */
    @Scheduled(fixedDelayString = "${worker.streams.delete-poll-delay-ms:100}")
    public void pollDeleteJobs() {
        deleteJobConsumer.pollOnce();
    }

    /**
     * Runs XAUTOCLAIM on the photo-jobs stream to reclaim messages idle longer
     * than {@code claim-idle-time-ms} (default 30 minutes).
     *
     * <p>Default interval: every 5 minutes. Override with
     * {@code worker.streams.autoclaim-interval-ms}.
     */
    @Scheduled(fixedDelayString = "${worker.streams.autoclaim-interval-ms:300000}")
    public void reclaimIdlePhotoJobs() {
        photoJobConsumer.reclaimIdleMessages();
    }

    /**
     * Touches {@code /tmp/worker-heartbeat} every 30 seconds so the Docker
     * healthcheck ({@code find /tmp/worker-heartbeat -mmin -1 | grep -q .})
     * reports the container as healthy.
     *
     * <p>The worker container mounts {@code /tmp} as a tmpfs volume with
     * {@code read_only: true} on the root filesystem, so this write is permitted.
     */
    @Scheduled(fixedDelay = 30_000)
    public void writeHeartbeat() {
        try {
            Files.write(HEARTBEAT_PATH, new byte[0],
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to write worker heartbeat file", e);
        }
    }
}
