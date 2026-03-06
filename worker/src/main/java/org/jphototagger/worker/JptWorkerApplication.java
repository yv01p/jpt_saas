package org.jphototagger.worker;

import org.jphototagger.worker.config.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableConfigurationProperties(WorkerProperties.class)
@EntityScan(basePackages = {"org.jphototagger.api.entity", "org.jphototagger.worker"})
@EnableJpaRepositories(basePackages = {"org.jphototagger.api.repository", "org.jphototagger.worker"})
public class JptWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(JptWorkerApplication.class, args);
    }
}
