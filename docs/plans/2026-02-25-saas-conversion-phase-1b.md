# JPhotoTagger SaaS Conversion — Phase 1b: Infrastructure — Docker Compose & Dockerfiles

> **Version:** 3.0
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
- Create: `nginx.conf` (minimal stub)
- Create: `secrets/.gitkeep`
- Add to: `.gitignore` (`secrets/` directory, `react-build/` directory)

**Step 1: Write `docker-compose.yml`**

Create the full Docker Compose stack with all services, volumes, networks, and secrets inline. Do NOT reference the design doc for syntax — use the valid Compose YAML below as the specification.

```yaml
services:
  nginx:
    image: nginx:alpine
    ports: ["80:80", "443:443"]
    volumes:
      - ./react-build:/usr/share/nginx/html:ro
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - certbot_certs:/etc/letsencrypt:ro
      - certbot_www:/var/www/certbot:ro
    depends_on:
      api:
        condition: service_started
    restart: unless-stopped

  api:
    build: ./api
    environment:
      DB_URL: ${DB_URL}
      DB_USER: ${DB_USER}
      DB_PASS: ${DB_PASS}
      MINIO_ENDPOINT: ${MINIO_ENDPOINT}
      MINIO_ACCESS_KEY: ${MINIO_ACCESS_KEY}
      MINIO_SECRET_KEY: ${MINIO_SECRET_KEY}
      JWT_SECRET: ${JWT_SECRET}
      REDIS_URL: ${REDIS_URL}
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID}
      GOOGLE_CLIENT_SECRET: ${GOOGLE_CLIENT_SECRET}
      SMTP_HOST: ${SMTP_HOST}
      SMTP_USER: ${SMTP_USER}
      SMTP_PASS: ${SMTP_PASS}
    depends_on:
      postgres:
        condition: service_healthy
      minio:
        condition: service_healthy
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 3s
      retries: 5
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    restart: unless-stopped

  worker:
    build: ./worker
    environment:
      DB_URL: ${WORKER_DB_URL}
      DB_USER: ${WORKER_DB_USER}
      DB_PASS: ${WORKER_DB_PASS}
      MINIO_ENDPOINT: ${MINIO_ENDPOINT}
      MINIO_ACCESS_KEY: ${MINIO_ACCESS_KEY}
      MINIO_SECRET_KEY: ${MINIO_SECRET_KEY}
      REDIS_URL: ${REDIS_URL}
      REDIS_PASSWORD: ${REDIS_PASSWORD}
    depends_on:
      postgres:
        condition: service_healthy
      minio:
        condition: service_healthy
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "find /tmp/worker-heartbeat -mmin -1 | grep -q ."]
      interval: 15s
      timeout: 3s
      retries: 3
      start_period: 30s
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    read_only: true
    tmpfs:
      - /tmp:size=512M
    restart: unless-stopped

  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASS}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  minio:
    image: minio/minio
    volumes:
      - minio_data:/data
    command: server /data
    environment:
      MINIO_ROOT_USER: ${MINIO_ACCESS_KEY}
      MINIO_ROOT_PASSWORD: ${MINIO_SECRET_KEY}
      MINIO_BROWSER: "off"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD} --appendonly yes --appendfsync everysec --maxmemory 256mb --maxmemory-policy noeviction
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD-SHELL", "REDISCLI_AUTH=$$REDIS_PASSWORD redis-cli ping"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  backup:
    image: minio/mc
    restart: unless-stopped
    entrypoint: >
      /bin/sh -c "
        mc alias set minio http://minio:9000 $$MINIO_ACCESS_KEY $$MINIO_SECRET_KEY &&
        mc alias set b2 https://s3.us-west-004.backblazeb2.com $$B2_ACCESS_KEY $$B2_SECRET_KEY &&
        while true; do
          mc mirror minio/jpt-photos b2/jpt-photos-backup &&
          touch /tmp/backup-heartbeat;
          sleep 3600;
        done"
    environment:
      MINIO_ACCESS_KEY: ${MINIO_ACCESS_KEY}
      MINIO_SECRET_KEY: ${MINIO_SECRET_KEY}
      B2_ACCESS_KEY: ${B2_BACKUP_ACCESS_KEY}
      B2_SECRET_KEY: ${B2_BACKUP_SECRET_KEY}
    healthcheck:
      test: ["CMD-SHELL", "find /tmp/backup-heartbeat -mmin -120 | grep -q ."]
      interval: 60s
      timeout: 3s
      retries: 3
      start_period: 120s
    depends_on:
      minio:
        condition: service_healthy

  pgbackup:
    build: ./pgbackup
    restart: unless-stopped
    user: "1000:1000"
    entrypoint: >
      /bin/sh -c "
        while true; do
          PGPASSWORD=$$DB_PASS pg_dump -h postgres -U $$DB_USER $$DB_NAME |
          restic backup --stdin --stdin-filename postgres.sql
            -r b2:jpt-db-backup --password-file /run/secrets/restic_pass &&
          restic forget --prune --keep-daily 30 --keep-weekly 12
            -r b2:jpt-db-backup --password-file /run/secrets/restic_pass &&
          touch /tmp/pgbackup-heartbeat;
          sleep 86400;
        done"
    environment:
      DB_USER: ${DB_USER}
      DB_PASS: ${DB_PASS}
      DB_NAME: ${DB_NAME}
      B2_ACCOUNT_ID: ${B2_ACCOUNT_ID}
      B2_ACCOUNT_KEY: ${B2_ACCOUNT_KEY}
    healthcheck:
      test: ["CMD-SHELL", "find /tmp/pgbackup-heartbeat -mmin -1500 | grep -q ."]
      interval: 300s
      timeout: 3s
      retries: 3
      start_period: 300s
    secrets:
      - restic_pass
    depends_on:
      postgres:
        condition: service_healthy

  certbot:
    image: certbot/certbot
    volumes:
      - certbot_certs:/etc/letsencrypt
      - certbot_www:/var/www/certbot
    entrypoint: >
      /bin/sh -c "trap exit TERM; while :; do
        certbot renew --webroot -w /var/www/certbot;
        sleep 12h & wait $${!};
      done"

  # Prometheus and Grafana — deferred to monitoring phase

volumes:
  postgres_data:
  minio_data:
  redis_data:
  certbot_certs:
  certbot_www:

secrets:
  restic_pass:
    file: ./secrets/restic_pass.txt
```

**Step 2: Write `docker-compose.dev.yml` (override for local dev)**

```yaml
# docker-compose.dev.yml — local development overrides
services:
  api:
    ports: ["8080:8080"]

  postgres:
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: jpt
      POSTGRES_USER: jpt
      POSTGRES_PASSWORD: jpt

  minio:
    ports: ["9000:9000", "9001:9001"]
    environment:
      MINIO_BROWSER: "on"
    command: server /data --console-address ":9001"

  redis:
    ports: ["6379:6379"]

  # Don't run these in dev
  nginx:
    profiles: ["production"]
  backup:
    profiles: ["production"]
  pgbackup:
    profiles: ["production"]
  certbot:
    profiles: ["production"]
  # Prometheus and Grafana — deferred to monitoring phase
```

**Step 3: Write `.env.example`**

```bash
# .env.example — copy to .env and fill in values
DB_URL=jdbc:postgresql://postgres:5432/jpt
DB_USER=jpt
DB_PASS=jpt
DB_NAME=jpt
WORKER_DB_URL=jdbc:postgresql://postgres:5432/jpt
WORKER_DB_USER=worker_db_user
WORKER_DB_PASS=changeme
MINIO_ENDPOINT=http://minio:9000
MINIO_ACCESS_KEY=changeme
MINIO_SECRET_KEY=changeme
# MINIO_SERVER_URL=https://app.example.com  # Required for production pre-signed URLs
REDIS_URL=redis://redis:6379
REDIS_PASSWORD=changeme
JWT_SECRET=  # Generate: openssl rand -base64 64
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
SMTP_HOST=
SMTP_USER=
SMTP_PASS=
B2_ACCESS_KEY=
B2_SECRET_KEY=
B2_BACKUP_ACCESS_KEY=
B2_BACKUP_SECRET_KEY=
B2_ACCOUNT_ID=
B2_ACCOUNT_KEY=
```

**Step 4: Write stub `nginx.conf`**

Create a minimal nginx.conf that proxies to the API. This is a placeholder — the full nginx config with TLS, rate limiting, and security headers will be finalized in a later phase.

```nginx
events { worker_connections 1024; }

http {
    include       /etc/nginx/mime.types;
    default_type  application/octet-stream;

    server {
        listen 80;

        location / {
            root /usr/share/nginx/html;
            try_files $uri $uri/ /index.html;
        }

        # NOTE: Trailing slash strips /api/ prefix — API routes are /auth/login, not /api/auth/login
        location /api/ {
            proxy_pass http://api:8080/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        # TODO: Add for production — certbot ACME challenge for TLS cert renewal
        # location /.well-known/acme-challenge/ {
        #     root /var/www/certbot;
        # }
    }
}
```

**Step 5: Provision secrets directory**

```bash
mkdir -p secrets
echo '*' > secrets/.gitignore
echo '!.gitignore' >> secrets/.gitignore
# For dev, create a dummy restic password:
openssl rand -base64 32 > secrets/restic_pass.txt
```

**Step 6: Create empty `react-build` placeholder**

```bash
mkdir -p react-build
echo '<html><body>Frontend not built yet</body></html>' > react-build/index.html
```

> **Note:** The `react-build/` directory is a placeholder. The frontend build phase will populate it. In dev, nginx is disabled via profiles so this directory is unused.

**Step 7: Verify the dev stack starts**

```bash
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.dev.yml up postgres minio redis -d
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps --format "{{.Name}} {{.Health}}"
```

Expected: postgres, minio, redis all show `healthy`.

**Step 8: Tear down and commit**

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
git add docker-compose.yml docker-compose.dev.yml .env.example nginx.conf secrets/.gitignore react-build/index.html .gitignore
git commit -m "infra: Docker Compose stack with all services"
```

---

### Task 1.6: API and Worker Dockerfiles

**Files:**
- Create: `api/Dockerfile`
- Create: `api/.dockerignore`
- Create: `worker/Dockerfile`
- Create: `worker/.dockerignore`
- Create: `pgbackup/Dockerfile`
- Modify: `api/build.gradle.kts` (fixed JAR name)
- Modify: `worker/build.gradle.kts` (fixed JAR name — when worker module exists)

**Step 1: Configure fixed JAR names in Gradle**

In `api/build.gradle.kts`, add:

```kotlin
tasks.bootJar {
    archiveFileName.set("app.jar")
}
```

> **Note:** The worker module does not exist yet. When it is scaffolded in a later phase, apply the same `archiveFileName.set("app.jar")` configuration.

**Step 2: Write `api/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

COPY build/libs/app.jar app.jar
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

> **Note:** Health check is defined in `docker-compose.yml`, not in the Dockerfile, so it can be tuned per environment.

**Step 3: Write `api/.dockerignore`**

```
.git
.gradle
.idea
node_modules
src
build/tmp
build/classes
build/generated
build/reports
*.md
```

**Step 4: Write `worker/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    libraw-utils \
    vips-tools \
    perl perl-image-exiftool \
    tini

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Non-root user
RUN addgroup -S worker && adduser -S worker -G worker
USER worker

WORKDIR /app
COPY build/libs/app.jar app.jar
ENTRYPOINT ["/sbin/tini", "--", "sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

> **Note:** The worker module does not exist yet. This Dockerfile is committed as a placeholder. The worker health check uses a file-based heartbeat at `/tmp/worker-heartbeat` — the worker process must write to this file on each processing cycle. The health check is defined in `docker-compose.yml`.

**Step 5: Write `worker/.dockerignore`**

```
.git
.gradle
.idea
node_modules
src
build/tmp
build/classes
build/generated
build/reports
*.md
```

**Step 6: Write `pgbackup/Dockerfile`**

```dockerfile
FROM postgres:16

RUN apt-get update && apt-get install -y --no-install-recommends \
    restic \
    && rm -rf /var/lib/apt/lists/*
```

> **Note:** Uses the restic version available in the Debian Bookworm repos. If a newer version is needed, switch to downloading the binary from GitHub releases with checksum verification.

**Step 7: Commit**

```bash
git add api/Dockerfile api/.dockerignore worker/Dockerfile worker/.dockerignore pgbackup/Dockerfile api/build.gradle.kts
git commit -m "infra: Dockerfiles for api, worker, pgbackup"
```

---

**Next Phase:** [Phase 2: Backend API — Auth, Security, REST Endpoints](2026-02-25-saas-conversion-phase-2.md)

---

## Changelog

### v3.0 (2026-02-26) — Post critical review #2

Review document: `docs/plans/2026-02-25-saas-conversion-phase-1b-critical-review-2.md`

**Critical issues addressed:**
- **[2.1] Prometheus/Grafana network isolation** — Deferred monitoring entirely; removed Prometheus and Grafana services, volumes, and network config from Compose stack
- **[2.2] Silent backup failures** — Added file-based heartbeat health checks to both `backup` (2h threshold) and `pgbackup` (25h threshold) containers; `touch` on successful backup, health check verifies recency

**Minor issues addressed:**
- **[3.1] API security hardening** — Added `cap_drop: ALL` and `security_opt: no-new-privileges:true` to API service, matching worker hardening
- **[3.2] Redis maxmemory** — Added `--maxmemory 256mb --maxmemory-policy noeviction` to Redis command
- **[3.3] Nginx ACME challenge** — Added TODO comment for production ACME challenge location block
- **[3.6] Missing prometheus.yml** — Resolved by removing monitoring services
- **[3.7] react-build in .gitignore** — Added `react-build/` to `.gitignore`

**Clarifications resolved:**
- **[Q1] MINIO_SERVER_URL** — Added commented-out entry in `.env.example`; deferred to production deployment
- **[Q2] Worker DB credentials** — Added `WORKER_DB_URL` env var; worker service now uses its own DB URL instead of sharing with API
- **[Q3] Nginx proxy stripping** — Added comment documenting that `/api/` prefix is stripped; API routes use paths without prefix

**Issues dismissed:**
- **[3.4] Data store network segmentation** — Deferred; single-user initial deployment
- **[3.5] MinIO env var naming** — Cosmetic; no functional impact

### v2.0 (2026-02-26) — Post critical review

Review document: `docs/plans/2026-02-25-saas-conversion-phase-1b-critical-review-1.md`

**Critical issues addressed:**
- **[2.1] Full inline Compose YAML** — Replaced "reference the design doc" delegation with complete, valid `docker-compose.yml` specification including all services, proper `${VAR}` interpolation syntax, and comments
- **[2.2] Certbot dev override** — Dismissed (false positive: already present in v1.0)
- **[2.3] Volumes and networks** — Added explicit top-level `volumes:` (7 named volumes) and `networks:` (internal network) definitions
- **[2.4] Secrets** — Added top-level `secrets:` definition for `restic_pass`, provisioning instructions, and `secrets/.gitignore`
- **[2.5] API JVM flags + health check** — Added `JAVA_OPTS` with `MaxRAMPercentage` and G1GC to Dockerfile; health check defined in Compose file (easier to tune per environment)
- **[2.6] Worker health check** — Added file-based heartbeat health check (`/tmp/worker-heartbeat`) in Compose; documented that worker process must write heartbeat on each cycle
- **[2.7] Fragile JAR glob** — Changed to fixed JAR name (`app.jar`) via Gradle `archiveFileName.set("app.jar")`; both Dockerfiles now `COPY build/libs/app.jar`
- **[2.8] Restic version pin** — Removed hardcoded `restic=0.16.4-1`; use distro-provided version via `apt-get install restic`

**Minor issues addressed:**
- **[3.1]** Changed MinIO `.env.example` defaults from `minioadmin` to `changeme`
- **[3.3]** Added `api: ports: ["8080:8080"]` to dev override; also disabled nginx in dev via profiles
- **[3.4]** Added `.dockerignore` files for `api/` and `worker/`
- **[3.6]** Added `restart: unless-stopped` to all production services
- **[3.7]** Improved verification step to check health status via `--format "{{.Name}} {{.Health}}"`

**Clarifications resolved:**
- **[Q1]** Added stub `nginx.conf` with basic proxy_pass config (placeholder for full TLS/rate-limiting config in later phase)
- **[Q2]** Added `react-build/index.html` placeholder; nginx disabled in dev via profiles
- **[Q3]** Documented that certbot bootstraps certs on first run (deployment concern, not Phase 1b)
- **[Q4]** Documented that worker Dockerfile is a placeholder; worker module scaffolded in a later phase

**Minor issues dismissed:**
- **[3.2]** `DB_NAME` mapping — already present in `.env.example`; resolved by full inline YAML
- **[3.5]** Alpine package version pinning — fragile across Alpine releases; base image tag provides sufficient reproducibility

### v1.0 (2026-02-25) — Initial plan
- Initial specification with delegation to design doc Section 7
