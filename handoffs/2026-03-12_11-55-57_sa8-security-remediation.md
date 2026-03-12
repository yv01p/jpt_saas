---
date: 2026-03-12T11:55:57-04:00
git_commit: 404e8621228953a650ca43cbc11ffc8c6e67ac5b
branch: master
repository: jpt_saas
topic: "SA4-8 Security Audit Remediation — Fix 8 Findings"
tags: [handoff, session-transition, security, spring-boot, java, nginx, minio]
status: in_progress
last_updated: 2026-03-12
type: implementation_handoff
---

# Handoff: SA4-8 Security Audit Remediation

## 0. Executive Summary (TL;DR)

1. I was remediating 8 verified security findings from `docs/plans/2026-03-12-saas-conversion-phase-4-security-audit-8.md` — all code changes are complete and compile, but tests need updating.
2. All 8 fixes are implemented in `git stash@{0}` — I stopped because 12 tests fail due to test fixtures not reflecting the new security behaviors (unverified email blocking login, SameSite=Strict on JWT cookies, generic error messages).
3. Pop the stash (`git stash pop`), then update 4 test files to align with the new security behaviors.

## 1. Technical State

**Active Working Set** (files in high rotation right now):
- `api/src/test/java/org/jphototagger/api/controller/AuthControllerTest.java:343` — `register()` helper creates unverified users; needs `verifyEmail()` step after registration for login tests
- `api/src/test/java/org/jphototagger/api/controller/AuthControllerTest.java:312` — `cookieAttributesAreSecureAndSameSiteLax` test name and assertion must change to Strict for JWT
- `api/src/test/java/org/jphototagger/api/controller/GlobalExceptionHandlerTest.java:19` — asserts `"Photo not found"` but handler now returns generic `"Not Found"`
- `api/src/test/java/org/jphototagger/api/security/OAuth2SuccessHandlerTest.java:245` — `assertCookieSecure` checks `SameSite=Lax` on JWT, must change to `SameSite=Strict`
- `api/src/test/java/org/jphototagger/api/controller/UserControllerTest.java:65` — `createUser()` INSERT missing columns; test was previously passing (tested against original code), failure may be intermittent or related to test ordering

**Current Errors / Blockers:**
```
12 tests failed across 4 test classes:
- AuthControllerTest: 8 failures — Status expected:<200> but was:<403> (unverified email now blocks login)
- UserControllerTest: 2 failures — Status expected:<200> but was:<404> (may be pre-existing, see §3)
- GlobalExceptionHandlerTest: 1 failure — expected: "Photo not found" but was: "Not Found"
- OAuth2SuccessHandlerTest: 1 failure — SameSite=Lax assertion fails (now SameSite=Strict)
```

**Environment:**
- Uncommitted changes: YES — all in `git stash@{0}` (15 files, see stash contents below)
- Staged changes: none
- ENV vars or config required: none beyond standard `.env`
- Any running processes / background jobs: none

## 2. Progress Tracker

| Task | Status | Location | Notes |
|------|--------|----------|-------|
| Finding #1: Unverified email login | ✅ Complete | `api/src/main/java/org/jphototagger/api/service/AuthService.java:131` | Added email_verified check after bcrypt |
| Finding #1: AuthController catch | ✅ Complete | `api/src/main/java/org/jphototagger/api/controller/AuthController.java:89` | Catches EmailVerificationRequiredException → 403 |
| Finding #2: XFF IP extraction | ✅ Complete | `api/src/main/java/org/jphototagger/api/security/RateLimitFilter.java:75` | Replaced with `request.getRemoteAddr()`, deleted `getClientIp()` |
| Finding #3: CSRF token | ✅ No action needed | `frontend/src/api/client.ts:66` | Informational — already secure |
| Finding #4: SameSite JWT→Strict | ✅ Complete | `api/src/main/java/org/jphototagger/api/controller/AuthController.java:145` | JWT=Strict, refresh stays Lax, logout cookie fixed |
| Finding #4: OAuth2 SameSite | ✅ Complete | `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:120` | JWT=Strict, refresh stays Lax |
| Finding #5: Password max length | ✅ Complete | `api/src/main/java/org/jphototagger/api/dto/RegisterRequest.java:9` | max=128 on Register, Login, changePassword guard |
| Finding #5: nginx auth body limit | ✅ Complete | `nginx.prod.conf:95` | `client_max_body_size 16k` on `/api/auth/` |
| Finding #6: Pagination validation | ✅ Complete | `api/src/main/java/org/jphototagger/api/controller/PhotoController.java:59` | @Validated + @Min/@Max, removed Math.min clamp |
| Finding #7: MinIO presign policy | ✅ Complete | `docker-compose.yml:176` | Added s3:GetObject on jpt-photos/*, updated MinioConfig comments |
| Finding #8: CSP directives | ✅ Complete | `nginx.prod.conf:76` | Added `object-src 'none'; base-uri 'self'` |
| Finding #9: GlobalExceptionHandler | ✅ Complete | `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java:57` | Generic "Not Found" message, generic ConstraintViolation message |
| Finding #9: UUID leak | ✅ Complete | `api/src/main/java/org/jphototagger/api/service/PhotoService.java:230` | Removed photoId from exception message |
| Fix AuthControllerTest | 🔄 In Progress | `api/src/test/java/org/jphototagger/api/controller/AuthControllerTest.java` | Need verifyEmail helper + SameSite assertion update |
| Fix GlobalExceptionHandlerTest | ⏳ Pending | `api/src/test/java/org/jphototagger/api/controller/GlobalExceptionHandlerTest.java:19` | Change assertion from "Photo not found" to "Not Found" |
| Fix OAuth2SuccessHandlerTest | ⏳ Pending | `api/src/test/java/org/jphototagger/api/security/OAuth2SuccessHandlerTest.java:245` | Change SameSite=Lax to SameSite=Strict for JWT |
| Investigate UserControllerTest | ⏳ Pending | `api/src/test/java/org/jphototagger/api/controller/UserControllerTest.java` | May be pre-existing; tested clean on original code in isolation |

## 3. Mental Model (Most Critical Section)

**Why the current approach was chosen:**

Each finding was verified against the actual source code before implementing. The audit report (SA4-8) had some inaccuracies:
- Finding #3 was correctly identified as informational (no action needed)
- Finding #7's analysis of MinIO presign was partially wrong — the audit said `getPresignedObjectUrl()` is pure HMAC (it is), but missed that MinIO still validates the signing user's permissions when the URL is accessed. The empty policy means pre-signed URLs will fail at access time.

For Finding #1 (unverified email login), I chose to throw `EmailVerificationRequiredException` **after** the bcrypt check to preserve the timing side-channel protection. The catch in AuthController returns 403 with a helpful message rather than the audit's suggestion of `BadCredentialsException("Invalid credentials")` which would confuse legitimate users who forgot to verify.

For Finding #4 (SameSite), the key insight is: JWT cookie → `Strict` (session cookie, SA4-F4 requirement), refresh cookie → keep `Lax` (allows silent re-authentication after clicking links from email/Slack). This is the standard "soft session recovery" pattern.

**Codebase Gotchas Discovered This Session:**
- `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java:1` — Already existed! The audit said "no @ControllerAdvice exists" but it does. It already handles EntityNotFoundException, EmailVerificationRequiredException, ConstraintViolationException, etc. I only needed to change the EntityNotFoundException response from `ex.getMessage()` to generic `"Not Found"`, and ConstraintViolationException from detailed property paths to generic `"Invalid request parameters"`.
- `api/src/main/java/org/jphototagger/api/exception/EmailVerificationRequiredException.java:1` — Also already existed. Used by PhotoService upload gate. I reused it for the login check.
- `api/src/test/java/org/jphototagger/api/controller/UserControllerTest.java:77` — Test accesses `/api/users/me` but controller maps `/users/me`. No context-path is configured. This test passed in isolation against original code, so something else (possibly Spring test context caching or a filter) makes it work. Needs investigation.

**Dead Ends — Do Not Repeat These:**
| Approach Tried | Why It Failed | Evidence |
|---------------|---------------|----------|
| Creating new GlobalExceptionHandler file | File already exists | `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java` — Write tool rejected |
| git stash + test + stash pop to verify pre-existing test failures | User interrupted the second stash attempt; first cycle confirmed UserControllerTest passes on original code in isolation | User tool rejection at second `git stash` |

**Key Decisions Made:**
| Decision | Rationale | Alternative Rejected |
|----------|-----------|---------------------|
| EmailVerificationRequiredException instead of BadCredentialsException for unverified login | Users need actionable feedback, not a brick wall; the email is already known to the user who registered it | Audit suggested BadCredentialsException — indistinguishable from wrong password |
| JWT SameSite=Strict, refresh SameSite=Lax | Strict on session cookie per SA4-F4; Lax on refresh enables external link → silent re-auth | Both Strict — would break external link flow |
| request.getRemoteAddr() instead of XFF parsing | Spring's ForwardedHeaderFilter (enabled via `forward-headers-strategy: framework`) already resolves this correctly | Keep XFF parsing with first-entry — fragile, duplicates framework logic |
| Generic "Not Found" in EntityNotFoundException handler | Prevents leaking entity type ("Photo not found", "User not found", "Metadata not available") | Pass through ex.getMessage() — information disclosure |
| Remove Math.min(size, 100) clamp from PhotoService | Validation at controller layer (@Min/@Max) makes service-layer clamp redundant dead code | Keep both — violates single enforcement point |

**Assumptions in Play:**
- `server.forward-headers-strategy: framework` in `application.yml:60` makes `request.getRemoteAddr()` return the real client IP — if this setting is ever removed, rate limiting breaks
- OAuth2 redirect flow works with SameSite=Strict on JWT cookie — the cookie is being SET (not read) during the redirect, so it should work, but needs testing
- The `UserControllerTest` failures may be pre-existing or test-ordering-dependent — confirmed it passes against original code in isolation

## 4. Delta — Changes Made This Session

All changes are in `git stash@{0}`. Here's what each file change does:

- `api/src/main/java/org/jphototagger/api/service/AuthService.java:87-88` — Added `email_verified` to SELECT; added check at line 131 after bcrypt; added max-length guard in `changePassword()` at line 145; added import for EmailVerificationRequiredException
- `api/src/main/java/org/jphototagger/api/controller/AuthController.java:89-91` — Added catch for EmailVerificationRequiredException → 403; changed JWT cookie to SameSite=Strict at line 145; changed logout clear-cookie to SameSite=Strict at line 133; added import
- `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:120` — Changed JWT cookie to SameSite=Strict (refresh stays Lax)
- `api/src/main/java/org/jphototagger/api/security/RateLimitFilter.java:75` — Replaced `getClientIp(request)` with `request.getRemoteAddr()`; deleted `getClientIp()` method entirely (was lines 123-130)
- `api/src/main/java/org/jphototagger/api/dto/RegisterRequest.java:9` — Added `max = 128` to @Size
- `api/src/main/java/org/jphototagger/api/dto/LoginRequest.java:8` — Added `@Size(max = 128)` and import
- `api/src/main/java/org/jphototagger/api/controller/PhotoController.java:31` — Added @Validated class annotation; added @Min(0) on page, @Min(1) @Max(100) on size for both listPhotos and listTrash; added imports
- `api/src/main/java/org/jphototagger/api/service/PhotoService.java:230` — Removed `": " + photoId` from EntityNotFoundException; removed `Math.min(size, 100)` from listPhotos and listTrash
- `api/src/main/java/org/jphototagger/api/controller/GlobalExceptionHandler.java:57` — Changed `ex.getMessage()` to `"Not Found"` for EntityNotFoundException; changed ConstraintViolationException to return generic `"Invalid request parameters"` instead of property paths
- `api/src/main/java/org/jphototagger/api/config/MinioConfig.java:16,38,54` — Updated 3 comments from "empty policy" to "read-only (s3:GetObject)"
- `docker-compose.yml:176` — Changed empty Statement `[]` to `[{"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::jpt-photos/*"]}]`
- `nginx.prod.conf:76` — Added `object-src 'none'; base-uri 'self'` to CSP
- `nginx.prod.conf:95` — Added `client_max_body_size 16k;` to `/api/auth/` location block

## 5. Next Steps (Ordered — Do Not Skip Steps)

1. **Restore stashed changes**:
   ```bash
   cd /home/ubuntu/jpt_saas && git stash pop
   ```
   Expected output: files restored, no conflicts

2. **Verify compilation**:
   ```bash
   ./gradlew :api:compileJava 2>&1 | tail -5
   ```
   Expected: `BUILD SUCCESSFUL`

3. **Fix AuthControllerTest** (`api/src/test/java/org/jphototagger/api/controller/AuthControllerTest.java`):
   - Add a `verifyEmail()` helper method that directly sets `email_verified = true` in DB via `jdbcTemplate`:
     ```java
     @Autowired @Qualifier("authJdbcTemplate") JdbcTemplate authJdbc;

     private void verifyEmail(String email) {
         authJdbc.update("UPDATE users SET email_verified = true WHERE email = ?", email);
     }
     ```
   - Call `verifyEmail(email)` after every `register(email, password)` call in tests that need to log in (lines 119, 136, 161, 204, 219, 233, 255, 278, 314)
   - Exception: `loginReturnsGeneric401ForLockedAccount` test at line 134 — the failed login attempts happen with wrong password, so email verification status doesn't matter for those. But the final correct-password attempt will get 403 instead of 401 if unverified. So verify email there too.
   - Rename test `cookieAttributesAreSecureAndSameSiteLax` → `cookieAttributesAreSecureWithCorrectSameSite` at line 312
   - Change assertion at line 332: `SameSite=Lax` → `SameSite=Strict` for JWT cookie
   - Keep `SameSite=Lax` assertion at line 338 for refresh cookie (unchanged)
   - Update `assertCookieAttributes()` helper at line 383: change from checking `SameSite=Lax` to accepting a parameter, or split into two helpers for JWT vs refresh
   - Consider adding a NEW test: `loginReturns403ForUnverifiedEmail` that registers but does NOT verify, then asserts 403

4. **Fix GlobalExceptionHandlerTest** (`api/src/test/java/org/jphototagger/api/controller/GlobalExceptionHandlerTest.java:19`):
   - Change `assertThat(response.getBody().error()).isEqualTo("Photo not found")` to `assertThat(response.getBody().error()).isEqualTo("Not Found")`

5. **Fix OAuth2SuccessHandlerTest** (`api/src/test/java/org/jphototagger/api/security/OAuth2SuccessHandlerTest.java:245`):
   - Change `assertThat(header).contains("SameSite=Lax")` to `assertThat(header).contains("SameSite=Strict")` for JWT cookie
   - Keep `SameSite=Lax` assertion for refresh cookie (if tested separately — check if `assertCookieSecure` is called for both jwt and refresh, and if so, needs to differentiate)

6. **Investigate UserControllerTest** (`api/src/test/java/org/jphototagger/api/controller/UserControllerTest.java`):
   - This test uses path `/api/users/me` but controller maps to `/users`. No context-path configured.
   - It passed against original code in isolation — the failures may be from test context pollution when running the full suite
   - Run in isolation first: `./gradlew :api:test --tests '*UserControllerTest*'`
   - If it passes in isolation, it's a test ordering issue (not caused by our changes)
   - If it fails, check if `createUser()` at line 65 is missing required NOT NULL columns

7. **Run full test suite**:
   ```bash
   ./gradlew :api:test 2>&1 | tail -10
   ```
   Expected: `BUILD SUCCESSFUL`, 139 tests passed, 0 failed

8. **Watch for**: OAuth2 flow — SameSite=Strict on JWT might need end-to-end testing with actual Google OAuth. The unit tests mock the OAuth flow and may not catch real-world SameSite behavior.

## 6. Artifacts & References

- **Audit document**: `docs/plans/2026-03-12-saas-conversion-phase-4-security-audit-8.md`
- **Previous audit remediations**: commit `404e86212` — "fix: remediate security audit 4 & 5 findings (SA4/SA5)"
- **Stashed changes**: `git stash@{0}` — 15 files, 129 insertions, 51 deletions
- **No new files created** — all changes are edits to existing files
- **Key references**: SA4-F4 (SameSite=Strict requirement), OWASP A07 (auth failures), MinIO IAM policy docs
