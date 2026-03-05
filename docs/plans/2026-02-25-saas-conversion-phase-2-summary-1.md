## JPhotoTagger SaaS Conversion — Phase 2: Backend API — Completion Summary

### 1. Overview
- **Original scope:** Implement the full backend API layer for the JPhotoTagger SaaS conversion — authentication (JWT + OAuth2), Spring Security with RLS, REST endpoints for photos/keywords/albums/search/saved searches, and rate limiting. Ten tasks (2.0–2.9) covering Gradle dependencies, JPA entities, repositories, JWT service, security configuration, auth controller, OAuth2 integration, photo CRUD, keyword/album/search endpoints, and Bucket4j rate limiting.
- **Overall status:** All 10 tasks are complete. Every task has at least one corresponding commit on `master`.

### 2. Completed Items
- **Task 2.0: Gradle Dependencies & Test Configuration** — Added Spring Security, jjwt, OAuth2, Bucket4j, Testcontainers Redis, `application-test.yml` (`38f585185`, `b633e7d1e`, `f69d751e1`)
- **Task 2.1: JPA Entities** — All table entities including composite-PK join tables (`17021591b`)
- **Task 2.2: Spring Data JPA Repositories** — Paginated search queries (`8d2d0ebd7`)
- **Task 2.3: JWT Authentication — Token Service** — Token generation, validation, startup secret check (`a8cf535df`)
- **Task 2.4: Spring Security Configuration + Global Exception Handler** — JWT filter, SPA CSRF handler, RLS aspect with `set_config()`, auth DataSource, RLS cleanup filter, actuator hardening, `ErrorResponse` (`3f6e47b57`)
- **Task 2.5: Auth Controller — Registration & Login** — Registration, login, refresh token rotation with family-based replay detection, logout, cookie security (`badd82418`, `b5c5b9a6f`, `d888dad9f`)
- **Task 2.6: OAuth2 Integration (Google/GitHub)** — OAuth2 login with redirect-based flow, no auto-merge (`a1280005e`, `3916e2442`)
- **Task 2.7: Photo CRUD Endpoints** — Full CRUD with soft delete, trash, pagination (`dacb8936f`)
- **Task 2.8: Keyword, Album, Search, SavedSearch Endpoints** — All endpoints with pagination (`74b1b31a4`)
- **Task 2.9: Rate Limiting — Bucket4j + Redis** — Per-user token buckets via Lettuce, configurable limits, `Retry-After` header (`5a6d08ce5`, `18d5dc52b`)

### 3. Partially Completed or Modified Items
- **Task 2.9 upload detection:** The plan specified upload detection as POST/PUT to paths containing `/photos`. During code quality review, this was tightened to POST-only matching the photos collection endpoint (`path.matches(".*/photos/?$")`), excluding sub-resource mutations like `/photos/{id}/restore`.
- **Task 2.9 Retry-After header:** Not in the original plan but added during code quality review to comply with RFC 6585. This is an additive enhancement, not a deviation.

### 4. Omitted or Deferred Items
- None. All 10 tasks (2.0–2.9) have corresponding commits and implementation evidence.

### 5. Discrepancy Explanations
- **Upload detection tightening (Task 2.9):** Code quality review identified that `path.contains("/photos")` was overly broad, matching non-upload mutations. The regex was narrowed to only match the collection endpoint, which better reflects the intent of distinguishing upload requests from general API calls.
- **Retry-After header (Task 2.9):** Added as a best-practice improvement during code quality review. RFC 6585 recommends this header on 429 responses.

### 6. Key Achievements
- All critical and security review findings from CR3 and SA-1 (documented in the v4.0 changelog) were addressed within the task implementations: `set_config()` for RLS parameterized queries, family-based refresh token replay detection, startup JWT secret validation, `RlsContextCleanupFilter`, column-level grants for `jpt_auth`, login counter reset, and SPA CSRF handling.
- The full phase was implemented incrementally with multiple fix-up commits addressing issues discovered during integration (cookie paths, Redis cleanup, JSON format, duplicate email handling, OAuth2 redirect flow).

### 7. Final Assessment
Phase 2 is fully complete. All 10 planned tasks have been implemented and committed to `master`, covering the complete backend API surface: authentication (JWT + OAuth2), authorization (Spring Security + PostgreSQL RLS), all REST endpoints (photos, keywords, albums, search, saved searches), and rate limiting. The two minor deviations from the original plan (tighter upload detection, Retry-After header) are quality improvements that better align with the plan's intent and HTTP standards. The implementation incorporates all findings from three rounds of critical implementation review and one security audit.
