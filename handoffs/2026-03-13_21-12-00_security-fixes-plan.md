---
date: 2026-03-13T21:12:00Z
git_commit: cc64071cfad9a3f12fc8ee2b7a017f9893bcd9ba
branch: master
repository: jpt_saas
topic: "Security Findings Fixes — Implementation Plan Writing"
tags: [handoff, session-transition, security, plan-writing, java, spring-boot]
status: in_progress
last_updated: 2026-03-13
type: implementation_handoff
---

# Handoff: Security Findings Fixes — Plan Document (Partially Reviewed)

## 0. Executive Summary (TL;DR)

1. I wrote a comprehensive implementation plan (`docs/superpowers/plans/2026-03-13-security-findings-fixes.md`) covering all 12 security findings from the 2026-03-13 scan, broken into 5 chunks / 20 tasks with full TDD steps and code.
2. Three parallel review agents completed; I fixed Chunks 1-2 issues (merged Tasks 2+3 into one task to resolve compilation dependency) but was interrupted before fixing Chunks 3-4 and Chunk 5 reviewer issues.
3. The next action is to apply the remaining reviewer fixes to the plan document (5 outstanding issues), then re-dispatch reviewers on the changed chunks.

## 1. Technical State

**Active Working Set** (files in high rotation right now):
- `docs/superpowers/plans/2026-03-13-security-findings-fixes.md:1` — The plan document being written and reviewed. Chunks 1-2 fixes applied, Chunks 3-5 fixes pending.
- `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:1` — The design spec (v4) this plan implements. Read-only reference.

**Current Errors / Blockers:**
```
None — this is a plan-writing task, no code has been modified yet.
```

**Environment:**
- Uncommitted changes: yes — `docs/superpowers/plans/2026-03-13-security-findings-fixes.md` (new, untracked)
- Staged changes: none
- ENV vars or config required: none
- Any running processes / background jobs: none

## 2. Progress Tracker

| Task | Status | Location | Notes |
|------|--------|----------|-------|
| Read spec fully | ✅ Complete | `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md:1` | All 6 sections + 823 lines |
| Explore codebase | ✅ Complete | — | All 19 source files read with line numbers |
| Write plan document | ✅ Complete | `docs/superpowers/plans/2026-03-13-security-findings-fixes.md:1` | 5 chunks, 20 tasks |
| Review Chunks 1-2 | ✅ Complete | — | 3 issues found, all fixed |
| Fix Chunks 1-2 issues | ✅ Complete | `docs/superpowers/plans/2026-03-13-security-findings-fixes.md:128` | Merged Tasks 2+3 into single task |
| Review Chunks 3-4 | ✅ Complete | — | 2 issues found, NOT yet fixed |
| Fix Chunks 3-4 issues | ⏳ Pending | — | See §3 for details |
| Review Chunk 5 | ✅ Complete | — | 5 issues found, NOT yet fixed |
| Fix Chunk 5 issues | ⏳ Pending | — | See §3 for details |
| Re-review fixed chunks | ⏳ Pending | — | After fixes applied |

## 3. Mental Model (Most Critical Section)

**Why the current approach was chosen:**
The plan follows the spec's recommended implementation order (Auth → OAuth2 → Restore → Share → Keywords → Schedulers) to minimize risk. Each chunk is independently testable. The plan uses TDD throughout with exact file paths, code blocks, and test commands. Tasks were originally 1:1 with spec findings but reviewers identified that Tasks 2 and 3 had a compilation dependency (Task 2 called `incrementFailedAttempts()` which didn't exist until Task 3), so they were merged.

**Codebase Gotchas Discovered This Session:**
- `api/src/main/java/org/jphototagger/api/dto/KeywordResponse.java` — Does NOT exist. The spec references it in Section 6 D4's `listKeywordsForPhoto` but the DTO hasn't been created yet. The plan must either create it or return `List<Keyword>` (current behavior).
- `api/src/main/java/org/jphototagger/api/service/AuthService.java:93` — The dummy BCrypt hash `"$2a$12$dummy.hash.to.prevent.timing.side.channel.attacks.00000000"` is 65 chars, not 60. BCrypt `matches()` regex-rejects it in ~1µs, creating a timing oracle. This is Finding #1.
- `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java:80` — Maps `EmailVerificationRequiredException` → HTTP 403 globally. Any code path that throws this exception leaks the oracle, even if the controller catches it. That's why the spec mandates throwing `BadCredentialsException` directly from the service.
- `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java:80` — Has `@Transactional(readOnly = true)` which holds a DB connection for up to 2 hours. The spec replaces this with keyset pagination (no transaction needed).
- `api/src/test/resources/application-test.yml:36` — `jpt_auth_password: test_auth_password` is already configured as a Flyway placeholder, so `jpt_auth` role exists in test containers after V4 migration runs.

**Dead Ends — Do Not Repeat These:**
| Approach Tried | Why It Failed | Evidence |
|---------------|---------------|----------|
| N/A — plan writing, no failed implementations | — | — |

**Key Decisions Made:**
| Decision | Rationale | Alternative Rejected |
|----------|-----------|---------------------|
| Merged Tasks 2+3 into single task | Task 2 calls `incrementFailedAttempts()` which Task 3 defines — compilation fails if applied separately | Placeholder method — reviewer correctly flagged as churn |
| Plan returns `List<Keyword>` not `KeywordResponse` in Task 12 | `KeywordResponse` DTO doesn't exist in codebase | Creating DTO — reviewer flagged this as spec deviation, needs resolution |

**Assumptions in Play:**
- Flyway V4 creates `jpt_auth` role in test containers — verified via `application-test.yml:36`
- `SchedulerTest.java` mocks `PhotoRepository` directly — the scheduler migration (Tasks 16-18) will require updating these mocks. Exact mock changes not yet specified (reviewer flagged this).

**Outstanding Reviewer Issues to Fix (7 total):**

### Chunks 3-4 Issues (2):

1. **Task 12 `listKeywordsForPhoto` return type:** Spec uses `List<KeywordResponse>` with DTO mapping, plan uses `List<Keyword>`. Options: (a) create `KeywordResponse` record, (b) document deviation. Recommend (a) since spec was deliberate about this.

2. **Task 9 Step 3 supersedes Task 8 Step 4:** Both modify `ShareController.getShare()`. Task 9's replacement of the entire photo block includes the IPTC/XMP stripping from Task 8. Add a note to Task 8 Step 4: "Note: Task 9 Step 3 replaces this entire block — apply Task 8's ShareService changes only, skip the controller change here."

### Chunk 5 Issues (5):

3. **Task 19 missing negative test:** Spec requires `jptAppWithoutRlsContextReturnsZeroRows` — a test verifying `jpt_app` without RLS context returns 0 rows vs `jpt_auth` returning N rows. Add this test to `SchedulerRepositoryTest`.

4. **Task 19 only tests 3 of 6 methods:** Missing: `deletePhotosByIds`, `purgeNullStorageKeyPhotos`, `findStorageKeysByUserId`. Add positive tests for each.

5. **Task 18 missing structured logging:** Spec requires `"UnverifiedAccountPurgeScheduler: purged {} accounts ({} photos queued)"` even on zero-count path. Add explicit log statement to the plan.

6. **Tasks 16/17 vague test update instructions:** Replace "may need test updates if tests mock PhotoRepository directly" with concrete guidance: `SchedulerTest` uses `@MockBean PhotoRepository` — update to `@MockBean SchedulerRepository` and change mock setup for `findPurgeableBatch()`, `deletePhotosByIds()`, etc.

7. **Task 20 `git add -A`:** Replace with `git add api/` to avoid staging unrelated files.

## 4. Delta — Changes Made This Session

- `docs/superpowers/plans/2026-03-13-security-findings-fixes.md:1` — Created complete implementation plan (20 tasks, 5 chunks). Applied Chunks 1-2 review fixes: merged old Tasks 2+3 into single Task 2 with complete `authenticate()` rewrite matching spec's 7-step flow, explicit test-update instructions for 403→401 changes.

## 5. Next Steps (Ordered — Do Not Skip Steps)

1. **Verify state** (run first to confirm plan file exists):
   ```bash
   wc -l docs/superpowers/plans/2026-03-13-security-findings-fixes.md
   ```
   Expected output: ~900-1000 lines

2. **Read the plan file** to get full context, then **apply Chunks 3-4 fixes** (2 issues from §3 above):
   - Location: `docs/superpowers/plans/2026-03-13-security-findings-fixes.md` — Task 8 Step 4 and Task 12 Step 3
   - For Task 12: Create `KeywordResponse` record in the file structure table and in Task 12's code. Or if you decide the deviation is acceptable (entity already exposed in current API), add a note explaining why.
   - For Task 8/9 overlap: Add note to Task 8 Step 4 that Task 9 Step 3 supersedes the controller change.

3. **Apply Chunk 5 fixes** (5 issues from §3 above):
   - Task 19: Add 4 more tests (3 positive + 1 negative)
   - Task 18: Add explicit structured log statement
   - Tasks 16/17: Replace vague mock instructions with concrete `SchedulerRepository` mock setup
   - Task 20: Replace `git add -A` with `git add api/`

4. **Re-dispatch reviewers** on changed chunks (use `superpowers:writing-plans` review loop):
   ```
   Agent tool: Review plan chunks 3-4 (re-review after fixes)
   Agent tool: Review plan chunk 5 (re-review after fixes)
   ```

5. **Watch for**: The Task 12 `KeywordResponse` decision — if you create the DTO, you also need to add it to the file structure table at the top of the plan, and add a creation step in Task 12.

## 6. Artifacts & References

- **Design spec**: `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md`
- **Plan document (in progress)**: `docs/superpowers/plans/2026-03-13-security-findings-fixes.md`
- **Security scan report**: `security-scan-report-2026-03-13.md`
- **Spec critical reviews**: `docs/superpowers/specs/2026-03-13-security-findings-fixes-design-critical-review-{1,2,3}.md`
- **Skill used**: `superpowers:writing-plans` (includes plan-document-reviewer-prompt.md for review dispatch)
- **Key source files read** (all with full line-number analysis):
  - `api/src/main/java/org/jphototagger/api/service/AuthService.java:1-204`
  - `api/src/main/java/org/jphototagger/api/controller/AuthController.java:1-169`
  - `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:1-131`
  - `api/src/main/java/org/jphototagger/api/service/PhotoService.java:1-324`
  - `api/src/main/java/org/jphototagger/api/controller/PhotoController.java:1-166`
  - `api/src/main/java/org/jphototagger/api/service/ShareService.java:1-185`
  - `api/src/main/java/org/jphototagger/api/controller/ShareController.java:1-196`
  - `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java:1-75`
  - `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java:1-105`
  - `api/src/main/java/org/jphototagger/api/service/KeywordService.java:1-76`
  - `api/src/main/java/org/jphototagger/api/repository/KeywordRepository.java:1-34`
  - `api/src/main/java/org/jphototagger/api/repository/PhotoKeywordRepository.java:1-15`
  - `api/src/main/java/org/jphototagger/api/scheduler/PhotoDeleteJobEnqueuer.java:1-74`
  - `api/src/main/java/org/jphototagger/api/scheduler/TrashPurgeScheduler.java:1-116`
  - `api/src/main/java/org/jphototagger/api/scheduler/OrphanReconciliationScheduler.java:1-208`
  - `api/src/main/java/org/jphototagger/api/scheduler/UnverifiedAccountPurgeScheduler.java:1-115`
  - `api/src/main/java/org/jphototagger/api/repository/PhotoRepository.java:1-103`
  - `api/src/main/java/org/jphototagger/api/entity/User.java:1-110`
  - `api/src/test/resources/application-test.yml:1-75`
