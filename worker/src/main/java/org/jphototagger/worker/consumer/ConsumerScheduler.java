package org.jphototagger.worker.consumer;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the Redis Streams consumer poll loops on a fixed-delay schedule.
 *
 * <p>Each consumer is single-threaded — one in-flight job at a time.
 * Spring's {@code @Scheduled} methods must be no-arg, so the consumers are
 * injected here and held as fields.
 *
 * <p>Fixed-delay (not fixed-rate) ensures sequential processing: the next
 * poll does not start until the previous one finishes.
 */
@Component
public class ConsumerScheduler {

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
}
