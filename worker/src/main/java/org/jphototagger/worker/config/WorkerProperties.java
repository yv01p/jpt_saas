package org.jphototagger.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the worker service, bound from {@code worker.*} in application.yml.
 */
@ConfigurationProperties(prefix = "worker")
public class WorkerProperties {

    private final Streams streams = new Streams();
    private final Process process = new Process();

    public Streams getStreams() { return streams; }
    public Process getProcess() { return process; }

    public static class Streams {
        /** Milliseconds before an idle stream entry is claimed for redelivery. */
        private long claimIdleTimeMs = 1800000;
        /** Maximum delivery attempts before dead-lettering. */
        private int maxRetries = 3;
        /** Fixed delay in milliseconds between photo-jobs poll iterations. */
        private long photoPollDelayMs = 100;
        /** Fixed delay in milliseconds between delete-jobs poll iterations. */
        private long deletePollDelayMs = 100;
        /** Fixed delay in milliseconds between XAUTOCLAIM sweeps. */
        private long autoclaimIntervalMs = 300_000;

        public long getClaimIdleTimeMs() { return claimIdleTimeMs; }
        public void setClaimIdleTimeMs(long claimIdleTimeMs) { this.claimIdleTimeMs = claimIdleTimeMs; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public long getPhotoPollDelayMs() { return photoPollDelayMs; }
        public void setPhotoPollDelayMs(long photoPollDelayMs) { this.photoPollDelayMs = photoPollDelayMs; }

        public long getDeletePollDelayMs() { return deletePollDelayMs; }
        public void setDeletePollDelayMs(long deletePollDelayMs) { this.deletePollDelayMs = deletePollDelayMs; }

        public long getAutoclaimIntervalMs() { return autoclaimIntervalMs; }
        public void setAutoclaimIntervalMs(long autoclaimIntervalMs) { this.autoclaimIntervalMs = autoclaimIntervalMs; }
    }

    public static class Process {
        /** Per-tool ProcessBuilder timeout in minutes. */
        private int timeoutMinutes = 5;

        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
    }
}
