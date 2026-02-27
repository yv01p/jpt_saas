# Critical Implementation Review: Phase 1b — Docker Compose & Dockerfiles

**Reviewer:** Claude (Senior Staff Engineer)
**Date:** 2026-02-26
**Plan reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-1b.md`
**Reference:** `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0, Section 7)

---

## 1. Overall Assessment

The plan is structurally sound and covers the three expected deliverables (Compose stack, dev overrides, Dockerfiles). However, it is dangerously under-specified — it delegates almost all detail to "Reference the design doc Section 7 exactly" without enumerating the services, volumes, networks, secrets, or health checks that must appear. This makes the plan a pointer, not a plan. Several concrete items from the design doc are either missing or contradicted. The Dockerfiles are minimal but have security and reliability gaps.

**Strengths:** Dev override approach with profiles is clean; worker Dockerfile correctly includes tini and non-root user; pgbackup pins restic version.

**Major concerns:** Missing services, missing security hardening, missing volumes/networks/secrets definitions, Dockerfile gaps.

---

## 2. Critical Issues

### 2.1 `docker-compose.yml` Step 1 is a delegation, not a specification

**Problem:** "Reference the design doc Section 7 exactly" is not an actionable step. The design doc's YAML is pseudocode (e.g., `DB_URL, DB_USER, DB_PASS` is not valid Compose syntax — each variable needs `${VAR}` interpolation). An implementer copying verbatim will produce a broken file.

**Impact:** Incorrect or incomplete Compose file; debugging at runtime.

**Fix:** The plan should either (a) include the full, valid `docker-compose.yml` inline, or (b) enumerate every service with its key attributes (image/build, environment variable syntax, volumes, health checks, networks, depends_on, security options) so the implementer doesn't need to cross-reference and translate pseudocode.

### 2.2 Missing `certbot` service in dev override

**Problem:** The dev override disables `backup`, `pgbackup`, `prometheus`, `grafana` via profiles — but does **not** disable `certbot`. Certbot will attempt certificate renewal against a non-existent domain in local dev, producing noisy failures.

**Impact:** Dev environment logs polluted; potential container restart loops.

**Fix:** Add `certbot` to the production profile in `docker-compose.dev.yml`:
```yaml
certbot:
  profiles: ["production"]
```

### 2.3 Missing `volumes:` and `networks:` top-level definitions

**Problem:** The design doc defines named volumes (`postgres_data`, `minio_data`, `redis_data`, `certbot_certs`, `certbot_www`, `prometheus_data`, `grafana_data`) and a network (`internal: true`). The plan does not mention these at all.

**Impact:** Compose will fail to start if services reference undefined named volumes; Prometheus/Grafana will be on the default network (externally reachable), violating the security requirement.

**Fix:** Explicitly list all top-level `volumes:` and `networks:` definitions in the plan.

### 2.4 Missing `secrets:` top-level definition

**Problem:** The design doc defines `secrets: restic_pass: file: ./secrets/restic_pass.txt` and the `pgbackup` service references `secrets: [restic_pass]`. The plan does not mention secrets.

**Impact:** `pgbackup` will fail to start; restic password not provisioned.

**Fix:** Include the `secrets:` block and document the provisioning step (`openssl rand -base64 32 > secrets/restic_pass.txt`). Add `secrets/` to `.gitignore`.

### 2.5 API Dockerfile missing JVM memory flags and health check

**Problem:** The API Dockerfile is a bare `java -jar`. No `-Xmx`, no `-XX:MaxRAMPercentage`, no `HEALTHCHECK`. On a memory-constrained VPS, the JVM will consume unbounded heap. Without a Dockerfile-level health check, `depends_on: { condition: service_healthy }` from downstream services won't work unless health checks are defined in Compose (which the plan also doesn't specify for the API).

**Impact:** OOM kills on VPS; broken health-check dependency chain.

**Fix:**
```dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### 2.6 Worker Dockerfile missing health check

**Problem:** Same as 2.5 — no `HEALTHCHECK`. The worker has no inbound ports, so an HTTP health check won't work, but it still needs a liveness signal.

**Impact:** Docker can't detect a hung worker process.

**Fix:** Add a file-based health check (worker writes a heartbeat file) or use a management port. At minimum, document the strategy.

### 2.7 API Dockerfile uses `COPY build/libs/api-*.jar` — glob is fragile

**Problem:** The wildcard `api-*.jar` will match multiple JARs if old builds aren't cleaned (e.g., `api-0.0.1.jar` and `api-0.0.2.jar`). `COPY` with multiple matches copies all of them but `java -jar app.jar` runs only one.

**Impact:** Stale JARs in the image; potential wrong-version execution.

**Fix:** Either (a) use a Gradle task to produce a fixed-name JAR (`api.jar`), or (b) add `RUN rm -f /app/*.jar` before `COPY`, or (c) use a multi-stage build that copies only the output of `gradle build`.

### 2.8 `pgbackup/Dockerfile` pins `restic=0.16.4-1` — may not be available in Debian repos

**Problem:** `postgres:16` is Debian-based. The `restic` package version `0.16.4-1` may not exist in the current Debian stable repos (Debian Bookworm ships `0.14.0`). The build will fail with "package has no installation candidate."

**Impact:** Build failure; blocks deployment.

**Fix:** Either (a) install restic from GitHub releases binary (pinned checksum), or (b) verify the exact version string available in the target Debian release, or (c) use a multi-stage build to download the binary.

---

## 3. Minor Issues & Improvements

### 3.1 `.env.example` has insecure defaults

`MINIO_ACCESS_KEY=minioadmin` and `MINIO_SECRET_KEY=minioadmin` are the MinIO defaults. If someone copies `.env.example` to `.env` and deploys to production without changing them, MinIO is wide open. Consider using placeholder values like `changeme` (as done for `REDIS_PASSWORD`) to make it obvious these must be changed.

### 3.2 `.env.example` missing `DB_NAME`

The `pgbackup` service requires `DB_NAME` in its environment. The `.env.example` includes it (`DB_NAME=jpt`), which is good — but `docker-compose.dev.yml` uses `POSTGRES_DB` directly. Ensure the Compose file correctly maps both.

### 3.3 Dev override doesn't expose API port

For local development, the API container should likely expose `8080:8080` (or use a different port) so developers can test without nginx. The dev override only exposes postgres, minio, and redis ports.

### 3.4 No `.dockerignore` files mentioned

Without `.dockerignore`, Docker build contexts will include `node_modules/`, `.git/`, `build/`, etc., making builds slow and potentially leaking secrets.

**Fix:** Add `.dockerignore` files to `api/` and `worker/` directories.

### 3.5 Worker Dockerfile doesn't pin Alpine package versions

`apk add --no-cache libraw-utils vips-tools perl perl-image-exiftool` installs latest versions. A future `docker build` may pull a breaking libvips or ExifTool update.

**Fix:** Pin versions (e.g., `vips-tools=8.15.1-r0`) or document the accepted trade-off.

### 3.6 No `restart:` policy on API or worker services

The design doc doesn't specify restart policies for api/worker, but production containers should have `restart: unless-stopped` to survive crashes and host reboots.

### 3.7 Verification step (Step 4) is weak

`docker compose ps` only checks container status, not actual readiness. Should also verify health check output:
```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps --format "{{.Name}} {{.Health}}"
```

---

## 4. Questions for Clarification

1. **Nginx config file:** The design doc mounts `./nginx.conf` into the nginx container, but this plan doesn't create `nginx.conf`. Is it planned for a later phase? If so, the Compose stack won't start without it.

2. **React build directory:** The nginx container mounts `./react-build`. This directory won't exist until the frontend is built. Should the Compose file handle this gracefully (e.g., create an empty directory)?

3. **Certbot volumes:** The design doc defines `certbot_certs` and `certbot_www` volumes. The nginx container mounts `certbot_certs` as read-only. Are these expected to be provisioned before first `docker compose up`, or will certbot bootstrap them?

4. **Worker module:** The worker Dockerfile does `COPY build/libs/worker-*.jar`, but does a `worker/` Gradle module exist yet? The phase index should clarify when the worker module is scaffolded.

---

## 5. Final Recommendation

**Major revisions needed.**

The plan's core issue is that it delegates the most complex deliverable (`docker-compose.yml`) to a one-line instruction ("Reference the design doc exactly") while the design doc itself uses pseudocode syntax that isn't valid Compose YAML. Key infrastructure elements (volumes, networks, secrets, restart policies, health checks) are not addressed. The Dockerfiles have build reliability issues (glob patterns, unavailable package versions) and missing production hardening (JVM flags, health checks).

**Key changes required before implementation:**

1. Fully specify `docker-compose.yml` with valid Compose syntax — don't delegate to the design doc
2. Add all top-level `volumes:`, `networks:`, and `secrets:` definitions
3. Add `certbot` to dev profile exclusions
4. Fix API/worker Dockerfiles: JVM memory flags, health checks, fixed JAR names, `.dockerignore`
5. Fix `pgbackup/Dockerfile` restic installation method
6. Add restart policies to production services
7. Clarify dependencies on nginx.conf and react-build directory
