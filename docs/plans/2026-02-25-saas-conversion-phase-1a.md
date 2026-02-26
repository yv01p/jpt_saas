# JPhotoTagger SaaS Conversion — Phase 1a: Spring Boot Scaffold & Database

> **Version:** 2.0
> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

**Database roles:**
- `jpt` — superuser/owner role used by Flyway for schema migrations (exempt from RLS)
- `jpt_app` — non-superuser runtime role used by the API application (subject to RLS)
- `worker_db_user` — restricted role used by the worker container (least privilege)

---

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
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
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
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('english',
            coalesce(filename, '') || ' ' ||
            coalesce(title, '') || ' ' ||
            coalesce(caption, '') || ' ' ||
            coalesce(description, '')
        )
    ) STORED,
    CONSTRAINT uq_user_content_hash UNIQUE (user_id, content_hash)
);

-- No ON DELETE CASCADE on photos.user_id — intentional.
-- Application must clean up photos (and MinIO objects) before deleting a user.

CREATE INDEX photos_user_idx ON photos (user_id) WHERE deleted_at IS NULL;
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
    parent_id UUID REFERENCES keywords(id),
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
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
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
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
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
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RlsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional
    void rlsPreventsAccessToOtherUsersPhotos() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        // Insert users
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
            userA, "a@test.com", "hash");
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)",
            userB, "b@test.com", "hash");

        // Insert photo for user A
        UUID photoId = UUID.randomUUID();
        jdbc.update("INSERT INTO photos (id, user_id, filename) VALUES (?, ?, ?)",
            photoId, userA, "test.jpg");

        // Switch to non-superuser role and set RLS context to user B
        jdbc.execute("SET ROLE jpt_app");
        jdbc.execute("SET LOCAL app.current_user_id = '" + userB + "'");

        // User B should NOT see user A's photo via RLS
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM photos WHERE id = ?",
            Integer.class, photoId);

        assertThat(count).isEqualTo(0);

        // Reset role for cleanup
        jdbc.execute("RESET ROLE");
    }

    @Test
    void rlsPoliciesExist() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM pg_policies WHERE policyname LIKE 'tenant_%'",
            Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(8); // all tenant tables
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
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO jpt_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO jpt_app;

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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WorkerDbUserTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void workerDbUserRoleExists() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM pg_roles WHERE rolname = 'worker_db_user'",
            Integer.class);
        assertThat(count).isEqualTo(1);
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
| 2.0     | 2026-02-26 | Applied critical review findings (see `2026-02-25-saas-conversion-phase-1a-critical-review-1.md`): **C1** — RLS test now uses `SET ROLE jpt_app`, `@Transactional`, and asserts `count == 0`. **C2** — JWT secret has no default in `application.yml`; dev fallback moved to `application-dev.yml`. **C3** — Flyway uses separate datasource (`spring.flyway.url/user/password`) exempt from RLS and `connection-init-sql`. **C4** — Reordered `UNIQUE` constraints before `CREATE TABLE album_photos`. **C5** — Added indexes on `email_tokens(user_id, purpose)` and `email_tokens(expires_at)`. **C6** — Added `user_id` to `photo_metadata` and `photo_keywords`; all RLS policies use direct equality. **M1** — Replaced `uuid_generate_v4()` with `gen_random_uuid()`; removed `uuid-ossp` extension. **M2** — Added comments noting passwords must be overridden in production. **M3** — Documented intentional lack of `ON DELETE CASCADE` on `photos.user_id`. **M4** — Added `CHECK (resource_type IN ('photo', 'album'))` to `shares`. **M5** — Added `UNIQUE NULLS NOT DISTINCT (user_id, name, parent_id)` to `keywords`. **M6** — Added `CHECK` constraint on `saved_searches.query_json`. **M7** — Disabled Redis auto-configuration in test profile. **M8** — `SchemaTest` now uses parameterized test for all 10 tables. **M9** — Specified `application-dev.yml` content. **Q2** — Documented database role purposes (jpt, jpt_app, worker_db_user). **Q4** — Added `-- SHA-256 hex` comment on `content_hash` column. |
