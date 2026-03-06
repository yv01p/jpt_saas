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

        public long getClaimIdleTimeMs() { return claimIdleTimeMs; }
        public void setClaimIdleTimeMs(long claimIdleTimeMs) { this.claimIdleTimeMs = claimIdleTimeMs; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class Process {
        /** Per-tool ProcessBuilder timeout in minutes. */
        private int timeoutMinutes = 5;

        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
    }
}
