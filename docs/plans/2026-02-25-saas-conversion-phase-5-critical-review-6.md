# Critical Implementation Review #6 — Phase 5: Sharing & Polish

**Plan reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-5.md` (v6.0)
**Design reference:** `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0)
**Previous reviews:** `critical-review-1.md`, `critical-review-2.md`, `critical-review-3.md`, `critical-review-4.md`, `critical-review-5.md`
**Reviewer:** Critical Implementation Review Skill v1.5.1
**Date:** 2026-03-12

---

## 1. Overall Assessment

The plan has reached strong maturity across six iterations. All 21 critical issues (C1–C21) and 30 minor issues (M1–M30) from Reviews #1–#5 have been addressed — the deploy pipeline, monitoring stack, CI environment, share feature design, and RLS grant scope are now well-specified. This sixth pass, cross-referencing the plan's MailPit-based E2E email verification (C21 fix) against the actual codebase, reveals **two new critical issues** that were introduced *by* the C21 fix itself: (1) there is no production `EmailService` implementation in the codebase — only a `StubEmailService` active in dev/test profiles that logs to stdout — so the API cannot send any SMTP traffic to MailPit, making the entire email verification flow non-functional, and (2) the E2E test's verification step uses the wrong HTTP method (`GET` vs the actual `POST /auth/verify`), the wrong parameter format (query string vs JSON body), and a regex that cannot match base64url verification tokens. There are also minor issues with SMTP port propagation and Alertmanager network isolation.

---

## 2. Critical Issues

### C22. No Production EmailService Implementation — MailPit Will Never Receive Emails

**Description:** The C21 fix added MailPit to the CI stack and an email verification step to the E2E test. This approach requires the API to actually send emails via SMTP to MailPit's port 1025. However, examining the actual codebase:

1. **No SMTP EmailService implementation exists.** The only `EmailService` implementation is `StubEmailService` (`api/src/main/java/org/jphototagger/api/service/StubEmailService.java`), which is annotated `@Profile({"dev", "test"})` and simply calls `log.info()` — it does not send any SMTP traffic.
2. **No `spring-boot-starter-mail` dependency** in `api/build.gradle.kts`. Without this, `JavaMailSender` is not auto-configured and SMTP sending is impossible.
3. **No `spring.mail.*` properties** in `api/src/main/resources/application.yml`. Even with the dependency, Spring Mail needs `spring.mail.host`, `spring.mail.port`, etc.
4. **In docker-compose (no profile set),** the `StubEmailService` is NOT active (it requires `dev` or `test` profile). `AuthService` constructor-injects `EmailService` — with no bean available, the application fails to start with `NoSuchBeanDefinitionException`.

**Why it matters:** The entire MailPit-based E2E email verification flow is non-functional at multiple levels. First, the application may not even start in docker-compose without a profile-less `EmailService`. Second, even if it starts (e.g., with a `@Primary` fallback), no SMTP traffic is sent to MailPit, so the E2E test has no email to read. The full user journey test blocks at step 1b.

**Fix:** The plan must include creating a production SMTP `EmailService` implementation (or specify that it was created in an earlier phase and needs updating). Specifically:

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
3. Create `SmtpEmailService.java` annotated with `@Profile("!dev & !test")` (or `@ConditionalOnProperty`) that injects `JavaMailSender` and sends real emails via SMTP.
4. Add `SMTP_PORT: ${SMTP_PORT}` to the API service environment in `docker-compose.yml` (see M31).

This should be added as a prerequisite step in Task 5.1 or as a new Task 5.0, since email verification is required for the E2E test and for production registration.

---

### C23. E2E Email Verification Step — Wrong HTTP Method, Wrong Parameter Format, Wrong Regex

**Description:** Task 5.6's E2E test verifies email with:
```typescript
const tokenMatch = emailData.Text.match(/\/auth\/verify\?token=([a-f0-9-]+)/);
await page.request.get(`/api/auth/verify?token=${tokenMatch[1]}`);
```

This has three independent bugs:

1. **Wrong HTTP method:** The actual endpoint is `@PostMapping("/verify")` (`AuthController.java:61`). A `GET` request will return HTTP 405 Method Not Allowed.
2. **Wrong parameter format:** The actual endpoint reads the token from a JSON body (`request.get("token")` from `@RequestBody Map<String, String>`, `AuthController.java:62-63`). A query parameter `?token=...` will not be read.
3. **Wrong regex:** Verification tokens are generated with `Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)` (`AuthService.java:64`) — 32 bytes producing a 43-character string using the charset `[A-Za-z0-9_-]`. The regex `[a-f0-9-]+` only matches lowercase hex and hyphens, missing uppercase letters, digits 7-9 (in non-hex positions), and underscores. It will fail to match most tokens.

**Why it matters:** Even if C22 is fixed and MailPit receives emails, the verification step will fail: the regex won't match the token, the HTTP method is wrong, and the parameter format is wrong. The E2E test is blocked at step 1b.

**Fix:** Correct all three issues:
```typescript
// Fix regex to match base64url tokens
const tokenMatch = emailData.Text.match(/\/auth\/verify\?token=([A-Za-z0-9_-]+)/);

// Fix HTTP method (POST) and parameter format (JSON body)
await page.request.post('/api/auth/verify', {
  data: { token: tokenMatch[1] }
});
```

Note: the regex pattern in the email body depends on how `SmtpEmailService` formats the verification link. If the email contains a link like `https://app.example.com/auth/verify?token=...`, the regex should match that format. Ensure the email template and the regex are consistent.

---

## 3. Previously Addressed Items

All issues from Reviews #1–#5 have been addressed in v6.0:

- **C1–C5** (Review #1): RLS bypass, share rate limiting, nginx path, CI workflow, deploy specification
- **C6–C10** (Review #2): Build/sign merge, build-on-VPS, rollback tags, Actuator prometheus, role separation rationale
- **C11–C15** (Review #3): SSH key handling, Dockerfile paths, rsync exclusions, healthcheck URL, share_reader password lifecycle
- **C16–C19** (Review #4): Flyway placeholder syntax, Redis exporter, `.env.ci` PostgreSQL hostname, `.env.ci` MinIO presign variable names
- **C20** (Review #5): `share_reader` GRANT expanded to include `albums`, `album_photos`, `photo_metadata`
- **C21** (Review #5): MailPit test SMTP service added to CI, email verification step added to E2E test (though this fix itself introduces C22 and C23)
- **M1–M30** (Reviews #1–#5): All resolved

---

## 4. Minor Issues & Improvements

### M31. `SMTP_PORT` Not Passed to API Container via Docker Compose

**Description:** The API service environment in `docker-compose.yml` (lines 39-41) passes `SMTP_HOST`, `SMTP_USER`, and `SMTP_PASS`, but NOT `SMTP_PORT`. The plan's `.env.ci` defines `SMTP_PORT=1025` for MailPit, but since `docker-compose.yml` doesn't reference `${SMTP_PORT}` in the API environment block, the variable is not propagated to the container.

**Why it matters:** Spring Mail defaults to port 25 (or 587 with STARTTLS). In CI, MailPit listens on port 1025. Without `SMTP_PORT` reaching the API container, Spring Mail connects to the wrong port and email sending fails silently or throws a connection refused error. In production, the SMTP port may also differ from the default.

**Fix:** Add `SMTP_PORT` to both `docker-compose.yml` and `.env.example`:
```yaml
# docker-compose.yml API environment
SMTP_PORT: ${SMTP_PORT:-587}
```
```
# .env.example
SMTP_PORT=587
```

### M32. Alertmanager Cannot Reach External SMTP — `backend` Network Is `internal: true`

**Description:** Task 5.4 places the `alertmanager` service on the `backend` network only. The `docker-compose.yml` (line 311) defines `backend` with `internal: true`, which prevents containers on that network from accessing external networks (internet). Alertmanager needs to reach an external SMTP server (e.g., `smtp.mailgun.org`) to deliver alert emails.

The API service can send emails because it's on both `frontend` (non-internal) and `backend` networks. Alertmanager, as specified, is only on `backend`.

**Why it matters:** All alert email delivery will fail with connection timeouts or DNS resolution errors. The monitoring stack will collect metrics and evaluate alert rules correctly, but no human will be notified when alerts fire.

**Fix:** Either:
1. Add `frontend` network to the alertmanager service (simplest)
2. Or route alerts through the API (adds coupling)
3. Or create a dedicated `monitoring` network without `internal: true`

### M33. `nginx.ci.conf` Should Disable `cookie-secure` for HTTP-Only CI

**Description:** The plan's `.env.ci` does not override `app.cookie-secure`, which defaults to `true` in `application.yml` (line 57). In CI, nginx uses HTTP only (no TLS per `nginx.ci.conf`). With `cookie-secure: true`, the API sets the `Secure` flag on JWT and refresh token cookies. Browsers (and Playwright) will not send `Secure` cookies over HTTP connections.

**Why it matters:** After login in the E2E test, the JWT cookie is set with `Secure` flag but the subsequent requests are over HTTP. The browser does not include the cookie, and all authenticated API calls return 401. The E2E test fails at step 3 (upload photo) or any post-login step.

**Fix:** Add `COOKIE_SECURE=false` to `.env.ci` and pass it through docker-compose to the API:
```yaml
# docker-compose.yml API environment
COOKIE_SECURE: ${COOKIE_SECURE:-true}
```
```yaml
# application.yml
app:
  cookie-secure: ${COOKIE_SECURE:true}
```
Or, if the existing `app.cookie-secure` already reads from an env var, just add the env var to docker-compose and `.env.ci`.

---

## 5. Questions for Clarification

1. **Production EmailService scope:** Was a production `SmtpEmailService` intended for a prior phase and missed, or is it expected to be created in Phase 5? The `AuthService` already depends on `EmailService`, and the only implementation is a dev/test stub.

2. **Email template format:** What format should the verification email body use? The E2E test's regex assumes the email contains a URL like `/auth/verify?token=<token>`. The `SmtpEmailService` implementation needs a consistent template. Should this be a plain-text link, an HTML email, or both?

3. **CI Spring profile:** Should the CI docker-compose stack run with a specific Spring profile (e.g., `ci`)? Currently no profile is set, which means neither `StubEmailService` (dev/test) nor a potential `SmtpEmailService` (production/default) would activate unless the profile annotations are adjusted.

---

## 6. Final Recommendation

**Approve with changes.**

The plan is very close to implementation-ready. The two critical issues (C22, C23) are concentrated in the email verification flow introduced by the C21 fix — the fix correctly identified the need for email verification in the E2E test but didn't account for the fact that no SMTP email infrastructure exists in the codebase. C22 requires creating a production `EmailService` implementation with Spring Mail, and C23 requires fixing three bugs in the E2E test's verification step (HTTP method, parameter format, regex). The minor issues (M31–M33) are straightforward configuration fixes. After addressing these, the plan is ready for implementation.
