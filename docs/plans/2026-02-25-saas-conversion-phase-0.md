# JPhotoTagger SaaS Conversion — Phase 0: Java Upgrade & Gradle Migration

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

> **Prerequisite:** The desktop app must pass its full existing test suite on Java 21 before Phase 1 begins.

### Task 0.1: Create Gradle Multi-Module Build

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `domain/build.gradle.kts`
- Create: `metadata/build.gradle.kts`
- Create: `repositories/build.gradle.kts`
- Create: `api/build.gradle.kts`
- Create: `worker/build.gradle.kts`
- Create: `shared/build.gradle.kts`

**Step 1: Create root `settings.gradle.kts`**

```kotlin
rootProject.name = "jpt-saas"

include("domain", "metadata", "repositories", "shared", "api", "worker")
```

**Step 2: Create root `build.gradle.kts`**

```kotlin
plugins {
    java
}

allprojects {
    group = "org.jphototagger"
    version = "2.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
```

**Step 3: Create `domain/build.gradle.kts`**

Map existing `Domain/` module. Move source files from `Domain/src/` to `domain/src/main/java/`.

```kotlin
dependencies {
    // Domain has no external deps beyond JDK
}
```

**Step 4: Create `metadata/build.gradle.kts`**

Consolidate `Exif/`, `Iptc/`, `XMP/` into single `metadata` module.

```kotlin
dependencies {
    implementation(project(":domain"))
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    implementation("com.adobe.xmp:xmpcore:6.1.11")
}
```

**Step 5: Create `shared/build.gradle.kts`**

```kotlin
dependencies {
    // Shared DTOs — no heavy deps
}
```

**Step 6: Create `api/build.gradle.kts` (Spring Boot stub)**

```kotlin
plugins {
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":metadata"))
    implementation(project(":shared"))
    implementation(project(":repositories"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("io.minio:minio:8.5.9")
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:minio")
}
```

**Step 7: Create `worker/build.gradle.kts`**

```kotlin
plugins {
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":metadata"))
    implementation(project(":shared"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("io.minio:minio:8.5.9")
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
```

**Step 8: Verify Gradle wrapper and build compiles**

Run: `./gradlew build --dry-run`
Expected: BUILD SUCCESSFUL (task graph resolves)

**Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts */build.gradle.kts gradlew gradlew.bat gradle/
git commit -m "build: migrate from Ant to Gradle 8 multi-module project"
```

### Task 0.2: Migrate Domain Module Sources to Gradle Layout

**Files:**
- Move: `Domain/src/org/...` → `domain/src/main/java/org/...`
- Move: `Domain/test/org/...` → `domain/src/test/java/org/...`

**Step 1: Create Gradle-standard directory structure and copy sources**

```bash
mkdir -p domain/src/main/java domain/src/test/java
cp -r Domain/src/org domain/src/main/java/
# Copy tests if they exist
[ -d Domain/test ] && cp -r Domain/test/org domain/src/test/java/
```

**Step 2: Verify domain module compiles**

Run: `./gradlew :domain:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Fix any Java 21 compilation errors in domain**

Common issues: `sun.*` imports, removed APIs, deprecated methods. Fix each error one at a time.

**Step 4: Run domain tests**

Run: `./gradlew :domain:test`
Expected: All tests pass

**Step 5: Commit**

```bash
git add domain/
git commit -m "build: migrate Domain module to Gradle layout, compile on Java 21"
```

### Task 0.3: Migrate Metadata Modules (Exif + Iptc + XMP) to Gradle Layout

**Files:**
- Move: `Exif/src/org/...` → `metadata/src/main/java/org/jphototagger/exif/...`
- Move: `Iptc/src/org/...` → `metadata/src/main/java/org/jphototagger/iptc/...`
- Move: `XMP/src/org/...` → `metadata/src/main/java/org/jphototagger/xmp/...`

**Step 1: Copy sources into consolidated metadata module**

```bash
mkdir -p metadata/src/main/java metadata/src/test/java
cp -r Exif/src/org metadata/src/main/java/
cp -r Iptc/src/org metadata/src/main/java/
cp -r XMP/src/org metadata/src/main/java/
```

**Step 2: Verify metadata module compiles**

Run: `./gradlew :metadata:compileJava`
Expected: BUILD SUCCESSFUL (may need dependency fixes)

**Step 3: Fix Java 21 compilation errors and update library versions**

Update metadata-extractor and xmpcore to current versions. Fix any deprecation or API changes.

**Step 4: Run metadata tests**

Run: `./gradlew :metadata:test`
Expected: All tests pass

**Step 5: Commit**

```bash
git add metadata/
git commit -m "build: consolidate Exif/Iptc/XMP into metadata module, Java 21"
```

### Task 0.4: Migrate Repositories Module

**Files:**
- Move: `Repositories/HSQLDB/src/org/...` → `repositories/src/main/java/org/...`

**Step 1: Copy repository sources**

```bash
mkdir -p repositories/src/main/java repositories/src/test/java
cp -r Repositories/HSQLDB/src/org repositories/src/main/java/
```

**Step 2: Verify compilation**

Run: `./gradlew :repositories:compileJava`

**Step 3: Fix compilation errors**

The HSQLDB-specific code will later be replaced with Spring Data JPA / PostgreSQL. For now just get it compiling on Java 21.

**Step 4: Commit**

```bash
git add repositories/
git commit -m "build: migrate Repositories module to Gradle layout, Java 21"
```

### Task 0.5: Audit ImgRdr Library

**Files:**
- Check: `Libraries/src/` and `Image/src/` for ImgRdr usage

**Step 1: Search for ImgRdr references**

```bash
grep -r "ImgRdr\|imgrdr" --include="*.java" .
```

**Step 2: Determine if ImgRdr is maintained**

Check Maven Central, GitHub. If unmaintained, identify replacement (TwelveMonkeys ImageIO is the likely candidate).

**Step 3: Replace if needed and verify image loading still works**

**Step 4: Commit**

```bash
git commit -m "build: replace ImgRdr with TwelveMonkeys ImageIO"
```

### Task 0.6: Full Test Suite Validation on Java 21

**Step 1: Run all module tests**

Run: `./gradlew test`
Expected: ALL tests pass

**Step 2: Verify the desktop app launches (manual smoke test)**

Run the `Program` module's main class. Verify basic functionality.

**Step 3: Document any test failures and fixes**

**Step 4: Commit (tag as upgrade-complete)**

```bash
git commit -m "milestone: Java 21 upgrade complete, all tests pass"
git tag v2.0.0-java21
```

---

**Next Phase:** [Phase 1a: Spring Boot Scaffold & Database](2026-02-25-saas-conversion-phase-1a.md)
