# Critical Implementation Review: Phase 0 — Java Upgrade & Gradle Migration (v2)

**Reviewer:** Senior Staff Engineer
**Date:** 2026-02-25
**Plan:** `2026-02-25-saas-conversion-phase-0.md` (v1.1)
**Previous Review:** v1 — most issues were addressed in plan v1.1. This review focuses on remaining and newly discovered issues.

---

## 1. Overall Assessment

Plan v1.1 addressed the major structural gaps from v1 (missing Lib/API/Image modules, naming collision, wrapper bootstrap, metadata merge strategy, convention plugins, version catalog). The plan is now well-scoped with explicit in-scope/out-of-scope boundaries and correct module ordering.

**Remaining concerns are concentrated in three areas:**
1. **JAXB migration** — 104 usages of `javax.xml.bind` across 31 files will break on Java 21 and are not mentioned anywhere in the plan.
2. **ImgRdr/Imagero dependency** — Task 0.8 understates the scope; 18 files across Domain, Exif, Iptc, XMP, and Image directly import `com.imagero.reader.*`.
3. **Repositories module has undeclared dependencies** on metadata and image modules.
4. **Resource files** (80+ `.properties` files) are not addressed in the migration steps.

---

## 2. Critical Issues

### 2.1 JAXB (`javax.xml.bind`) Removed in Java 11+ — Not Addressed

**Problem:** The codebase has **104 occurrences** of `javax.xml.bind.*` imports across **31 files** in Lib, Domain, Exif, and other modules. These APIs were removed from the JDK in Java 11 (JEP 320). On Java 21, all JAXB-using code will fail with `ClassNotFoundException`.

**Affected in-scope modules:** `lib`, `domain`, `metadata` (Exif)

**Impact:** Compilation will fail for lib, domain, and metadata modules. This is the single biggest Java 21 migration risk.

**Fix:**
1. Add Jakarta JAXB dependencies to `gradle/libs.versions.toml`:
   ```toml
   [versions]
   jakarta-xml-bind = "4.0.2"
   jaxb-runtime = "4.0.5"

   [libraries]
   jakarta-xml-bind-api = { module = "jakarta.xml.bind:jakarta.xml.bind-api", version.ref = "jakarta-xml-bind" }
   jaxb-runtime = { module = "org.glassfish.jaxb:jaxb-runtime", version.ref = "jaxb-runtime" }
   ```
2. Add a **dedicated migration step** in each affected module task: replace `javax.xml.bind.*` → `jakarta.xml.bind.*` imports, and add the JAXB dependencies to the module's `build.gradle.kts`.
3. Note: Spring Boot 3.x uses Jakarta namespace already, so this aligns with Phase 1+.

### 2.2 ImgRdr/Imagero Is NOT Unused — 18 Files Import It Directly

**Problem:** Task 0.8 states ImgRdr "has zero Java source imports" and frames the audit as checking for reflective/SPI usage. In reality, **18 files** across Domain, Exif, Iptc, XMP, Image, and Program directly import `com.imagero.reader.*`:

- `Domain/src/org/jphototagger/domain/metadata/xmp/Xmp.java`
- `Domain/src/org/jphototagger/domain/metadata/iptc/Iptc.java`
- `Domain/src/org/jphototagger/domain/metadata/mapping/` (4 files)
- `Exif/src/org/jphototagger/exif/ImageroExifMetadataReader.java`
- `Exif/src/org/jphototagger/exif/ExifTag.java`
- `Iptc/src/org/jphototagger/iptc/IptcEntry.java`, `IptcMetadata.java`
- `XMP/src/org/jphototagger/xmp/XmpMetadata.java`
- `Image/src/org/jphototagger/image/thumbnail/` (2 files)

**Impact:** The `domain`, `metadata`, and `image` modules will **not compile** without ImgRdr on the classpath.

**Fix:**
1. Add `ImgrRdr.jar` as a file dependency to the modules that need it. In the version catalog or directly:
   ```kotlin
   // In modules that import com.imagero.reader.*
   implementation(files("${rootProject.projectDir}/Libraries/ImgrRdr.jar"))
   ```
2. Task 0.8 should be rewritten: "Add ImgRdr as a file dependency to domain, metadata, and image modules. Verify compilation. Document as tech debt for later replacement with metadata-extractor or direct ImageIO."
3. Move Task 0.8 **before** Task 0.4 (Domain migration) since Domain depends on it.

### 2.3 Repositories Module Missing Dependencies on Metadata and Image

**Problem:** `repositories/build.gradle.kts` (Task 0.1, Step 12) declares dependencies on `:domain`, `:lib`, `:jpt-api`, and `hsqldb`. However, 18 files in `Repositories/HSQLDB/src/` import from:
- `org.jphototagger.exif.*`
- `org.jphototagger.iptc.*`
- `org.jphototagger.xmp.*`
- `org.jphototagger.image.*`

**Impact:** `./gradlew :repositories:compileJava` will fail.

**Fix:** Add to `repositories/build.gradle.kts`:
```kotlin
implementation(project(":metadata"))
implementation(project(":image"))
```
And update Task 0.7 to run **after** Tasks 0.5 and 0.6.

### 2.4 Resource Files (80+ .properties) Not Migrated

**Problem:** The migration steps only copy `src/org/...` Java sources. The codebase contains **80+ `.properties` files** (i18n bundles, EXIF tag translations, maker note mappings) co-located with Java sources in the same package directories. These are loaded at runtime via `ResourceBundle.getBundle()` or `Class.getResourceAsStream()`.

Gradle expects resources in `src/main/resources/` (separate from `src/main/java/`), OR they can remain alongside Java sources if `sourceSets.main.resources.srcDirs` includes the java source dir.

**Impact:** Tests and runtime will fail with `MissingResourceException` even though compilation succeeds.

**Fix:** Either:
- **(Recommended)** Keep `.properties` files alongside Java files and add to the convention plugin:
  ```kotlin
  sourceSets {
      main {
          resources.srcDirs("src/main/java")
          resources.include("**/*.properties", "**/*.xml")
      }
  }
  ```
- Or create a separate step in each migration task to move `.properties` files to `src/main/resources/` preserving the package directory structure.

---

## 3. Minor Issues & Improvements

### 3.1 Task Ordering Is Implicit

The plan presents tasks 0.1–0.9 sequentially, but the actual dependency graph is:
```
0.1 (Gradle setup) → 0.2 (lib) → 0.3 (jpt-api) → 0.4 (domain) → 0.5 (metadata) → 0.6 (image) → 0.7 (repositories) → 0.8 (ImgRdr) → 0.9 (validation)
```
Task 0.8 (ImgRdr) should actually be resolved **before** 0.4/0.5/0.6 since those modules import from it. Consider making this explicit.

### 3.2 `shared` Module Still Empty

The `shared` module is created in Phase 0 but has zero content. While minor, creating empty modules with no sources causes `compileJava` to succeed vacuously, which is misleading. Consider deferring to Phase 1a where it's first populated, or adding a single placeholder class.

### 3.3 Convention Plugin Missing JUnit 5 Test Dependency

The convention plugin sets `useJUnitPlatform()` but no module-level `build.gradle.kts` (for lib, jpt-api, domain, etc.) declares a JUnit 5 test dependency. Tests will fail to compile. Add to the convention plugin:
```kotlin
dependencies {
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
}
```

### 3.4 `image` Module Needs `lib` as a Dependency

The Image module imports `org.jphototagger.lib.io.IoUtil`. The `image/build.gradle.kts` already declares `implementation(project(":lib"))` — this is correct. However, it also needs the ImgRdr file dependency (see Critical Issue 2.2).

### 3.5 Testcontainers Dependency in `server` Is Premature

`server/build.gradle.kts` declares Testcontainers for PostgreSQL and MinIO, but there are no tests in Phase 0 for the server stub. These deps add build time. Consider deferring to Phase 1a.

### 3.6 `hsqldb` Should Be `implementation`, Not `runtimeOnly`

`repositories/build.gradle.kts` declares `runtimeOnly(libs.hsqldb)`. If any source files in Repositories import HSQLDB-specific classes (e.g., `org.hsqldb.jdbc.JDBCDataSource`), this will fail at compile time. Verify and adjust if needed.

---

## 4. Questions for Clarification

1. **JAXB migration strategy:** Should `javax.xml.bind` → `jakarta.xml.bind` conversion happen in Phase 0, or should a compatibility bridge (`javax` namespace JAXB 2.x as external dep) be used temporarily? The former is cleaner but touches 31 files; the latter is quicker but creates tech debt that Spring Boot 3 (Jakarta EE 9+) will force resolution of anyway.

2. **ImgRdr long-term plan:** Is `ImgrRdr.jar` a commercial/proprietary library? If so, is it licensed for SaaS use? This has legal implications beyond the build migration.

3. **Existing Ant tests:** Do the existing tests in `Lib/test/`, `Domain/test/`, `XMP/test/` currently pass on the system's installed JDK? If they haven't been run in a while, there may be pre-existing failures unrelated to the migration.

---

## 5. Final Recommendation

**Approve with changes.**

The plan v1.1 is structurally sound and the v1 review's major issues were well-addressed. The remaining critical issues are:

1. **Add JAXB/Jakarta migration steps** — without this, lib, domain, and metadata will not compile on Java 21. This is the #1 blocker.
2. **Fix ImgRdr assessment and add as file dependency** — domain, metadata, and image modules depend on it directly (18 files).
3. **Add `:metadata` and `:image` to repositories' dependencies.**
4. **Handle resource file migration** — add `resources.srcDirs` to convention plugin or migrate `.properties` files explicitly.
5. **Add JUnit 5 dependency** to convention plugin.

Items 1 and 2 are compilation blockers. Items 3–5 are test/runtime blockers. All are straightforward fixes to an otherwise well-structured plan.
