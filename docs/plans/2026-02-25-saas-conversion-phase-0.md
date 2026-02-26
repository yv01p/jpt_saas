# JPhotoTagger SaaS Conversion — Phase 0: Java Upgrade & Gradle Migration

> **Version:** 1.5
> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate existing JPhotoTagger modules to a Gradle 8 multi-module build and compile/test on Java 21. This phase covers the core modules needed for compilation; desktop-only modules are deferred.

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3.4.x, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

### Prerequisites

- **Java 21 JDK** (e.g., Eclipse Temurin 21 LTS)
- **Gradle 8.8+** (only for initial wrapper bootstrap; `./gradlew` is self-contained after that)

Install:
```bash
sudo apt install -y temurin-21-jdk   # or: sdk install java 21.0.x-tem
sdk install gradle 8.8               # or: brew install gradle
```

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
- Create: `shared/src/main/java/org/jphototagger/shared/package-info.java`
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
jakarta-xml-bind = "4.0.2"
jaxb-runtime = "4.0.5"
testcontainers = "1.19.7"
junit-jupiter = "5.10.2"

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
jakarta-xml-bind-api = { module = "jakarta.xml.bind:jakarta.xml.bind-api", version.ref = "jakarta-xml-bind" }
jaxb-runtime = { module = "org.glassfish.jaxb:jaxb-runtime", version.ref = "jaxb-runtime" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit-jupiter" }
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }

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

sourceSets {
    main {
        resources.srcDirs("src/main/java")
        resources.include("**/*.properties", "**/*.xml")
        // After each module migration, verify no other resource types are co-located:
        // find <module>/src/main/java -type f ! -name '*.java' ! -name '*.properties' ! -name '*.xml'
        // Add any discovered extensions to this include list.
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    // Version catalog (libs) is not accessible in buildSrc convention plugins — keep in sync with libs.versions.toml
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
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
    implementation(libs.jakarta.xml.bind.api)
    runtimeOnly(libs.jaxb.runtime)
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
    annotationProcessor(libs.netbeans.lookup)
    implementation(libs.jakarta.xml.bind.api)
    runtimeOnly(libs.jaxb.runtime)
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
    implementation(libs.netbeans.lookup)
    annotationProcessor(libs.netbeans.lookup)
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
    implementation(libs.metadata.extractor)
    implementation(libs.netbeans.lookup)
    annotationProcessor(libs.netbeans.lookup)
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
    implementation(project(":metadata"))
    implementation(project(":image"))
    implementation(libs.netbeans.lookup)
    annotationProcessor(libs.netbeans.lookup)
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

**Step 13a: Create `shared/src/main/java/org/jphototagger/shared/package-info.java`**

```java
/**
 * Shared DTOs for server and worker modules.
 */
package org.jphototagger.shared;
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
    // Testcontainers deferred to Phase 1a when server tests are written
}

// No main class in Phase 0 — disable bootJar to prevent build failure
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
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

// No main class in Phase 0 — disable bootJar to prevent build failure
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
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
git add settings.gradle.kts build.gradle.kts buildSrc/ gradle/ */build.gradle.kts gradlew gradlew.bat .gitignore shared/
git commit -m "build: migrate from Ant to Gradle 8 multi-module project with convention plugins"
```

### Task 0.2: Migrate Lib Module Sources to Gradle Layout

**Files:**
- Move: `Lib/src/org/...` → `lib/src/main/java/org/...`
- Move: `Lib/test/org/...` → `lib/src/test/java/org/...`

**Step 1: Create Gradle-standard directory structure and copy sources**

> **Note (applies to all module migrations 0.2–0.7):** Before copying test sources, verify the test directory layout with `ls`. If a module uses `test/java/org/` instead of `test/org/`, adjust the copy path accordingly to avoid placing sources in the wrong location.

```bash
mkdir -p lib/src/main/java lib/src/test/java
cp -r Lib/src/org lib/src/main/java/
[ -d Lib/test ] && cp -r Lib/test/org lib/src/test/java/
```

**Step 2: Migrate JAXB imports from `javax.xml.bind` to `jakarta.xml.bind`**

Find and replace all `javax.xml.bind` imports in `lib/src/`:
```bash
find lib/src -name "*.java" -exec grep -l "javax.xml.bind" {} \; | xargs sed -i 's/javax\.xml\.bind/jakarta.xml.bind/g'
```

**Step 3: Verify lib module compiles**

Run: `./gradlew :lib:compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Fix any Java 21 compilation errors in lib**

Common issues: `sun.*` imports, removed APIs, deprecated methods. Fix each error one at a time.

**Step 5: Run lib tests**

Run: `./gradlew :lib:test`
Expected: All tests pass

**Step 6: Commit**

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

**Step 2: Migrate JAXB imports from `javax.xml.bind` to `jakarta.xml.bind`**

```bash
find domain/src -name "*.java" -exec grep -l "javax.xml.bind" {} \; | xargs sed -i 's/javax\.xml\.bind/jakarta.xml.bind/g'
```

**Step 3: Verify domain module compiles and `@ServiceProvider` annotation processing works**

Run: `./gradlew :domain:compileJava`
Expected: BUILD SUCCESSFUL

Verify `META-INF/services` files are generated: `find domain/build -path '*/META-INF/services/*' -print`. If empty, investigate NetBeans annotation processor compatibility with Java 21 and Gradle's annotation processing model.

**Step 4: Fix any Java 21 compilation errors in domain**

Common issues: `sun.*` imports, removed APIs, deprecated methods. Fix each error one at a time.

**Step 5: Run domain tests**

Run: `./gradlew :domain:test`
Expected: All tests pass

**Step 6: Commit**

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
# Also copy test sources
[ -d Exif/test ] && rsync -a Exif/test/org/ metadata/src/test/java/org/
[ -d Iptc/test ] && rsync -a Iptc/test/org/ metadata/src/test/java/org/
[ -d XMP/test ] && rsync -a XMP/test/org/ metadata/src/test/java/org/
# Verify: total files in sources == total files in target
```

**Step 2: Migrate JAXB imports from `javax.xml.bind` to `jakarta.xml.bind`**

```bash
find metadata/src -name "*.java" -exec grep -l "javax.xml.bind" {} \; | xargs sed -i 's/javax\.xml\.bind/jakarta.xml.bind/g'
```

**Step 3: Verify metadata module compiles**

Run: `./gradlew :metadata:compileJava`
Expected: BUILD SUCCESSFUL (may need dependency fixes)

**Step 4: Verify bundled metadata-extractor version compatibility**

Check the version of `Libraries/metadata-extractor.jar` (e.g., `unzip -p Libraries/metadata-extractor.jar META-INF/MANIFEST.MF | grep Implementation-Version`). If it differs from 2.19.0, review the metadata-extractor changelog for API changes that may affect `MetaDataExtractorExifMetadataReader` and other existing consumers. Fix any incompatibilities.

**Step 5: Fix Java 21 compilation errors and update library versions**

Update metadata-extractor and xmpcore to current versions. Fix any deprecation or API changes.

**Step 6: Run metadata tests**

Run: `./gradlew :metadata:test`
Expected: All tests pass

**Step 7: Commit**

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

**Step 2: Verify HSQLDB dependency scope**

Check for compile-time HSQLDB references: `grep -r "org.hsqldb" Repositories/HSQLDB/src/`. If any files import `org.hsqldb.*`, change `runtimeOnly(libs.hsqldb)` to `implementation(libs.hsqldb)` in `repositories/build.gradle.kts`.

**Step 3: Verify compilation**

Run: `./gradlew :repositories:compileJava`

**Step 4: Fix compilation errors**

The HSQLDB-specific code will later be replaced with Spring Data JPA / PostgreSQL. For now just get it compiling on Java 21.

**Step 5: Commit**

```bash
git add repositories/
git commit -m "build: migrate Repositories module to Gradle layout, Java 21"
```

### Task 0.8: Replace Imagero (ImgrRdr.jar) with metadata-extractor

18 files across domain, metadata, and image modules import `com.imagero.reader.*`. Imagero is a commercial library with unknown SaaS licensing. metadata-extractor (Apache 2.0) is already a dependency and can replace all Imagero functionality.

> **Note:** Compilation gates in each sub-task verify API compatibility only. Behavioral parity (correct metadata values extracted from real images) will be validated in Phase 1 with integration tests against sample images.

**Key API mappings (Imagero → metadata-extractor):**

```
Imagero API                              → metadata-extractor equivalent
─────────────────────────────────────────────────────────────────────────
MetadataUtils.getIPTC(reader)            → ImageMetadataReader.readMetadata(file).getFirstDirectoryOfType(IptcDirectory.class)
IPTCEntryMeta.OBJECT_NAME               → IptcDirectory.TAG_OBJECT_NAME (0x0005)
IPTCEntryMeta.BYLINE                    → IptcDirectory.TAG_BY_LINE (0x0050)
IFDEntry.getValueAsString()             → ExifSubIFDDirectory.getString(tagType)
ImageReader / JpegReader / TiffReader   → ImageMetadataReader.readMetadata(File) (stateless, no close needed)
Imagero maker note classes              → NikonType2MakernoteDirectory (+ other brand directories)
Imagero thumbnail extraction            → ExifThumbnailDirectory.getThumbnailData() + ImageIO
```

Note: Imagero readers are closeable streams; metadata-extractor is stateless (`readMetadata(File)`). Imagero may return raw byte arrays where metadata-extractor returns decoded strings — verify at each call site.

**Sub-task 0.8.1: Create `IptcField` enum in domain module**

Create `domain/src/main/java/org/jphototagger/domain/metadata/iptc/IptcField.java` — an enum with 21 constants mapping to IPTC dataset numbers (e.g., `OBJECT_NAME(5)`, `BYLINE(80)`, `CAPTION_ABSTRACT(120)`). This replaces the Imagero `IPTCEntryMeta` enum that domain files currently reference. The enum is self-contained with no external dependencies.

Include accessor methods:
- `int getDatasetNumber()` — returns the IPTC dataset number for programmatic reading
- `static IptcField fromDatasetNumber(int datasetNumber)` — reverse lookup from metadata-extractor results (throw `IllegalArgumentException` for unknown dataset numbers)

**Sub-task 0.8.2: Migrate domain files from `IPTCEntryMeta` → `IptcField`**

*Depends on: 0.8.1*

Mechanical find-replace across 7 domain files: change `import com.imagero.reader.iptc.IPTCEntryMeta` → `import org.jphototagger.domain.metadata.iptc.IptcField` and update all type references from `IPTCEntryMeta` to `IptcField`. Verify: `./gradlew :domain:compileJava`

**Sub-task 0.8.3: Rewrite IPTC metadata readers to use metadata-extractor**

*Depends on: 0.8.1*

Rewrite `IptcMetadata.java` and `IptcEntry.java` in the metadata module to use metadata-extractor's `IptcDirectory` instead of Imagero's `MetadataUtils.getIPTC()`. Before removing the Imagero import from `XmpMetadata.java`, check whether it's an unused import or a functional dependency — if functional, rewrite the dependent code path to use metadata-extractor/xmpcore equivalents. Verify: `./gradlew :metadata:compileJava`

**Sub-task 0.8.4: Consolidate EXIF readers onto metadata-extractor**

Expand the existing `MetaDataExtractorExifMetadataReader` to handle all image formats. Delete `ImageroExifMetadataReader`. Before removing the `IFDEntry` constructor from `ExifTag`, verify no other callers exist: `grep -r 'new ExifTag.*IFDEntry\|ExifTag(.*IFDEntry' --include='*.java'`. If only `Program/` uses it, note the breakage (out of scope). Remove the constructor. Check for and update any `META-INF/services` registration files that reference `ImageroExifMetadataReader`. Verify: `./gradlew :metadata:compileJava`

**Sub-task 0.8.5: Rewrite `NikonMakerNotes` to use metadata-extractor**

Replace Imagero's Nikon maker note parsing with metadata-extractor's native `NikonType2MakernoteDirectory` support. Verify: `./gradlew :metadata:compileJava`

**Sub-task 0.8.6: Replace Imagero thumbnail APIs**

Before rewriting, read `DcrawThumbnailCreator` to determine which code paths use Imagero vs. the external `dcraw` tool — only replace the Imagero-dependent paths. Rewrite `ThumbnailUtil` and `DcrawThumbnailCreator` in the image module to use metadata-extractor's `ExifThumbnailDirectory` + `javax.imageio.ImageIO` instead of Imagero's thumbnail extraction. Verify: `./gradlew :image:compileJava`

**Sub-task 0.8.7: Remove ImgrRdr.jar and verify clean build**

Delete `Libraries/ImgrRdr.jar`. Verify no remaining references: `grep -r "ImgrRdr\|imagero" --include="*.kts" --include="*.gradle" --include="*.java"`. Run: `./gradlew compileJava`. Expected: BUILD SUCCESSFUL with zero Imagero references.

> **Note:** `Program/` sources still reference Imagero and will not compile after this deletion. This is expected — `Program/` is out of Phase 0 scope and will be migrated if/when needed.

**Commit:**

```bash
git add domain/src/ metadata/src/ image/src/ Libraries/
git commit -m "refactor: replace Imagero (ImgrRdr.jar) with metadata-extractor (Apache 2.0)"
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

> **Phase 1a reminder:** Add Testcontainers dependencies to `server/build.gradle.kts` when the first server test is written:
> ```kotlin
> testImplementation("org.testcontainers:junit-jupiter")
> testImplementation("org.testcontainers:postgresql")
> testImplementation("org.testcontainers:minio")
> ```
> Also: Phase 1a currently references `api/` — rename to `server/` to match Phase 0 naming.
> Also: Add Testcontainers BOM to `worker/build.gradle.kts`: `testImplementation(platform("org.testcontainers:testcontainers-bom:1.19.7"))` — currently missing, risking version conflicts.

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| 1.5 | 2026-02-25 | Applied Critical Implementation Review v4. Key changes: (1) Added `annotationProcessor(libs.netbeans.lookup)` to domain, metadata, image, and repositories modules — without this, `@ServiceProvider` annotations silently fail to generate `META-INF/services` files. (2) Added test directory layout verification note to Task 0.2 (applies to 0.2–0.7). (3) Added `ExifTag` `IFDEntry` constructor caller verification to sub-task 0.8.4. (4) Added `DcrawThumbnailCreator` Imagero vs. dcraw code path analysis note to sub-task 0.8.6. (5) Added co-located resource type verification comment to convention plugin. (6) Added Testcontainers version and BOM to version catalog for Phase 1a consistency. (7) Added `META-INF/services` generation verification step to Task 0.4 domain compilation. (8) Added `XmpMetadata.java` functional dependency check to sub-task 0.8.3. |
| 1.4 | 2026-02-25 | Applied Critical Implementation Review v3. Key changes: (1) Added Prerequisites section (Java 21 JDK, Gradle 8.8+). (2) Added `libs` version catalog comment in convention plugin — accessor not available in buildSrc. (3) Disabled `bootJar` in server and worker modules — no main classes in Phase 0. (4) Added API mapping table and behavioral parity note to Task 0.8. (5) Added `getDatasetNumber()` and `fromDatasetNumber(int)` to IptcField enum spec. (6) Added dependency annotations to sub-tasks 0.8.2 and 0.8.3. (7) Added META-INF/services check to sub-task 0.8.4. (8) Added metadata-extractor version verification step to Task 0.5. (9) Added HSQLDB import verification step to Task 0.7. (10) Replaced `git add -A` with targeted staging in Task 0.8. (11) Added Program/ breakage note to sub-task 0.8.7. (12) Added Testcontainers BOM reminder for Phase 1a. |
| 1.0 | 2026-02-25 | Initial plan |
| 1.1 | 2026-02-25 | Applied Critical Implementation Review v1. Key changes: (1) Added `lib` and `jpt-api` module migration tasks — Domain and Repositories depend on them. (2) Fixed Domain deps: requires `:lib`, `:jpt-api`, NetBeans Lookup (kept for Phase 0 compat, replaced by Spring DI in Phase 1). (3) Fixed Repositories deps: requires `:domain`, `:lib`, `:jpt-api`, HSQLDB as runtimeOnly. (4) Added `image` module migration task. (5) Renamed Spring Boot API module from `api/` to `server/` to avoid collision with existing `API/` directory. Renamed existing API interfaces module to `jpt-api/`. (6) Added Gradle wrapper bootstrap step. (7) Replaced `subprojects {}` block with convention plugin (`buildSrc/jpt.java-conventions.gradle.kts`). (8) Added `gradle/libs.versions.toml` version catalog; updated Spring Boot from 3.3.0 to 3.4.2. (9) Switched metadata merge from `cp -r` to `rsync -a` for safety. (10) Simplified ImgRdr audit task. (11) Added `.gitignore` entries for `.gradle/` and `build/`. (12) Narrowed scope: explicitly listed in-scope vs out-of-scope modules; changed goal from "full existing test suite" to "all migrated modules compile and pass their own tests." |
| 1.3 | 2026-02-25 | Added Task 0.8: Replace Imagero (ImgrRdr.jar) with metadata-extractor (Apache 2.0). Removed ImgrRdr.jar file dependency from domain, metadata, and image build.gradle.kts definitions — Imagero is now fully replaced in Phase 0 rather than carried as tech debt. Added `implementation(libs.metadata.extractor)` to image module for thumbnail extraction. Removed ImgRdr licensing tech debt note. Renumbered old Task 0.8 (Validation) → Task 0.9. |
| 1.2 | 2026-02-25 | Applied Critical Implementation Review v2. Key changes: (1) Added Jakarta JAXB dependencies (`jakarta.xml.bind-api` 4.0.2, `jaxb-runtime` 4.0.5) to version catalog; added JAXB migration steps (`javax.xml.bind` → `jakarta.xml.bind`) to Tasks 0.2, 0.4, 0.5 — 104 usages across 31 files would fail on Java 21 without this. (2) Added `ImgrRdr.jar` as file dependency to domain, metadata, and image `build.gradle.kts` — 18 files across these modules directly import `com.imagero.reader.*`; documented as tech debt with licensing concern for SaaS use. Removed standalone Task 0.8 (ImgRdr audit) since dependency is now declared upfront. (3) Added `:metadata` and `:image` to `repositories/build.gradle.kts` — 18 repository files import from exif/iptc/xmp/image packages. (4) Added `sourceSets.main.resources.srcDirs("src/main/java")` to convention plugin to pick up 80+ `.properties` files co-located with Java sources. (5) Added JUnit 5 (`junit-jupiter` 5.10.2) to version catalog and convention plugin — `useJUnitPlatform()` was set but no test dependency was declared. (6) Added `package-info.java` placeholder to `shared` module. (7) Removed Testcontainers from `server/build.gradle.kts` (no tests in Phase 0); added reminder note for Phase 1a. (8) Renumbered Task 0.9 → 0.8 after removing ImgRdr audit task. (9) Added Phase 1a reminders: re-add Testcontainers deps, rename `api/` → `server/`. |
