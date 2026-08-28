# Security Fixes Group 1: Auth Hardening (Findings #1, #2, #3)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix dummy BCrypt hash timing oracle, eliminate email verification oracle, implement atomic lockout with bounded lock + post-expiry reset.

**Dependencies:** None — fully independent.

**Tech Stack:** Java 17, Spring Boot 3.4.2, PostgreSQL 16 (Flyway), JUnit 5 + Testcontainers.

**Design Spec:** `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md` (Sections 1-2)

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `api/src/main/java/org/jphototagger/api/service/AuthService.java` | Modify | Atomic lockout, dummy hash fix, oracle elimination |
| `api/src/main/java/org/jphototagger/api/controller/AuthController.java` | Modify | Remove `EmailVerificationRequiredException` catch |
| `api/src/test/java/org/jphototagger/api/service/AuthServiceTest.java` | Create | Auth hardening tests |

---

### Task 1: Fix dummy BCrypt hash timing oracle (Finding #1)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/AuthService.java:20-37`
- Test: `api/src/test/java/org/jphototagger/api/service/AuthServiceTest.java`

- [ ] **Step 1: Write the failing test for `getDummyHash()` producing valid BCrypt**

Create test file:

```java
package org.jphototagger.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceTest {

    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(12);
    }

    @Test
    void dummyHash_producesValidBcryptFromInjectedEncoder() {
        // getDummyHash() must produce a hash that passwordEncoder.matches() actually runs BCrypt against.
        // The old constant "$2a$12$dummy..." was 65 chars — BCrypt requires exactly 60 — so matches()
        // short-circuited (~1µs). This test ensures the dummy hash is valid.
        String dummyHash = passwordEncoder.encode("__dummy__credential__for__timing__equalization__");
        assertThat(dummyHash).hasSize(60);
        assertThat(passwordEncoder.matches("wrong_password", dummyHash)).isFalse();
        // The important thing: matches() runs the full BCrypt comparison, not a regex short-circuit.
        // We verify by confirming the correct dummy credential matches.
        assertThat(passwordEncoder.matches("__dummy__credential__for__timing__equalization__", dummyHash)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it passes (validates our understanding)**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.AuthServiceTest.dummyHash_producesValidBcryptFromInjectedEncoder" --no-daemon`
Expected: PASS

- [ ] **Step 3: Replace the hardcoded dummy hash with lazy AtomicReference in AuthService**

In `AuthService.java`, add field after line 28:

```java
private final java.util.concurrent.atomic.AtomicReference<String> dummyHash = new java.util.concurrent.atomic.AtomicReference<>();

private String getDummyHash() {
    return dummyHash.updateAndGet(h -> h != null ? h :
        passwordEncoder.encode("__dummy__credential__for__timing__equalization__"));
}
```

Then replace line 93:
```java
// OLD:
passwordEncoder.matches(password, "$2a$12$dummy.hash.to.prevent.timing.side.channel.attacks.00000000");
// NEW:
passwordEncoder.matches(password, getDummyHash());
```

- [ ] **Step 4: Run existing AuthController tests to verify no regression**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.AuthControllerTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/AuthService.java api/src/test/java/org/jphototagger/api/service/AuthServiceTest.java
git commit -m "fix(auth): replace invalid dummy BCrypt hash with lazy AtomicReference (Finding #1)"
```

---

### Task 2: Extract `incrementFailedAttempts()` + eliminate oracle + atomic lockout (Findings #2, #3)

> **Merged tasks (review fix):** Tasks 2 and 3 from v1 of this plan were interdependent — Task 2 called `incrementFailedAttempts()` which didn't exist until Task 3. Merged into a single task to avoid compilation failures.

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/AuthService.java:86-145`
- Modify: `api/src/main/java/org/jphototagger/api/controller/AuthController.java:9,90-96`
- Test: `api/src/test/java/org/jphototagger/api/controller/AuthControllerTest.java` (existing)

- [ ] **Step 1: Write failing test — unverified user gets 401 not 403**

Add to `AuthControllerTest.java`:

```java
@Test
void login_unverifiedEmail_returns401NotDistinguishable() throws Exception {
    // Register a user but don't verify email
    String email = "unverified-" + UUID.randomUUID() + "@test.com";
    mockMvc.perform(post("/auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new RegisterRequest(email, "Password123!"))))
            .andExpect(status().isAccepted());

    // Attempt login with correct password — should get 401, same as wrong password
    mockMvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LoginRequest(email, "Password123!"))))
            .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: Write lockout integration test**

Add to `AuthControllerTest.java`:

```java
@Test
void login_fiveWrongPasswords_locksAccount() throws Exception {
    String email = "lockout-" + UUID.randomUUID() + "@test.com";
    // Register and verify
    mockMvc.perform(post("/auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new RegisterRequest(email, "Password123!"))))
            .andExpect(status().isAccepted());
    // Verify email directly in DB
    authJdbc.update("UPDATE users SET email_verified = true WHERE email = ?", email);

    // 5 wrong password attempts
    for (int i = 0; i < 5; i++) {
        mockMvc.perform(post("/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, "WrongPassword!"))))
                .andExpect(status().isUnauthorized());
    }

    // 6th attempt with correct password should still fail (locked)
    mockMvc.perform(post("/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LoginRequest(email, "Password123!"))))
            .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 3: Run unverified test to confirm it fails (currently returns 403)**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.AuthControllerTest.login_unverifiedEmail_returns401NotDistinguishable" --no-daemon`
Expected: FAIL (403 instead of 401)

- [ ] **Step 4: Extract `incrementFailedAttempts()` helper with atomic SQL**

In `AuthService.java`, add private method:

```java
private void incrementFailedAttempts(UUID userId) {
    authJdbc.update(
        "UPDATE users " +
        "SET failed_login_attempts = CASE " +
        "        WHEN locked_until IS NOT NULL AND locked_until < NOW() " +
        "            THEN 1 " +
        "        ELSE failed_login_attempts + 1 " +
        "    END, " +
        "    locked_until = CASE " +
        "        WHEN locked_until IS NOT NULL AND locked_until < NOW() " +
        "            THEN NULL " +
        "        WHEN failed_login_attempts + 1 >= 5 " +
        "             AND (locked_until IS NULL) " +
        "            THEN NOW() + INTERVAL '15 minutes' " +
        "        ELSE locked_until " +
        "    END " +
        "WHERE id = ?",
        userId);
}
```

- [ ] **Step 5: Rewrite `authenticate()` to match spec's 7-step flow**

Replace the entire `authenticate()` method body (lines 86-145) with the spec's canonical flow. The final method must match this exact ordering:

```java
public Map<String, Object> authenticate(String email, String password) {
    // Step 1: Query user by email
    var rows = authJdbc.queryForList(
            "SELECT id, email, password_hash, failed_login_attempts, locked_until, email_verified FROM users WHERE email = ?",
            email);

    if (rows.isEmpty()) {
        // Unknown email — use dummy hash for timing equalization
        passwordEncoder.matches(password, getDummyHash());
        throw new BadCredentialsException("Invalid credentials");
    }

    Map<String, Object> user = rows.get(0);
    UUID userId = (UUID) user.get("id");
    String storedHash = (String) user.get("password_hash");
    int failedAttempts = (int) user.get("failed_login_attempts");
    Instant lockedUntil = user.get("locked_until") != null
            ? ((java.sql.Timestamp) user.get("locked_until")).toInstant()
            : null;

    // Step 2: Always check password (timing preservation)
    boolean passwordCorrect = passwordEncoder.matches(password, storedHash);

    // Step 3: Evaluate isLocked from initial SELECT (no re-fetch)
    boolean isLocked = failedAttempts >= MAX_FAILED_ATTEMPTS
            && lockedUntil != null
            && lockedUntil.isAfter(Instant.now());

    // Step 4: Wrong password
    if (!passwordCorrect) {
        incrementFailedAttempts(userId);
        throw new BadCredentialsException("Invalid credentials");
    }

    // Step 5: Correct password but locked — increment for timing equalization
    if (isLocked) {
        incrementFailedAttempts(userId);
        throw new BadCredentialsException("Invalid credentials");
    }

    // Step 6: Correct password, not locked, but unverified email — oracle eliminated
    Boolean emailVerified = (Boolean) user.get("email_verified");
    if (emailVerified == null || !emailVerified) {
        incrementFailedAttempts(userId);
        throw new BadCredentialsException("Invalid credentials");
    }

    // Step 7: Success — reset counter
    authJdbc.update(
            "UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?",
            userId);

    return Map.of("userId", userId, "email", (String) user.get("email"));
}
```

- [ ] **Step 6: Fix AuthController — remove EmailVerificationRequiredException catch**

In `AuthController.java`, remove lines 90-92:
```java
// REMOVE:
} catch (EmailVerificationRequiredException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse("Please verify your email before logging in", 403));
```

Also remove the import on line 9:
```java
// REMOVE:
import org.jphototagger.api.exception.EmailVerificationRequiredException;
```

- [ ] **Step 7: Fix existing tests that expect 403 for unverified email**

Search `AuthControllerTest.java` for any assertions using `status().isForbidden()` or `HttpStatus.FORBIDDEN` or `403` in the context of unverified-email login scenarios. Update them to `status().isUnauthorized()` / `401`. Known candidates:
- Any test named `*unverified*` or `*emailVerification*` that asserts 403

- [ ] **Step 8: Run full AuthController test suite**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.AuthControllerTest" --no-daemon`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/AuthService.java api/src/main/java/org/jphototagger/api/controller/AuthController.java api/src/test/java/org/jphototagger/api/controller/AuthControllerTest.java
git commit -m "fix(auth): atomic lockout, oracle elimination, bounded lockout with post-expiry reset (Findings #2, #3)"
```
