package org.jphototagger.api;

import org.jphototagger.api.config.TestRedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class JptSaasApplicationTest {
    @Test
    void contextLoads() {
    }
}
