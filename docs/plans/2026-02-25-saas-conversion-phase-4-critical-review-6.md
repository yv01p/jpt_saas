# Phase 4 Critical Implementation Review — v6

**Plan reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-4.md` (v6.0 — 2026-03-06)
**Previous reviews:** `...-critical-review-1.md` through `...-critical-review-5.md`
**New code reviewed:** `OrphanReconciliationScheduler.java`, `PhotoDeleteJobEnqueuer.java`, `UnverifiedAccountPurgeScheduler.java`, `SchedulerTest.java`, `worker/Dockerfile`
**Date:** 2026-03-10
**Reviewer:** Senior Staff Engineer (critical-implementation-review skill)

---

## Scope Note

All issues from reviews v1–v5 (CI-1 through CI-22, MI-1 through MI-36) are correctly resolved in v6.0 per the changelog. This review identifies new issues in the v6.0 plan text and in the newly-written backend scheduler code (which was produced during Phase 4 work). The scheduler code is Phase 3 implementation that shipped alongside Phase 4 plan revisions — it is reviewed here because it is in the unstaged working tree.

---

## 1. Overall Assessment

v6.0 is the most complete and rigorous version of this plan. All prior blocking issues — cross-phase forward dependencies, missing ProtectedRoute, CSRF bootstrap, session hydration, MSW version mismatch, TQ/Zustand pins, empty TDD stubs across Tasks 4.5/4.7a/4.7b/4.7c/4.7d/4.8, `deleted_at` snake_case mismatch, UUID vs number IDs, 204 No Content crash, header spread CSRF override, and two-step login flow — are correctly resolved.

**Remaining concerns:**

1. **Task 4.4 has 4 comment-only TDD stubs that were never addressed** — viewport virtualization, staleTime configuration, infinite scroll trigger, and pagination termination tests all have empty/comment-only bodies. Task 4.4 was the only task not explicitly named in the CI-12/CI-13/CI-14 fix passes.
2. **Task 4.4 `PhotoCard` test passes a wire-format mock where the component expects the post-transform camelCase type** — `photo.thumbnailUrl` is `undefined` at runtime; the `src` assertion fails.
3. **Task 4.3 test calls `vi.mocked(useAuth)` without a corresponding `vi.mock()` declaration** — the test throws `TypeError` at runtime.
4. **`PhotoDeleteJobEnqueuer.enqueue` uses `executePipelined(RedisCallback)` with `redisTemplate.opsForXxx()` calls inside** — the pipeline contract is ambiguous; `SessionCallback` is the explicit, documented-correct form.
5. **`OrphanReconciliationScheduler.reconcileUser` calls `photoRepository.findAllById(candidateIds)` with an unbounded list** — for users with thousands of photos, the generated SQL `IN (?, ?, ...)` clause approaches PostgreSQL's parameter limit.

---

## 2. Critical Issues

### CI-23: Task 4.4 — 4 TDD Stubs Still Comment-Only

**Description:** Four tests in Task 4.4 have comment-only bodies with no assertions:

```typescript
test('PhotoGrid does not render items outside the viewport', () => {
  // Render with 100 photos, assert fewer than 100 img elements are in the DOM
});

test('photo list query has staleTime of 10 minutes', () => {
  // Assert queryClient cache config for ['photos'] key
});

test('fetches next page when user scrolls to end of loaded photos', async () => {
  // Simulate virtualizer reaching end of loaded data, assert fetchNextPage called
});

test('does not fetch next page when all photos are loaded', async () => {
  // Set up data where page * size + photos.length >= total, assert fetchNextPage not called
});
```

All four are `test('description', () => {})` or `test('description', async () => {})` with no `expect()` call — they pass trivially.

Prior fix passes covered Tasks 4.5 (CI-13), 4.2/4.3/4.7c (CI-14), 4.7a/4.7b/4.7d/4.8 (CI-12). Task 4.4 was not explicitly named in any of those passes and remains with hollow stubs.

**Why it matters:** The `useInfiniteQuery` + TanStack Virtual integration is the most architecturally complex component in Phase 4. These four tests are exactly the ones that verify its correctness — virtualization (the `count` vs rendered row guarantee), cache TTL configuration (the `staleTime: 10min` pre-signed URL refresh strategy), and the `fetchNextPage` trigger logic. All four are currently unverifiable by the test suite.

**Fix:** Implement each stub with MSW handlers and `expect()` assertions.

*Viewport virtualization:*
```typescript
test('PhotoGrid does not render items outside the viewport', () => {
  const photos = Array.from({ length: 100 }, (_, i) =>
    camelizeKeys(mockPhoto({ id: `550e8400-e29b-41d4-a716-${String(i).padStart(12, '0')}` })) as Photo
  );
  render(<PhotoGrid photos={photos} onLoadMore={() => {}} hasMore={false} />);
  const imgs = screen.getAllByRole('img');
  expect(imgs.length).toBeLessThan(100); // only visible rows rendered
});
```

*staleTime:*
```typescript
test('photo list query has staleTime of 10 minutes', async () => {
  server.use(
    http.get('/api/photos', () => HttpResponse.json({ photos: [], total: 0, page: 0 }))
  );
  render(<LibraryPage />, { wrapper: QueryClientWrapper });
  await waitFor(() => {
    const state = queryClient.getQueryState(['photos']);
    expect(state?.dataUpdatedAt).toBeDefined();
  });
  const cache = queryClient.getQueryCache().find({ queryKey: ['photos'] });
  expect(cache?.options.staleTime).toBe(10 * 60 * 1000);
});
```

*Pagination trigger and termination:*
```typescript
test('fetches next page when user scrolls to end of loaded photos', async () => {
  let pageCount = 0;
  server.use(http.get('/api/photos', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 0);
    pageCount = Math.max(pageCount, page + 1);
    return HttpResponse.json({ photos: [camelizeKeys(mockPhoto()) as Photo], total: 200, page });
  }));
  render(<LibraryPage />, { wrapper: QueryClientWrapper });
  await screen.findByRole('img');
  // Simulate scroll trigger — advance virtualizer range to near end of loaded data
  fireEvent.scroll(screen.getByTestId('photo-grid-scroll-container'), { target: { scrollTop: 9999 } });
  await waitFor(() => expect(pageCount).toBeGreaterThan(1));
});

test('does not fetch next page when all photos are loaded', async () => {
  let fetchCount = 0;
  server.use(http.get('/api/photos', () => {
    fetchCount++;
    return HttpResponse.json({
      photos: Array.from({ length: 3 }, (_, i) =>
        camelizeKeys(mockPhoto({ id: `550e8400-e29b-41d4-a716-${String(i).padStart(12, '0')}` })) as Photo
      ),
      total: 3, page: 0,
    });
  }));
  render(<LibraryPage />, { wrapper: QueryClientWrapper });
  await screen.findAllByRole('img');
  const countAfterLoad = fetchCount;
  fireEvent.scroll(screen.getByTestId('photo-grid-scroll-container'), { target: { scrollTop: 9999 } });
  await new Promise(r => setTimeout(r, 100)); // allow any pending fetch
  expect(fetchCount).toBe(countAfterLoad); // no second page fetch — all 3 photos loaded
});
```

---

### CI-24: Task 4.4 `PhotoCard` Test — Wire-Format Mock Passed to camelCase Component

**Description:** The `PhotoCard` thumbnail test passes a wire-format object (from `mockPhoto()`) directly as a component prop:

```typescript
test('thumbnail_url from API response is used directly as img src', () => {
  const photo = mockPhoto({ thumbnail_url: 'https://minio/thumb/1.jpg' });
  render(<PhotoCard photo={photo} />);
  expect(screen.getByRole('img')).toHaveAttribute('src', 'https://minio/thumb/1.jpg');
});
```

`mockPhoto()` returns the API wire format with `thumbnail_url` (snake_case). The `PhotoCard` component accepts `photo: Photo` — the TypeScript `Photo` interface uses `thumbnailUrl` (camelCase, post-transform). At runtime, `photo.thumbnailUrl` is `undefined`; the `<img src={undefined}>` renders with no `src`. The assertion fails.

Every other test that renders components directly uses `mockUser` (post-transform) or manually constructs a `Photo`-shaped object. Only this one test silently mixes wire format with the component interface. The TypeScript compiler doesn't catch it because `mockPhoto()` returns `Record<string, unknown>`.

**Why it matters:** The test is supposed to verify the URL pass-through from API → component. Instead it silently tests nothing: the component renders `src={undefined}`, not the expected URL. This is a false-green during development and a broken assertion once the component is built.

**Fix:** Apply `camelizeKeys` before passing to the component, mirroring the actual `apiFetch` transform path:

```typescript
import { camelizeKeys } from '../api/client';

test('thumbnail_url from API response is used directly as img src', () => {
  const photo = camelizeKeys(
    mockPhoto({ thumbnail_url: 'https://minio/thumb/1.jpg' })
  ) as Photo;
  render(<PhotoCard photo={photo} />);
  expect(screen.getByRole('img')).toHaveAttribute('src', 'https://minio/thumb/1.jpg');
});
```

Also add a `mockPhotoApp()` factory to `frontend/src/test/factories.ts` that returns the post-transform camelCase `Photo` for component tests:

```typescript
// frontend/src/test/factories.ts
export function mockPhotoApp(overrides: Partial<Photo> = {}): Photo {
  return camelizeKeys(mockPhoto()) as Photo & typeof overrides extends Partial<Photo>
    ? Photo
    : never;
}
```

This prevents the same mistake in future component tests.

---

### CI-25: Task 4.3 — `vi.mocked(useAuth)` Used Without `vi.mock()` — Test Throws at Runtime

**Description:** Task 4.3's second test calls `vi.mocked(useAuth).mockReturnValue(...)`:

```typescript
test('login form submits with correct credentials', async () => {
  const loginMock = vi.fn();
  vi.mocked(useAuth).mockReturnValue({ login: loginMock, logout: vi.fn() });
  render(<LoginPage />, { wrapper: MemoryRouter });
  // ...
  expect(loginMock).toHaveBeenCalledWith({ email: 'a@b.com', password: 'password123' });
});
```

`vi.mocked()` wraps a value with mock type information, but does NOT replace the real function. Without a `vi.mock('../api/hooks/useAuth')` (or `vi.mock('@/api/hooks/useAuth')`) declaration before the test suite, `useAuth` is the real hook. `vi.mocked(useAuth).mockReturnValue(...)` calls `.mockReturnValue` on a non-mock function, throwing:

```
TypeError: vi.mocked(useAuth).mockReturnValue is not a function
```

The test crashes before any assertion runs. This issue was inadvertently introduced when CI-14 added a test body to what was previously a hollow stub — the test body chosen uses module mocking, but the required module mock declaration was not added.

**Why it matters:** The test is meant to verify that `LoginPage` calls `useAuth().login()` with the correct credentials — a core login flow contract. Instead it crashes and provides no coverage. The phase completion gate (`npm run test`) fails.

**Fix:** Add a `vi.mock` declaration at the top of `LoginPage.test.tsx` and handle the typing explicitly:

```typescript
// frontend/src/pages/LoginPage.test.tsx

import { vi } from 'vitest';

// Hoist vi.mock — must be outside describe/test
vi.mock('../api/hooks/useAuth');
import { useAuth } from '../api/hooks/useAuth';

// ...

test('login form submits with correct credentials', async () => {
  const loginMock = vi.fn();
  vi.mocked(useAuth).mockReturnValue({ login: loginMock, logout: vi.fn() });
  render(<LoginPage />, { wrapper: MemoryRouter });
  await userEvent.type(screen.getByLabelText(/email/i), 'a@b.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'password123');
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  expect(loginMock).toHaveBeenCalledWith({ email: 'a@b.com', password: 'password123' });
});
```

Note: Because the first test (`'LoginPage renders email/password fields'`) also renders `LoginPage`, the module mock applies to all tests in the file. Ensure that tests requiring the REAL login behavior (i.e., the `'redirects to location.state.from'` test which uses actual MSW handlers) either reset the mock or are moved to a separate test file that does NOT apply `vi.mock('../api/hooks/useAuth')`.

---

## 3. Previously Addressed Items

All 22 critical issues (CI-1–CI-22) and 36 minor issues (MI-1–MI-36) from reviews v1–v5 are correctly resolved in v6.0. Highlights of the most significant resolutions:

- **CI-18 (Blocking):** All `id` fields changed from `number` to `string` (UUID). Test mocks updated to `MOCK_UUID` format. ✓
- **CI-19 (Blocking):** `apiFetch` guards 204 No Content with `res.status === 204` check before `res.json()`. ✓
- **CI-20 (Blocking):** Header spread order reversed — `{ ...options, credentials: 'include', headers: { csrf, ...options.headers } }`. ✓
- **CI-21 (Blocking):** Two-step login flow correctly implemented: `POST /api/auth/login` → `GET /api/users/me`. ✓
- **CI-22 (High):** Task 4.5 mocks use `processing_status` (wire format) not `status`. ✓
- **MI-34 (Medium):** `factories.ts` defines `mockPhoto()`, `mockUser`, `mockUserWire`, `mockMetadata()`, `mockMetadataWithGps()`. Wire-format vs post-transform variants are documented. ✓
- **MI-35 (Medium):** `snakeifyKeys` added to `apiFetch` for outgoing JSON bodies. GPS toggle test asserts `{ show_gps: true }`. ✓

---

## 4. Minor Issues & Improvements

### MI-37: `PhotoDeleteJobEnqueuer.enqueue` — `executePipelined(RedisCallback)` + `redisTemplate.opsForXxx()` Is Ambiguous

**Description:** The `enqueue` method uses `executePipelined(RedisCallback<Object>)` but invokes `redisTemplate.opsForStream().add(...)` inside the callback rather than using the `connection` parameter directly:

```java
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (Photo photo : photos) {
        // ...
        redisTemplate.opsForStream().add("delete-jobs", msg);   // ← uses template, not connection
    }
    return null;
});
```

Spring Data Redis documentation states that `executePipelined(RedisCallback)` requires commands to be issued through the `connection` argument to guarantee pipelining. When `redisTemplate.opsForXxx()` is used inside the callback, the commands MAY be pipelined (if Spring's thread-local connection binding routes through the pipeline) or MAY NOT (if the template opens a fresh connection). The behavior is implementation-version-dependent and not guaranteed by the API contract.

The `SchedulerTest.trashPurge_enqueuesMinioDeleteJob` test verifies that messages appear in the stream — it does not verify that all messages were sent in a single round-trip. The test passes regardless of whether pipelining works.

**Why it matters:** The class-level Javadoc states "sending all XADD commands in a single round-trip." If the pipeline is not actually being used, each `add()` is a separate network round-trip. For a user with 100 trash-purged photos, that's 100 sequential Redis commands instead of 1 batched pipeline flush. The correctness guarantee is maintained, but the stated performance contract is not.

**Fix:** Use `executePipelined(SessionCallback<Object>)` — this form explicitly routes all `opsForXxx()` calls through the pipeline connection:

```java
public void enqueue(List<Photo> photos) {
    redisTemplate.executePipelined(new SessionCallback<Object>() {
        @SuppressWarnings("unchecked")
        @Override
        public Object execute(RedisOperations operations) {
            for (Photo photo : photos) {
                if (photo.getStorageKey() == null) {
                    log.warn("Skipping delete-job for photo {} — null storage_key", photo.getId());
                    continue;
                }
                UUID photoId = photo.getId();
                UUID userId  = photo.getUserId();
                Map<String, String> msg = Map.of(
                        "photo_id",     photoId.toString(),
                        "original_key", photo.getStorageKey(),
                        "thumbnail_sm", userId + "/thumbnails/" + photoId + "_sm.jpg",
                        "thumbnail_md", userId + "/thumbnails/" + photoId + "_md.jpg"
                );
                operations.opsForStream().add("delete-jobs", msg);
            }
            return null;
        }
    });
}
```

`SessionCallback` is the documented form for pipelining `opsForXxx()` operations in Spring Data Redis. All operations in the callback are guaranteed to go through the pipeline.

---

### MI-38: `OrphanReconciliationScheduler.reconcileUser` — Unbounded `findAllById` IN-Clause

**Description:** After collecting all MinIO candidate objects for a user prefix, the scheduler issues:

```java
Set<UUID> existingIds = photoRepository.findAllById(candidateIds).stream()
        .map(Photo::getId)
        .collect(Collectors.toCollection(HashSet::new));
```

`findAllById(candidateIds)` generates SQL `SELECT ... WHERE id IN (?, ?, ?, ...)` with `candidateIds.size()` bound parameters. PostgreSQL's maximum parameter count per query is 65,535. A user with more than 65K originals in MinIO would cause this query to fail with:

```
org.postgresql.util.PSQLException: ERROR: too many parameters in prepared statement
```

For a personal photo SaaS with a reasonable quota (10 GB), a typical user cannot have 65K+ raw originals. However, the `OrphanReconciliationScheduler` is intended to run on all users in the system — a power user with many small images or a misconfiguration could theoretically accumulate that volume. More practically, a user who uploaded many files before a schema change or mid-migration could leave a large orphan set.

**Why it matters:** The scheduler would silently fail for that user with an uncaught exception from Spring Data JPA (the `try/catch` inside the for-loop on MinIO `Result<Item>` objects catches MinIO errors, but the `photoRepository.findAllById` call is outside that try block — it's in `reconcileUser()` at the caller level, inside the `try (Stream<UUID> userIds = ...)` block but not caught individually). An uncaught JPA exception would bubble up to `reconcileOrphans()`, terminate the stream, and abort the entire reconciliation run — meaning subsequent users are skipped entirely.

**Fix:** Partition `candidateIds` into chunks of a safe size (e.g., 1,000) before querying:

```java
private static final int ID_BATCH_SIZE = 1_000;

private Set<UUID> findExistingIds(List<UUID> candidateIds) {
    Set<UUID> existingIds = new HashSet<>();
    for (int i = 0; i < candidateIds.size(); i += ID_BATCH_SIZE) {
        List<UUID> batch = candidateIds.subList(i, Math.min(i + ID_BATCH_SIZE, candidateIds.size()));
        photoRepository.findAllById(batch).forEach(p -> existingIds.add(p.getId()));
    }
    return existingIds;
}
```

Replace the current single-query call with `findExistingIds(candidateIds)`. This keeps the N+1 concern at bay (at most `ceil(N/1000)` queries, not N queries) while bounding parameter count safely.

---

### MI-39: `OrphanReconciliationScheduler` — No Test for `Result.get()` Exception Path

**Description:** The scheduler catches exceptions from `result.get()` and logs them:

```java
} catch (Exception e) {
    log.error("OrphanReconciliationScheduler: error processing MinIO object", e);
}
```

This silently skips objects that throw during enumeration. The three existing tests cover happy paths (orphan detected, existing photo skipped, recent object skipped) but none tests the error path. If `result.get()` throws for a MinIO connectivity issue, the scheduler should continue rather than abort — and the test validates that contract.

**Fix:** Add a test:
```java
@Test
@SuppressWarnings("unchecked")
void orphanReconciliation_continuesAfterItemEnumerationError() throws Exception {
    User user = createVerifiedUser("orphan-error@example.com");
    UUID goodPhotoId = UUID.randomUUID();
    String goodKey = user.getId() + "/originals/" + goodPhotoId + ".jpg";

    Result<Item> badResult = mock(Result.class);
    when(badResult.get()).thenThrow(new RuntimeException("MinIO transient error"));

    Item goodItem = mock(Item.class);
    when(goodItem.objectName()).thenReturn(goodKey);
    when(goodItem.isDir()).thenReturn(false);
    when(goodItem.lastModified()).thenReturn(ZonedDateTime.now().minusHours(3));
    Result<Item> goodResult = mock(Result.class);
    when(goodResult.get()).thenReturn(goodItem);

    when(minioInternalClient.listObjects(any(ListObjectsArgs.class)))
            .thenReturn(List.of(badResult, goodResult));

    orphanReconciliationScheduler.reconcileOrphans();

    // Good orphan must still be enqueued despite the bad result
    boolean found = readDeleteJobs().stream().anyMatch(msg ->
            goodPhotoId.toString().equals(msg.getValue().get("photo_id")));
    assertThat(found).as("good orphan must be processed despite earlier error").isTrue();
}
```

---

### MI-40: `@Transactional(readOnly = true)` on `reconcileOrphans` Holds DB Connection for Full Run Duration

**Description:** `reconcileOrphans()` is annotated `@Transactional(readOnly = true)` and streams user IDs via `userRepository.streamAllIds()`. The Spring Data JPA Hibernate-backed stream requires an open session (and thus a DB connection) for the full duration of iteration. For a deployment with 10,000 users where each user has a slow MinIO listing call (network I/O), the connection can be held for the entire `lockAtMostFor = "PT2H"` window.

This is not a correctness issue for small-to-medium deployments, but at scale it can exhaust the connection pool and starve other requests. The Javadoc acknowledges the OOM concern (streaming UUIDs) but does not mention the connection pool concern.

**Fix (documentation):** Add a note to the class Javadoc:
> Note: The `@Transactional(readOnly = true)` annotation holds a single DB connection for the duration of the full reconciliation run (up to 2 hours). On deployments with connection pool sizes below 10, consider configuring a dedicated scheduler connection pool or restructuring to process users in short-lived transactions.

This is an architectural note, not a code change — on the current single-VPS deployment the connection pool is unlikely to be a bottleneck.

---

### MI-41: `worker/Dockerfile` — `-XX:+UseG1GC` Is Redundant in Java 21

**Description:**

```dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
```

G1GC is the default garbage collector in Java 9+. In Java 21 (the target runtime), `+UseG1GC` is a no-op — it was already selected. The flag is harmless but misleading: it implies a non-default GC was chosen, which future operators may unnecessarily compare against ZGC or Shenandoah when tuning.

**Fix:** Remove the redundant flag:

```dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
```

If GC selection is intentional (e.g., to prevent a future JDK default change from silently switching GC), add a comment:
```dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"   # Explicit G1GC — prevent JDK default changes
```

---

## 5. Questions for Clarification

**Q19:** The `LoginPage` test suite uses both the MSW approach (test 3 — `redirects to location.state.from`) and the `vi.mocked(useAuth)` approach (test 2 — `login form submits with correct credentials`). If `vi.mock('../api/hooks/useAuth')` is applied to the entire file, test 3 must either reset the mock for the real login flow OR use `vi.mocked(useAuth).mockImplementation(...)` to replicate the real behavior. Which approach is preferred for Task 4.3: pure MSW (consistent with all other tasks) or a mix of module mocking and MSW?

**Q20:** `PhotoDeleteJobEnqueuer.enqueueOrphan` (called once per orphaned object in a per-user loop) is non-pipelined — each call is a separate XADD. If orphan reconciliation encounters a user with 500 orphaned objects (e.g., after a bulk import failure), this generates 500 individual Redis commands. Should `OrphanReconciliationScheduler.reconcileUser` accumulate orphan entries and call `enqueue(List<Photo>)` in batch, or is 500 separate XADDs acceptable for a weekly maintenance job?

**Q21:** Does `photoRepository.streamAllIds()` exist in the current Phase 3 `PhotoRepository` or is it a new method that needs to be added to the Phase 3 codebase (similar to how `findAllByUserIdWithStorageKey` is referenced in `UnverifiedAccountPurgeScheduler`)? The scheduler code appears to call a non-standard repository method.

---

## 6. Updated Dependency Map (v6.0)

All v5.0 fixes confirmed. New findings annotated `[NEW]`.

```
4.0 (backend prerequisites — new in v6.0)
  ✓ show_gps migration + User.java field
  ✓ UserController GET/PATCH /api/users/me
  ✓ UserResponse / UpdateUserRequest DTOs with @JsonProperty
  ✓ thumbnail_url / original_url in PhotoResponse (15min / 1hr TTL)
  ✓ Convention: snakeifyKeys on request bodies, @JsonProperty on both DTOs
  └── 4.1 (scaffold)
        ✓ Vite 6.3.5 pinned
        ✓ MSW v2 @ 2.7.3 pinned
        ✓ shadcn-ui @ 0.9.4 pinned
        ✓ ESLint react/no-danger
        ✓ Vite proxy configured
        ✓ REPLACE_ME sentinels for all 16 unpinned packages (MI-31 fully fixed)
        └── 4.2 (API client + types + auth store + ProtectedRoute)
              ✓ bootstrapCsrf() / async init() / hydrateSession()
              ✓ apiFetch: 204 guard, header spread fix, snakeifyKeys, camelizeKeys
              ✓ Two-step login flow documented in useAuth
              ✓ isHydrating: true in create()
              ✓ QueryCache + MutationCache 401 handlers with full test bodies
              ✓ factories.ts with mockPhoto (wire format) + mockUser (camelCase)
              ✓ All id fields: string (UUID)
              │
              ├── 4.3 (LoginPage, RegisterPage)   [can parallel 4.6, 4.7a–d, 4.8]
              │       ✓ Two MSW endpoints mocked in redirect test
              │       ✓ ?verified=true banner
              │       [CI-25: vi.mocked(useAuth) without vi.mock() — test crashes]
              │       [Q19: mixing module mock + MSW in same file needs resolution]
              │
              ├── 4.4 (LibraryPage + PhotoGrid + PhotoCard)   [can parallel 4.3, 4.6, 4.7a–d, 4.8]
              │       ✓ useInfiniteQuery (TQ v5, initialPageParam: 0)
              │       ✓ PAGE_SIZE from api/constants.ts
              │       ✓ rowVirtualizer.range ?? fallback
              │       ✓ refetchInterval recovery polling with full test body
              │       ✓ afterEach vi.useRealTimers()
              │       [CI-23: 4 comment-only stubs — viewport, staleTime, fetchNext, stopFetch]
              │       [CI-24: PhotoCard test uses wire-format mock — photo.thumbnailUrl is undefined]
              │
              │       └── 4.5 (UploadDropzone + useUpload)   [SEQUENTIAL — must follow 4.4]
              │               ✓ All 8 stubs filled with MSW handlers + expect() (CI-13 fixed)
              │               ✓ Wire format mocks: processing_status (CI-22 fixed)
              │               ✓ afterEach vi.useRealTimers()
              │
              ├── 4.6 (PhotoPage + MetadataPanel)   [can parallel 4.3, 4.7a–d, 4.8]
              │       ✓ keyword-photo assignment endpoints documented (moved from 4.7a)
              │       ✓ originalUrl staleTime 55min / gcTime 60min
              │       ✓ camelizeKeys(mockPhoto()) used in component tests
              │
              ├── 4.7a (KeywordsPage)   [standalone — parallel after 4.2]
              │       ✓ Full test bodies (CI-12 fixed)
              │
              ├── 4.7b (AlbumsPage)   [standalone — parallel after 4.2]
              │       ✓ Full test bodies (CI-12 fixed)
              │
              ├── 4.7c (SearchPage)   [standalone — parallel after 4.2]
              │       ✓ Full test bodies including saved search isolation (CI-14 fixed)
              │       ✓ useInfiniteQuery with [query, filters] in queryKey
              │
              ├── 4.7d (TrashPage)   [standalone — parallel after 4.2]
              │       ✓ Snake_case wire format documented + MSW handlers correct
              │       ✓ Full test bodies
              │
              └── 4.8 (SettingsPage)   [standalone — parallel after 4.2]
                      ✓ Fetches quota from GET /api/users/me (not /api/quota)
                      ✓ All 5 tests filled with MSW handlers (MI-32 fixed)
                      ✓ snakeifyKeys on PATCH body tested: { show_gps: true }
                      └── 4.9 (Router — integration gate)
                              ✓ <Navigate to="/library" replace />
                              ✓ catch-all * → NotFoundPage
                              ✓ /share/:token deferred to Task 5.2
```

---

## 7. Backend Code Review — Scheduler Components

These components (`OrphanReconciliationScheduler`, `PhotoDeleteJobEnqueuer`, `UnverifiedAccountPurgeScheduler`, `SchedulerTest`) are production code present in the unstaged working tree and are reviewed here for the first time.

**`PhotoDeleteJobEnqueuer`:** Functional and crash-safe. The pipeline concern (MI-37) is the only structural issue. `enqueueOrphan` is correctly non-pipelined for single-item calls.

**`OrphanReconciliationScheduler`:** The two-pass algorithm (MinIO listing → batch DB lookup → enqueue orphans) is well-designed. The 2-hour recency threshold prevents races with in-progress uploads (SA1-F4). The `@SchedulerLock(lockAtMostFor="PT2H")` bound is appropriate. Issues: unbounded IN-clause (MI-38), missing error-path test (MI-39), DB connection held for full run (MI-40).

**`UnverifiedAccountPurgeScheduler`:** The delete ordering (enqueue MinIO → delete DB) is correct and crash-safe. The `album_photos` → `photos` → `albums` → `keywords` manual cascade order is thorough and correctly handles the self-referencing `parent_id` on `keywords` (UPDATE to NULL before DELETE). The use of `authTxTemplate` (BYPASSRLS data source) for user-level operations is correct.

**`SchedulerTest`:** Integration test with Testcontainers PostgreSQL + real Redis is the correct testing approach for schedulers. All three schedulers have substantive behavioral tests. ShedLock keys are correctly cleared in `@BeforeEach`. The `setCreatedAtDaysAgo` raw JDBC helper is acceptable for test setup. The `trashPurge_doesNotRunConcurrentlyAcrossInstances` test validates annotation metadata — it is less comprehensive than a true concurrency test but is sufficient for a distributed lock contract check.

**`worker/Dockerfile`:** Pinning by SHA256 digest (base image) + exact package versions is correct and consistent with the plan's pin-everything convention. Runs as non-root. Uses tini for PID 1. The only trivial issue is the redundant `-XX:+UseG1GC` (MI-41).

---

## 8. Final Recommendation

**Approve with changes.**

v6.0 resolved all prior blocking issues comprehensively, including the systemic TDD stub problem and the significant backend compatibility corrections (UUID IDs, 204 crash, CSRF header, two-step login). The new backend scheduler code is solid and well-tested.

Three blocking issues remain in the plan and must be fixed before implementation:

| Priority | Issue | Fix |
|----------|-------|-----|
| **Blocking** | CI-23: Task 4.4 — 4 comment-only TDD stubs | Add MSW handlers + `expect()` for viewport, staleTime, fetchNext, pagination termination |
| **Blocking** | CI-24: PhotoCard test wire-format mock | Wrap `mockPhoto()` with `camelizeKeys()` before passing to `PhotoCard`; add `mockPhotoApp()` factory |
| **Blocking** | CI-25: Task 4.3 `vi.mocked` without `vi.mock` | Add `vi.mock('../api/hooks/useAuth')` declaration; reconcile with MSW-based tests in same file |
| **Medium** | MI-37: `enqueue` pipeline ambiguity | Switch `executePipelined(RedisCallback)` to `executePipelined(SessionCallback)` |
| **Medium** | MI-38: `findAllById` unbounded IN-clause | Partition into 1,000-element batches |
| **Low** | MI-39: Missing error-path test in orphan reconciliation | Add test for `Result.get()` exception |
| **Low** | MI-40: DB connection held for full orphan run | Document in class Javadoc |
| **Low** | MI-41: Redundant `-XX:+UseG1GC` in Dockerfile | Remove or add intent comment |
