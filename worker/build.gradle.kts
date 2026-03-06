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

dependencies {
    implementation(project(":domain"))
    implementation(project(":metadata"))
    implementation(project(":shared"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    implementation(project(":api"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation(libs.minio)
    implementation(libs.tika.core)
    implementation(libs.metadata.extractor)
    implementation(libs.jsoup)
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
