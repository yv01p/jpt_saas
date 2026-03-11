package org.jphototagger.worker.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ConsumerScheduler.
 */
@ExtendWith(MockitoExtension.class)
class ConsumerSchedulerTest {

    @Mock private PhotoJobConsumer photoJobConsumer;
    @Mock private DeleteJobConsumer deleteJobConsumer;

    private ConsumerScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ConsumerScheduler(photoJobConsumer, deleteJobConsumer);
    }

    @Test
    void writeHeartbeat_createsHeartbeatFileAtTmpPath() throws IOException {
        // The Docker healthcheck runs:
        //   find /tmp/worker-heartbeat -mmin -1 | grep -q .
        // This passes only if /tmp/worker-heartbeat was modified within the last 1 minute.
        // writeHeartbeat() must create or touch that file so the container is not
        // permanently marked unhealthy after start_period expires.
        Path heartbeat = Path.of("/tmp/worker-heartbeat");
        Files.deleteIfExists(heartbeat);

        Instant before = Instant.now().minusMillis(500);
        scheduler.writeHeartbeat();

        assertThat(heartbeat).exists();
        Instant lastModified = Files.getLastModifiedTime(heartbeat).toInstant();
        assertThat(lastModified).isAfterOrEqualTo(before);
    }
}
