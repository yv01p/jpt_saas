# Phase 4 Critical Implementation Review — v4

**Plan reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-4.md` (v4.0 — 2026-03-06)
**Previous reviews:** `...-critical-review-1.md`, `...-critical-review-2.md`, `...-critical-review-3.md`
**Date:** 2026-03-06
**Reviewer:** Senior Staff Engineer (critical-implementation-review skill)

---

## Scope Note

All issues from reviews v1 (CI-1–CI-5, MI-1–MI-10), v2 (CI-6–CI-9, MI-11–MI-17), and v3 (CI-10–CI-12, MI-18–MI-24) are resolved in v4.0 per the changelog. This review focuses exclusively on new findings in the v4.0 plan text.

---

## 1. Overall Assessment

v4.0 is a significant improvement. CI-10 (CSRF `.finally()` bug), CI-11 (TQ/Zustand unpinned), and MI-18–MI-24 are all correctly resolved. The `async/await init()` pattern is clean and correct, the `isHydrating: true` in `create()` is precise, the `rowVirtualizer.range ?? { startIndex: 0, endIndex: 0 }` null guard is sound, and moving keyword-photo assignment to Task 4.6 was the right call.

**Remaining concerns:**

1. **The CI-12 fix is incomplete** — Task 4.5 (8 stubs) and Tasks 4.2/4.3/4.7c (8 more stubs) were not covered. Twenty percent of the test surface remains hollow.
2. **`deleted_at` vs `deletedAt` is a runtime correctness bug** — the TypeScript `Photo` interface uses camelCase but the API returns snake_case, meaning `photo.deletedAt` is always `undefined` in the TrashPage.
3. **`5.x.x` wildcards are not version pins** — the CI-11 fix replaced unpinned with `5.x.x`, which npm resolves to the latest 5.x at install time, perpetuating the non-determinism problem.
4. **`<Navigate to="/library" />` is missing `replace`** — the back button creates a redirect loop.
5. **`hydrateSession()` is called in tests but never defined or exported** — the test file will not compile.

---

## 2. Critical Issues

### CI-13: Task 4.5 — All 8 TDD Stubs Still Have Empty Bodies (`{ ... }`)

**Description:** The v4.0 changelog states CI-12 was fixed by replacing hollow stubs "across Tasks 4.7a, 4.7b, 4.7d, and 4.8 GPS toggle." Task 4.5 is not in that list. The plan still shows:

```typescript
test('drop triggers POST /api/photos', async () => { ... });
test('polls /api/photos/{id}/status after upload', async () => { ... });
test('terminal "done" status stops polling', async () => { ... });
test('terminal "failed" status renders failureReason message, not generic label', async () => { ... });
test('polling stops after 10-minute timeout and shows timeout message', async () => { ... });
test('polling uses exponential backoff after 5 polls', async () => { ... });
test('polling stops when component unmounts', async () => { ... });
test('"still processing" message appears after 30 seconds in non-terminal state', async () => { ... });
```

In Vitest, `test('desc', async () => { ... })` with an empty body has no `expect()` calls and trivially passes. The v3 review's dependency map explicitly flagged "[CI-12: all 8 test stubs have empty bodies — no assertions]" for Task 4.5. The fix was applied to three other tasks but not 4.5.

The `useUpload` hook is the most complex piece of logic in Phase 4 (bounded polling, exponential backoff, 10-minute timeout, component unmount cleanup, failure-reason mapping). It has zero real test coverage.

**Why it matters:** Upload, polling, and timeout logic are the highest-risk runtime behaviors in the phase. An implementor can write any polling implementation — or skip the timeout entirely — and all 8 tests will pass. The phase completion gate (`npm run test`) passes vacuously for the most critical component.

**Fix:** Each of the 8 stubs requires an MSW handler, a `render()`, and at minimum one `expect()`. Example for the highest-risk cases:

```typescript
test('drop triggers POST /api/photos', async () => {
  let uploadCalled = false;
  server.use(
    http.post('/api/photos', () => {
      uploadCalled = true;
      return HttpResponse.json({ id: 1, status: 'pending' });
    }),
    http.get('/api/photos/1/status', () => HttpResponse.json({ status: 'done' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  expect(uploadCalled).toBe(true);
});

test('polling uses exponential backoff after 5 polls', async () => {
  vi.useFakeTimers();
  const callTimes: number[] = [];
  let pollCount = 0;
  server.use(
    http.post('/api/photos', () => HttpResponse.json({ id: 1, status: 'pending' })),
    http.get('/api/photos/1/status', () => {
      callTimes.push(Date.now());
      pollCount++;
      return HttpResponse.json({ status: pollCount >= 10 ? 'done' : 'processing' });
    }),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  // ... trigger upload, advance time, assert intervals grow exponentially
  const interval6 = callTimes[6] - callTimes[5];
  const interval7 = callTimes[7] - callTimes[6];
  expect(interval7).toBeGreaterThan(interval6); // exponential growth
  vi.useRealTimers();
});

test('polling stops after 10-minute timeout and shows timeout message', async () => {
  vi.useFakeTimers();
  server.use(
    http.post('/api/photos', () => HttpResponse.json({ id: 1, status: 'pending' })),
    http.get('/api/photos/1/status', () => HttpResponse.json({ status: 'processing' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  // trigger upload then advance past 10 minutes
  await vi.advanceTimersByTimeAsync(10 * 60 * 1000 + 1000);
  expect(screen.getByText(/processing timed out/i)).toBeInTheDocument();
  vi.useRealTimers();
});
```

---

### CI-14: Tasks 4.2, 4.3, and 4.7c — Hollow Stubs Still Present

**Description:** The CI-12 fix covered 4.7a, 4.7b, 4.7d, and 4.8 GPS toggle. The following stubs were missed:

**Task 4.2 — Two 401 handler tests are comment-only:**
```typescript
test('401 response clears auth store and redirects to /login', async () => {
  server.use(http.get('/api/photos', () => new HttpResponse(null, { status: 401 })));
  // trigger query, assert store cleared and window.location updated to /login
});

test('401 on mutation clears auth store and redirects to /login', async () => {
  // trigger mutation returning 401, assert same behavior
});
```

**Task 4.3 — One login redirect test is comment-only:**
```typescript
test('LoginPage redirects to location.state.from after login', async () => {
  // Set up MemoryRouter with initialEntries location.state.from = '/photo/42'
  // After successful login, assert navigation to '/photo/42'
});
```

**Task 4.7c — Three tests are todos (no callback), two are comment-only:**
```typescript
test('full-text search fires on submit');        // todo — no callback
test('EXIF field filter applies to results');    // todo — no callback
test('keyword search filters results');          // todo — no callback

test('saved search is not visible to a different user ID', () => {
  // set userId=42 saved searches, switch to userId=99, assert no searches loaded
});
test('saved search re-applies on next visit by reading localStorage on mount', () => {
  // pre-populate localStorage with saved_searches_42, mount SearchPage, assert search applied
});
```

This is 8 additional hollow tests across 3 tasks — the same count as Task 4.5. Combined with CI-13, 16 of the phase's tests are hollow.

**Why it matters:** The `QueryCache`/`MutationCache` 401 handlers are a critical security boundary (session expiry detection). The post-login redirect is a critical UX contract. All 5 SearchPage tests are hollow, making the SearchPage essentially untested.

**Fix:** Implement each stub with full bodies. For the 401 tests, spy on `window.location.replace` and assert store state:

```typescript
test('401 response clears auth store and redirects to /login', async () => {
  const replaceSpy = vi.spyOn(window.location, 'replace').mockImplementation(() => {});
  useAuthStore.setState({ isAuthenticated: true, user: mockUser });
  server.use(http.get('/api/photos', () => new HttpResponse(null, { status: 401 })));
  const { result } = renderHook(
    () => useQuery({ queryKey: ['photos'], queryFn: () => fetch('/api/photos').then(r => { if (!r.ok) throw new ApiError(r.status, ''); return r.json(); }) }),
    { wrapper: QueryClientWrapper }
  );
  await waitFor(() => expect(result.current.isError).toBe(true));
  expect(useAuthStore.getState().isAuthenticated).toBe(false);
  expect(replaceSpy).toHaveBeenCalledWith('/login');
});
```

For the SearchPage saved-search tests:
```typescript
test('saved search is not visible to a different user ID', () => {
  localStorage.setItem('saved_searches_42', JSON.stringify([{ query: 'sunset' }]));
  useAuthStore.setState({ user: { id: 99, email: 'other@b.com', showGps: false } });
  render(<SearchPage />, { wrapper: QueryClientWrapper });
  expect(screen.queryByText('sunset')).not.toBeInTheDocument();
  localStorage.clear();
});
```

---

### CI-15: `deleted_at` (API snake_case) vs `deletedAt` (TypeScript camelCase) — Runtime Correctness Bug

**Description:** The `Photo` TypeScript interface in Task 4.2 defines:

```typescript
deletedAt: string | null;   // null for active photos; ISO-8601 string when soft-deleted
```

The TrashPage tests (Task 4.7d) include this inline comment:

```typescript
// deleted_at is the JSON field name returned by the API (snake_case from PhotoResponse DTO).
```

And the test mocks use snake_case:

```typescript
mockPhoto({ id: 1, filename: 'old.jpg', deleted_at: '2026-03-01T10:00:00Z' })
```

The `PhotoResponse` Java DTO uses Jackson's default naming strategy, which renders Java's `deletedAt` field as `deleted_at` in JSON (or must be explicitly configured). The plan's own comment confirms the API returns `deleted_at`.

When `fetch('/api/photos/trash')` returns `{ "deleted_at": "2026-03-01T..." }` and the code accesses `photo.deletedAt`, the value is `undefined` — JavaScript does not automatically map `deleted_at` to `deletedAt`. The TrashPage will never display a deletion date or retention window. The `retention window displays correctly` test passes in the plan's test setup only because `mockPhoto` is constructed directly in the test (bypassing the fetch) — but the real integration breaks silently.

**Why it matters:** This is a runtime data loss bug. The `deletedAt` field is used for display ("Deleted March 1, 2026"), retention calculation ("25 days remaining"), and arguably for the restore button's conditional rendering. All three will be wrong in production.

**Fix:** Choose one of two approaches consistently and apply it everywhere:

**Option A — Align TypeScript type with API response (snake_case):**
```typescript
export interface Photo {
  // ...
  deleted_at: string | null;
}
```
And update all references in the codebase from `photo.deletedAt` to `photo.deleted_at`. This is the zero-transformation approach.

**Option B — Transform API responses to camelCase:**
Add a `toCamelCase` transformer in the fetch wrapper in `client.ts`, or use a library like `camelcase-keys`. Document this explicitly so all future API additions know to rely on the transform. This is the right long-term approach for a project where the Java backend uses snake_case JSON by default.

Whichever option is chosen, it must be applied consistently to all snake_case fields. Audit the rest of `PhotoResponse`'s fields (e.g., `createdAt`, `sizeBytes`, `takenAt`, `uploadedAt`, `processingStatus`, `thumbnailUrl`, `originalUrl`, `failureReason`) — each one may have a similar mismatch between Java naming and the TypeScript interface. The `showGps` field in `UserResponse` (annotated `@JsonProperty("show_gps")`) has the same problem.

---

### CI-16: `5.x.x` Wildcards Are Not Version Pins — CI-11 Fix Is Incomplete

**Description:** The CI-11 fix changed the npm install command from:

```bash
npm install react-router-dom@6 @tanstack/react-query zustand
```

to:

```bash
npm install react-router-dom@6 @tanstack/react-query@5.x.x @tanstack/react-virtual@3.x.x zustand@5.x.x
```

In npm's semver resolution, `5.x.x` is equivalent to `>=5.0.0 <6.0.0`. This resolves to the **latest published 5.x version at install time** — identical to `@5` or `@^5`. It is not a pin. Two developers running `npm install` a week apart may get different patch versions. The plan's own convention states: "Do not use `@latest` in any `npx` or `npm install` command. Pin all versions explicitly." `5.x.x` violates this convention.

The plan does include a comment "Verify current stable patch versions before running" with links to release pages, which suggests the intent is correct — but the intent and the command are inconsistent. An implementor who runs the command as written (reasonably assuming `5.x.x` is somehow specific) gets a non-deterministic install.

**Why it matters:** This is the same risk that originally motivated CI-11: the TQ v5 `useInfiniteQuery` API (specifically `initialPageParam`) is already pinned to v5 semantics in Task 4.4. Any future 6.x release of TQ would silently break the build. More practically, patch releases can introduce bugs or change behavior between team members' environments.

**Fix:** Replace all `x` wildcards with concrete version numbers at the time the plan is finalized (or at the latest, just before implementation). The comment links should be used to look up the version, then that exact version replaces the placeholder in the command:

```bash
# Example — replace with actual current versions verified at implementation time:
npm install react-router-dom@6.x.y @tanstack/react-query@5.67.2 @tanstack/react-virtual@3.10.9 zustand@5.0.3
```

Alternatively, keep the `5.x.x` placeholder but add a prominent implementation note:

> **IMPORTANT:** Before running this command, replace `5.x.x`, `3.x.x`, etc. with the exact current patch versions from the linked release pages. Do not run the command with the `.x.x` placeholders — npm will install the latest patch, not a pinned version.

---

### CI-17: `<Navigate to="/library" />` Missing `replace` Prop — Back-Button Redirect Loop

**Description:** Task 4.9 defines the root redirect as:

```typescript
<Route path="/" element={<Navigate to="/library" />} />
```

Without `replace`, React Router v6's `<Navigate>` component performs a **push** navigation. The browser history gains an entry for `/`. When the user is on `/library` and presses the back button, they navigate to `/`, which immediately redirects back to `/library` via the `<Navigate>`, creating an infinite redirect loop that traps the user.

**Why it matters:** This is a UX correctness bug that affects every user of the application every time they use the back button from the library view. The smoke test in Task 4.9 Step 2 checks "Back button after redirect lands at `/login` (not `/library`)" — which tests the ProtectedRoute redirect — but does not test the root-redirect back-button behavior.

**Fix:**

```typescript
<Route path="/" element={<Navigate to="/library" replace />} />
```

The `replace` prop causes the `/` history entry to be replaced by `/library`, so the back button skips over it entirely. This matches the intended behavior ("redirect, don't navigate") and is the standard pattern for index redirects.

---

## 3. Minor Issues & Improvements

### MI-25: Task 4.8 `usedBytes` Floor Guard Test — Missing Query Mock

**Description:**

```typescript
test('usedBytes floor guard: never renders negative storage value', () => {
  const quota = { usedBytes: -1, limitBytes: 10_000_000_000 };
  render(<SettingsPage />);
  expect(screen.getByText('0.0 GB of 10.0 GB used')).toBeInTheDocument();
});
```

`quota` is defined as a local variable but never used — there is no `server.use()` handler and no query mock. `SettingsPage` fetches quota via `useQuery`, which in the test environment either returns `undefined` (loading state) or errors. The `expect` will never find the text "0.0 GB of 10.0 GB used" because the component is stuck in the loading skeleton state.

**Fix:** Add an MSW handler that returns the negative value:

```typescript
test('usedBytes floor guard: never renders negative storage value', async () => {
  server.use(
    http.get('/api/quota', () => HttpResponse.json({ usedBytes: -1, limitBytes: 10_000_000_000 })),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText('0.0 GB of 10.0 GB used')).toBeInTheDocument();
});
```

---

### MI-26: `hydrateSession()` Referenced in Tests But Never Defined or Exported

**Description:** Task 4.2 tests call:

```typescript
await hydrateSession();
```

The plan defines `fetchCurrentUser()` as an export from `client.ts` and places the hydration logic inline inside `init()` in `main.tsx`. There is no `hydrateSession` function defined or exported anywhere. The test file will fail to compile:

```
error TS2304: Cannot find name 'hydrateSession'.
```

**Fix:** Extract the hydration logic from `init()` into a testable exported function:

```typescript
// frontend/src/api/client.ts
export async function hydrateSession(): Promise<void> {
  try {
    const user = await fetchCurrentUser();
    if (user) useAuthStore.getState().setAuth(user);
  } catch {
    // Network error — leave isAuthenticated false
  } finally {
    useAuthStore.setState({ isHydrating: false });
  }
}
```

And call it from `init()`:

```typescript
// main.tsx init()
await hydrateSession();
// (replaces the inline try/catch/finally block)
```

This makes the hydration logic independently testable without running the entire `init()` function.

---

### MI-27: `mockPhoto({ id: '1' })` — String ID Where `Photo.id: number` Is Expected

**Description:** Task 4.6 tests use:

```typescript
http.get('/api/photos/1', () => HttpResponse.json(mockPhoto({ id: '1' }))),
```

The `Photo` TypeScript interface defines `id: number`. Passing `id: '1'` (string) to `mockPhoto` produces a type error at build time if `mockPhoto` is typed. The URL path `/api/photos/1` uses a string `'1'` (correct for the URL), but the mock response object should use a numeric `1`.

**Fix:** Use the correct type in the mock data:

```typescript
http.get('/api/photos/1', () => HttpResponse.json(mockPhoto({ id: 1 }))),
// Similarly for keywords:
http.get('/api/keywords', () => HttpResponse.json([
  { id: 1, name: 'Animals', parentId: null, children: [] }
])),
```

---

### MI-28: `PAGE_SIZE = 50` Still Duplicated Across LibraryPage and SearchPage

**Description:** MI-18 extracted `const PAGE_SIZE = 50` within `LibraryPage`. Task 4.7c introduces a second identical constant:

```typescript
// SearchPage.tsx
const PAGE_SIZE = 50;
```

This is the same DRY violation as MI-18 but between files rather than within a file. If the page size changes, both constants must be updated.

**Fix:** Extract to a shared module:

```typescript
// frontend/src/api/constants.ts
export const PAGE_SIZE = 50;
```

Import from both `LibraryPage` and `SearchPage`.

---

### MI-29: `vi.useFakeTimers()` Not Cleaned Up in `afterEach`

**Description:** The recovery polling test in Task 4.4 calls `vi.useRealTimers()` at the end of the test body:

```typescript
test('photo list refetches every 5s when any photo has non-terminal status', async () => {
  vi.useFakeTimers();
  // ...
  vi.useRealTimers();  // only runs if test completes without error
});
```

If the test fails before reaching `vi.useRealTimers()`, subsequent tests run with faked timers. `setTimeout`/`setInterval` in other components will not fire, causing unrelated tests to hang or fail with misleading timeouts.

The same pattern appears implicitly in CI-13's fix stubs for Task 4.5's polling tests.

**Fix:** Use `afterEach` for timer cleanup:

```typescript
afterEach(() => {
  vi.useRealTimers();
});
```

Or use Vitest's `fakeTimers` configuration option to restore automatically:

```typescript
test('...', async () => {
  vi.useFakeTimers();
  try {
    // test body
  } finally {
    vi.useRealTimers();
  }
});
```

---

### MI-30: CSRF Cookie Non-HttpOnly Requirement Not Documented for Backend Configuration

**Description:** Task 4.2 specifies: "Fetch wrapper with `X-XSRF-TOKEN` header read from `XSRF-TOKEN` cookie." JavaScript can only read a cookie if it is **not** marked `HttpOnly`. Spring Security's default CSRF token repository (`CookieCsrfTokenRepository`) sets the `XSRF-TOKEN` cookie as non-HttpOnly specifically to enable client-side reading, but this is not the default for all session cookies and could be misconfigured in a security-conscious review.

**Why it matters:** If the CSRF cookie is accidentally configured as HttpOnly (e.g., by a security scanner recommendation or a misconfigured Spring Security filter), the JavaScript cookie reader returns an empty string, the `X-XSRF-TOKEN` header is sent as empty, and all POST/PUT/DELETE mutations fail with 403 — silently from the frontend's perspective until the user attempts a write action.

**Fix:** Add a backend configuration note to Task 4.2's Spring Security prerequisite block:

> **Backend configuration requirement:** The CSRF token must be stored in a non-HttpOnly cookie so JavaScript can read it. Use `CookieCsrfTokenRepository.withHttpOnlyFalse()` in the Spring Security config. The session cookie (`JSESSIONID`) must remain `HttpOnly`. This is the intended dual-cookie pattern: one HttpOnly session cookie (server-readable only) + one non-HttpOnly CSRF cookie (JS-readable only).

---

## 4. Questions for Clarification

**Q13:** Is `deleted_at` the only snake_case field in `PhotoResponse` whose camelCase mapping is unresolved, or are there others (e.g., `failureReason` → `failure_reason`, `thumbnailUrl` → `thumbnail_url`, `processingStatus` → `processing_status`)? Clarifying the complete list determines whether Option A (align TS types to snake_case) or Option B (camelCase transformer) is the right architectural choice for CI-15.

**Q14:** `5.x.x` version placeholders — at what point does the implementor substitute real version numbers? Before committing the plan, or at implementation time? If the latter, should the plan enforce this with a TODO marker rather than a `5.x.x` that looks syntactically valid to npm?

**Q15:** Does AlbumsPage support album creation and deletion, or only membership management (add/remove photos)? The plan specifies the latter but the `Album` type and the API may have endpoints for both. If album CRUD is in scope, add tests; if out of scope, add an explicit exclusion note.

---

## 5. Updated Dependency Map (v4.0)

All v3.0 fixes confirmed applied. New findings annotated `[NEW]`.

```
4.1 (scaffold)
  ✓ Vite 6.3.5 pinned
  ✓ MSW v2 @ 2.7.3 pinned
  ✓ shadcn-ui @ 0.9.4 pinned
  ✓ ESLint react/no-danger configured
  ✓ Vite proxy configured
  [CI-16: @tanstack/react-query@5.x.x / zustand@5.x.x — wildcards, not true pins]
  └── 4.2 (API client + auth store + ProtectedRoute + API types)
        ✓ bootstrapCsrf() implemented
        ✓ async/await init() — CSRF failure correctly aborts render (CI-10 fixed)
        ✓ session hydration (GET /api/users/me) — Phase 3 prerequisite documented
        ✓ isHydrating: true in store create() — no external setState race (MI-19 fixed)
        ✓ QueryCache + MutationCache 401 handlers
        ✓ Full API type definitions (Photo, Album, Keyword, QuotaInfo, SearchResult, User, PhotoMetadata, ShareToken)
        ✓ deletedAt: string | null added to Photo
        [CI-15: deleted_at API field vs deletedAt TS type — camelCase mismatch, runtime undefined]
        [CI-14: two 401 handler tests are comment-only — no assertions]
        [MI-26: hydrateSession() referenced in tests but not exported/defined]
        │
        ├── 4.3 (LoginPage, RegisterPage)    [can parallel 4.6, 4.7a–d, 4.8]
        │       ✓ ?verified=true banner
        │       ✓ 12-char password minimum
        │       [CI-14: login redirect test 'redirects to location.state.from' is comment-only]
        │
        ├── 4.4 (LibraryPage + PhotoGrid + PhotoCard)    [can parallel 4.3, 4.6, 4.7a–d, 4.8]
        │       ✓ useInfiniteQuery (TQ v5 API — initialPageParam: 0)
        │       ✓ PAGE_SIZE constant extracted (MI-18 fixed)
        │       ✓ rowVirtualizer.range with null guard (MI-20 fixed)
        │       ✓ refetchInterval recovery polling for non-terminal status (MI-23 fixed)
        │       [MI-29: vi.useFakeTimers() not in afterEach — test pollution risk]
        │       [MI-28: PAGE_SIZE=50 duplicated in SearchPage (see 4.7c)]
        │
        │       └── 4.5 (UploadDropzone + useUpload)    [SEQUENTIAL — must follow 4.4]
        │               ✓ LibraryPage.tsx Modify step documented (MI-11 fixed)
        │               ✓ bounded polling spec (3s×5, exponential, 10min timeout)
        │               ✓ failureReason mapped to user-readable strings
        │               [CI-13: ALL 8 test stubs have { ... } empty bodies — no assertions]
        │
        ├── 4.6 (PhotoPage + MetadataPanel)    [can parallel 4.3, 4.7a–d, 4.8]
        │       ✓ keyword-photo assignment moved here from 4.7a (MI-21 fixed)
        │       ✓ Phase 3 prerequisite block for 3 new keyword endpoints
        │       ✓ originalUrl staleTime 55min / gcTime 60min (1-hour TTL)
        │       ✓ GPS absent from DOM when showGps=false
        │       ✓ Concrete assign/remove keyword tests with MSW handlers and expect()
        │       [MI-27: mockPhoto({ id: '1' }) — string where Photo.id: number expected]
        │
        ├── 4.7a (KeywordsPage)    [can parallel 4.3, 4.4, 4.6, 4.7b–d, 4.8]
        │       ✓ keyword CRUD tests have MSW handlers + expect() (CI-12 fixed)
        │       ✓ edit uses PUT (not PATCH — verified against KeywordController)
        │       ✓ assignment moved to 4.6
        │
        ├── 4.7b (AlbumsPage)    [can parallel 4.3, 4.4, 4.6, 4.7a/c/d, 4.8]
        │       ✓ album list/detail/add-photo/remove-photo tests have bodies (CI-12 fixed)
        │       ✓ album detail is inline panel
        │       [Q15: album create/delete — in scope or not?]
        │
        ├── 4.7c (SearchPage)    [can parallel 4.3, 4.4, 4.6, 4.7a/b/d, 4.8]
        │       ✓ useInfiniteQuery with query+filters in queryKey (MI-22 fixed)
        │       ✓ PAGE_SIZE constant defined locally
        │       [CI-14: 3 todo tests (no callback) + 2 comment-only tests — no assertions]
        │       [MI-28: PAGE_SIZE=50 duplicated from 4.4 — extract to shared constant]
        │
        ├── 4.7d (TrashPage)    [can parallel 4.3, 4.4, 4.6, 4.7a–c, 4.8]
        │       ✓ endpoint corrected to /api/photos/trash (CI-12 fix)
        │       ✓ restore returns 200 (CI-12 fix)
        │       ✓ test bodies have MSW handlers + expect() (CI-12 fixed)
        │       [CI-15: deleted_at (API) vs deletedAt (TS interface) — tests use deleted_at
        │               in mockPhoto but runtime fetch returns deleted_at → photo.deletedAt=undefined]
        │
        └── 4.8 (SettingsPage)    [can parallel 4.3, 4.4, 4.6, 4.7a–d]
                ✓ GPS toggle test has MSW handler + expect() (CI-12 fixed)
                ✓ loading skeleton + error state
                ✓ usedBytes ?? 0 + Math.max(0, ...) guard
                [MI-25: usedBytes floor guard test — quota not mocked, expect will fail]
                └── 4.9 (Router — integration gate)
                        PREREQUISITE: 4.3, 4.4, 4.5, 4.6, 4.7a–d, 4.8 all complete
                        ✓ /share/:token deferred to Task 5.2
                        ✓ catch-all * → NotFoundPage
                        ✓ / → Navigate to /library
                        [CI-17: <Navigate to="/library" /> missing replace prop — back-button loop]
```

---

## 6. Security Assessment

**CSRF:** Correctly implemented in v4.0. `bootstrapCsrf()` is called before any mutation; the `async/await init()` correctly aborts rendering on failure (CI-10 resolved). The CSRF cookie non-HttpOnly requirement is undocumented (MI-30) — low severity, but a backend misconfiguration risk.

**XSS (EXIF rendering):** Defense-in-depth maintained. React text nodes + `react/no-danger` ESLint rule + Phase 3 Jsoup sanitization. The GPS-absent-from-DOM requirement is correctly specified and tested.

**Session security:** `isHydrating: true` in the store's `create()` eliminates the initialization race. The `hydrateSession()` compilation gap (MI-26) does not affect runtime security — only testability.

**Data exposure (deleted_at):** The CI-15 snake_case mismatch makes `photo.deletedAt` undefined in the TrashPage. This is a functional bug, not a security issue — but it could also affect the conditional logic that determines whether a photo is in the trash vs. active, depending on how other pages use the `deletedAt` field.

**Zero trust alignment:** All protected routes use `ProtectedRoute`. The 401 handler clears local state and forces re-authentication. The `<Navigate replace />` fix (CI-17) is also relevant here — without `replace`, an unauthenticated user pressing back from `/login` could briefly navigate to `/library` before being redirected, creating a micro-window of confusion (not a security gap, but worth closing for UX correctness).

---

## 7. Final Recommendation

**Major revisions needed.**

v4.0 resolved all three v3 blocking issues cleanly. However, the CI-12 fix was applied to only half the hollow stubs, leaving 16 tests without bodies across 4 tasks. CI-15 (`deleted_at` vs `deletedAt`) is a new correctness bug introduced by the v4.0 codebase verification (which confirmed the snake_case field name) without resolving the TypeScript interface mismatch. CI-16 and CI-17 are small but must be fixed before implementation.

| Priority | Issue | Fix |
|----------|-------|-----|
| **Blocking** | CI-13: Task 4.5 — all 8 stubs `{ ... }` | Add MSW handlers + `expect()` to all 8 stubs |
| **Blocking** | CI-14: Tasks 4.2 (2), 4.3 (1), 4.7c (5) — hollow stubs | Add `expect()` assertions; promote todos to full test bodies |
| **Blocking** | CI-15: `deleted_at` vs `deletedAt` type mismatch | Pick Option A (snake_case in TS interface) or Option B (camelCase transformer); apply consistently to all fields |
| **High** | CI-16: `5.x.x` is not a pin | Replace with exact versions at plan finalization, or add prominent "replace x" warning to command |
| **High** | CI-17: `<Navigate>` missing `replace` | Add `replace` prop |
| **Medium** | MI-25: floor guard test missing query mock | Add `server.use()` with negative usedBytes |
| **Medium** | MI-26: `hydrateSession()` not exported | Extract hydration logic from `init()` into exported function |
| **Low** | MI-27: `mockPhoto({ id: '1' })` type error | Use numeric `id: 1` |
| **Low** | MI-28: `PAGE_SIZE=50` in two files | Extract to `api/constants.ts` |
| **Low** | MI-29: `vi.useFakeTimers()` not in `afterEach` | Add cleanup in `afterEach` or `finally` block |
| **Low** | MI-30: CSRF cookie HttpOnly constraint undocumented | Add backend config note to Task 4.2 prerequisite block |
