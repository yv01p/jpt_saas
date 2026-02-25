# JPhotoTagger SaaS Conversion — Phase 5: Sharing & Polish

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

### Task 5.1: Share Token Service

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/service/ShareService.java`
- Create: `api/src/main/java/org/jphototagger/api/controller/ShareController.java`

**Step 1: Write failing tests**

```java
@Test
void createShare_stores256BitTokenHash() { }

@Test
void createShare_returnsPlaintextTokenOnce() { }

@Test
void lookupShare_byHashedToken() { }

@Test
void expiredShareReturns404() { }

@Test
void shareToDeletedPhotoReturns404() { }

@Test
void shareStripsGpsByDefault() { }
```

**Step 2: Implement ShareService**

- `SecureRandom` 256-bit token generation
- SHA-256 hash storage
- Default 30-day expiry
- GPS stripping on shared photo metadata
- Join photos table and filter `deleted_at IS NULL`

**Step 3: Implement ShareController**

- `POST /shares` — create share (authenticated)
- `GET /share/{token}` — public access (unauthenticated)
- `DELETE /shares/{id}` — revoke share (authenticated)
- `GET /shares` — list user's shares (authenticated)

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: share tokens — 256-bit SecureRandom, SHA-256 storage, GPS stripping"
```

### Task 5.2: Share Frontend — Public View + Manage Shares UI

**Files:**
- Create: `frontend/src/pages/SharePage.tsx`
- Modify: `frontend/src/pages/SettingsPage.tsx` — add Manage Shares section

**Step 1: Write tests**

**Step 2: Implement SharePage**

Public photo/album view without auth. No GPS by default.

**Step 3: Implement Manage Shares section in Settings**

List active shares with creation date, expiry, resource. Revoke individual or bulk.

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: public share view and manage shares UI"
```

### Task 5.3: Nginx Configuration File

**Files:**
- Create: `nginx/nginx.conf`

**Step 1: Write nginx.conf per design Section 7**

Include all rate limit zones (http{} context), HTTPS redirect, SSL config, security headers, all location blocks per the design document exactly.

**Step 2: Validate**

Run: `docker compose exec nginx nginx -t`
Expected: syntax ok, test successful

**Step 3: Commit**

```bash
git add nginx/
git commit -m "infra: Nginx config — rate limits, HTTPS, MinIO proxy, security headers"
```

### Task 5.4: Monitoring — Prometheus + Grafana

**Files:**
- Create: `prometheus.yml`
- Create: `grafana/provisioning/dashboards/jpt.json` (optional)

**Step 1: Configure Prometheus scrape targets**

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'api'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['api:8080']
  - job_name: 'worker'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['worker:8080']
```

**Step 2: Configure alerting rules for the 4 baseline alerts**

- VPS disk >80%
- Redis memory >80%
- Redis Streams pending >50 for >10 min
- API 5xx rate >1% over 5 min

**Step 3: Commit**

```bash
git add prometheus.yml grafana/
git commit -m "infra: Prometheus config and alerting rules"
```

### Task 5.5: CI Pipeline — GitHub Actions

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/deploy.yml`

**Step 1: Write CI workflow**

```yaml
# .github/workflows/ci.yml
name: CI
on: [pull_request]
jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21, distribution: temurin }
      - run: ./gradlew build
      - run: ./gradlew test  # includes Testcontainers integration tests
  frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20 }
      - run: cd frontend && npm ci && npm test
  e2e:
    runs-on: ubuntu-latest
    needs: [backend, frontend]
    steps:
      - uses: actions/checkout@v4
      - run: docker compose up -d
      - run: cd frontend && npx playwright test
  nginx-validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: docker run --rm -v $PWD/nginx/nginx.conf:/etc/nginx/nginx.conf:ro nginx:alpine nginx -t
```

**Step 2: Write deploy workflow (on merge to master)**

Per design Section 8 — build, sign, rsync, verify, healthcheck, rollback.

**Step 3: Commit**

```bash
git add .github/
git commit -m "ci: GitHub Actions — CI pipeline and deploy workflow"
```

### Task 5.6: Final Integration Test — Full E2E

**Files:**
- Create: `frontend/e2e/full-journey.spec.ts`

**Step 1: Write Playwright E2E test**

```typescript
test('full user journey', async ({ page }) => {
    // 1. Register
    // 2. Login
    // 3. Upload a JPEG photo
    // 4. Wait for processing to complete
    // 5. Verify photo appears in grid
    // 6. View photo metadata
    // 7. Add keyword
    // 8. Create album, add photo
    // 9. Search by keyword
    // 10. Create share link
    // 11. Open share link in incognito — verify photo visible
    // 12. Delete photo — verify in trash
    // 13. Restore photo — verify back in library
});
```

**Step 2: Run E2E against Docker Compose stack**

Run: `docker compose up -d && cd frontend && npx playwright test`
Expected: PASS

**Step 3: Commit**

```bash
git add frontend/e2e/
git commit -m "test: full E2E journey — register through share and trash"
```

---

**Project Complete.**
