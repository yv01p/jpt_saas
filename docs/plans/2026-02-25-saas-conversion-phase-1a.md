# JPhotoTagger SaaS Conversion — Phase 1a: Spring Boot Scaffold & Database

> **Version:** 4.0
> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

**Database roles:**
- `jpt` — PostgreSQL superuser and table owner, used by Flyway for schema migrations (exempt from RLS; `SUPERUSER` attribute means `FORCE ROW LEVEL SECURITY` does not apply). Verify that Docker Compose `POSTGRES_USER=jpt` creates it with `SUPERUSER` — this is the PostgreSQL default.
- `jpt_app` — non-superuser runtime role used by the API application (subject to RLS)
- `worker_db_user` — restricted role used by the worker container (least privilege)

**Cross-phase dependency:** The RLS policies defined in this phase are non-functional until the per-request `SET LOCAL app.current_user_id` interceptor is implemented in Phase 2 (auth/API layer). The `connection-init-sql` nil UUID is a safety net only.

**Test environment note:** In Testcontainers, the default superuser (typically `test`) owns the tables, not `jpt`. Since `test` is a superuser, it bypasses RLS regardless of `FORCE ROW LEVEL SECURITY`. RLS is validated via `SET ROLE jpt_app`. This is a known test/prod divergence that does not affect test correctness.

**Schema deviation notes:**
- `users.email_verified BOOLEAN` — not in the design doc schema but kept as a deliberate optimization. The design doc derives verification from `email_tokens` table lookups; this flag avoids that join for hot-path checks. The verification endpoint must atomically update both the `email_tokens` record and set `email_verified = TRUE`. Update the design doc to reflect this addition.
- `photo_metadata.user_id` and `photo_keywords.user_id` — not in the design doc schema but required for direct-equality RLS policies (avoids correlated subqueries). Update the design doc to reflect these additions.

---

### Task 1.0: Gradle Build Configuration

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `api/build.gradle.kts`

**Step 1: Create `settings.gradle.kts`**

```kotlin
// settings.gradle.kts
rootProject.name = "jpt-saas"
include("api")
```

**Step 2: Create root `build.gradle.kts`**

```kotlin
// build.gradle.kts
plugins {
    java
    id("org.springframework.boot") version "3.4.3" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "org.jphototagger"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
```

**Step 3: Create `api/build.gradle.kts`**

```kotlin
// api/build.gradle.kts
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("io.minio:minio:8.5.14")  // TODO: verify latest stable at implementation time

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}
```

**Step 4: Generate Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.12`

**Step 5: Verify Gradle builds**

Run: `./gradlew :api:dependencies`
Expected: resolves all dependencies without error

**Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts api/build.gradle.kts gradle/ gradlew gradlew.bat
git commit -m "feat: Gradle 8 multi-project build with Spring Boot 3 dependencies"
```

### Task 1.1: Spring Boot API Scaffold

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/JptSaasApplication.java`
- Create: `api/src/main/resources/application.yml`
- Create: `api/src/main/resources/application-dev.yml`

**Step 1: Write failing test — application context loads**

```java
// api/src/test/java/org/jphototagger/api/JptSaasApplicationTest.java
package org.jphototagger.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class JptSaasApplicationTest {
    @Test
    void contextLoads() {
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :api:test --tests JptSaasApplicationTest`
Expected: FAIL — no main class

**Step 3: Write the Spring Boot application class**

```java
// api/src/main/java/org/jphototagger/api/JptSaasApplication.java
package org.jphototagger.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JptSaasApplication {
    public static void main(String[] args) {
        SpringApplication.run(JptSaasApplication.class, args);
    }
}
```

**Step 4: Write `application.yml`**

```yaml
# api/src/main/resources/application.yml
spring:
  servlet:
    multipart:
      max-file-size: 200MB
      max-request-size: 200MB
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/jpt}
    username: ${DB_USER:jpt_app}
    password: ${DB_PASS}
    hikari:
      maximum-pool-size: 10
      connection-init-sql: "SET app.current_user_id = '00000000-0000-0000-0000-000000000000'"
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    url: ${FLYWAY_DB_URL:${DB_URL:jdbc:postgresql://localhost:5432/jpt}}
    user: ${FLYWAY_DB_USER:jpt}
    password: ${FLYWAY_DB_PASS}
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}
      password: ${REDIS_PASSWORD:}

app:
  jwt-secret: ${JWT_SECRET}
  jwt-expiry-minutes: 15
  default-quota-bytes: 10737418240  # 10 GB
  default-share-days: 30
  trash-retention-days: 30

minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
  bucket: jpt-photos
```

> **Note:** The `connection-init-sql` nil UUID (`00000000-...`) is a safety net only. It ensures any query on an uninitialized connection returns empty results. The `users` table has a CHECK constraint (in V1 migration) preventing any real user from having this UUID. Per-request code must always call `SET LOCAL app.current_user_id` within the transaction.

**Step 5: Write `application-dev.yml`**

```yaml
# api/src/main/resources/application-dev.yml
spring:
  datasource:
    password: ${DB_PASS:jpt}
  flyway:
    password: ${FLYWAY_DB_PASS:jpt}

app:
  jwt-secret: ${JWT_SECRET:dev-secret-change-me-in-prod-must-be-at-least-256-bits-long-ok}
```

**Step 6: Write test `application-test.yml`**

```yaml
# api/src/test/resources/application-test.yml
spring:
  datasource:
    url: jdbc:tc:postgresql:16:///jpt
  flyway:
    enabled: true
    url: jdbc:tc:postgresql:16:///jpt
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration

app:
  jwt-secret: test-secret-not-for-production-must-be-at-least-256-bits-long-ok
```

**Step 7: Run test to verify it passes**

Run: `./gradlew :api:test --tests JptSaasApplicationTest`
Expected: PASS (with Testcontainers)

**Step 8: Commit**

```bash
git add api/src/
git commit -m "feat: Spring Boot 3 API scaffold with application config"
```

### Task 1.2: Flyway Schema — Core Tables

**Files:**
- Create: `api/src/main/resources/db/migration/V1__core_schema.sql`

**Step 1: Write failing integration test — all tables exist**

```java
// api/src/test/java/org/jphototagger/api/db/SchemaTest.java
package org.jphototagger.api.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {
        "users", "email_tokens", "photos", "photo_metadata",
        "keywords", "photo_keywords", "albums", "album_photos",
        "shares", "saved_searches"
    })
    void tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
            Integer.class, tableName);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void photosTableHasProcessingStatus() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = 'photos' AND column_name = 'processing_status'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void photosTableHasDeletedAt() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = 'photos' AND column_name = 'deleted_at'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void deduplicationConstraintExists() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints WHERE constraint_name = 'uq_user_content_hash'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :api:test --tests SchemaTest`
Expected: FAIL — tables don't exist

**Step 3: Write V1 migration**

```sql
-- api/src/main/resources/db/migration/V1__core_schema.sql

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    oauth_provider VARCHAR(50),
    oauth_id VARCHAR(255),
    quota_bytes BIGINT NOT NULL DEFAULT 10737418240,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    CONSTRAINT users_no_nil_uuid CHECK (id != '00000000-0000-0000-0000-000000000000')
);

CREATE TABLE email_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('verify', 'reset')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX email_tokens_user_idx ON email_tokens (user_id, purpose);
CREATE INDEX email_tokens_expires_idx ON email_tokens (expires_at);

CREATE TABLE photos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    filename VARCHAR(512) NOT NULL,
    caption TEXT,
    title VARCHAR(512),
    description TEXT,
    storage_key VARCHAR(512),
    size_bytes BIGINT,
    content_hash VARCHAR(64),  -- SHA-256 hex
    taken_at TIMESTAMPTZ,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processing_status VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (processing_status IN ('pending', 'processing', 'done', 'failed')),
    deleted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('english',
            coalesce(filename, '') || ' ' ||
            coalesce(title, '') || ' ' ||
            coalesce(caption, '') || ' ' ||
            coalesce(description, '')
        )
    ) STORED,
    -- NOTE: UNIQUE allows multiple NULL content_hash per user (SQL NULL != NULL).
    -- This is intentional: photos in 'pending' status have no hash yet.
    -- Deduplication only applies after processing completes.
    CONSTRAINT uq_user_content_hash UNIQUE (user_id, content_hash)
);

-- No ON DELETE CASCADE on photos.user_id — intentional.
-- Application must clean up photos (and MinIO objects) before deleting a user.

CREATE INDEX photos_user_idx ON photos (user_id) WHERE deleted_at IS NULL;
CREATE INDEX photos_taken_at_idx ON photos (user_id, taken_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX photos_search_idx ON photos USING GIN (search_vector);
CREATE INDEX photos_deleted_idx ON photos (user_id, deleted_at) WHERE deleted_at IS NOT NULL;

-- Unique constraints needed by album_photos composite FKs — must precede album_photos
ALTER TABLE photos ADD CONSTRAINT photos_id_user_id_unique UNIQUE (id, user_id);

CREATE TABLE photo_metadata (
    photo_id UUID PRIMARY KEY REFERENCES photos(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    exif_data JSONB,
    iptc_data JSONB,
    xmp_data JSONB
);

CREATE INDEX photo_exif_gin ON photo_metadata USING GIN (exif_data);
CREATE INDEX photo_iptc_gin ON photo_metadata USING GIN (iptc_data);

CREATE TABLE keywords (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    -- No ON DELETE CASCADE: deleting a parent keyword requires children to be
    -- deleted or re-parented first. Application layer must handle tree restructuring.
    parent_id UUID REFERENCES keywords(id),
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_keyword_per_parent UNIQUE NULLS NOT DISTINCT (user_id, name, parent_id)
);

CREATE TABLE photo_keywords (
    photo_id UUID NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    keyword_id UUID NOT NULL REFERENCES keywords(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    PRIMARY KEY (photo_id, keyword_id)
);

CREATE TABLE albums (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

-- Unique constraint needed by album_photos composite FK — must precede album_photos
ALTER TABLE albums ADD CONSTRAINT albums_id_user_id_unique UNIQUE (id, user_id);

CREATE TABLE album_photos (
    album_id UUID NOT NULL,
    photo_id UUID NOT NULL,
    user_id UUID NOT NULL,
    PRIMARY KEY (album_id, photo_id),
    FOREIGN KEY (album_id, user_id) REFERENCES albums(id, user_id),
    FOREIGN KEY (photo_id, user_id) REFERENCES photos(id, user_id)
);

CREATE TABLE shares (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    resource_type VARCHAR(50) NOT NULL CHECK (resource_type IN ('photo', 'album')),
    resource_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ DEFAULT (now() + interval '30 days'),
    permissions VARCHAR(50) NOT NULL DEFAULT 'view',
    include_gps BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE saved_searches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    query_json JSONB NOT NULL CHECK (query_json IS NOT NULL AND query_json != '{}'::jsonb)
);
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :api:test --tests SchemaTest`
Expected: PASS

**Step 5: Commit**

```bash
git add api/src/main/resources/db/migration/V1__core_schema.sql api/src/test/java/org/jphototagger/api/db/SchemaTest.java
git commit -m "feat: V1 Flyway migration — core schema with all tables"
```

### Task 1.3: Flyway Schema — RLS Policies

**Files:**
- Create: `api/src/main/resources/db/migration/V2__rls_policies.sql`

**Step 1: Write failing test — RLS enforces tenant isolation**

```java
// api/src/test/java/org/jphototagger/api/db/RlsTest.java
package org.jphototagger.api.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RlsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate txTemplate;

    @Test
    void rlsPreventsAccessToOtherUsersPhotos() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        // Transaction 1: Insert test data as superuser, COMMIT
        txTemplate.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
                userA, userA + "@test.com", "hash");
            jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
                userB, userB + "@test.com", "hash");
            jdbc.update("INSERT INTO photos (id, user_id, filename) VALUES (?, ?, ?)",
                photoId, userA, "test.jpg");
        });

        try {
            // Transaction 2: Query as jpt_app role with userB context
            txTemplate.executeWithoutResult(status -> {
                jdbc.execute("SET ROLE jpt_app");
                jdbc.execute("SET LOCAL app.current_user_id = '" + userB + "'");

                Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM photos WHERE id = ?",
                    Integer.class, photoId);
                assertThat(count).isEqualTo(0);

                jdbc.execute("RESET ROLE");
            });
        } finally {
            // Unconditionally reset role before cleanup to avoid RLS filtering out deletes
            try { jdbc.execute("RESET ROLE"); } catch (Exception ignored) {}
            jdbc.update("DELETE FROM photos WHERE id = ?", photoId);
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userA, userB);
        }
    }

    @Test
    void rlsPoliciesExist() {
        List<String> policyNames = jdbc.queryForList(
            "SELECT policyname FROM pg_policies WHERE policyname LIKE 'tenant_%' ORDER BY policyname",
            String.class);

        assertThat(policyNames).containsExactlyInAnyOrder(
            "tenant_photos",
            "tenant_photo_metadata",
            "tenant_keywords",
            "tenant_photo_keywords",
            "tenant_albums",
            "tenant_album_photos",
            "tenant_shares",
            "tenant_saved_searches"
        );
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :api:test --tests RlsTest`
Expected: FAIL — no RLS policies

**Step 3: Write V2 migration**

```sql
-- api/src/main/resources/db/migration/V2__rls_policies.sql

-- Create application role for API connections (non-superuser)
-- NOTE: Password must be overridden in production via ALTER ROLE or Docker secrets.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jpt_app') THEN
        CREATE ROLE jpt_app WITH LOGIN PASSWORD 'changeme';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO jpt_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jpt_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO jpt_app;

-- Auto-grant on future tables created by Flyway migrations
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jpt_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO jpt_app;

-- Enable RLS on all tenant tables
ALTER TABLE photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE photo_metadata ENABLE ROW LEVEL SECURITY;
ALTER TABLE keywords ENABLE ROW LEVEL SECURITY;
ALTER TABLE photo_keywords ENABLE ROW LEVEL SECURITY;
ALTER TABLE albums ENABLE ROW LEVEL SECURITY;
ALTER TABLE album_photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE shares ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_searches ENABLE ROW LEVEL SECURITY;

-- RLS policies — direct user_id equality (no correlated subqueries)
CREATE POLICY tenant_photos ON photos
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_photo_metadata ON photo_metadata
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_keywords ON keywords
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_photo_keywords ON photo_keywords
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_albums ON albums
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_album_photos ON album_photos
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_shares ON shares
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_saved_searches ON saved_searches
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- Force RLS on table owners too (so even superuser-like roles are restricted)
ALTER TABLE photos FORCE ROW LEVEL SECURITY;
ALTER TABLE photo_metadata FORCE ROW LEVEL SECURITY;
ALTER TABLE keywords FORCE ROW LEVEL SECURITY;
ALTER TABLE photo_keywords FORCE ROW LEVEL SECURITY;
ALTER TABLE albums FORCE ROW LEVEL SECURITY;
ALTER TABLE album_photos FORCE ROW LEVEL SECURITY;
ALTER TABLE shares FORCE ROW LEVEL SECURITY;
ALTER TABLE saved_searches FORCE ROW LEVEL SECURITY;
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :api:test --tests RlsTest`
Expected: PASS

**Step 5: Commit**

```bash
git add api/src/main/resources/db/migration/V2__rls_policies.sql api/src/test/java/
git commit -m "feat: V2 Flyway migration — RLS policies on all tenant tables"
```

### Task 1.4: Flyway Schema — Worker DB User (Least Privilege)

**Files:**
- Create: `api/src/main/resources/db/migration/V3__worker_db_user.sql`

**Step 1: Write failing test — worker user has restricted access**

```java
// api/src/test/java/org/jphototagger/api/db/WorkerDbUserTest.java
package org.jphototagger.api.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class WorkerDbUserTest {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void resetRole() {
        try { jdbc.execute("RESET ROLE"); } catch (Exception ignored) {}
    }

    @Test
    void workerDbUserRoleExists() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM pg_roles WHERE rolname = 'worker_db_user'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void workerCannotAccessUsersTable() {
        jdbc.execute("SET ROLE worker_db_user");
        try {
            assertThatThrownBy(() ->
                jdbc.queryForObject("SELECT count(*) FROM users", Integer.class))
                .hasMessageContaining("permission denied");
        } finally {
            jdbc.execute("RESET ROLE");
        }
    }

    @Test
    void workerCannotDeleteFromPhotos() {
        jdbc.execute("SET ROLE worker_db_user");
        try {
            assertThatThrownBy(() ->
                jdbc.execute("DELETE FROM photos WHERE id = '00000000-0000-0000-0000-000000000000'"))
                .hasMessageContaining("permission denied");
        } finally {
            jdbc.execute("RESET ROLE");
        }
    }

    @Test
    void workerCannotAccessSharesTable() {
        jdbc.execute("SET ROLE worker_db_user");
        try {
            assertThatThrownBy(() ->
                jdbc.queryForObject("SELECT count(*) FROM shares", Integer.class))
                .hasMessageContaining("permission denied");
        } finally {
            jdbc.execute("RESET ROLE");
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :api:test --tests WorkerDbUserTest`
Expected: FAIL

**Step 3: Write V3 migration**

```sql
-- api/src/main/resources/db/migration/V3__worker_db_user.sql
-- NOTE: Password must be overridden in production via ALTER ROLE or Docker secrets.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'worker_db_user') THEN
        CREATE ROLE worker_db_user WITH LOGIN PASSWORD 'changeme';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO worker_db_user;

-- Worker reads photo job details
GRANT SELECT ON photos TO worker_db_user;

-- Worker writes extracted metadata
GRANT INSERT, UPDATE ON photo_metadata TO worker_db_user;

-- Worker updates only specific columns on photos
GRANT UPDATE (storage_key, content_hash, processing_status, size_bytes) ON photos TO worker_db_user;

-- Explicitly NOT granted: users, shares, keywords, albums, saved_searches, email_tokens
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :api:test --tests WorkerDbUserTest`
Expected: PASS

**Step 5: Commit**

```bash
git add api/src/main/resources/db/migration/V3__worker_db_user.sql api/src/test/java/
git commit -m "feat: V3 Flyway migration — restricted worker_db_user role"
```

---

**Next Phase:** [Phase 1b: Infrastructure — Docker Compose & Dockerfiles](2026-02-25-saas-conversion-phase-1b.md)

---

## Change Log

| Version | Date       | Changes |
|---------|------------|---------|
| 1.0     | 2026-02-25 | Initial plan |
| 2.0     | 2026-02-26 | Applied critical review v1 findings (see `2026-02-25-saas-conversion-phase-1a-critical-review-1.md`): **C1** — RLS test now uses `SET ROLE jpt_app`, `@Transactional`, and asserts `count == 0`. **C2** — JWT secret has no default in `application.yml`; dev fallback moved to `application-dev.yml`. **C3** — Flyway uses separate datasource (`spring.flyway.url/user/password`) exempt from RLS and `connection-init-sql`. **C4** — Reordered `UNIQUE` constraints before `CREATE TABLE album_photos`. **C5** — Added indexes on `email_tokens(user_id, purpose)` and `email_tokens(expires_at)`. **C6** — Added `user_id` to `photo_metadata` and `photo_keywords`; all RLS policies use direct equality. **M1** — Replaced `uuid_generate_v4()` with `gen_random_uuid()`; removed `uuid-ossp` extension. **M2** — Added comments noting passwords must be overridden in production. **M3** — Documented intentional lack of `ON DELETE CASCADE` on `photos.user_id`. **M4** — Added `CHECK (resource_type IN ('photo', 'album'))` to `shares`. **M5** — Added `UNIQUE NULLS NOT DISTINCT (user_id, name, parent_id)` to `keywords`. **M6** — Added `CHECK` constraint on `saved_searches.query_json`. **M7** — Disabled Redis auto-configuration in test profile. **M8** — `SchemaTest` now uses parameterized test for all 10 tables. **M9** — Specified `application-dev.yml` content. **Q2** — Documented database role purposes (jpt, jpt_app, worker_db_user). **Q4** — Added `-- SHA-256 hex` comment on `content_hash` column. |
| 4.0     | 2026-02-26 | Applied critical review v3 findings (see `2026-02-25-saas-conversion-phase-1a-critical-review-3.md`): **C1** — RLS test finally block now unconditionally resets role before cleanup. **C2** — Documented `jpt` must be PostgreSQL superuser; verify Docker Compose creates it as such. **C3** — Added `@AfterEach` role reset to `WorkerDbUserTest`. **M1** — Removed fragile `user: ""`/`password: ""` from test Flyway config. **M2** — Added `photos_taken_at_idx` composite index for timeline queries. **M3** — Deferred `search_vector` field weighting to later phase. **M4** — Documented NULL content_hash uniqueness behavior. **M5** — Documented no cascade on `keywords.parent_id`. **M6** — Acknowledged composite FK overhead (monitoring). **M7** — Added note to verify MinIO version at implementation. **Q2** — Documented test/prod table owner divergence. **Q3** — Added cross-phase dependency note for `SET LOCAL` interceptor (Phase 2). |
| 3.0     | 2026-02-26 | Applied critical review v2 findings (see `2026-02-25-saas-conversion-phase-1a-critical-review-2.md`): **C1** — Added Task 1.0 with Gradle build configuration (`settings.gradle.kts`, root and api `build.gradle.kts`, wrapper). **C2** — RLS test restructured to use `TransactionTemplate` with two separate transactions instead of single `@Transactional`; cleanup block added. **C3** — Added `CHECK (id != '00000000-...')` constraint on `users` table to guarantee nil UUID can never be a real user. **C4** — Kept `email_verified` column; documented as deliberate deviation from design doc with atomicity requirement. **M1** — Added `spring.flyway.url/user/password` overrides to `application-test.yml`. **M3** — Narrowed `jpt_app` grants from `ALL PRIVILEGES` to `SELECT, INSERT, UPDATE, DELETE`. **M4** — Added `ALTER DEFAULT PRIVILEGES` for `jpt_app` so future tables get correct grants automatically. **M5** — RLS policy existence test now asserts exact set of policy names instead of `>= 8` count. **M6** — Added negative grant tests for `worker_db_user` (cannot access `users`, cannot `DELETE` from `photos`, cannot access `shares`). **M8** — Added `updated_at TIMESTAMPTZ` to `users`, `photos`, `albums`, `keywords`, and `shares` tables. |
