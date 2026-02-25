# JPhotoTagger SaaS Conversion — Phase 1b: Infrastructure — Docker Compose & Dockerfiles

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

### Task 1.5: Docker Compose Stack

**Files:**
- Create: `docker-compose.yml`
- Create: `docker-compose.dev.yml`
- Create: `.env.example`

**Step 1: Write `docker-compose.yml`**

Create the full Docker Compose stack per the design document Section 7. Include all services: nginx, api, worker, postgres, minio, redis, backup, pgbackup, certbot, prometheus, grafana.

Reference the design doc Section 7 exactly for:
- All environment variables
- Health checks
- Volume mounts
- Network configuration
- Worker container hardening (cap_drop, security_opt, read_only, tmpfs)
- Redis AOF config
- MinIO MINIO_BROWSER=off
- pgbackup with restic
- Prometheus/Grafana on internal network

**Step 2: Write `docker-compose.dev.yml` (override for local dev)**

```yaml
# docker-compose.dev.yml — local development overrides
services:
  postgres:
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: jpt
      POSTGRES_USER: jpt
      POSTGRES_PASSWORD: jpt

  minio:
    ports: ["9000:9000", "9001:9001"]
    environment:
      MINIO_BROWSER: "on"  # Enable console in dev
    command: server /data --console-address ":9001"

  redis:
    ports: ["6379:6379"]

  # Don't run backup/pgbackup/certbot/prometheus/grafana in dev
  backup:
    profiles: ["production"]
  pgbackup:
    profiles: ["production"]
  certbot:
    profiles: ["production"]
  prometheus:
    profiles: ["monitoring"]
  grafana:
    profiles: ["monitoring"]
```

**Step 3: Write `.env.example`**

```bash
# .env.example — copy to .env and fill in values
DB_URL=jdbc:postgresql://postgres:5432/jpt
DB_USER=jpt
DB_PASS=jpt
DB_NAME=jpt
WORKER_DB_USER=worker_db_user
WORKER_DB_PASS=changeme
MINIO_ENDPOINT=http://minio:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
REDIS_URL=redis://redis:6379
REDIS_PASSWORD=changeme
JWT_SECRET=  # Generate: openssl rand -base64 64
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
SMTP_HOST=
SMTP_USER=
SMTP_PASS=
GRAFANA_PASSWORD=changeme
B2_ACCESS_KEY=
B2_SECRET_KEY=
B2_BACKUP_ACCESS_KEY=
B2_BACKUP_SECRET_KEY=
B2_ACCOUNT_ID=
B2_ACCOUNT_KEY=
```

**Step 4: Verify the dev stack starts**

```bash
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.dev.yml up postgres minio redis -d
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
```

Expected: postgres, minio, redis healthy

**Step 5: Tear down and commit**

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
git add docker-compose.yml docker-compose.dev.yml .env.example
git commit -m "infra: Docker Compose stack with all services"
```

### Task 1.6: API and Worker Dockerfiles

**Files:**
- Create: `api/Dockerfile`
- Create: `worker/Dockerfile`
- Create: `pgbackup/Dockerfile`

**Step 1: Write `api/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Step 2: Write `worker/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    libraw-utils \
    vips-tools \
    perl perl-image-exiftool \
    tini

# Non-root user
RUN addgroup -S worker && adduser -S worker -G worker
USER worker

WORKDIR /app
COPY build/libs/worker-*.jar app.jar
ENTRYPOINT ["/sbin/tini", "--", "java", "-jar", "app.jar"]
```

**Step 3: Write `pgbackup/Dockerfile`**

```dockerfile
FROM postgres:16
RUN apt-get update && apt-get install -y --no-install-recommends \
    restic=0.16.4-1 \
    && rm -rf /var/lib/apt/lists/*
```

**Step 4: Commit**

```bash
git add api/Dockerfile worker/Dockerfile pgbackup/Dockerfile
git commit -m "infra: Dockerfiles for api, worker, pgbackup"
```

---

**Next Phase:** [Phase 2: Backend API — Auth, Security, REST Endpoints](2026-02-25-saas-conversion-phase-2.md)
