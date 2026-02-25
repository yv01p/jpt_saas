# Critical Design Review: JPhotoTagger SaaS Conversion

**Reviewed:** 2026-02-24
**Original Plan:** `2026-02-24-saas-conversion-design.md`
**Reviewer:** Senior Principal Software Architect

---

## 1. Overall Assessment

The design is well-structured and pragmatic for its target scale (a few thousand users). Key strengths include: pre-signed URL pattern keeping binaries off the API layer, JWT in httpOnly cookie, S3-compatible MinIO with a clean hyperscaler escape hatch, Flyway from day one, and a solid testing strategy with Testcontainers. The multi-module Gradle reorganization is sensible.

**Major concerns:**

- Row-level multi-tenancy enforced *only* at the application layer is a data leakage risk with no DB-level safety net.
- The Java 7→21 upgrade running **in parallel** with SaaS conversion significantly increases integration risk; the Lucene 9.x rewrite alone is a significant project.
- There is no async job infrastructure for thumbnail generation or metadata extraction, creating an invisible bottleneck.
- The OAuth account-merge-by-email flow is a known account takeover vector.
- Storage quotas are absent despite `size_bytes` existing in the schema.

---

## 2. Critical Issues

### 2.1 Row-Level Multi-Tenancy Without PostgreSQL RLS

**Description:** Every query is scoped to `user_id` but this is enforced exclusively at the repository layer ("enforced at the repository layer"). No database-level enforcement exists.

**Why it matters:** One missed `WHERE user_id = ?` clause anywhere — in a new query, a join, a raw query in a future feature, a library query — produces a data leak between tenants. For a photo storage product, cross-user data access is a severe privacy breach and a regulatory liability (GDPR, CCPA).

**Suggestion:** Enable PostgreSQL Row Level Security (RLS) on all tenant tables (`photos`, `photo_metadata`, `keywords`, `albums`, etc.) with a policy like `USING (user_id = current_setting('app.current_user_id')::uuid)`. Set this via Spring's `JdbcTemplate` or a Hibernate session customizer. Application-layer enforcement remains but RLS provides an inviolable defense-in-depth layer that costs nothing to operate.

---

### 2.2 No Async Infrastructure for Thumbnail Generation and Metadata Extraction

**Description:** The design says metadata extraction happens "in the background after upload completes" and thumbnails are generated via ImageMagick CLI. There is no message queue, task executor, or job scheduler described.

**Why it matters:** Without explicit async infrastructure, "background" likely means a `CompletableFuture` or Spring `@Async` on the shared servlet thread pool. ImageMagick spawns external processes; under concurrent load these will exhaust the thread pool, stall HTTP connections, and create process storms on the VPS. This is the **first component expected to fail under load**. A user uploading 500 RAW photos will effectively DDoS the instance.

**Suggestion:** Introduce a bounded async job queue. Options in ascending complexity:
- Spring `@Async` with a dedicated `ThreadPoolTaskExecutor` (min viable — limits concurrency, no persistence)
- A database-backed job table + scheduled polling (simple, no new infra)
- A lightweight queue like Redis Streams or a dedicated queue service (correct solution; Redis is already present)

At minimum, define the concurrency model explicitly. Redis Streams for job dispatch is strongly recommended since Redis is already in the stack.

---

### 2.3 Java 7→21 Upgrade Running in Parallel with SaaS Conversion

**Description:** The upgrade tracks are described as parallel. Phase 1 targets compile-only; Phases 2–3 involve library replacements (Lucene 3.x→9.x rewrite, ImgRdr replacement). These same modules are being simultaneously converted for multi-tenancy.

**Why it matters:** The Lucene 3.x→9.x migration is a complete API rewrite (analyzers, IndexWriter, QueryParser, NRT search — all changed). ImgRdr is an unknown risk. Running these rewrites while also adding REST APIs, PostgreSQL migrations, and multi-user logic means failures are hard to isolate: is the bug in the Java 21 port, the Lucene rewrite, or the multi-tenancy logic? Integration surface grows exponentially.

**Suggestion:** Sequence these tracks, not parallelise them:
1. Complete Java 21 upgrade + all library updates (including Lucene rewrite) with existing desktop tests as the regression baseline.
2. Validate the upgraded desktop app still works correctly.
3. Begin SaaS conversion on the verified-stable Java 21 codebase.

The design's Phase 1 (compile) → Phase 2 (libraries) is correct sequencing within the upgrade track; the issue is the overlap with the SaaS conversion phases.

---

### 2.4 OAuth Account Auto-Merge by Email is an Account Takeover Vector

**Description:** "If a user registers with email then logs in with Google using the same address, accounts merge automatically."

**Why it matters:** This is a well-documented vulnerability. An attacker who registers `victim@gmail.com` via email/password before the legitimate user authenticates with Google gains full access to the account once the merge fires. OWASP lists this pattern under account pre-hijacking.

**Suggestion:** Never auto-merge silently. Instead:
- If an email-only account exists and a Google login arrives for the same email, do **not** merge automatically.
- Send a verification email to the existing account: "Did you just attempt to link your Google account? Click here to confirm."
- Only merge after the existing session confirms the link.
- Alternatively, block the OAuth login and display: "An account with this email already exists. Log in with your password to link your Google account."

---

### 2.5 No Storage Quota Design

**Description:** The schema records `size_bytes` per photo but there is no quota table, enforcement logic, or per-user limit described anywhere.

**Why it matters:** A photo SaaS without storage quotas will have a small number of power users consume disproportionate storage, potentially exhausting the VPS disk and making the service unavailable for all users. A single user uploading uncompressed RAW files can easily consume hundreds of gigabytes. This is also required for any business model (free tier vs. paid tiers).

**Suggestion:** Add a `user_quota` table or a `quota_bytes` column to `users`. Enforce at the upload endpoint before accepting a new file: check `SUM(size_bytes) WHERE user_id = ? + new_file_size <= quota_bytes`. This check must be atomic (advisory lock or serializable transaction) to prevent race conditions with concurrent uploads. Define default quota in config (e.g., 10 GB free tier).

---

### 2.6 Lucene Index Backup and Corruption Risk

**Description:** The Lucene index is embedded in the Spring Boot process. There is no mention of Lucene index backup, nor any strategy for handling index corruption on JVM crash.

**Why it matters:** Lucene's `IndexWriter` uses a write-ahead log but is not crash-safe in the same sense as PostgreSQL. An abrupt JVM termination (OOM, kill signal) can leave the index in a state that requires `IndexWriter.forceMerge` or full rebuild. If the index is the source of truth for search (not derived from PostgreSQL), corruption means permanent data loss. Even if PostgreSQL is the source of truth, a full index rebuild from database for thousands of users with millions of photos could take hours.

**Suggestion:**
- Treat the Lucene index as a **derived cache** of PostgreSQL data only — it can always be rebuilt.
- Add an admin endpoint `/admin/search/reindex` that triggers a full rebuild.
- Exclude the Lucene index directory from the MinIO/restic backup (it's regenerable); document the rebuild procedure instead.
- Consider index isolation: a separate index directory per user, enabling per-user rebuild without full reindex.

---

### 2.7 MinIO Backup Strategy Inconsistency Risk

**Description:** MinIO backup is described as "restic backup of the data volume." This is a filesystem-level snapshot of a running MinIO process.

**Why it matters:** MinIO writes objects using internal structure (bitrot protection, versioning metadata). Backing up the raw filesystem while MinIO is running risks inconsistent state — a partially-written object can appear as corrupt data in the backup. This is identical to backing up a running PostgreSQL data directory with `cp`.

**Suggestion:** Replace filesystem backup with MinIO's own tools:
- Use `mc mirror jpt-photos/ b2-bucket/jpt-photos-backup/` (MinIO Client mirror to Backblaze B2) for a consistent, incremental object backup.
- Use restic for the MinIO data volume only as a fallback/disaster recovery snapshot with the MinIO container stopped.

---

## 3. Alternative Architectural Challenge

**Alternative: Replace Lucene with PostgreSQL Full-Text Search + JSONB Indexing**

Instead of a Lucene 9.x rewrite, use PostgreSQL's native full-text search (`tsvector`/`tsquery`) and JSONB GIN indexes for metadata search.

- Store `exif_json`, `iptc_json`, `xmp_json` as PostgreSQL `jsonb` columns with GIN indexes.
- Add a `tsvector` generated column on `keywords.name`, `photos.filename`, and extracted metadata fields for full-text search.
- Use `@@` operator for full-text queries; use `jsonb @>` for exact metadata matches.

**Pro:** Eliminates the Lucene 9.x rewrite entirely (the highest-risk library migration), removes a separate index lifecycle to manage, provides ACID consistency between metadata and search index, and simplifies the backup story (everything is in PostgreSQL).

**Con:** Full-text search quality is lower than Lucene — no BM25 ranking, weaker stemming/tokenisation options, no fuzzy matching. For photo metadata search (exact EXIF values, keyword lookups), this gap is often acceptable, but it closes the door to advanced search features (similarity search, NLP queries) without later rearchitecting.

---

## 4. Minor Issues & Improvements

1. **`photo_metadata` schema as JSON blobs:** Storing all EXIF/IPTC/XMP as JSON columns makes indexing specific fields (e.g., "find all photos taken with a Canon 5D") require JSON path queries. Consider a flat `photo_exif` table for the most-queried fields (camera model, ISO, focal length, GPS), keeping the blob columns for raw/uncommon fields.

2. **Keyword hierarchy using adjacency list:** `keywords.parent_id` (adjacency list) is simple but queries for entire subtrees require recursive CTEs. Consider PostgreSQL's `ltree` extension for efficient ancestor/descendant queries if the keyword tree is expected to be deep.

3. **Redis has no password in the Docker Compose example:** The example config shows `redis:7-alpine` with no `--requirepass` or `AUTH`. On a VPS, if Redis port is accidentally exposed (misconfigured firewall), it's open without authentication.

4. **Docker Compose `depends_on` does not check readiness:** `depends_on: [postgres, minio, redis]` only waits for containers to start, not for services to be ready. The API will crash-loop until PostgreSQL accepts connections. Add `healthcheck` directives to each service and use `depends_on: condition: service_healthy`.

5. **CI/CD deployment gap for React build:** The Docker Compose mounts `./react-build:/usr/share/nginx/html`. How does the built artifact get onto the VPS? The CI pipeline description ends at tests; the deployment step (build React, SCP/rsync to VPS, restart nginx) is not described.

6. **CSRF protection:** Storing JWT in an httpOnly cookie requires explicit CSRF token handling in Spring Security for the SPA. The design should note whether the double-submit cookie pattern or the Synchronizer Token Pattern is used, and confirm Spring Security's CSRF is not disabled for the API endpoints.

7. **No mention of photo deduplication:** A user who uploads the same photo twice (common in photo management workflows) will silently store duplicates. A content hash (SHA-256) stored on upload and checked before writing to MinIO would prevent waste. This is minor for MVP but a common user complaint later.

8. **SSL certificate management:** The design mentions "Caddy or Certbot" but doesn't commit. Caddy is a full reverse proxy that would replace Nginx in the current design. Choosing Certbot implies staying with Nginx. This decision should be made explicit — Caddy is simpler for certificate automation but is an additional technology choice.

---

## 5. Questions for Clarification

1. **Business model:** Is there a free tier? If so, what are the storage limits? This directly drives the quota design and the urgency of issue 2.5.

2. **Maximum file size:** RAW camera files (CR3, ARW, NEF) can be 50–100 MB each. Does the chunked upload design handle these? What is the intended max file size limit?

3. **Async background processing:** When the design says "background metadata extraction after upload completes," what implementation is intended — `@Async`, a thread pool, or something else? This is the gap in issue 2.2.

4. **Lucene as source of truth vs. derived cache:** Can the Lucene index always be rebuilt from PostgreSQL, or does it store data not in PostgreSQL? This determines the severity of issue 2.6.

5. **Sequential vs. parallel upgrade tracks:** Is there a hard deadline driving the decision to parallelize the Java upgrade with the SaaS conversion? If so, what is the risk tolerance for integration failures?

6. **Monitoring/alerting:** Phase 5 mentions "monitoring" but provides no detail. What is the target observability stack? (Prometheus + Grafana, Datadog, VictoriaMetrics, etc.) Disk full on the VPS is a plausible failure mode that needs alerting.

---

## 6. Final Recommendation

**Approve with changes — targeted revisions required.**

The core architecture is sound and well-matched to the stated scale. Four issues must be addressed before implementation begins:

1. **Add PostgreSQL RLS** as a defense-in-depth layer for multi-tenancy isolation (2.1).
2. **Define the async job model explicitly** for thumbnail generation and metadata extraction — Redis Streams is recommended given existing Redis (2.2).
3. **Sequence the Java upgrade before SaaS conversion**, not in parallel, given the Lucene rewrite scope (2.3).
4. **Fix the OAuth account merge flow** to require explicit confirmation by the existing account holder (2.4).

Issues 2.5 (quotas) and 2.7 (MinIO backup) are important but can be addressed in Phase 5. Issues 2.6 (Lucene backup) is addressed by treating the index as a derived cache.

The alternative of replacing Lucene with PostgreSQL full-text search should be seriously evaluated against the Lucene 9.x rewrite cost — for a photo metadata search use case, PostgreSQL JSONB + FTS may be sufficient and removes the highest-risk library migration.
