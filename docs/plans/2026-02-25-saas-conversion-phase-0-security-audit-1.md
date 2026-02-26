# Security Audit: SaaS Conversion Phase 0 — Java Upgrade & Gradle Migration

**Auditor:** LCSA (Lead Cyber-Security Auditor)
**Date:** 2026-02-25
**Target:** `docs/plans/2026-02-25-saas-conversion-phase-0.md` (v1.5)
**Scope:** Phase 0 implementation plan — Gradle build migration, Java 21 compilation, Imagero replacement. This is a build-system and source-migration plan; no runtime services, endpoints, or user-facing features are deployed. The security surface is therefore limited to supply chain, build integrity, and secure defaults established for later phases.

---

## Pass 1: Reconnaissance & Attack Surface Mapping

**Phase 0 scope is narrow:** copy source files into a new Gradle layout, update imports (javax→jakarta), replace a commercial library (Imagero) with an open-source one (metadata-extractor), and verify compilation + tests on Java 21.

**Entry points:** None. No HTTP endpoints, no user input, no runtime services.
**Trust boundaries:** Build-time only — Gradle downloads dependencies from Maven Central.
**Sensitive data flows:** None at runtime. Build scripts reference dependency coordinates.
**Authentication/Authorization:** Not applicable in Phase 0.

**Attack surface in scope:**
1. Supply chain — dependency versions, integrity, known CVEs
2. Build configuration — convention plugin, Gradle wrapper
3. Code patterns established that carry into later phases
4. Secrets/credentials — any hardcoded in plan or build files

---

## Pass 2: Systematic Vulnerability Hunting

### Finding #1: Dependency Versions with Known CVEs — Testcontainers 1.19.7

**Vulnerability:** Vulnerable Component — OWASP A06
**Severity:** Low
**Confidence:** High
**Attack Complexity:** N/A (test-only dependency)

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-0.md`, Lines 97, 114 (version catalog)
- Related: `worker/build.gradle.kts` (lines 382–384), Phase 1a reminder (lines 733–738)

**Risk & Exploit Path:**
Testcontainers 1.19.7 (February 2024) is over a year old. While this is a test-only dependency (never shipped to production), Testcontainers versions before 1.20.x had container escape and Docker socket exposure issues in certain configurations. The risk is limited because: (a) it's `testImplementation` only, (b) it runs in CI/dev, not production. However, pinning stale test dependencies sets a bad precedent and may conflict with Testcontainers features needed in Phase 1a (e.g., improved PostgreSQL module).

**Evidence / Trace:**
```toml
# libs.versions.toml
testcontainers = "1.19.7"   # ← STALE — current stable is 1.20.x+
```

**Remediation:**
- Primary fix: Update to Testcontainers 1.20.4+ (latest stable at plan execution time). Check changelog for breaking changes.
- Defense-in-depth: Add a Gradle dependency verification file (`gradle/verification-metadata.xml`) or use the `--write-verification-metadata` flag to pin checksums.

---

### Finding #2: No Gradle Dependency Verification or Checksum Pinning

**Vulnerability:** Supply Chain Integrity — OWASP A06 (Vulnerable and Outdated Components) / A08 (Software and Data Integrity Failures)
**Severity:** Medium
**Confidence:** Confirmed
**Attack Complexity:** Medium

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-0.md`, Task 0.1 (entire Gradle setup)
- Missing: `gradle/verification-metadata.xml`

**Risk & Exploit Path:**
The plan establishes a Gradle build that downloads ~30+ transitive dependencies from Maven Central with no integrity verification beyond TLS. A compromised Maven Central mirror, DNS hijack, or dependency confusion attack could inject a malicious artifact. Gradle supports dependency verification (`verification-metadata.xml`) which pins SHA-256/SHA-512 checksums for every artifact. The plan does not include this.

This is the foundation build — every subsequent phase inherits this gap. A supply chain compromise at this stage would persist through all future phases undetected.

**Evidence / Trace:**
The plan creates `gradle/libs.versions.toml` (version catalog) and `settings.gradle.kts` but no `gradle/verification-metadata.xml`. No `--write-verification-metadata` step is included.

**Remediation:**
- Primary fix: Add a step after Task 0.1 Step 17:
  ```bash
  ./gradlew --write-verification-metadata sha256,sha512 build --dry-run
  git add gradle/verification-metadata.xml
  ```
- Defense-in-depth: Enable Gradle's `dependencyLocking` for reproducible builds. Consider using `--refresh-dependencies` in CI to detect upstream changes.

---

### Finding #3: Gradle Wrapper JAR Not Verified

**Vulnerability:** Supply Chain Integrity — OWASP A08
**Severity:** Low
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-0.md`, Task 0.1 Step 1 (line 77–79)

**Risk & Exploit Path:**
The plan bootstraps the Gradle wrapper with `gradle wrapper --gradle-version 8.8`. The resulting `gradle-wrapper.jar` is a binary committed to the repository. Attackers have historically targeted Gradle wrapper JARs in open-source projects (substituting a trojanized JAR). The plan does not include a verification step using Gradle's official `wrapper` validation.

**Evidence / Trace:**
```bash
gradle wrapper --gradle-version 8.8
# ← No subsequent: gradle wrapper --validate-checksums
# ← No: gradle/wrapper/gradle-wrapper.jar.sha256
```

**Remediation:**
- Primary fix: After generating the wrapper, add:
  ```bash
  ./gradlew wrapper --gradle-version 8.8 --validate-checksums
  ```
  Or verify the wrapper JAR SHA-256 against Gradle's published checksums.
- Defense-in-depth: Add a CI step that runs `gradle wrapper --validate-checksums` on every PR. GitHub's `gradle/wrapper-validation-action` automates this.

---

### Finding #4: `resources.include` Pattern May Miss Security-Sensitive Resource Types

**Vulnerability:** Security Misconfiguration — OWASP A05
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-0.md`, Lines 143–148 (convention plugin `sourceSets`)

**Risk & Exploit Path:**
The convention plugin configures `resources.include("**/*.properties", "**/*.xml")` for co-located resources. If any Java source directory contains `.keystore`, `.jks`, `.pem`, `.p12`, or `.env` files (even accidentally), they would be excluded from the build — which is actually *good* from a security perspective. However, the inverse risk exists: if future phases add resource types (e.g., `.json`, `.yml`, `.sql`) co-located with Java sources, they'll be silently excluded, potentially causing runtime failures that mask security configurations.

The plan includes a mitigation comment (line 145–146) instructing verification with `find`. This is adequate.

**Evidence / Trace:**
```kotlin
resources.include("**/*.properties", "**/*.xml")
// After each module migration, verify no other resource types are co-located
```

**Remediation:**
- This is already mitigated by the verification comment. No action required unless the `find` check is skipped during implementation.
- Defense-in-depth: Add a Gradle task that fails the build if unrecognized file types exist in `src/main/java`:
  ```kotlin
  tasks.register("checkColocatedResources") { /* ... */ }
  ```

---

### Finding #5: `sed -i` JAXB Migration May Silently Corrupt Files

**Vulnerability:** Requires Verification — Build Integrity
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-0.md`, Lines 432, 506, 556 (Tasks 0.2, 0.4, 0.5)

**Risk & Exploit Path:**
The plan uses `find ... | xargs sed -i 's/javax\.xml\.bind/jakarta.xml.bind/g'` for JAXB migration. This is a global find-replace with no backup. If the regex matches inside string literals, comments, or Javadoc (e.g., a comment saying "// Migrated from javax.xml.bind"), the replacement is still applied. This is cosmetically harmless for comments but could corrupt string constants used in reflection, XML namespace URIs, or error messages.

This is not a security vulnerability per se, but could introduce subtle bugs in XML processing code that affect data integrity in later phases.

**Evidence / Trace:**
```bash
find lib/src -name "*.java" -exec grep -l "javax.xml.bind" {} \; | \
  xargs sed -i 's/javax\.xml\.bind/jakarta.xml.bind/g'  # ← No backup, global replace
```

**Remediation:**
- Primary fix: Add `--backup` or review diff before committing: `git diff` after the sed to verify only import statements were changed.
- This is low-risk because `javax.xml.bind` as a string constant in application code is extremely unlikely. The compilation gate catches any actual breakage.

---

### Finding #6: Imagero Replacement Lacks Behavioral Parity Verification Until Phase 1

**Vulnerability:** Business Logic / Data Integrity Risk — OWASP A08 (Software Integrity)
**Severity:** Medium
**Confidence:** High
**Attack Complexity:** Low

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-0.md`, Task 0.8 (lines 648–711)

**Risk & Exploit Path:**
The plan replaces a commercial library (Imagero/ImgrRdr.jar) with metadata-extractor across 18 files. Sub-tasks 0.8.1–0.8.7 verify compilation only ("compileJava" gates). The plan explicitly acknowledges: "Behavioral parity (correct metadata values extracted from real images) will be validated in Phase 1 with integration tests against sample images."

This means Phase 0 ships a library replacement with no runtime verification. If metadata-extractor returns different values (e.g., different byte ordering for GPS coordinates, different IPTC encoding, missing maker note fields), the discrepancy won't be caught until Phase 1. For a photo metadata application, incorrect metadata extraction is a data integrity issue that could cascade into:
- Incorrect GPS coordinates served via share links (privacy concern if coordinates are wrong direction)
- Missing IPTC fields in keyword/caption extraction
- Thumbnail extraction failures for certain camera models

**Evidence / Trace:**
```
> **Note:** Compilation gates in each sub-task verify API compatibility only. Behavioral parity
> (correct metadata values extracted from real images) will be validated in Phase 1
```

**Remediation:**
- Primary fix: Add a minimal behavioral verification step to Task 0.8 or 0.9 — a small set (5-10) of sample images from common camera models with known metadata values, verified programmatically. This can be a simple JUnit test in the metadata module.
- The plan already acknowledges this gap. The risk is accepted if Phase 1 integration tests are prioritized early.

---

### Finding #7: HSQLDB Included as Runtime Dependency — Unnecessary Attack Surface

**Vulnerability:** Requires Verification — OWASP A06
**Severity:** Low
**Confidence:** Medium
**Attack Complexity:** High

**Location:**
- File: `docs/plans/2026-02-25-saas-conversion-phase-0.md`, Lines 271–287 (Task 0.7, `repositories/build.gradle.kts`)

**Risk & Exploit Path:**
HSQLDB 2.7.2 is included as `runtimeOnly` (potentially `implementation` if source imports exist). HSQLDB has historically had SQL injection and RCE vulnerabilities (CVE-2022-41853 — arbitrary Java method execution via SQL). While HSQLDB will be replaced by PostgreSQL in Phase 1, it remains on the classpath through Phase 0 and potentially Phase 1a if not explicitly removed. If any test or accidental configuration initializes an HSQLDB instance, it could be exploitable.

The plan correctly notes "The HSQLDB-specific code will later be replaced with Spring Data JPA / PostgreSQL." The risk is low because no runtime services exist in Phase 0, but the dependency should be removed as soon as the PostgreSQL migration is complete in Phase 1.

**Evidence / Trace:**
```kotlin
// repositories/build.gradle.kts
runtimeOnly(libs.hsqldb)  // ← HSQLDB 2.7.2, CVE-2022-41853 applies to older versions
```

**Remediation:**
- Primary fix: Verify HSQLDB 2.7.2 is patched against CVE-2022-41853 (it is — fixed in 2.7.1). No immediate action needed.
- Phase 1 action: Remove HSQLDB dependency entirely when PostgreSQL migration is complete. Add a reminder to the Phase 1 plan.

---

## Pass 3: Cross-Cutting & Compositional Analysis

### Chained Attacks
No chained attack paths identified. Phase 0 has no runtime attack surface.

### Implicit Trust Assumptions
- The plan trusts Maven Central as the sole artifact source. This is standard but unverified (Finding #2).
- The plan trusts that `cp -r` and `rsync -a` preserve file integrity during migration. This is safe on Linux with standard filesystem semantics.

### Defense-in-Depth Gaps
- No dependency verification (Finding #2) + no wrapper validation (Finding #3) = full supply chain trust gap. If either Maven Central or the Gradle wrapper is compromised, the entire build is compromised with no detection mechanism.

### Deployment Context
Phase 0 is build-only; no deployment. The build configuration established here propagates to all future phases, making supply chain findings more impactful than their immediate Phase 0 severity suggests.

---

## 1. Executive Summary

Phase 0 is a build migration plan with **no runtime attack surface** — no endpoints, no user input, no deployed services. The security posture is strong for what it is: a methodical source migration with compilation gates, targeted `git add` (avoiding `git add -A`), and explicit dependency declarations.

The primary security concerns are **supply chain integrity** — the plan establishes a Gradle build with ~30+ dependencies downloaded from Maven Central with no checksum verification, no Gradle wrapper validation, and no dependency locking. These are not exploitable in isolation but create a foundational trust gap that every subsequent phase inherits.

The Imagero-to-metadata-extractor replacement is well-structured but defers behavioral verification to Phase 1, creating a window where metadata extraction correctness is unverified. For a photo metadata application, this is a data integrity risk worth mitigating earlier.

**Overall assessment:** The plan is safe to implement. The findings are preventive improvements, not blocking vulnerabilities. The two medium-severity findings (dependency verification, behavioral parity) should be addressed before Phase 1 begins.

## 2. Findings Summary Table

| # | Title | Category | Severity | Confidence | Similar Instances | Status |
|---|-------|----------|----------|------------|-------------------|--------|
| 1 | Stale Testcontainers version | A06 | Low | High | 1 | ADVISORY |
| 2 | No Gradle dependency verification | A06/A08 | Medium | Confirmed | 1 | RECOMMENDED |
| 3 | Gradle wrapper JAR not verified | A08 | Low | High | 1 | RECOMMENDED |
| 4 | Resource include pattern risks | A05 | Low | Medium | 1 | MITIGATED |
| 5 | sed JAXB migration no backup | Integrity | Low | Medium | 3 | ADVISORY |
| 6 | Imagero replacement unverified until Phase 1 | A08 | Medium | High | 1 | RECOMMENDED |
| 7 | HSQLDB on classpath | A06 | Low | Medium | 1 | ACCEPTED |

## 3. Security Quality Score (SQS)

| Finding Severity | Count | Deduction |
|-----------------|-------|-----------|
| Critical | 0 | 0 |
| High | 0 | 0 |
| Medium | 2 | −16 |
| Low | 5 (grouped as 1: all build-config) | −2 |

**Final SQS:** 82/100
**Hard gates triggered:** No
**Posture:** Acceptable — proceed with implementation; address medium findings before Phase 1 begins.

## 4. Positive Security Observations

1. **Targeted `git add` instead of `git add -A`** — prevents accidental commit of secrets, build artifacts, or IDE files. Explicitly called out in the changelog (v1.4).
2. **`bootJar` disabled in Phase 0** — server and worker modules have no main class; disabling bootJar prevents accidental deployment of stub services.
3. **Imagero licensing concern identified proactively** — the plan replaces a commercial library with unknown SaaS licensing (Imagero) with Apache 2.0 licensed metadata-extractor *before* any SaaS features are built. This eliminates a legal/compliance risk.
4. **Worker least-privilege design referenced early** — the design doc (v4.0) establishes a restricted `worker_db_user` PostgreSQL role. Phase 0 doesn't implement this but the architecture is already security-conscious.
5. **Compilation gates at every step** — every module migration verifies `./gradlew :module:compileJava` before proceeding, preventing broken state propagation.

## 5. Prioritized Remediation Roadmap

| Priority | Finding | Why | Effort | Owner |
|----------|---------|-----|--------|-------|
| 1 | #2 — Dependency verification | Foundational supply chain control; every future phase inherits this gap | Quick Win | DevOps |
| 2 | #6 — Behavioral parity tests | Data integrity risk for core application function; add sample-image JUnit tests to Task 0.8 or 0.9 | Moderate | Backend |
| 3 | #3 — Wrapper validation | Single binary committed to repo; one-time CI setup | Quick Win | DevOps |
| 4 | #1 — Testcontainers version | Version bump in `libs.versions.toml`; verify no breaking changes | Quick Win | Backend |
| 5 | #7 — HSQLDB removal tracking | Add explicit removal step to Phase 1 plan | Quick Win | Backend |
