plugins {
    id("jpt.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

sourceSets {
    main {
        resources {
            // Override convention plugin's restrictive include filter
            // to allow .yml and .sql files from src/main/resources
            include("**/*")
        }
    }
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}

// Re-enable the plain jar so the worker module can consume api's entities/repositories
tasks.jar {
    enabled = true
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation(libs.minio)
    implementation(libs.tika.core)
    implementation(libs.jsoup)

    // Security & OAuth2
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // JWT
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // Rate limiting (Lettuce — Spring Boot default Redis client)
    implementation(libs.bucket4j.lettuce)

    // Distributed scheduler lock
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.6.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-redis-spring:6.6.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    // testcontainers:redis is not used — GenericContainer (from :testcontainers) is used instead
}
