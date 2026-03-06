package org.jphototagger.worker;

import org.jphototagger.worker.config.TestRedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class JptWorkerApplicationTest {
    @Test
    void contextLoads() {
    }
}
