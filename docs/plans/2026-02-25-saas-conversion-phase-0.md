# JPhotoTagger SaaS Conversion — Phase 0: Java Upgrade & Gradle Migration

> **Version:** 1.1
> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate existing JPhotoTagger modules to a Gradle 8 multi-module build and compile/test on Java 21. This phase covers the core modules needed for compilation; desktop-only modules are deferred.

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3.4.x, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

> **Prerequisite:** All migrated modules must compile and pass their own tests on Java 21 before Phase 1 begins.

**Modules in scope for Phase 0:**
- `lib` (from `Lib/`) — 214 files, depended on by nearly everything
- `jpt-api` (from `API/`) — 73 files, core interfaces
- `domain` (from `Domain/`) — 236 files
- `metadata` (from `Exif/`, `Iptc/`, `XMP/`) — 102 files consolidated
- `repositories` (from `Repositories/HSQLDB/`) — 73 files
- `image` (from `Image/`) — 15 files
- `shared` — new, shared DTOs placeholder
- `server` — new, Spring Boot REST API stub
- `worker` — new, worker container stub

**Modules explicitly out of scope for Phase 0** (desktop-only, no SaaS consumers):
- `Program/` (421 files — main desktop entry point, Swing wiring)
- `Modules/*` (11 sub-modules: FindDuplicates, ImportFiles, Synonyms, etc.)
- `Plugins/`, `ExportersImporters/`, `UserServices/`
- `KML/`, `LookAndFeels/`, `Localization/`, `Resources/`

These will be addressed if/when SaaS phases require their functionality.

---

### Task 0.1: Create Gradle Multi-Module Build

**Files:**
- Create: `gradle/libs.versions.toml` (version catalog)
- Create: `buildSrc/src/main/kotlin/jpt.java-conventions.gradle.kts` (convention plugin)
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `lib/build.gradle.kts`
- Create: `jpt-api/build.gradle.kts`
- Create: `domain/build.gradle.kts`
- Create: `metadata/build.gradle.kts`
- Create: `repositories/build.gradle.kts`
- Create: `image/build.gradle.kts`
- Create: `shared/build.gradle.kts`
- Create: `server/build.gradle.kts`
- Create: `worker/build.gradle.kts`
- Update: `.gitignore`

**Step 1: Bootstrap Gradle wrapper**

Requires Gradle installed on the system.

```bash
gradle wrapper --gradle-version 8.8
```

**Step 2: Create `gradle/libs.versions.toml` (version catalog)**

```toml
[versions]
spring-boot = "3.4.2"
spring-dependency-management = "1.1.7"
metadata-extractor = "2.19.0"
xmpcore = "6.1.11"
hsqldb = "2.7.2"
minio = "8.5.9"
tika = "2.9.2"
jjwt = "0.12.5"
bucket4j = "8.10.1"
netbeans-lookup = "RELEASE220"

[libraries]
metadata-extractor = { module = "com.drewnoakes:metadata-extractor", version.ref = "metadata-extractor" }
xmpcore = { module = "com.adobe.xmp:xmpcore", version.ref = "xmpcore" }
hsqldb = { module = "org.hsqldb:hsqldb", version.ref = "hsqldb" }
minio = { module = "io.minio:minio", version.ref = "minio" }
tika-core = { module = "org.apache.tika:tika-core", version.ref = "tika" }
jjwt-api = { module = "io.jsonwebtoken:jjwt-api", version.ref = "jjwt" }
jjwt-impl = { module = "io.jsonwebtoken:jjwt-impl", version.ref = "jjwt" }
jjwt-jackson = { module = "io.jsonwebtoken:jjwt-jackson", version.ref = "jjwt" }
bucket4j-redis = { module = "com.bucket4j:bucket4j-redis", version.ref = "bucket4j" }
netbeans-lookup = { module = "org.netbeans.api:org-openide-util-lookup", version.ref = "netbeans-lookup" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "spring-dependency-management" }
```

**Step 3: Create `buildSrc/src/main/kotlin/jpt.java-conventions.gradle.kts` (convention plugin)**

```kotlin
plugins {
    java
}

group = "org.jphototagger"
version = "2.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

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
```

**Step 4: Create `buildSrc/build.gradle.kts`**

```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}
```

**Step 5: Create root `settings.gradle.kts`**

```kotlin
rootProject.name = "jpt-saas"

include("lib", "jpt-api", "domain", "metadata", "image", "repositories", "shared", "server", "worker")
```

**Step 6: Create root `build.gradle.kts`**

```kotlin
// Root project — convention plugin applied via buildSrc
```

**Step 7: Create `lib/build.gradle.kts`**

```kotlin
plugins {
    id("jpt.java-conventions")
}

dependencies {
    // Lib has mostly JDK-only deps (Swing utilities, IO, etc.)
}
```

**Step 8: Create `jpt-api/build.gradle.kts`**

```kotlin
plugins {
    id("jpt.java-conventions")
}

dependencies {
    // Core interfaces — minimal deps
}
```

**Step 9: Create `domain/build.gradle.kts`**

```kotlin
plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    implementation(libs.netbeans.lookup)
}
```

**Step 10: Create `metadata/build.gradle.kts`**

Consolidate `Exif/`, `Iptc/`, `XMP/` into single `metadata` module.

```kotlin
plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    implementation(libs.metadata.extractor)
    implementation(libs.xmpcore)
}
```

**Step 11: Create `image/build.gradle.kts`**

```kotlin
plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
}
```

**Step 12: Create `repositories/build.gradle.kts`**

```kotlin
plugins {
    id("jpt.java-conventions")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))
    runtimeOnly(libs.hsqldb)
}
```

**Step 13: Create `shared/build.gradle.kts`**

```kotlin
plugins {
    id("jpt.java-conventions")
}

dependencies {
    // Shared DTOs — no heavy deps
}
```

**Step 14: Create `server/build.gradle.kts` (Spring Boot stub)**

```kotlin
plugins {
    id("jpt.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":metadata"))
    implementation(project(":shared"))
    implementation(project(":repositories"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))

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
    implementation(libs.minio)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    implementation(libs.bucket4j.redis)
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:minio")
}
```

**Step 15: Create `worker/build.gradle.kts`**

```kotlin
plugins {
    id("jpt.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":metadata"))
    implementation(project(":shared"))
    implementation(project(":lib"))
    implementation(project(":jpt-api"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation(libs.minio)
    implementation(libs.tika.core)
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
```

**Step 16: Update `.gitignore` for Gradle**

Add to `.gitignore`:
```
.gradle/
build/
```

**Step 17: Verify Gradle wrapper and build compiles**

Run: `./gradlew build --dry-run`
Expected: BUILD SUCCESSFUL (task graph resolves)

**Step 18: Commit**

```bash
git add settings.gradle.kts build.gradle.kts buildSrc/ gradle/ */build.gradle.kts gradlew gradlew.bat .gitignore
git commit -m "build: migrate from Ant to Gradle 8 multi-module project with convention plugins"
```

### Task 0.2: Migrate Lib Module Sources to Gradle Layout

**Files:**
- Move: `Lib/src/org/...` → `lib/src/main/java/org/...`
- Move: `Lib/test/org/...` → `lib/src/test/java/org/...`

**Step 1: Create Gradle-standard directory structure and copy sources**

```bash
mkdir -p lib/src/main/java lib/src/test/java
cp -r Lib/src/org lib/src/main/java/
[ -d Lib/test ] && cp -r Lib/test/org lib/src/test/java/
```

**Step 2: Verify lib module compiles**

Run: `./gradlew :lib:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Fix any Java 21 compilation errors in lib**

Common issues: `sun.*` imports, removed APIs, deprecated methods. Fix each error one at a time.

**Step 4: Run lib tests**

Run: `./gradlew :lib:test`
Expected: All tests pass

**Step 5: Commit**

```bash
git add lib/
git commit -m "build: migrate Lib module to Gradle layout, compile on Java 21"
```

### Task 0.3: Migrate API Module Sources to Gradle Layout

**Files:**
- Move: `API/src/org/...` → `jpt-api/src/main/java/org/...`
- Move: `API/test/org/...` → `jpt-api/src/test/java/org/...`

**Step 1: Create Gradle-standard directory structure and copy sources**

```bash
mkdir -p jpt-api/src/main/java jpt-api/src/test/java
cp -r API/src/org jpt-api/src/main/java/
[ -d API/test ] && cp -r API/test/org jpt-api/src/test/java/
```

**Step 2: Verify jpt-api module compiles**

Run: `./gradlew :jpt-api:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Fix any Java 21 compilation errors**

**Step 4: Run jpt-api tests**

Run: `./gradlew :jpt-api:test`
Expected: All tests pass

**Step 5: Commit**

```bash
git add jpt-api/
git commit -m "build: migrate API module to jpt-api Gradle layout, compile on Java 21"
```

### Task 0.4: Migrate Domain Module Sources to Gradle Layout

**Files:**
- Move: `Domain/src/org/...` → `domain/src/main/java/org/...`
- Move: `Domain/test/org/...` → `domain/src/test/java/org/...`

**Step 1: Create Gradle-standard directory structure and copy sources**

```bash
mkdir -p domain/src/main/java domain/src/test/java
cp -r Domain/src/org domain/src/main/java/
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

### Task 0.5: Migrate Metadata Modules (Exif + Iptc + XMP) to Gradle Layout

**Files:**
- Move: `Exif/src/org/...` → `metadata/src/main/java/org/jphototagger/exif/...`
- Move: `Iptc/src/org/...` → `metadata/src/main/java/org/jphototagger/iptc/...`
- Move: `XMP/src/org/...` → `metadata/src/main/java/org/jphototagger/xmp/...`

**Step 1: Copy sources into consolidated metadata module using rsync**

```bash
mkdir -p metadata/src/main/java metadata/src/test/java
rsync -a Exif/src/org/ metadata/src/main/java/org/
rsync -a Iptc/src/org/ metadata/src/main/java/org/
rsync -a XMP/src/org/ metadata/src/main/java/org/
# Verify: total files in sources == total files in target
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

### Task 0.6: Migrate Image Module to Gradle Layout

**Files:**
- Move: `Image/src/org/...` → `image/src/main/java/org/...`
- Move: `Image/test/org/...` → `image/src/test/java/org/...`

**Step 1: Copy sources**

```bash
mkdir -p image/src/main/java image/src/test/java
cp -r Image/src/org image/src/main/java/
[ -d Image/test ] && cp -r Image/test/org image/src/test/java/
```

**Step 2: Verify compilation**

Run: `./gradlew :image:compileJava`

**Step 3: Fix compilation errors**

**Step 4: Run image tests**

Run: `./gradlew :image:test`

**Step 5: Commit**

```bash
git add image/
git commit -m "build: migrate Image module to Gradle layout, Java 21"
```

### Task 0.7: Migrate Repositories Module

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

### Task 0.8: Audit ImgRdr Library

**Step 1: Check if ImgRdr is used reflectively or via ImageIO SPI**

`Libraries/ImgrRdr.jar` exists on the classpath (via NetBeans project files for Domain, Program, Iptc) but has zero Java source imports. Check for reflective usage or SPI registration.

```bash
grep -r "ImgRdr\|imgrdr\|imagero" --include="*.java" --include="*.xml" --include="*.properties" .
```

**Step 2: If unused, remove from the build. If used, add as a file dependency.**

**Step 3: Commit**

```bash
git commit -m "build: resolve ImgRdr library dependency"
```

### Task 0.9: Migrated Module Test Suite Validation on Java 21

**Step 1: Run all migrated module tests**

Run: `./gradlew test`
Expected: All tests pass across lib, jpt-api, domain, metadata, image, repositories

**Step 2: Document any test failures and fixes**

**Step 3: Commit (tag as upgrade-complete)**

```bash
git commit -m "milestone: Java 21 + Gradle migration complete, all migrated module tests pass"
git tag v2.0.0-java21
```

---

**Next Phase:** [Phase 1a: Spring Boot Scaffold & Database](2026-02-25-saas-conversion-phase-1a.md)

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-02-25 | Initial plan |
| 1.1 | 2026-02-25 | Applied Critical Implementation Review v1. Key changes: (1) Added `lib` and `jpt-api` module migration tasks — Domain and Repositories depend on them. (2) Fixed Domain deps: requires `:lib`, `:jpt-api`, NetBeans Lookup (kept for Phase 0 compat, replaced by Spring DI in Phase 1). (3) Fixed Repositories deps: requires `:domain`, `:lib`, `:jpt-api`, HSQLDB as runtimeOnly. (4) Added `image` module migration task. (5) Renamed Spring Boot API module from `api/` to `server/` to avoid collision with existing `API/` directory. Renamed existing API interfaces module to `jpt-api/`. (6) Added Gradle wrapper bootstrap step. (7) Replaced `subprojects {}` block with convention plugin (`buildSrc/jpt.java-conventions.gradle.kts`). (8) Added `gradle/libs.versions.toml` version catalog; updated Spring Boot from 3.3.0 to 3.4.2. (9) Switched metadata merge from `cp -r` to `rsync -a` for safety. (10) Simplified ImgRdr audit task. (11) Added `.gitignore` entries for `.gradle/` and `build/`. (12) Narrowed scope: explicitly listed in-scope vs out-of-scope modules; changed goal from "full existing test suite" to "all migrated modules compile and pass their own tests." |
