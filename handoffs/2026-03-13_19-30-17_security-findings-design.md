---
date: 2026-03-13T19:30:17Z
git_commit: 743d17a7dae26fe8a48a61b924f7ed413971b1de
branch: master
repository: jpt_saas
topic: "Security Findings Fixes — Design Complete, Implementation Pending"
tags: [handoff, session-transition, security, spring-boot, java]
status: in_progress
last_updated: 2026-03-13
type: implementation_handoff
---

# Handoff: Security Findings Design → Implementation

## 0. Executive Summary (TL;DR)

1. We reviewed `security-scan-report-2026-03-13.md` (12 findings: 2 High, 3 Medium, 3 Low validated + 4 discarded-but-fixed), ran all findings through the brainstorming skill to design complete fixes, and wrote + iterated a spec through one full spec-review cycle — all 7 reviewer issues are resolved in the updated spec.
2. We stopped immediately after updating the spec (`docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md`) with the reviewer fixes but before running the second spec-review pass; the spec is not yet committed.
3. Run the spec reviewer one more time to confirm the updated spec passes, then invoke the `superpowers:writing-plans` skill, then implement all fixes in the order defined in the spec's Implementation Order section.

---

## 1. Technical State

**Active Working Set:**
- `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:1` — the design spec; **modified but not committed** after resolving all 7 spec-reviewer issues
- `security-scan-report-2026-03-13.md:1` — the original scan report; read-only reference, all 12 findings are understood
- `api/src/main/java/org/jphototagger/api/service/AuthService.java:86` — target for Findings #1, #2, #3
- `api/src/main/java/org/jphototagger/api/controller/AuthController.java:90` — target for Finding #2 (403→401)
- `api/src/main/java/org/jphototagger/api/controller/ShareController.java:83` — target for Findings #4, #5, C3
- `api/src/main/java/org/jphototagger/api/service/ShareService.java:148` — target for Finding #4 (new strip methods)
- `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java:64` — target for Finding C3 (add ownerId param)
- `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java:58` — target for Finding #6
- `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java:85` — target for Finding #6
- `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java:81` — target for Finding #6
- `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java:34` — target for Finding #6 (new overloads)
- `api/src/main/java/org/jphototagger/api/service/PhotoService.java:298` — target for Finding #7 (restore reorder)
- `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:66` — target for Finding #8
- `api/src/main/java/org/jphototagger/api/service/KeywordService.java:54` — target for D1
- `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java:25` — target for D3
- `api/src/main/java/org/jphototagger/api/controller/PhotoController.java:124` — target for D4
- `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java:51` — update to delegate to MetadataLocationStripper

**Current Errors / Blockers:**
```
None
```

**Environment:**
- Uncommitted changes: **yes** — `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md` (updated after spec review), `docs/plans/2026-02-25-saas-conversion-phase-5.md` (pre-existing modification, not from this session), `react-build/index.html` (pre-existing modification, not from this session)
- Staged changes: none
- ENV vars or config required: none beyond normal dev setup
- Any running processes / background jobs: none

---

## 2. Progress Tracker

| Task | Status | Location | Notes |
|------|--------|----------|-------|
| Read security scan report | ✅ Complete | `security-scan-report-2026-03-13.md:1` | All 12 findings understood |
| Read all target source files | ✅ Complete | See §1 | AuthService, ShareController, schedulers, etc. all read |
| Brainstorming session (3 key decisions) | ✅ Complete | — | Decisions: B for MetadataLocationStripper, A for schedulers, B for atomic auth |
| Design spec — first draft | ✅ Complete | `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:1` | Committed at 743d17a7d |
| Spec review — round 1 | ✅ Complete | — | 7 issues found (see §3) |
| Spec review — round 1 fixes | ✅ Complete | `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:1` | File updated, NOT YET COMMITTED |
| Spec review — round 2 | ⏳ Pending | — | Must run before writing-plans |
| writing-plans skill invocation | ⏳ Pending | — | After spec review passes |
| Implementation of all 12 findings | ⏳ Pending | — | 6 sections, ordered in spec |

---

## 3. Mental Model (Most Critical Section)

### Why the current approach was chosen

**Three key design decisions made and approved by the user:**

1. **`MetadataLocationStripper` utility (Finding #4):** Chosen over duplicating logic in ShareService because the vulnerability exists precisely because there were two diverging implementations. A single utility that both `ShareService` (JSON string path) and `PhotoMetadataResponse` (Map path) delegate to eliminates future divergence. The utility uses **Map-based** methods — JSON parsing/serialization stays in `ShareService` where it already exists.

2. **`authJdbcTemplate` for schedulers (Finding #6):** Chosen over setting RLS context in scheduler threads because `authJdbcTemplate` (`jpt_auth` BYPASSRLS role) is already the established pattern for cross-tenant admin operations. `UnverifiedAccountPurgeScheduler` already uses it for user-level deletes, confirming it works.

3. **Single atomic SQL for lockout counter (Finding #3):** The `UPDATE ... SET failed_login_attempts = failed_login_attempts + 1, locked_until = CASE ... END WHERE id = ?` is a single operation that fixes the race and is called from exactly two places: wrong-password and correct-password-unverified-email paths. This ensures both paths use the same atomic increment.

### Codebase Gotchas Discovered This Session

- `api/src/main/java/org/jphototagger/api/service/AuthService.java:93` — The dummy BCrypt hash is `65` chars. BCrypt requires `60` exactly (`$2a$12$` = 7 + 53 hash chars). The regex fails instantly. Fix: use `new BCryptPasswordEncoder(12).encode("__dummy__")` as a static field (computed at class load, always valid).
- `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java:39` — The authoritative IPTC key set has **9 elements** with both `iptc:`-prefixed and unprefixed variants. The XMP key set has **5 elements**. These are the canonical key sets `MetadataLocationStripper` must use — not the larger sets originally drafted in the spec (which was Issue #3 from the reviewer).
- `api/src/main/resources/db/migration/V4__create_jpt_auth_role.sql:20` — `jpt_auth` has column-level UPDATE on users but `used_bytes` is NOT in that list. The `purgeNullStorageKeyPhotos()` CTE updates `users.used_bytes` — needs `GRANT UPDATE (used_bytes) ON users TO jpt_auth` added to V14 migration.
- `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java:34` — Redis message format is `{photo_id, original_key, thumbnail_sm, thumbnail_md}`. Thumbnail keys are always `{userId}/thumbnails/{photoId}_sm.jpg` and `_md.jpg`. Both new enqueuer methods (`enqueueByRows` and `enqueueStorageKeys`) must produce this exact format.
- `api/src/main/java/org/jphototagger/api/controller/ShareController.java:148` — `getSharedAlbumPhotos()` currently returns `findAlbumPhotos()` bare with NO post-processing. It leaks `storage_key` AND generates no presigned URLs for album photos. The fix adds both — use `shareData.get("user_id")` as `albumOwnerId` (all album photos belong to the album creator).

### Dead Ends — Do Not Repeat These

| Approach Tried | Why It Failed | Evidence |
|---------------|---------------|----------|
| Hardcoded 60-char BCrypt string constant | Cannot provide one in the spec without running code; risk of spec shipping another invalid hash | Spec reviewer Issue #1 |
| Using `jpt_auth` datasource for scheduler fixes without checking permissions | `jpt_auth` doesn't have `UPDATE (used_bytes)` on users — the `purgeNullStorageKeyPhotos()` CTE would fail | V4 migration, spec reviewer Issue #6 |
| MetadataLocationStripper with its own key set definitions | Diverges from `PhotoMetadataResponse` — the exact bug pattern we're fixing | Spec reviewer Issue #3 |
| `enqueueStorageKeys(UUID, List<String>)` directly — no photoId | Storage keys contain `photoId` in format `{userId}/originals/{photoId}.ext` — must parse it | Spec reviewer Issue #5 |

### Key Decisions Made

| Decision | Rationale | Alternative Rejected |
|----------|-----------|---------------------|
| `MetadataLocationStripper` uses Map-based methods | JSON parsing stays in ShareService where it already exists; PhotoMetadataResponse works with Maps natively | String-based methods requiring extra serialize/deserialize in PhotoMetadataResponse |
| `PhotoMetadataResponse.IPTC_LOCATION_KEYS` / `XMP_LOCATION_KEYS` are the source of truth | They are the existing working implementation; stripper adopts them, not vice versa | Using the spec's original (larger) key set — diverges from authenticated path |
| `DUMMY_HASH` computed at class load via `BCryptPasswordEncoder(12).encode()` | Guaranteed valid; no hardcoded string in source | Hardcoded pre-computed string — can't verify validity in spec without running code |
| `getSharedAlbumPhotos()` uses `shareData.get("user_id")` as album owner UUID | All album photos guaranteed to belong to album creator (enforced by `validateResourceExists()`) | Parsing userId from each photo's storage_key — unnecessary complexity |
| V14 migration adds `GRANT SELECT ON photos TO jpt_auth` AND `GRANT UPDATE (used_bytes) ON users TO jpt_auth` | jpt_auth needs SELECT on photos for scheduler read queries; needs used_bytes UPDATE for purgeNullStorageKeyPhotos CTE | Assuming permissions already exist — too risky |

### Assumptions in Play

- `jpt_auth` already has DELETE on photos (confirmed by existing `UnverifiedAccountPurgeScheduler` code that deletes from photos via authJdbc and reportedly works) — if wrong, V14 migration needs `GRANT DELETE ON photos TO jpt_auth` too.
- All photos in an album belong to the album's creator — if wrong, the `shareData.get("user_id")`-as-ownerId approach for album photo URL generation produces incorrect thumbnail paths.
- `OrphanReconciliationScheduler` replacing `userRepository.streamAllIds()` with `authJdbc.queryForList("SELECT id FROM users")` (loading all user IDs into memory) is acceptable at current scale — if wrong (very large user count), a server-side cursor via `JdbcTemplate.query()` with `RowCallbackHandler` is needed.

---

## 4. Delta — Changes Made This Session

The spec at `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:1` was updated to fix all 7 spec-reviewer issues but NOT yet committed. Key changes from the first draft:

- `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:40` — Finding #1: `DUMMY_HASH` changed from hardcoded invalid string to `new BCryptPasswordEncoder(12).encode("__dummy__...")` static field initializer
- `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:60` — Finding #3: Added explicit documentation that re-locking after expired lockout is **intentional** behavior
- `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:90` — Finding #4: `MetadataLocationStripper` redesigned to use **Map-based** methods (not JSON string methods); key sets now explicitly adopt `PhotoMetadataResponse`'s 9-element IPTC set and 5-element XMP set as authoritative
- `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:145` — Finding #5: Album photo URL generation now explicitly uses `shareData.get("user_id")` as album owner UUID
- `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:170` — Finding #6: `PhotoDeleteJobEnqueuer` two new methods (`enqueueByRows`, `enqueueStorageKeys`) fully specified with exact message field names, photoId parsing logic promoted to static helper `extractPhotoIdFromKey()`
- `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:175` — Finding #6: V14 migration explicitly grants `SELECT ON photos` and `UPDATE (used_bytes) ON users` to `jpt_auth`
- `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:210` — Finding #6: `OrphanReconciliationScheduler` uses `authJdbc.queryForList("SELECT id FROM users")` for user list; `@Transactional(readOnly = true)` annotation removed

---

## 5. Next Steps (Ordered — Do Not Skip Steps)

1. **Verify state** (confirm spec is modified and not yet committed):
   ```bash
   git status --short docs/superpowers/specs/
   ```
   Expected output: ` M docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md`

2. **Commit the updated spec:**
   ```bash
   git add docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md
   git commit -m "docs: resolve all 7 spec-reviewer issues in security findings design"
   ```

3. **Run spec reviewer (round 2)** — dispatch the spec-document-reviewer subagent with this exact prompt:
   > "Review `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md` for clarity, completeness, internal consistency, and implementability. Assume all vulnerability analysis is correct. Focus on: (1) whether each fix describes what changes and where, (2) whether before/after states are unambiguous, (3) whether any references to methods/classes are undefined, (4) whether null/error cases are handled, (5) whether the V14 migration covers all permissions needed by `jpt_auth` for scheduler operations."

   Expected: APPROVED. If issues found, fix and re-dispatch (max 5 iterations).

4. **Invoke `superpowers:writing-plans` skill** to create the implementation plan from the approved spec.

5. **Implement in this order** (from spec Section "Implementation Order"):
   1. Auth fixes (#1, #2, #3) — `AuthService.java` + `AuthController.java`
   2. OAuth2 guard (#8) — `OAuth2SuccessHandler.java`
   3. Restore reorder (#7) — `PhotoService.java:298`
   4. Share system (#4, #5, C3) — new `MetadataLocationStripper.java` + `ShareService.java` + `ShareController.java` + `ShareLookupRepository.java` + `PhotoMetadataResponse.java`
   5. Keyword fixes (D1, D3, D4) — `KeywordService.java` + `KeywordRepository.java` + `PhotoController.java` + `PhotoService.java`
   6. Scheduler fixes (#6) — new `V14__grant_photos_select_to_jpt_auth.sql` + `PhotoDeleteJobEnqueuer.java` + all three schedulers

6. **Watch for:** The `purgeNullStorageKeyPhotos()` CTE in `TrashPurgeScheduler` does `UPDATE users SET used_bytes = ...` — this requires `GRANT UPDATE (used_bytes) ON users TO jpt_auth` which must be in V14. If forgotten, this CTE will throw `permission denied` at runtime with no compile-time warning.

---

## 6. Artifacts & References

- **Design spec:** `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:1`
- **Security scan report (input):** `security-scan-report-2026-03-13.md:1`
- **New files to be created during implementation:**
  - `api/src/main/java/org/jphototagger/api/service/MetadataLocationStripper.java` (new Spring @Component)
  - `api/src/main/resources/db/migration/V14__grant_photos_select_to_jpt_auth.sql` (new migration)
- **Key source files already read this session (no need to re-read):**
  - `api/src/main/java/org/jphototagger/api/service/AuthService.java:1`
  - `api/src/main/java/org/jphototagger/api/controller/AuthController.java:1`
  - `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:1`
  - `api/src/main/java/org/jphototagger/api/service/PhotoService.java:1`
  - `api/src/main/java/org/jphototagger/api/controller/ShareController.java:1`
  - `api/src/main/java/org/jphototagger/api/service/ShareService.java:1`
  - `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java:1`
  - `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java:1`
  - `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java:1`
  - `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java:1`
  - `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java:1`
  - `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java:1`
  - `api/src/main/resources/db/migration/V2__rls_policies.sql:1`
  - `api/src/main/resources/db/migration/V4__create_jpt_auth_role.sql:1`
  - `api/src/main/resources/db/migration/V11__grant_updated_at_to_jpt_auth.sql:1`
- **Related tickets / issues:** `security-scan-report-2026-03-13.md` (source of all 12 findings)
