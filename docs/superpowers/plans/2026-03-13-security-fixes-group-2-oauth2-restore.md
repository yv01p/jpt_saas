# Security Fixes Group 2: OAuth2 Guard + Restore Reorder (Findings #7, #8)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `email_verified` claim guard to OAuth2 login flow; fix restore() race condition by acquiring user lock before photo read.

**Dependencies:** None — fully independent.

**Tech Stack:** Java 17, Spring Boot 3.4.2, PostgreSQL 16 (Flyway), JUnit 5 + Testcontainers.

**Design Spec:** `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md` (Sections 3-4)

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java` | Modify | Add `email_verified` guard |
| `api/src/main/java/org/jphototagger/api/service/PhotoService.java` | Modify | Restore reorder |

---

### Task 1: OAuth2 `email_verified` claim guard (Finding #8)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java:70-74`
- Test: `api/src/test/java/org/jphototagger/api/security/OAuth2SuccessHandlerTest.java` (existing)

- [ ] **Step 1: Write failing test for unverified email rejection**

Add to `OAuth2SuccessHandlerTest.java`:

```java
@Test
void onAuthenticationSuccess_emailNotVerified_redirectsWithError() throws Exception {
    // Mock OidcUser with emailVerified = false
    OidcUser oidcUser = mock(OidcUser.class);
    when(oidcUser.getEmail()).thenReturn("unverified@example.com");
    when(oidcUser.getSubject()).thenReturn("sub123");
    when(oidcUser.getEmailVerified()).thenReturn(false);

    OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
    when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
    when(token.getPrincipal()).thenReturn(oidcUser);

    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, token);

    verify(response).sendRedirect(contains("error=email_not_verified"));
}

@Test
void onAuthenticationSuccess_emailVerifiedNull_redirectsWithError() throws Exception {
    OidcUser oidcUser = mock(OidcUser.class);
    when(oidcUser.getEmail()).thenReturn("null-verified@example.com");
    when(oidcUser.getSubject()).thenReturn("sub456");
    when(oidcUser.getEmailVerified()).thenReturn(null);

    OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
    when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
    when(token.getPrincipal()).thenReturn(oidcUser);

    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, token);

    verify(response).sendRedirect(contains("error=email_not_verified"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.security.OAuth2SuccessHandlerTest.onAuthenticationSuccess_emailNotVerified_redirectsWithError" --no-daemon`
Expected: FAIL

- [ ] **Step 3: Add email_verified guard to OAuth2SuccessHandler**

In `OAuth2SuccessHandler.java`, add after line 73 (after the null/blank email check):

```java
Boolean emailVerified = oidcUser.getEmailVerified();
if (!Boolean.TRUE.equals(emailVerified)) {
    response.sendRedirect(redirectUri + "login?error=email_not_verified");
    return;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.security.OAuth2SuccessHandlerTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/security/OAuth2SuccessHandler.java api/src/test/java/org/jphototagger/api/security/OAuth2SuccessHandlerTest.java
git commit -m "fix(oauth2): reject unverified email_verified claims (Finding #8)"
```

---

### Task 2: Fix restore() race condition (Finding #7)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/PhotoService.java:297-319`

- [ ] **Step 1: Reorder restore() to acquire user lock FIRST**

Replace the `restore()` method at lines 297-319 in `PhotoService.java`:

```java
@Transactional
public void restore(UUID userId, UUID photoId) {
    // Lock user row FIRST — mirrors softDelete() pattern.
    // Second concurrent request blocks here until first commits.
    User user = entityManager.createQuery(
            "SELECT u FROM User u WHERE u.id = :userId", User.class)
            .setParameter("userId", userId)
            .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
            .getSingleResult();

    // Re-read photo inside the lock — second concurrent request sees
    // deletedAt == null (already restored by first) and throws here.
    Photo photo = photoRepository.findById(photoId)
            .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() != null)
            .orElseThrow(() -> new EntityNotFoundException("Photo not found in trash"));

    long newUsed = user.getUsedBytes() + photo.getSizeBytes();
    if (newUsed > user.getQuotaBytes()) {
        throw new IllegalStateException("Restoring this photo would exceed your storage quota");
    }

    photo.setDeletedAt(null);
    photoRepository.save(photo);

    user.setUsedBytes(newUsed);
    userRepository.save(user);
}
```

- [ ] **Step 2: Run existing photo tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.controller.PhotoControllerTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/PhotoService.java
git commit -m "fix(photo): acquire user lock before photo read in restore() (Finding #7)"
```
