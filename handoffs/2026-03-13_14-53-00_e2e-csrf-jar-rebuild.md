---
date: 2026-03-13T14:53:00-04:00
git_commit: 5de9426d64b9a45bfc737c8d9b06fd66af46ccb5
branch: master
repository: jpt_saas
topic: "Task 5.6 — E2E CSRF Bug — SecurityConfig JAR Rebuild Required"
tags: [handoff, session-transition, docker, playwright, arm64, e2e, spring-boot, csrf, security]
status: in_progress
last_updated: 2026-03-13
type: implementation_handoff
---

# Handoff: E2E Stack Healthy — CSRF Handler Bug Requires JAR Rebuild

## 0. Executive Summary (TL;DR)

The ARM64 Docker stack is now fully healthy (all 7 services), the Playwright E2E test infrastructure is wired up, and the test runs and reaches the register form — but registration always returns 401 because `SecurityConfig.spaCsrfTokenRequestHandler()` uses `XorCsrfTokenRequestAttributeHandler` which XOR-decodes header tokens, but the SPA sends a raw UUID cookie value that is not XOR-encoded. The single next action is to fix `SecurityConfig.java:95-101` to use `CsrfTokenRequestAttributeHandler` (base class, no XOR), rebuild the JAR, rebuild the Docker image, and restart the API container.

## 1. Technical State

**Active Working Set:**
- `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java:95` — `spaCsrfTokenRequestHandler()` — THIS IS THE BUG: uses `XorCsrfTokenRequestAttributeHandler`, must change to base `CsrfTokenRequestAttributeHandler`
- `docker-compose.ci.yml:1` — All ARM64 + CI fixes applied this session (see §4), uncommitted
- `api/src/main/resources/application-test.yml:1` — Untracked, created prev session; dummy OAuth2 config
- `frontend/e2e/full-journey.spec.ts:28` — E2E test, passes mailpit/nginx steps, fails at registration

**Current Errors / Blockers:**
```
POST /api/auth/register → HTTP 401 {"error":"Unauthorized","status":401}

Root cause: XorCsrfTokenRequestAttributeHandler.resolveCsrfTokenValue() calls
Base64.getUrlDecoder().decode(headerValue) on a raw UUID like
"bb24f374-571d-4273-89ad-6269b31be809" — this either throws or returns garbage bytes,
causing the decoded token to not match the stored token → CSRF validation fails →
ExceptionTranslationFilter: anonymous user → AuthenticationEntryPoint → 401.
```

**Environment:**
- Uncommitted changes: YES — `docker-compose.ci.yml`, `docker-compose.yml`, `frontend/package-lock.json`
- Untracked: `api/src/main/resources/application-test.yml`
- `.env` file: present (copied from `.env.ci` — not committed)
- `react-build/dist/`: present with built frontend — not in git
- `api/build/libs/app.jar`: present (14:08 UTC build) — not in git
- Caddy: STOPPED (`sudo systemctl stop caddy`) — port 80 free for nginx

**Docker stack — FULLY HEALTHY right now:**
- All 7 services healthy: postgres ✅, redis ✅, minio ✅, mailpit ✅, api ✅, worker ✅, nginx ✅ (up)
- **IMPORTANT**: mailpit port 8025 is currently bound via manual `docker run` workaround (see §3 dead ends). It will NOT survive `docker compose down/up` — see §5 Step 2 for workaround.

## 2. Progress Tracker

| Task | Status | Location | Notes |
|------|--------|----------|-------|
| Write E2E test (Task 5.6) | ✅ Complete | `frontend/e2e/full-journey.spec.ts:1` | Committed |
| Add playwright.config.ts | ✅ Complete | `frontend/playwright.config.ts:1` | Committed |
| Add @playwright/test | ✅ Complete | `frontend/package.json:1` | Committed |
| Fix EMAIL_FROM typo | ✅ Complete | `docker-compose.yml:43` | Uncommitted |
| Fix Redis NOAUTH | ✅ Complete | `docker-compose.ci.yml:70` | Lettuce+URL |
| Fix Flyway user | ✅ Complete | `docker-compose.ci.yml:74` | DB_USER=jpt_app |
| Fix WORKER_DB_PASS | ✅ Complete | `docker-compose.ci.yml:77` | V3 migration placeholder |
| Fix OAuth2 ClientRegistrationRepository | ✅ Complete | `api/src/main/resources/application-test.yml:1` | Untracked |
| Fix health check start_period | ✅ Complete | `docker-compose.ci.yml:61-66` | 240s + 10 retries |
| Fix MailHealthIndicator 503 | ✅ Complete | `docker-compose.ci.yml:80` | MANAGEMENT_HEALTH_MAIL_ENABLED=false |
| Fix worker DB user (jpt_worker→worker_db_user) | ✅ Complete | `docker-compose.ci.yml:97` | DB_USER override |
| Grant worker BYPASSRLS | ✅ Complete | psql manual | `ALTER ROLE worker_db_user BYPASSRLS` — NOT in migration |
| Fix nginx cannot resolve minio | ✅ Complete | `docker-compose.ci.yml:12-14` | nginx added to backend network |
| Fix nginx serving wrong dir | ✅ Complete | `docker-compose.ci.yml:7` | react-build → react-build/dist |
| Fix CSRF Secure cookie over HTTP | ✅ Complete | `docker-compose.ci.yml:83` | APP_COOKIE_SECURE=false |
| Fix mailpit host port binding | ✅ Workaround | manual `docker run` | Docker Compose ports broken for services only in override when on custom network |
| **Fix SecurityConfig CSRF XOR handler** | ❌ BLOCKER | `SecurityConfig.java:95-101` | Must change to base class + JAR rebuild |
| Install Playwright browsers | ✅ Complete | `frontend/` | chromium installed |
| Run E2E tests successfully | ❌ Blocked | `frontend/` | Blocked by CSRF bug |
| Commit all ARM64 fixes | ⏳ Pending | — | After tests pass |
| Restart Caddy | ⏳ Pending | — | Last step |

## 3. Mental Model (Most Critical Section)

**Why the CSRF bug exists:**
`SecurityConfig.spaCsrfTokenRequestHandler()` at `:95` returns an `XorCsrfTokenRequestAttributeHandler` (wrapped as base class). `XorCsrfTokenRequestAttributeHandler.resolveCsrfTokenValue()` base64url-decodes the incoming header value and XOR-unmasks it against the stored token. BUT the SPA (`frontend/src/api/client.ts:53`) reads the raw `XSRF-TOKEN` cookie value (which is a plain UUID from `CookieCsrfTokenRepository`) and sends it as-is as `X-XSRF-TOKEN`. Since the UUID isn't base64url+XOR encoded, decoding returns null/garbage → comparison with stored UUID fails → CSRF rejected → Spring routes anonymous CSRF failure to the AuthenticationEntryPoint (not AccessDeniedHandler, because user is anonymous) → 401.

**The correct Spring Security SPA CSRF pattern (from official docs):**
When using `CookieCsrfTokenRepository` with a SPA that reads the cookie:
- The cookie stores the RAW token (UUID)
- The SPA reads the raw UUID from `document.cookie`
- The SPA sends it as `X-XSRF-TOKEN: <raw-uuid>`
- The server must validate it as a raw string comparison, NOT XOR-decode it

For BREACH protection with SPAs, the proper setup requires a custom `SpaCsrfTokenRequestHandler` that uses the BASE class for header resolution but XOR for form params. For a pure SPA with no server-rendered forms, just the base `CsrfTokenRequestAttributeHandler` is sufficient.

**Codebase Gotchas Discovered This Session:**
- `api/Dockerfile:9` — `COPY build/libs/app.jar app.jar` — Docker builds from pre-built JAR. Gradle rebuild is MANDATORY before Docker rebuild. `--no-cache` does NOT help.
- `docker-compose.ci.yml` mailpit port binding — Services defined ONLY in the override file (not in base) silently fail to bind host ports when the container is on a custom network. Works when started on default bridge then connected to custom network.
- `worker/src/main/resources/application.yml:4` — worker uses `${DB_USER:jpt_worker}` NOT `${WORKER_DB_USER}`. V3 migration creates `worker_db_user`. Need `DB_USER: worker_db_user` override in compose.
- `worker_db_user` PostgreSQL role is missing `BYPASSRLS` in V3 migration. Worker queries `photos` table which has RLS policies. Fix applied manually: `ALTER ROLE worker_db_user BYPASSRLS;`. NOT captured in migrations — will break on fresh DB unless added to V4 migration.

**Dead Ends — Do Not Repeat These:**

| Approach Tried | Why It Failed | Evidence |
|---------------|---------------|----------|
| AMD64 SHA-pinned redis/postgres on ARM64 | exec format error — no QEMU | `docker logs jpt_saas-redis-1: exec format error` |
| `cap_drop: []` to remove hardening | Compose MERGES arrays, does not replace | redis still failed with `operation not permitted` |
| `docker compose build --no-cache api` without Gradle rebuild | Copies stale JAR from disk — `--no-cache` only skips layer cache | `application-test.yml` missing from new image |
| Setting `WORKER_DB_USER: worker_db_user` in ci override | worker/application.yml reads `DB_USER` not `WORKER_DB_USER` | Still connected as `jpt_worker` |
| Starting mailpit in compose with ports binding on backend network | Docker Compose ports binding silently fails for services-only-in-override on custom networks | `docker inspect → NetworkSettings.Ports: {"8025/tcp": null}` |
| `APP_COOKIE_SECURE: "false"` alone (without CSRF handler fix) | Unsecures the cookie but XOR decoding still rejects raw UUID in header | `curl` test with correct cookie → still 401 |

**Key Decisions Made:**

| Decision | Rationale | Alternative Rejected |
|----------|-----------|---------------------|
| `CsrfTokenRequestAttributeHandler` (base, no XOR) for SPA | SPA reads raw UUID from cookie; base class does raw string compare | `XorCsrfTokenRequestAttributeHandler` — designed for server-rendered forms, incompatible with cookie-reading SPAs |
| `ALTER ROLE worker_db_user BYPASSRLS` directly | Fastest fix; V4 migration needed later | Modify V3 migration — would change checksum and break Flyway |
| mailpit `docker run` workaround | Docker Compose bug with ports on custom-network-only services | Adding to base compose — undesirable for production |

**Assumptions in Play:**
- V4 Flyway migration should add `ALTER ROLE worker_db_user BYPASSRLS` — this is currently a manual DB state that will break on fresh DB.
- After the CSRF fix + JAR rebuild, the full E2E test should pass in one shot — all other infra issues are fixed.
- The `react-build/dist/` directory is a pre-built frontend (from the CI pipeline). If it's missing, run `cd frontend && npm run build && cp -r dist ../react-build/`.

## 4. Delta — Changes Made This Session (Uncommitted)

- `docker-compose.ci.yml:4-14` — nginx: volume changed to `./react-build/dist` (was `./react-build`), added `networks: frontend + backend` (minio resolution fix)
- `docker-compose.ci.yml:78-83` — api: added `MANAGEMENT_HEALTH_MAIL_ENABLED: "false"` and `APP_COOKIE_SECURE: "false"`
- `docker-compose.ci.yml:95-97` — worker: added `DB_USER: worker_db_user` override
- `docker-compose.yml:43` — Fixed `${EMAIL_FROM:noreply@yourdomain.com}` → `${EMAIL_FROM:-noreply@yourdomain.com}` (missing `-`)
- `api/src/main/resources/application-test.yml` — Untracked: dummy OAuth2 registrations for Docker E2E; unchanged this session
- `frontend/package-lock.json` — Updated after `@playwright/test` install; unchanged this session
- Manual Postgres change (not in files): `ALTER ROLE worker_db_user BYPASSRLS;` — applies to current DB only

## 5. Next Steps (Ordered — Do Not Skip Steps)

1. **Verify stack is still healthy:**
   ```bash
   docker ps --format '{{.Names}}\t{{.Status}}' | grep jpt_saas
   ```
   Expected: api=healthy, worker=healthy, nginx=up, mailpit=healthy, postgres/redis/minio=healthy.
   If mailpit shows no port binding: re-run mailpit workaround (see Step 2).

2. **If mailpit needs to be restarted** (after any `docker compose down/up`):
   ```bash
   docker stop jpt_saas-mailpit-1 2>/dev/null; docker rm jpt_saas-mailpit-1 2>/dev/null
   docker run -d --name jpt_saas-mailpit-1 -p 0.0.0.0:8025:8025 axllent/mailpit:v1.21
   docker network connect jpt_saas_backend jpt_saas-mailpit-1
   curl -s http://localhost:8025/api/v1/messages  # Should return {"total":0,...}
   ```

3. **Fix SecurityConfig CSRF handler** (the root cause of 401):
   Edit `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java:95-101`:
   ```java
   // BEFORE (broken — XOR-decodes header, incompatible with SPA raw-cookie approach):
   private static CsrfTokenRequestAttributeHandler spaCsrfTokenRequestHandler() {
       CsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
       delegate.setCsrfRequestAttributeName(null);
       return delegate;
   }

   // AFTER (correct — base class does raw string compare, matches what SPA sends):
   private static CsrfTokenRequestAttributeHandler spaCsrfTokenRequestHandler() {
       CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
       handler.setCsrfRequestAttributeName(null);
       return handler;
   }
   ```
   Only `XorCsrfTokenRequestAttributeHandler` → `CsrfTokenRequestAttributeHandler` changes. The `import` for `XorCsrfTokenRequestAttributeHandler` at line 20 can be removed if no longer needed.

4. **Rebuild JAR** (MANDATORY — Docker copies pre-built JAR):
   ```bash
   cd /home/ubuntu/jpt_saas && ./gradlew :api:bootJar -x test 2>&1 | tail -5
   ```
   Expected: `BUILD SUCCESSFUL`

5. **Rebuild Docker image and restart API:**
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.ci.yml build api 2>&1 | tail -5
   docker compose -f docker-compose.yml -f docker-compose.ci.yml up -d --force-recreate api 2>&1 | tail -5
   ```
   Then wait ~100s for Spring Boot to start:
   ```bash
   for i in $(seq 1 20); do sleep 15; API=$(docker inspect jpt_saas-api-1 --format '{{.State.Health.Status}}'); echo "$(date +%H:%M:%S) api=$API"; [ "$API" = "healthy" ] && break; done
   ```

6. **Verify CSRF fix works:**
   ```bash
   XSRF=$(curl -si http://localhost/api/csrf | grep -o 'XSRF-TOKEN=[^;]*' | cut -d= -f2)
   curl -s -X POST http://localhost/api/auth/register \
     -H 'Content-Type: application/json' \
     -H "X-XSRF-TOKEN: $XSRF" \
     --cookie "XSRF-TOKEN=$XSRF" \
     -d '{"email":"verify-test@example.com","password":"TestPassword123!"}'
   ```
   Expected: `{}` or `{"message":"..."}` with HTTP 200 (NOT 401).

7. **Run E2E tests:**
   ```bash
   cd /home/ubuntu/jpt_saas/frontend && npx playwright test
   ```
   Expected: `1 passed` for `full user journey`.
   On failure, inspect trace: `npx playwright show-trace test-results/full-journey-full-user-journey-chromium/trace.zip`

8. **After tests pass — commit everything:**
   ```bash
   cd /home/ubuntu/jpt_saas
   git add docker-compose.ci.yml docker-compose.yml frontend/package-lock.json \
     api/src/main/resources/application-test.yml \
     api/src/main/java/org/jphototagger/api/security/SecurityConfig.java
   git commit -m "fix: ARM64 CI stack + CSRF SPA handler — multi-arch images, health checks, redis/flyway auth, cookie-secure, worker DB user"
   ```

9. **Restart Caddy:**
   ```bash
   sudo systemctl start caddy
   ```

**Watch for:**
- `worker_db_user BYPASSRLS` is a manual DB change. If the DB is wiped and re-created, you'll need to re-apply it via `docker exec jpt_saas-postgres-1 psql -U jpt_app -d jpt -c "ALTER ROLE worker_db_user BYPASSRLS;"` after the API starts (which triggers Flyway migrations).
- If `api/build/libs/app.jar` is missing, run `./gradlew :api:bootJar -x test` first.
- If the full stack is down, start it with: `docker compose -f docker-compose.yml -f docker-compose.ci.yml up -d` then apply the mailpit workaround (Step 2) and the BYPASSRLS grant.

## 6. Artifacts & References

- **Plan**: `docs/plans/2026-02-25-saas-conversion-phase-5.md:885` — Task 5.6 spec
- **E2E test**: `frontend/e2e/full-journey.spec.ts:1` — fully committed
- **Playwright config**: `frontend/playwright.config.ts:1` — fully committed
- **CSRF bug location**: `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java:95`
- **SPA API client (reads XSRF cookie)**: `frontend/src/api/client.ts:53`
- **CSRF bootstrap function**: `frontend/src/api/client.ts:6`
- **OAuth2 fix**: `api/src/main/resources/application-test.yml:1` — untracked
- **CI compose**: `docker-compose.ci.yml:1` — all ARM64 + CI workarounds (uncommitted)
- **Base compose**: `docker-compose.yml:43` — EMAIL_FROM typo fix (uncommitted)
- **Worker Spring config**: `worker/src/main/resources/application.yml:4` — uses `${DB_USER:jpt_worker}`
- **Dockerfile**: `api/Dockerfile:9` — copies pre-built JAR; MUST run Gradle before Docker build
- **Previous handoffs**: `handoffs/2026-03-13_13-24-45_e2e-stack-arm64.md:1` and `handoffs/2026-03-13_14-13-55_e2e-stack-arm64-fix2.md:1`
