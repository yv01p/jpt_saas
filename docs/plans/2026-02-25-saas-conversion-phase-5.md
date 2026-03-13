# JPhotoTagger SaaS Conversion — Phase 5: Sharing & Polish

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Version:** 7.0
**Last updated:** 2026-03-12
**Reviews incorporated:** `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-1.md`, `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-2.md`, `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-3.md`, `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-4.md`, `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-5.md`, `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-6.md`, `docs/plans/2026-03-12-saas-conversion-phase-5-security-audit-1.md`

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

### Task 5.1: Share Token Service

**Prerequisite 1:** Add `/share/**` to the public (unauthenticated) paths in `SecurityConfig.java`. This was intentionally removed in Phase 2 (v3.0, M3) to avoid exposing a 404 endpoint before the controller exists. It must be re-added here.

**Prerequisite 2: Production EmailService (C22 fix, M31 fix, M33 fix)**

No SMTP-capable `EmailService` exists in the codebase — only `StubEmailService` (active in `dev`/`test` profiles, logs to stdout). In docker-compose (no profile set), no `EmailService` bean is available, causing `NoSuchBeanDefinitionException` on startup. A minimal implementation is required for E2E tests and eventual production use.

1. Add `spring-boot-starter-mail` to `api/build.gradle.kts`:
   ```kotlin
   implementation("org.springframework.boot:spring-boot-starter-mail")
   ```

2. Add `spring.mail.*` properties to `api/src/main/resources/application.yml`:
   ```yaml
   spring:
     mail:
       host: ${SMTP_HOST:localhost}
       port: ${SMTP_PORT:587}
       username: ${SMTP_USER:}
       password: ${SMTP_PASS:}
       properties:
         mail.smtp.auth: true
         mail.smtp.starttls.enable: true
   ```

3. Create `api/src/main/java/org/jphototagger/api/service/SimpleEmailService.java` — a minimal, default-profile implementation that sends plain text emails via `JavaMailSender`. This is a placeholder to be replaced with a proper HTML-templated implementation before production go-live:
   ```java
   @Service
   @Profile("!dev & !test")
   public class SimpleEmailService implements EmailService {
       @Autowired private JavaMailSender mailSender;
       @Value("${app.base-url}") private String baseUrl;

       @Override
       public void sendVerificationEmail(String to, String token) {
           var msg = new SimpleMailMessage();
           msg.setTo(to);
           msg.setSubject("Verify your email");
           msg.setText(baseUrl + "/auth/verify?token=" + token);
           mailSender.send(msg);
       }
       // similar for sendPasswordResetEmail
   }
   ```

4. Add `SMTP_PORT: ${SMTP_PORT:-587}` to the API service environment in `docker-compose.yml` (M31 fix — `SMTP_HOST`, `SMTP_USER`, `SMTP_PASS` are already passed but `SMTP_PORT` is missing; Spring Mail defaults to port 25/587, but MailPit in CI uses port 1025).

5. Add `COOKIE_SECURE: ${COOKIE_SECURE:-true}` to the API service environment in `docker-compose.yml` (M33 fix — `application.yml` reads `${COOKIE_SECURE:true}` but the env var is not plumbed through docker-compose; in CI with HTTP-only nginx, the `Secure` cookie flag prevents Playwright from sending cookies, breaking all post-login E2E steps).

6. Add to `.env.example`: `SMTP_PORT=587` and `COOKIE_SECURE=true`.

7. Add to `.env.ci`: `COOKIE_SECURE=false` (CI uses HTTP-only nginx).

8. Add a startup validation (SA-F8 fix) to reject known-weak JWT secrets in non-test environments:
   ```java
   @Value("${JWT_SECRET}")
   private String jwtSecret;

   @PostConstruct
   void validateSecrets() {
       if (jwtSecret.startsWith("ci_test")) {
           throw new IllegalStateException("CI test JWT secret detected — do not use .env.ci in production");
       }
   }
   ```

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java` — add `/share/**` to public paths
- Create: `api/src/main/java/org/jphototagger/api/service/ShareService.java`
- Create: `api/src/main/java/org/jphototagger/api/controller/ShareController.java`
- Create: Flyway migration `V10__share_reader_role.sql` — create `share_reader` PostgreSQL role (M21 fix)
- Create: `api/src/main/java/org/jphototagger/api/config/ShareReaderDataSourceConfig.java` — secondary DataSource for RLS bypass

**Step 1: Create `share_reader` PostgreSQL role (C1 fix, C10 clarification, C15 fix, C20 fix)**

The `shares` table has RLS enabled with policy `user_id = current_setting('app.current_user_id')::uuid`. Unauthenticated `GET /share/{token}` requests have no user context, so share lookups require an RLS bypass.

> **NOTE:** Migration V4 already created `jpt_auth` with `BYPASSRLS` for authentication operations (login, registration, email verification). `share_reader` is intentionally a **separate** role — `jpt_auth` is scoped to `users` and `email_tokens` tables only. Granting share/photo access to `jpt_auth` would violate least-privilege. Each role has a single, clear purpose.

Create Flyway migration `V10__share_reader_role.sql` that:
- Creates a `share_reader` role using Flyway `${placeholder}` syntax (NOT psql `:'variable'` syntax), consistent with V4's `jpt_auth` role creation. Wrapped in `IF NOT EXISTS` guard for idempotency, matching V4's pattern:
  ```sql
  DO $$
  BEGIN
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'share_reader') THEN
          CREATE ROLE share_reader WITH LOGIN PASSWORD '${share_reader_password}' BYPASSRLS;
      END IF;
      EXECUTE format('GRANT CONNECT ON DATABASE %I TO share_reader', current_database());
  END
  $$;
  GRANT SELECT ON shares, photos, albums, album_photos, photo_metadata TO share_reader;
  ```
- Does NOT grant superuser or any write permissions
- Include comment: `-- NOTE: jpt_auth (V4) handles auth operations; share_reader is intentionally separate for least-privilege isolation`

Add Flyway placeholder configuration in `api/src/main/resources/application.yml`:
```yaml
spring:
  flyway:
    placeholders:
      share_reader_password: ${SHARE_READER_PASSWORD}
```

Add secondary DataSource configuration in `api/src/main/resources/application.yml`:
```yaml
app:
  share-reader:
    jdbc-url: ${DB_URL:jdbc:postgresql://localhost:5432/jpt}
    username: share_reader
    password: ${SHARE_READER_PASSWORD}
    hikari:
      maximum-pool-size: 3
```

> **NOTE (M26 fix):** Pool size is intentionally small — share lookups are infrequent, read-only, and short-lived. HikariCP defaults to 10 connections if unspecified, which wastes PostgreSQL connections on a resource-constrained VPS.

Add `SHARE_READER_PASSWORD: ${SHARE_READER_PASSWORD}` to the API service environment in `docker-compose.yml`.

Add `SHARE_READER_PASSWORD=` to `.env.example`.

Create `ShareReaderDataSourceConfig.java` using `@ConfigurationProperties("app.share-reader")` to wire a `shareReaderDataSource` bean. The `@Bean` method must be **package-private** (no `public` modifier) to prevent injection outside the config package. Create a dedicated `ShareLookupRepository` class (not a general-purpose repository) that is the **sole consumer** of `shareReaderDataSource` for unauthenticated share lookups via parameterized native queries.

> **SECURITY (SA-F1 fix):** Although `share_reader` has `BYPASSRLS`, the risk is mitigated by: (1) package-private bean scoping, (2) a dedicated repository class as the sole consumer, (3) an ArchUnit test enforcing this constraint (see Step 2), (4) parameterized queries (Spring Data JPA default — eliminates SQL injection), and (5) column-limited queries (select only needed columns, not `SELECT *`). The share lookup query must also validate token format (hex string, 64 chars for SHA-256 output) before querying.

> **SECURITY (SA-F3 fix):** All authenticated share endpoints (`POST /shares`, `DELETE /shares/{id}`, `GET /shares`) use the **primary DataSource** with RLS active. Only the unauthenticated `GET /share/{token}` lookup uses `shareReaderDataSource`.

**Step 2: Write failing tests**

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

@Test
void unauthenticatedShareLookup_succeedsWithoutUserContext() { }

@Test
void deleteShare_byOtherUser_returns404() { }  // SA-F3 fix — verify RLS prevents cross-tenant deletion

@Test
void shareReaderDataSource_onlyUsedByShareLookupRepository() { }  // SA-F1 fix — ArchUnit test
```

> **NOTE (SA-F1 fix):** The ArchUnit test verifies that only `ShareLookupRepository` accesses the `shareReaderDataSource` bean. This prevents accidental RLS bypass through other code paths.

**Step 3: Implement ShareService (M22 fix)**

- `SecureRandom` 256-bit token generation
- SHA-256 hash storage
- Default 30-day expiry
- GPS stripping on shared photo metadata (unless `includeGps` is true)
- Join photos table and filter `deleted_at IS NULL`
- Unauthenticated lookups use `shareReaderDataSource` to bypass RLS
- **SECURITY (SA-F2 fix):** All share lookup failures (not found, expired, deleted resource) must return identical 404 responses via a single code path — do not leak failure reason in status code, response body, or headers
- Add paginated repository method for `GET /shares` endpoint: `Page<Share> findByUserId(UUID userId, Pageable pageable)` — the existing `List<Share> findByUserId(UUID userId)` returns an unpaginated list, which is incompatible with Spring Data's `Pageable`. All other list endpoints use `Page<T>`.

**Step 4: Implement ShareController**

- `POST /shares` — create share (authenticated)
- `GET /share/{token}` — public access (unauthenticated), uses RLS-bypassing DataSource
  - For photo shares: returns photo metadata and image URL
  - For album shares: returns share metadata plus paginated photo list (`GET /share/{token}/photos?page=0&size=20`)
- `DELETE /shares/{id}` — revoke share (authenticated)
- `GET /shares?page=0&size=20` — list user's shares (authenticated, paginated via `Pageable`, consistent with all other list endpoints) (M14 fix)

**Step 5: Run tests, verify pass**

**Step 6: Commit**

```bash
git commit -m "feat: share tokens — 256-bit SecureRandom, SHA-256 storage, GPS stripping, RLS bypass via share_reader role"
```

### Task 5.2: Share Frontend — Public View + Manage Shares UI

**Files:**
- Create: `frontend/src/pages/SharePage.tsx`
- Modify: `frontend/src/pages/SettingsPage.tsx` — add Manage Shares section
- Modify: `frontend/src/App.tsx` — add `/share/:token` public route (M2 fix)
- Modify: `frontend/src/api/types.ts` — update `ShareToken` type (M1 fix)

**Step 1: Update `ShareToken` type in `frontend/src/api/types.ts` (M1 fix)**

Replace the incomplete `ShareToken` type with the full model matching the backend `Share` entity:

```typescript
export interface ShareToken {
  id: string;
  token: string;
  resourceType: 'photo' | 'album';
  resourceId: string;
  expiresAt: string | null;
  includeGps: boolean;
  permissions: string;
  createdAt: string;
}
```

**Step 2: Add `/share/:token` route to `App.tsx` (M2 fix)**

Add the public share route alongside `/login` and `/register`, outside `ProtectedRoute`:

```tsx
<Route path="/share/:token" element={<SharePage />} />
```

**Step 3: Write tests**

**Step 4: Implement SharePage (M7 fix)**

Public view without auth. Render based on resource type:

- **Photo share:** Single photo view with metadata. GPS stripped unless `includeGps` is true.
- **Album share:** Paginated photo grid showing all photos in the album. Reuse the existing `LibraryPage` grid component layout in a read-only, unauthenticated context. Each photo clickable for full-size view. No edit/delete/keyword actions.

> **SECURITY (SA-F11 fix):** All metadata rendering in `SharedPhotoView` and `SharedAlbumView` must use React's default JSX escaping (plain `{value}` expressions). Do not use `dangerouslySetInnerHTML` for any user-uploaded metadata fields (EXIF/IPTC/XMP). Photo metadata fields originate from uploaded files and may contain attacker-controlled content. Add a code comment: `// SECURITY: Never use dangerouslySetInnerHTML for user-uploaded metadata`.

```tsx
if (share.resourceType === 'photo') {
  return <SharedPhotoView photo={photo} includeGps={share.includeGps} />;
} else {
  return <SharedAlbumView albumId={share.resourceId} includeGps={share.includeGps} />;
}
```

**Step 5: Implement Manage Shares section in Settings**

List active shares with creation date, expiry, resource type, resource name. Revoke individual or bulk.

**Step 6: Run tests, verify pass**

**Step 7: Commit**

```bash
git commit -m "feat: public share view (photo + album), manage shares UI, updated ShareToken type"
```

### Task 5.3: Nginx Configuration Enhancements

**Files:**
- Modify: `nginx.prod.conf` (existing file at project root, mounted by docker-compose as `/etc/nginx/nginx.conf:ro`)

**Step 1: Add missing rate-limit zones (C2 fix)**

Add to the `http{}` context alongside the existing `auth` zone:

```nginx
limit_req_zone $binary_remote_addr zone=login:10m rate=10r/m;
limit_req_zone $binary_remote_addr zone=register:10m rate=5r/m;
limit_req_zone $binary_remote_addr zone=share:10m rate=60r/m;
```

**Step 2: Add dedicated location blocks (C2, C3 fix)**

Add these location blocks, ordered before the generic `/api/` block. The existing `/api/auth/` location block MUST be preserved — it serves as the fallback for `/auth/verify`, `/auth/refresh`, and other auth endpoints. The new `/api/auth/login` and `/api/auth/register` blocks take precedence via nginx longest-prefix matching. (M15 fix)

1. `/api/auth/login` — with `login` rate-limit zone
2. `/api/auth/register` — with `register` rate-limit zone
3. `/api/share/` — with `share` rate-limit zone (prevents token enumeration):
   ```nginx
   location /api/share/ {
       limit_req zone=share burst=10 nodelay;
       proxy_pass http://api:8080/share/;
       proxy_set_header Host $host;
       proxy_set_header X-Real-IP $remote_addr;
       proxy_set_header X-Forwarded-For $remote_addr;
       proxy_set_header X-Forwarded-Proto https;
   }
   ```
4. `/share/` — SPA location for frontend share pages with `try_files` and share rate limiting
5. MinIO proxy with regex path guard and `Authorization ""` header stripping per design Section 7 (SA-F9 fix — the complete config block must be specified, not just described):
   ```nginx
   # MinIO object storage — proxied, not exposed directly
   # HARD REQUIREMENT: MinIO bucket policy MUST be private (no anonymous access).
   # Pre-signed URLs are the sole access path.
   location ~ ^/photos/[a-f0-9-]+/(originals|thumbnails)/[a-f0-9-]+ {
       proxy_pass http://minio:9000;
       proxy_set_header Host $host;
       proxy_set_header Authorization "";  # Strip ambient credentials at proxy layer
   }
   ```
   Also add the corresponding block to `nginx.ci.conf`.

**Step 3: Fix CSP `style-src` (M3 fix)**

Update the Content-Security-Policy header to include `'unsafe-inline'` for styles, required by Tailwind/shadcn (documented in design v4.0, SA#12/CR#7):

```
style-src 'self' 'unsafe-inline';
```

**Step 4: Validate**

```bash
docker run --rm -v $PWD/nginx.prod.conf:/etc/nginx/nginx.conf:ro nginx:alpine nginx -t
```
Expected: syntax ok, test successful

**Step 5: Commit**

```bash
git commit -m "infra: enhance nginx.prod.conf — share/login/register rate limits, MinIO proxy, CSP fix"
```

### Task 5.4: Monitoring — Prometheus + Grafana + Alertmanager

**Files:**
- Create: `prometheus.yml`
- Create: `alert_rules.yml`
- Create: `alertmanager.yml` (M8 fix)
- Create: `grafana/provisioning/dashboards/jpt.json` (optional)
- Modify: `docker-compose.yml` — add prometheus, grafana, alertmanager, node-exporter, and redis-exporter services (M4, M9, C17 fix)
- Modify: `api/build.gradle.kts` — add `micrometer-registry-prometheus` dependency (C9 fix)
- Modify: `api/src/main/resources/application.yml` — expose prometheus actuator endpoint (C9 fix)
- Modify: `worker/src/main/resources/application.yml` — expose prometheus actuator endpoint (C9 fix)

**Step 1: Enable Actuator Prometheus endpoint (C9 fix)**

The `micrometer-registry-prometheus` dependency already exists in `server/build.gradle.kts` and `worker/build.gradle.kts`, but is **missing** from `api/build.gradle.kts`. Add it:

```kotlin
implementation("io.micrometer:micrometer-registry-prometheus")
```

Update both `application.yml` files to expose the prometheus endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

Verify that the existing nginx block `location /api/actuator/ { return 404; }` keeps `/actuator/prometheus` internal-only. Prometheus accesses it via the `backend` Docker network, not through nginx.

**Step 2: Configure Prometheus scrape targets**

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']

rule_files:
  - 'alert_rules.yml'

scrape_configs:
  - job_name: 'api'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['api:8080']
  - job_name: 'worker'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['worker:8080']
  - job_name: 'node'
    static_configs:
      - targets: ['node-exporter:9100']
  - job_name: 'redis'
    static_configs:
      - targets: ['redis-exporter:9121']
```

**Step 3: Create `alert_rules.yml` with the 4 baseline alerts**

- VPS disk >80%
- Redis memory >80%
- Redis Streams pending >50 for >10 min
- API 5xx rate >1% over 5 min

**Step 4: Create `alertmanager.yml.example` for email delivery (M8 fix, SA-F4 fix)**

Ship `alertmanager.yml.example` in the repo with placeholder values. Add `alertmanager.yml` to `.gitignore` — production credentials must never be committed. The actual `alertmanager.yml` is managed on-VPS (protected from rsync by `--exclude`, see Task 5.5).

```yaml
global:
  smtp_smarthost: 'smtp.example.com:587'  # Set to actual SMTP provider hostname, matching SMTP_HOST in .env (M16 fix)
  smtp_from: 'alerts@yourdomain.com'
  smtp_require_tls: true
  smtp_auth_username: 'alertmanager'  # Set to actual SMTP username on VPS
  smtp_auth_password_file: /run/secrets/smtp_alert_password  # SA-F4 fix — read from Docker secret, not plaintext

route:
  receiver: 'email'
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h

receivers:
  - name: 'email'
    email_configs:
      - to: 'admin@yourdomain.com'
```

Mount the SMTP password via Docker secrets in `docker-compose.yml`:
```yaml
alertmanager:
  secrets:
    - smtp_alert_password
```

> **NOTE (M16 fix):** `smtp_smarthost` must be set to the actual SMTP provider hostname (e.g., `smtp.mailgun.org`), NOT `localhost`. Inside a Docker container, `localhost` refers to the container itself. Use the same SMTP host as `SMTP_HOST` in `.env`. Alertmanager does not support env var substitution in its config file, so `smtp_smarthost` and `smtp_auth_username` must be set manually on the VPS before deployment. The password is read from a Docker secret file (`smtp_auth_password_file`, supported since Alertmanager v0.22.0).

**Step 5: Add services to `docker-compose.yml` (M4, M9, M11, M12, C17 fix)**

Replace the placeholder comment `# Prometheus and Grafana — deferred to monitoring phase` with:

- `prometheus` service: image `prom/prometheus:v2.53.0`, mount `prometheus.yml` and `alert_rules.yml`, connect to `backend` network, expose port 9090 (internal only), persistent volume for data
- `grafana` service: image `grafana/grafana:11.1.0`, mount `grafana/provisioning/`, connect to `backend` network, expose port 3000 (internal only), depends on `prometheus`, persistent volume for data. Access via SSH port forwarding only (per design doc) — no nginx proxy or `frontend` network needed.
- `alertmanager` service: image `prom/alertmanager:v0.27.0`, mount `alertmanager.yml`, connect to **both `backend` and `frontend` networks** (M32 fix — `backend` is `internal: true`, alertmanager needs external network access to reach SMTP servers), expose port 9093 (internal only). Use `smtp_auth_password_file` for credential management (SA-F4 fix — see Step 4)
- `node-exporter` service (M9, M17 fix):
  ```yaml
  node-exporter:
    image: prom/node-exporter:v1.8.1
    pid: host
    volumes:
      - /proc:/host/proc:ro
      - /sys:/host/sys:ro
      - /:/rootfs:ro
    command:
      - '--path.procfs=/host/proc'
      - '--path.sysfs=/host/sys'
      - '--path.rootfs=/rootfs'
    networks:
      - backend
    read_only: true
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    mem_limit: 64m
    cpus: '0.25'
  ```
  `pid: host` and `--path.*` flags are required to report true host metrics rather than container-namespaced metrics. Container hardening (`read_only`, `cap_drop`, `security_opt`, resource limits) follows the pattern applied to all other services (SA-F5 fix).

- `redis-exporter` service (C17 fix — required for Redis baseline alerts):
  ```yaml
  redis-exporter:
    image: oliver006/redis_exporter:v1.62.0
    environment:
      REDIS_ADDR: redis://redis:6379
      REDIS_PASSWORD: ${REDIS_PASSWORD}
    networks:
      - backend
    depends_on:
      redis:
        condition: service_healthy
    mem_limit: 64m
    cpus: '0.25'
  ```
  Without this service, the two Redis baseline alerts ("Redis memory >80%", "Redis Streams pending >50") have no data source and will never fire.

**Step 6: Commit**

```bash
git add prometheus.yml alert_rules.yml alertmanager.yml grafana/ docker-compose.yml
git commit -m "infra: Prometheus, Grafana, Alertmanager — scrape targets, alert rules, email delivery"
```

### Task 5.5: CI Pipeline — GitHub Actions

**Files:**
- Modify: `.github/workflows/ci.yml` — add jobs alongside existing Trivy scanning (C4 fix)
- Create: `.github/workflows/deploy.yml`
- Create: `.env.ci` — environment variables with test-safe defaults for CI (M13, M20 fix)
- Create: `docker-compose.ci.yml` — compose override removing non-essential services and switching nginx to HTTP-only for CI (M20, M24 fix)
- Create: `nginx.ci.conf` — HTTP-only nginx config for CI (no TLS certificates required) (M24 fix)

**Step 1: Add CI jobs to existing workflow (C4 fix)**

The existing `ci.yml` contains two Trivy security scanning jobs (`trivy-scan` and `trivy-docker-worker`). These MUST be preserved. Add the following new jobs alongside them:

```yaml
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21, distribution: temurin }
      - run: ./gradlew build  # includes test task via Gradle dependency graph (M18 fix)
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
      - name: Create CI environment
        run: cp .env.ci .env
      - run: docker compose -f docker-compose.yml -f docker-compose.ci.yml up -d --wait --wait-timeout 120  # M19, M20, M29 fix
      - run: cd frontend && npm ci && npx playwright install --with-deps  # M23 fix — install browsers + OS deps on E2E runner
      - run: cd frontend && npx playwright test
  nginx-validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: docker run --rm -v $PWD/nginx.prod.conf:/etc/nginx/nginx.conf:ro nginx:alpine nginx -t
```

Note: `nginx-validate` references `nginx.prod.conf` (not `nginx/nginx.conf`).

Create `docker-compose.ci.yml` override (M20, M24, C21 fix) that:
- Removes services not needed for E2E testing (backup, pgbackup, certbot)
- Overrides the nginx service to use `nginx.ci.conf` (HTTP-only) instead of `nginx.prod.conf` (which requires TLS certificates that don't exist in CI)
- Adds MailPit service for E2E email verification (C21 fix):
  ```yaml
  services:
    nginx:
      volumes:
        - ./nginx.ci.conf:/etc/nginx/nginx.conf:ro
      ports:
        - "80:80"
    mailpit:
      image: axllent/mailpit:v1.21
      networks:
        - backend
      ports:
        - "8025:8025"
      mem_limit: 64m
      cpus: '0.25'
  ```
  MailPit captures all SMTP traffic on port 1025 and exposes a REST API on port 8025 for programmatic email retrieval. The E2E test uses this API to extract verification tokens.

Create `nginx.ci.conf` (M24 fix) — a stripped-down copy of `nginx.prod.conf` with:
- HTTP only (no `ssl_*` directives, no port 443, no HTTPS redirect)
- Same `proxy_pass` rules and location blocks as production
- Same CSP/security headers (so E2E tests validate them), **except** `img-src` which must use `http://localhost:9000` instead of `https://minio.yourdomain.com` to match the CI MinIO URL (M27 fix — intentional CI deviation, add comment in config):
  ```nginx
  # CI deviation: img-src uses localhost:9000 instead of production MinIO domain
  img-src 'self' data: blob: http://localhost:9000;
  ```
- This avoids the need for self-signed certificates while still testing the nginx proxy layer.

Create `.env.ci` with test-safe defaults for the docker-compose stack (M13, M20 fix). These are not real secrets — CI is an ephemeral, isolated environment. Must include ALL variables referenced by `docker-compose.yml`:

```
# CI-ONLY — DO NOT use in production. All values are intentionally weak test defaults. (SA-F8 fix)

# Database
DB_URL=jdbc:postgresql://postgres:5432/jpt
DB_USER=jpt_app
DB_PASS=ci_test_password
DB_NAME=jpt
FLYWAY_DB_URL=jdbc:postgresql://postgres:5432/jpt
FLYWAY_DB_USER=jpt_admin
FLYWAY_DB_PASS=ci_test_password
JPT_AUTH_PASSWORD=ci_test_auth_password
SHARE_READER_PASSWORD=ci_test_share_reader_password
WORKER_DB_URL=jdbc:postgresql://postgres:5432/jpt
WORKER_DB_USER=jpt_worker
WORKER_DB_PASS=ci_test_password

# MinIO
MINIO_ENDPOINT=http://minio:9000
MINIO_PUBLIC_URL=http://localhost:9000
MINIO_ACCESS_KEY=ci_minio_root
MINIO_SECRET_KEY=ci_minio_root_secret
MINIO_API_ACCESS_KEY=ci_minio_api
MINIO_API_SECRET_KEY=ci_minio_api_secret
MINIO_WORKER_ACCESS_KEY=ci_minio_worker
MINIO_WORKER_SECRET_KEY=ci_minio_worker_secret
MINIO_API_PRESIGN_ACCESS_KEY=ci_minio_presign
MINIO_API_PRESIGN_SECRET_KEY=ci_minio_presign_secret
MINIO_BACKUP_ACCESS_KEY=ci_minio_backup
MINIO_BACKUP_SECRET_KEY=ci_minio_backup_secret

# Redis
REDIS_URL=redis://redis:6379
REDIS_PASSWORD=ci_test_redis

# Auth
JWT_SECRET=ci_test_jwt_secret_that_is_at_least_32_characters_long
GOOGLE_CLIENT_ID=ci-unused
GOOGLE_CLIENT_SECRET=ci-unused

# SMTP (MailPit in CI for email verification — C21 fix)
SMTP_HOST=mailpit
SMTP_PORT=1025
SMTP_USER=
SMTP_PASS=

# CI-specific overrides
COOKIE_SECURE=false  # M33 fix — CI uses HTTP-only nginx, Secure cookies break Playwright
```

**Step 2: Write deploy workflow (C5, C6, C7, C8, M10, SA-F6, SA-F7, SA-F12 fix)**

Create `.github/workflows/deploy.yml`. Strategy: **build-on-VPS** — the CI `build` job validates compilation and tests pass, then the `deploy` job rsync's source to the VPS and builds Docker images there. This avoids registry complexity and large tarball transfers for a single-VPS deployment. (C7 fix)

The `build` and `sign` jobs are merged into a single `build` job since signing is a trivial post-build step and splitting them across runners requires unnecessary artifact transfer. (C6 fix)

Checksums are dropped — code integrity is guaranteed by git checkout + rsync over SSH. The previous checksum glob (`frontend/dist/**`) also required `shopt -s globstar` which isn't enabled by default in bash. (M10 fix)

**VPS deploy infrastructure (SA-F6 fix — mandatory, not optional):**

Before the first deploy, the VPS must be configured with:

1. A dedicated `deploy` user with minimal permissions (only `/opt/jpt/` write access and `docker compose` capability, no sudo).

2. A deploy script at `/opt/jpt/deploy.sh` that restricts allowed operations:
   ```bash
   #!/bin/bash
   set -euo pipefail
   cd /opt/jpt
   case "${SSH_ORIGINAL_COMMAND:-$1}" in
     tag-previous)  docker tag jpt-api:latest jpt-api:previous 2>/dev/null || true; \
                    docker tag jpt-worker:latest jpt-worker:previous 2>/dev/null || true ;;
     build)         docker compose build api worker && docker compose up -d ;;
     healthcheck)   docker compose exec -T api wget -qO- http://localhost:8080/actuator/health | grep -q UP ;;
     rollback)      if docker image inspect jpt-api:previous >/dev/null 2>&1; then \
                        docker compose stop api worker && \
                        docker tag jpt-api:previous jpt-api:latest && \
                        docker tag jpt-worker:previous jpt-worker:latest && \
                        docker compose up -d api worker; \
                    else \
                        echo "No previous images available for rollback" >&2; exit 1; \
                    fi ;;
     rsync\ --server*) $SSH_ORIGINAL_COMMAND ;;
     *)             echo "Unknown command" >&2; exit 1 ;;
   esac
   ```

3. An `authorized_keys` entry with restrictions:
   ```
   command="/opt/jpt/deploy.sh",no-agent-forwarding,no-port-forwarding,no-pty ssh-ed25519 AAAA...
   ```

> **NOTE (SA-F6 fix):** The `command=` restriction ensures that even if the deploy SSH key is compromised, only the predefined operations in `deploy.sh` can be executed. The `rsync --server*` pattern allows rsync file transfer while blocking arbitrary commands. The `rollback` subcommand verifies `:previous` images exist before attempting restore (SA-F12 fix).

```yaml
name: Deploy
on:
  workflow_run:
    workflows: ["CI"]  # M28 fix — gate deploy on CI (including Trivy security scanning) passing
    branches: [master]
    types: [completed]

jobs:
  build:
    if: ${{ github.event.workflow_run.conclusion == 'success' }}  # M28 fix — only deploy if CI passed
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21, distribution: temurin }
      - run: ./gradlew build  # includes tests via Gradle dependency graph (M18 fix)
      - uses: actions/setup-node@v4
        with: { node-version: 20 }
      - run: cd frontend && npm ci && npm run build

  deploy:
    runs-on: ubuntu-latest
    needs: [build]
    steps:
      - uses: actions/checkout@v4
      - name: Setup SSH key (C11 fix)
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.DEPLOY_SSH_KEY }}" > ~/.ssh/deploy_key
          chmod 600 ~/.ssh/deploy_key
          ssh-keyscan -H ${{ secrets.VPS_HOST }} >> ~/.ssh/known_hosts
      - name: Tag current images for rollback (C8 fix)
        run: |
          ssh -i ~/.ssh/deploy_key ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} tag-previous
      - name: Rsync source to VPS (C13, SA-F7 fix — exclude production-managed files)
        run: |
          rsync -avz --delete \
            --exclude='.env' \
            --exclude='secrets/' \
            --exclude='alertmanager.yml' \
            --exclude='certbot/' \
            -e "ssh -i ~/.ssh/deploy_key" \
            ./ ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}:/opt/jpt/
      - name: Build images and deploy on VPS (C12 fix)
        run: |
          ssh -i ~/.ssh/deploy_key ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} build

  healthcheck:
    runs-on: ubuntu-latest
    needs: [deploy]
    steps:
      - name: Setup SSH key (C11 fix)
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.DEPLOY_SSH_KEY }}" > ~/.ssh/deploy_key
          chmod 600 ~/.ssh/deploy_key
          ssh-keyscan -H ${{ secrets.VPS_HOST }} >> ~/.ssh/known_hosts
      - name: Wait for healthy deployment (C14 fix)
        run: |
          for i in $(seq 1 12); do
            ssh -i ~/.ssh/deploy_key ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} healthcheck && exit 0
            sleep 5
          done
          exit 1
      - name: Rollback on failure (C8, M30, SA-F12 fix)
        if: failure()
        run: |
          ssh -i ~/.ssh/deploy_key ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }} rollback
```

**Required GitHub secrets:**
- `DEPLOY_SSH_KEY` — private key for VPS access (restricted by `command=` in `authorized_keys` — see above)
- `VPS_HOST` — target server hostname
- `VPS_USER` — deploy user on VPS (dedicated user with minimal permissions, no sudo)

**Step 3: Commit**

```bash
git add .github/ .env.ci
git commit -m "ci: add backend/frontend/E2E/nginx-validate CI jobs, build-on-VPS deploy workflow"
```

### Task 5.6: Final Integration Test — Full E2E

**Files:**
- Create: `frontend/e2e/full-journey.spec.ts`

**Step 1: Write Playwright E2E test**

```typescript
test('full user journey', async ({ page, browser }) => {
    // 1. Register

    // 1b. Complete email verification via MailPit API (C21, C23 fix)
    //     Fetch verification email from MailPit, extract token, call /auth/verify
    const messagesRes = await page.request.get('http://localhost:8025/api/v1/messages');
    const messages = await messagesRes.json();
    const verifyEmail = messages.messages[0];
    const emailRes = await page.request.get(`http://localhost:8025/api/v1/message/${verifyEmail.ID}`);
    const emailData = await emailRes.json();
    // Extract verification token from email body (C23 fix — match base64url tokens: [A-Za-z0-9_-]+)
    const tokenMatch = emailData.Text.match(/\/auth\/verify\?token=([A-Za-z0-9_-]+)/);
    // C23 fix — use POST with JSON body (not GET with query param)
    await page.request.post('/api/auth/verify', {
      data: { token: tokenMatch[1] }
    });

    // 2. Login
    // 3. Upload a JPEG photo

    // 4. Wait for processing to complete (M5 fix — concrete polling strategy)
    await expect(async () => {
      const response = await page.request.get(`/api/photos/${photoId}`);
      const photo = await response.json();
      expect(photo.status).toBe('ready');
    }).toPass({ timeout: 30_000, intervals: [1_000, 2_000, 5_000] });

    // 5. Verify photo appears in grid
    // 6. View photo metadata
    // 7. Add keyword
    // 8. Create album, add photo
    // 9. Search by keyword
    // 10. Create share link

    // 11. Open share link in new browser context — no auth state (M6 fix)
    const anonContext = await browser.newContext();
    const anonPage = await anonContext.newPage();
    await anonPage.goto(`/share/${shareToken}`);
    // verify photo visible
    await anonContext.close();

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

---

## Changelog

### v7.0 (2026-03-12) — Security Audit #1 + Critical Review #6 Remediation

**Reviews:** `docs/plans/2026-03-12-saas-conversion-phase-5-security-audit-1.md`, `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-6.md`

**Security audit findings fixed (SA-P5-1):**
- **SA-F1** (Task 5.1): Mitigated `share_reader` BYPASSRLS over-privilege — added package-private bean scoping, dedicated `ShareLookupRepository` as sole consumer, ArchUnit test to enforce constraint, column-limited parameterized queries, and token format validation before querying. BYPASSRLS retained for simplicity with strict code-level controls.
- **SA-F2** (Task 5.1): Added note requiring all share lookup failures (not found, expired, deleted resource) to return identical 404 responses via single code path — prevents timing side-channel leakage.
- **SA-F3** (Task 5.1): Explicitly documented that authenticated share endpoints use primary DataSource with RLS; only unauthenticated lookup uses `shareReaderDataSource`. Added `deleteShare_byOtherUser_returns404()` integration test.
- **SA-F4** (Task 5.4): Changed alertmanager SMTP credential handling to use `smtp_auth_password_file` (Docker secrets) instead of plaintext in config. Ship `alertmanager.yml.example` with placeholders; actual `alertmanager.yml` managed on-VPS, added to `.gitignore` and rsync `--exclude`.
- **SA-F5** (Task 5.4): Added container hardening to node-exporter (`read_only`, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, `mem_limit`, `cpus`), consistent with all other services.
- **SA-F6** (Task 5.5): Made VPS SSH restriction mandatory — added `deploy.sh` script with `command=` restriction in `authorized_keys`, dedicated `deploy` user, and subcommand-based operations (`tag-previous`, `build`, `healthcheck`, `rollback`, `rsync --server*`).
- **SA-F7** (Task 5.5): Expanded rsync `--exclude` list to cover `alertmanager.yml` and `certbot/` (in addition to existing `.env` and `secrets/`).
- **SA-F8** (Task 5.1): Added CI-ONLY comment header to `.env.ci` and startup validation that rejects known-weak JWT secrets in non-test environments.
- **SA-F9** (Task 5.3): Added complete `/photos/` MinIO proxy location block with regex path guard matching design doc Section 7, replacing the one-liner description.
- **SA-F10** (Task 5.3): Accepted CSP `style-src 'unsafe-inline'` as documented trade-off — no change needed.
- **SA-F11** (Task 5.2): Added security note prohibiting `dangerouslySetInnerHTML` for user-uploaded metadata in share components.
- **SA-F12** (Task 5.5): Added `:previous` image existence check in rollback subcommand before attempting restore.

**Critical issues fixed (Critical Review #6):**
- **C22** (Task 5.1): Added prerequisite step creating minimal `SimpleEmailService` with `spring-boot-starter-mail` dependency and `spring.mail.*` configuration. No SMTP-capable EmailService existed — only `StubEmailService` (dev/test profile). Application could not start in docker-compose without a profile, and no SMTP traffic could reach MailPit for E2E testing.
- **C23** (Task 5.6): Fixed three bugs in E2E email verification step — wrong HTTP method (GET→POST), wrong parameter format (query string→JSON body), wrong regex (`[a-f0-9-]+`→`[A-Za-z0-9_-]+` for base64url tokens).

**Minor issues fixed (Critical Review #6):**
- **M31** (Task 5.1): Added `SMTP_PORT: ${SMTP_PORT:-587}` to API service environment in docker-compose — Spring Mail defaults to port 25/587, but MailPit uses 1025.
- **M32** (Task 5.4): Added `frontend` network to alertmanager service — `backend` network is `internal: true`, preventing external SMTP access for alert email delivery.
- **M33** (Task 5.1): Added `COOKIE_SECURE: ${COOKIE_SECURE:-true}` to API service environment in docker-compose, `COOKIE_SECURE=false` to `.env.ci` — Secure cookies over HTTP-only CI nginx prevented Playwright from sending auth cookies.

**Clarification questions resolved (Critical Review #6):**
- Q1 (Production EmailService scope): Minimal `SimpleEmailService` created as placeholder — sends plain text emails via `JavaMailSender`. To be replaced with HTML-templated implementation before production go-live.
- Q2 (Email template format): Plain text with verification URL (`{baseUrl}/auth/verify?token={token}`), consistent with E2E regex.
- Q3 (CI Spring profile): No profile needed — `SimpleEmailService` activates by default (`@Profile("!dev & !test")`), `StubEmailService` stays for dev/test.

### v6.0 (2026-03-12) — Critical Review #5 Remediation

**Review:** `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-5.md`

**Critical issues fixed:**
- **C20** (Task 5.1): Expanded `share_reader` GRANT to include `albums`, `album_photos`, and `photo_metadata` — album share lookups query `album_photos` (which has RLS + FORCE ROW LEVEL SECURITY), and GPS stripping reads `photo_metadata`. `BYPASSRLS` bypasses policy checks but not permission checks, so explicit `GRANT SELECT` is required. Without this, every album share lookup fails with `permission denied for table album_photos`.
- **C21** (Task 5.5/5.6): Added MailPit test SMTP service to `docker-compose.ci.yml` and email verification step to E2E test. `AuthController` returns 403 for unverified users, so the Register → Login flow fails without completing email verification. MailPit captures SMTP traffic and exposes a REST API for programmatic email retrieval. Updated `.env.ci` SMTP config to point to MailPit (`SMTP_HOST=mailpit`, `SMTP_PORT=1025`).

**Minor issues fixed:**
- **M26** (Task 5.1): Added `maximum-pool-size: 3` to `share-reader` HikariCP config — HikariCP defaults to 10, which wastes PostgreSQL connections for infrequent, read-only share lookups on a resource-constrained VPS.
- **M27** (Task 5.5): Fixed CI `nginx.ci.conf` CSP `img-src` to use `http://localhost:9000` instead of production `https://minio.yourdomain.com` — CI MinIO presigned URLs point to localhost, causing CSP-blocked images in E2E tests.
- **M28** (Task 5.5): Changed `deploy.yml` trigger from `push` to `workflow_run` gated on CI workflow success — prevents deploying code while Trivy security scanning is simultaneously flagging vulnerabilities.
- **M29** (Task 5.5): Added `--wait-timeout 120` to `docker compose up` in E2E CI job — without a timeout, a broken service hangs the job until GitHub Actions' 6-hour default.
- **M30** (Task 5.5): Changed deploy rollback from `docker compose down` (stops all services) to `docker compose stop api worker` (targeted restart) — avoids unnecessary PostgreSQL/Redis/MinIO restarts that extend outage and risk data integrity.

**Clarification questions resolved:**
- Q1 (Album share query path): Include `albums` table in grant — share page should display album name (C20)
- Q2 (E2E email verification): MailPit test SMTP service with REST API for programmatic email retrieval (C21)

### v5.0 (2026-03-12) — Critical Review #4 Remediation

**Review:** `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-4.md`

**Critical issues fixed:**
- **C16** (Task 5.1): Fixed Flyway V10 migration placeholder syntax — changed from psql's `:'variable'` to Flyway's `${variable}`, consistent with V4. Added `IF NOT EXISTS` guard and `GRANT CONNECT` via `format()` for idempotency, matching V4's pattern.
- **C17** (Task 5.4): Added `redis-exporter` service (`oliver006/redis_exporter:v1.62.0`) to docker-compose.yml and prometheus scrape config. Without it, the two Redis baseline alerts (memory >80%, Streams pending >50) had no data source and would never fire.
- **C18** (Task 5.5): Fixed `.env.ci` PostgreSQL hostname — changed `db` to `postgres` to match the actual docker-compose service name. All JDBC URLs (`DB_URL`, `WORKER_DB_URL`, `FLYWAY_DB_URL`) were affected.
- **C19** (Task 5.5): Fixed `.env.ci` MinIO presign variable names — changed `MINIO_PRESIGN_ACCESS_KEY`/`SECRET_KEY` to `MINIO_API_PRESIGN_ACCESS_KEY`/`SECRET_KEY` to match docker-compose.yml references (note `API_` prefix).

**Minor issues fixed:**
- **M22** (Task 5.1): Added paginated repository method `Page<Share> findByUserId(UUID, Pageable)` — the existing `List<Share>` return type is incompatible with Spring Data pagination required by `GET /shares?page=0&size=20`.
- **M23** (Task 5.5): Added `npm ci && npx playwright install --with-deps` to E2E CI job — browser binaries and OS-level dependencies are not present on the E2E runner (different from the `frontend` runner).
- **M24** (Task 5.5): Added `nginx.ci.conf` (HTTP-only) and docker-compose.ci.yml nginx override — `nginx.prod.conf` requires TLS certificates that don't exist in CI. The CI config preserves all proxy rules and security headers while removing TLS requirements.
- **M25** (Task 5.5): Added `MINIO_BACKUP_ACCESS_KEY` and `MINIO_BACKUP_SECRET_KEY` to `.env.ci` — minio-init (not removed by CI override) creates the backup MinIO user and fails with empty credentials, blocking API/worker startup via `depends_on`.

**Clarification questions resolved:**
- Q1 (Redis exporter): Standard `oliver006/redis_exporter` added — no custom scraper intended (C17)
- Q2 (CI nginx strategy): HTTP-only `nginx.ci.conf` with volume override in `docker-compose.ci.yml` (M24)

### v4.0 (2026-03-12) — Critical Review #3 Remediation

**Review:** `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-3.md`

**Critical issues fixed:**
- **C11** (Task 5.5): Fixed SSH key handling — write secret to file (`~/.ssh/deploy_key`) + `ssh-keyscan` for host key verification. GitHub secrets are strings, not file paths.
- **C12** (Task 5.5): Fixed Dockerfile paths — replaced `docker build -f Dockerfile.api` with `docker compose build api worker`, which uses the correct paths already defined in docker-compose.yml (`./api`, `./worker`).
- **C13** (Task 5.5): Added `--exclude='.env' --exclude='secrets/'` to rsync `--delete` to protect production credentials and runtime files on VPS.
- **C14** (Task 5.5): Fixed healthcheck — SSH into VPS and check API health directly via `docker compose exec` instead of curling nginx (which returns SPA HTML for `/actuator/health`).
- **C15** (Task 5.1): Added complete `share_reader` password lifecycle — Flyway placeholder for role password, `SHARE_READER_PASSWORD` env var in docker-compose, secondary DataSource config in `application.yml`, `.env.example` entry, and `@ConfigurationProperties` wiring.

**Minor issues fixed:**
- **M16** (Task 5.4): Fixed alertmanager `smtp_smarthost` — changed from `localhost:587` to placeholder with note to set actual SMTP provider hostname (localhost refers to container, not host).
- **M17** (Task 5.4): Added `pid: host` and `--path.*` flags to node-exporter for accurate host-level metrics instead of container-namespaced metrics.
- **M18** (Task 5.5): Removed redundant `./gradlew test` — Gradle `build` task already includes `test` via dependency graph.
- **M19** (Task 5.5): Changed `docker compose up -d` to `docker compose up -d --wait` in E2E CI job to wait for healthchecks before running Playwright.
- **M20** (Task 5.5): Expanded `.env.ci` to include all required variables (37+) matching `docker-compose.yml` references. Added `docker-compose.ci.yml` override to remove non-essential services (backup, pgbackup, certbot).
- **M21** (Task 5.1): Specified migration version as `V10__share_reader_role.sql` (latest existing is V9).

**Clarification questions resolved:**
- Q1 (SSH key pattern): Adopted write-to-file + ssh-keyscan as standard (no existing pattern in CI) (C11)
- Q2 (Production `.env` management): `.env` lives at `/opt/jpt/.env`, managed separately on VPS, protected by rsync `--exclude` (C13)
- Q3 (`share_reader` password lifecycle): Set via Flyway placeholder from env var, matching existing `jpt_auth`/`JPT_AUTH_PASSWORD` pattern (C15)

### v3.0 (2026-03-12) — Critical Review #2 Remediation

**Review:** `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-2.md`

**Critical issues fixed:**
- **C6** (Task 5.5): Merged `build` + `sign` into single job — eliminated broken cross-runner artifact transfer
- **C7** (Task 5.5): Changed to build-on-VPS deploy strategy — rsync source to VPS, build Docker images there. Eliminates registry complexity for single-VPS deployment
- **C8** (Task 5.5): Added pre-deploy step to tag current images as `:previous` before building new ones, so rollback has valid images to restore
- **C9** (Task 5.4): Added `micrometer-registry-prometheus` to `api/build.gradle.kts` (already in `server` and `worker`), updated both `application.yml` files to expose `health,prometheus` endpoints
- **C10** (Task 5.1): Added rationale note explaining why `share_reader` is intentionally separate from existing `jpt_auth` role (different table scopes, least-privilege isolation)

**Minor issues fixed:**
- **M9** (Task 5.4): Added `node-exporter` service to `docker-compose.yml` and scrape target to `prometheus.yml` for host-level disk/CPU/memory metrics
- **M10** (Task 5.5): Dropped checksum step — moot with build-on-VPS strategy; also fixes `shopt -s globstar` requirement
- **M11** (Task 5.4): Pinned monitoring image tags to specific versions (`prom/prometheus:v2.53.0`, `grafana/grafana:11.1.0`, `prom/alertmanager:v0.27.0`, `prom/node-exporter:v1.8.1`)
- **M12** (Task 5.4): Changed network from `internal` to `backend` — `internal` doesn't exist in docker-compose; Grafana accessed via SSH port forwarding only (per design doc)
- **M13** (Task 5.5): Added `.env.ci` with test-safe defaults for E2E CI job environment configuration
- **M14** (Task 5.1): Added pagination to `GET /shares` (`?page=0&size=20`) consistent with all other list endpoints
- **M15** (Task 5.3): Added explicit note to preserve existing `/api/auth/` location block as fallback for non-login/register auth endpoints

**Clarification questions resolved:**
- Q1 (jpt_auth reuse): Keep separate `share_reader` role — different table scopes (C10)
- Q2 (Image delivery): Build-on-VPS strategy (C7)
- Q3 (Grafana access): SSH port forwarding only, no nginx proxy (M12)

### v2.0 (2026-03-12) — Critical Review #1 Remediation

**Review:** `docs/plans/2026-02-25-saas-conversion-phase-5-critical-review-1.md`

**Critical issues fixed:**
- **C1** (Task 5.1): Added RLS bypass strategy — dedicated `share_reader` PostgreSQL role with BYPASSRLS on `shares` and `photos` tables, secondary DataSource, and explicit test for unauthenticated share lookup
- **C2** (Task 5.3): Added share API rate limiting — `share` zone at 60r/m, dedicated `/api/share/` location block in nginx, plus missing `login` and `register` zones
- **C3** (Task 5.3): Fixed file path — changed from `Create: nginx/nginx.conf` to `Modify: nginx.prod.conf` with enumerated specific additions
- **C4** (Task 5.5): Fixed file directive — changed from `Create: .github/workflows/ci.yml` to `Modify`, explicitly preserving existing Trivy scanning jobs
- **C5** (Task 5.5): Fully specified deploy workflow — build, sign, rsync, verify, healthcheck loop, rollback, and required GitHub secrets

**Minor issues fixed:**
- **M1** (Task 5.2): Updated `ShareToken` frontend type to match full backend `Share` entity (added `id`, `resourceType`, `resourceId`, `includeGps`, `permissions`, `createdAt`)
- **M2** (Task 5.2): Added `/share/:token` route registration in `App.tsx` outside `ProtectedRoute`
- **M3** (Task 5.3): Fixed CSP `style-src` to include `'unsafe-inline'` for Tailwind/shadcn (per design v4.0, SA#12/CR#7)
- **M4** (Task 5.4): Added `prometheus`, `grafana`, and `alertmanager` service definitions to `docker-compose.yml`, replacing placeholder comment
- **M5** (Task 5.6): Added concrete async polling strategy using Playwright's `toPass()` with 30s timeout and escalating intervals
- **M6** (Task 5.6): Replaced "incognito" with `browser.newContext()` for isolated unauthenticated session
- **M7** (Task 5.2): Added album share rendering spec — paginated photo grid reusing `LibraryPage` layout in read-only mode
- **M8** (Task 5.4): Added `alertmanager.yml` config for SMTP email delivery and `alertmanager` service in docker-compose

**Clarification questions resolved:**
- Q1: RLS bypass → `share_reader` role (C1)
- Q2: Nginx strategy → modify `nginx.prod.conf` in-place (C3)
- Q3: Deploy workflow → fully specified (C5)
- Q4: CI jobs → add alongside existing Trivy jobs (C4)

### v1.0 (2026-02-25) — Initial Plan

Original Phase 5 plan covering sharing, nginx, monitoring, CI/CD, and E2E testing.
