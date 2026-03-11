# Phase 4 Critical Implementation Review — v3

**Plan reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-4.md` (v3.0 — 2026-03-06)
**Previous reviews:** `...-critical-review-1.md`, `...-critical-review-2.md`
**Date:** 2026-03-06
**Reviewer:** Senior Staff Engineer (critical-implementation-review skill)

---

## Scope Note

All issues from reviews v1 (CI-1–CI-5, MI-1–MI-10) and v2 (CI-6–CI-9, MI-11–MI-17) are resolved in v3.0. This review focuses exclusively on new findings in the v3.0 plan text, with particular attention to inter-task dependency correctness.

---

## 1. Overall Assessment

v3.0 is the strongest version of this plan. All prior blocking issues (cross-phase forward dependency, missing `ProtectedRoute`, CSRF bootstrap gap, MSW v1/v2 mismatch, session hydration) are correctly resolved. The `useInfiniteQuery` pagination spec is well-written and mathematically sound.

**Remaining concerns:**

1. **The `main.tsx` startup sequence has a structural bug** — the two code snippets for CSRF failure handling are incompatible. The `.finally()` block renders the React app even after a CSRF failure, overwriting the error message. The error-surface path is broken.
2. **`@tanstack/react-query` and `zustand` are installed without version pins** — violating the plan's own pin-everything convention. The TQ v4→v5 break is particularly dangerous because the `useInfiniteQuery` API changed incompatibly.
3. **TDD stubs are still todo-only (no function body) in Tasks 4.7a, 4.7b, 4.7d, and the GPS toggle in 4.8** — a partial fix for CI-2 that was declared fully resolved in the v2 changelog.

---

## 2. Critical Issues

### CI-10: `main.tsx` — `.finally()` Overwrites CSRF Failure Error Message

**Description:** The plan presents two code snippets for `main.tsx`:

1. The main startup sequence, which ends with `.finally(() => { useAuthStore.setState(...); ReactDOM.createRoot(...).render(...); })`.
2. A CSRF failure handler: `.catch(() => { document.getElementById('root').innerHTML = '<p>Unable to connect...</p>'; throw new Error('CSRF bootstrap failed'); })`.

These snippets are structurally incompatible. When the `.catch()` re-throws, the promise chain remains rejected — but `.finally()` executes regardless of resolution or rejection. The `ReactDOM.createRoot(...).render(...)` call inside `.finally()` overwrites the error HTML that was set by the `.catch()`. The user sees a blank unauthenticated React app instead of the error message. The CSRF failure detection is silently broken.

Additionally, the single `.catch()` block that appears after `fetchCurrentUser()` silently swallows ALL errors — including the re-thrown `Error('CSRF bootstrap failed')` — making the CSRF failure indistinguishable from an expected 401.

**Why it matters:** Correctness of the startup error path. If the server is unreachable and CSRF bootstrap fails, the app renders silently with no feedback, appearing to function while all API calls fail. This is a worse user experience than the intended "Unable to connect" error and makes production debugging much harder.

**Fix:** Replace the promise chain with `async/await` for explicit, non-ambiguous control flow:

```typescript
// frontend/src/main.tsx
async function init() {
  // Step 1: CSRF bootstrap — abort if server is unreachable
  try {
    await bootstrapCsrf();
  } catch {
    document.getElementById('root')!.innerHTML =
      '<p style="padding:2rem">Unable to connect to the server. Please refresh the page.</p>';
    return; // Return here — do NOT render the React app
  }

  // Step 2: Session hydration — 401 is expected when unauthenticated
  try {
    const user = await fetchCurrentUser();
    if (user) useAuthStore.getState().setAuth(user);
  } catch {
    // Network error or unexpected server error: leave isAuthenticated false.
    // ProtectedRoute will redirect to /login — safe default.
  } finally {
    useAuthStore.setState({ isHydrating: false });
  }

  // Step 3: Render — only reached if CSRF bootstrap succeeded
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode><App /></React.StrictMode>
  );
}

init();
```

This makes CSRF failure truly abort rendering (the `return` prevents the render call), and separates hydration errors from CSRF errors cleanly.

---

### CI-11: `@tanstack/react-query` and `zustand` Installed Without Version Pins — TQ v5 Breaking Change

**Description:** Task 4.1 Step 1 installs:

```bash
npm install react-router-dom@6 @tanstack/react-query zustand
```

`@tanstack/react-query` and `zustand` are both unpinned, violating the plan's explicit phase-wide convention ("Do not use `@latest` in any `npx` or `npm install` command. Pin all versions explicitly"). This was the entire point of MI-1/CI-7 — but those fixes targeted `shadcn-ui` and `vite`, leaving the most critical runtime library unpinned.

TanStack Query v5 (released October 2023, now the default via `npm install @tanstack/react-query`) has breaking changes from v4 that directly conflict with the plan's `useInfiniteQuery` spec:

- `useInfiniteQuery` in v5 **requires** `initialPageParam` as a non-optional field. The plan uses the v4 pattern `queryFn: ({ pageParam = 0 })` — in v5, `pageParam` is always typed as the `initialPageParam` type, so the default `= 0` is redundant and TypeScript will reject the missing `initialPageParam` field.
- The `QueryCache`/`MutationCache` `onError` callback signature changed in v5.
- `gcTime` (renamed from `cacheTime` in v4) is correct for v5 — but only if v5 is the target.

If a developer runs `npm install` today and gets TQ v5, Task 4.4's `useInfiniteQuery` fails type checking immediately. If they get TQ v4, `gcTime` may be unrecognised. Either way, unpinned means the scaffold is non-deterministic.

**Why it matters:** This breaks either TypeScript compilation (v5 install) or cache behaviour (v4 install with `gcTime`). It also makes the plan's own `useInfiniteQuery` code example wrong for whichever version isn't targeted.

**Fix:** Pin to explicit versions at implementation time. If targeting TQ v5 (recommended):

```bash
npm install @tanstack/react-query@5.x.x zustand@4.x.x
```

And update the `useInfiniteQuery` spec to include `initialPageParam: 0`:

```typescript
useInfiniteQuery<SearchResult>({
  queryKey: ['photos'],
  queryFn: ({ pageParam }) => fetchPhotos({ page: pageParam as number, size: PAGE_SIZE }),
  initialPageParam: 0,  // Required in TQ v5
  getNextPageParam: (lastPage) =>
    lastPage.page * PAGE_SIZE + lastPage.photos.length < lastPage.total
      ? lastPage.page + 1
      : undefined,
  staleTime: 10 * 60 * 1000,
  gcTime:    15 * 60 * 1000,
})
```

---

### CI-12: TDD Stubs Still Hollow in Tasks 4.7a, 4.7b, 4.7d, and 4.8 GPS Toggle

**Description:** The v2 changelog states CI-2 was "replaced all empty TDD stubs with concrete test assertions across Tasks 4.2–4.8." This is incorrect. Tasks 4.7a, 4.7b, 4.7d, and the GPS toggle test in 4.8 still contain no function bodies:

```typescript
// Task 4.7a — these are NOT failing tests
test('hierarchical keyword tree renders from API data');
test('add keyword calls POST /api/keywords with correct parent');
test('edit keyword calls PATCH /api/keywords/{id}');
test('delete keyword calls DELETE /api/keywords/{id}');
test('assigning keyword to photo updates photo keyword list');

// Task 4.7b
test('album list renders from API data');
test('album detail shows member photos');

// Task 4.7d
test('soft-deleted photos render with deletion date');
test('restore button calls POST /api/photos/{id}/restore');
test('retention window displays correctly');

// Task 4.8
test('GPS toggle calls PATCH /api/users/me and updates auth store');
```

In Vitest, `test('description')` without a callback is a **todo test**, not a failing test. It appears in the output as "todo" and is not counted as a failure. The TDD gate (`npm run test — all tests pass`) is satisfied trivially. The "write failing test" step of the TDD cycle is skipped entirely for these four tasks.

Task 4.5 uses `{ ... }` notation — these are syntactically empty bodies with no assertions:

```typescript
test('drop triggers POST /api/photos', async () => { ... });  // No expect()
```

**Why it matters:** These four tasks have zero automated test coverage despite appearing to follow TDD. The library's keyword, album, and trash management features — plus GPS preference toggling — will be untested. The phase completion gate passes vacuously.

**Fix:** Each test must have a function body with at minimum:
- An MSW handler for the API call
- A `render()` call
- At least one `expect()` assertion covering both the happy path and an API response assertion

Example for Task 4.7a:
```typescript
test('add keyword calls POST /api/keywords with correct parent', async () => {
  let capturedBody: unknown;
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([{ id: 1, name: 'Animals', parentId: null, children: [] }])),
    http.post('/api/keywords', async ({ request }) => {
      capturedBody = await request.json();
      return HttpResponse.json({ id: 2, name: 'Dogs', parentId: 1, children: [] });
    }),
  );
  render(<KeywordsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(screen.getByRole('button', { name: /add child keyword/i }));
  await userEvent.type(screen.getByLabelText(/keyword name/i), 'Dogs');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  expect(capturedBody).toEqual({ name: 'Dogs', parentId: 1 });
});
```

---

## 3. Minor Issues & Improvements

### MI-18: `PAGE_SIZE = 50` Hardcoded in Two Places — DRY Violation

`getNextPageParam` and `queryFn` both hardcode `50`:

```typescript
queryFn: ({ pageParam = 0 }) => fetchPhotos({ page: pageParam, size: 50 }),
getNextPageParam: (lastPage) =>
  lastPage.page * 50 + lastPage.photos.length < lastPage.total
    ? lastPage.page + 1
    : undefined,
```

If the page size changes, only one of these values is likely to be updated, silently breaking pagination logic.

**Fix:** Extract to a module-level constant used in both places:

```typescript
const PAGE_SIZE = 50;
```

---

### MI-19: `isHydrating` Initial Value Not Specified in Store Definition

The plan says "`isHydrating` starts `true`" and sets it via `useAuthStore.setState({ isHydrating: true })` in `main.tsx`. However, the store's `create()` call is not shown in the plan, and the store's initial state is undefined. If the `create()` call initializes `isHydrating: false` (the natural TypeScript zero-value default), there is a window between module load and the `setState` call where the store reflects the wrong state.

**Fix:** Specify `isHydrating: true` as the initial value in the `create(set => ({ isHydrating: true, ... }))` call, making `main.tsx`'s explicit `setState` redundant and removable.

---

### MI-20: `range.endIndex` in Infinite Scroll Trigger — Variable Origin Not Defined

The infinite scroll trigger references `range.endIndex` without defining `range`:

```typescript
useEffect(() => {
  if (range.endIndex >= photos.length - 10 && hasNextPage && !isFetchingNextPage) {
    fetchNextPage();
  }
}, [range.endIndex, photos.length, hasNextPage, isFetchingNextPage]);
```

TanStack Virtual v3's API is `virtualizer.range` on the `Virtualizer` instance returned by `useVirtualizer()`. The plan does not show how `range` is destructured from the virtualizer. An implementor may name the virtualizer instance differently (e.g., `rowVirtualizer`) and misidentify this variable.

**Fix:** Specify the complete reference:

```typescript
const rowVirtualizer = useVirtualizer({ count: photos.length, ... });
const range = rowVirtualizer.range;
```

---

### MI-21: KeywordsPage Photo Assignment — API Endpoint and UI Model Unspecified

Task 4.7a includes "Assign keywords to photos" and the test `'assigning keyword to photo updates photo keyword list'`, but specifies neither:
- The API endpoint (e.g., `POST /api/photos/{id}/keywords` or `PUT /api/keywords/{id}/photos`)
- The UI model: does the Keywords page contain a photo selector? Or is keyword assignment done from the photo detail page (Task 4.6)?

The `types.ts` defined in Task 4.2 has no type for a keyword-photo assignment request or response. Without this, the implementor must invent both the endpoint contract and the UI interaction from nothing.

**Fix:** Clarify the interaction direction. If keyword assignment is on the Photo page (PhotoPage already has keyword context), move this feature to Task 4.6. If it belongs on the Keywords page, specify the endpoint, request body (`{ photoIds: number[] }`), and the UI model (e.g., a photo multi-select modal).

---

### MI-22: SearchPage Results — No Pagination Strategy Specified

`SearchPage` (Task 4.7c) consumes `SearchResult`, which includes `total` and `page` fields — confirming the backend returns paginated search results. The plan does not specify how the frontend handles this:

- `useInfiniteQuery` (same as library view)?
- `useQuery` with explicit page controls (previous/next buttons)?
- Client-side filtering of the already-loaded library from `['photos']` cache?

Given the backend is paginated, and a user may search across thousands of photos, this matters for correctness and performance.

**Fix:** Specify the pagination strategy. If using `useInfiniteQuery`, include an `initialPageParam` and `queryKey` that includes the search term (e.g., `['search', query, filters]`) so different searches are cached independently and don't pollute the library cache.

---

### MI-23: PhotoCard Shows Stale Processing Status After Page Refresh — No Recovery Polling

`PhotoCard` renders `status` and `failureReason` from the `Photo` object. `UploadDropzone`/`useUpload` handle real-time polling during the active upload session. But if a user:
1. Uploads a photo (status: `pending`)
2. Refreshes the page before processing completes
3. Returns to the library view

...the `PhotoCard` shows `pending` indefinitely, because no polling is active. The status is stale and will never update without a further action (another refresh, or re-navigating to the page).

**Fix:** Add a recovery polling mechanism. Either:
- **Option A (preferred):** In `LibraryPage`, after the photo list loads, filter photos with non-terminal status (`pending`/`processing`) and start a `useQuery` poll for each (with the same backoff spec as `useUpload`). The `staleTime: 10min` on the list query means status changes won't be reflected until the list refetches — a short `refetchInterval` (e.g., 5s) on the photo list query when any non-terminal photo exists would be simpler.
- **Option B:** `PhotoCard` self-polls for its own status when `status` is non-terminal, using `useQuery` with `refetchInterval`.

The plan should specify this explicitly, since it affects the `PhotoCard` and `LibraryPage` specs.

---

### MI-24: Parallelization Claim Contradicts Task 4.5's Sequential Dependency on Task 4.4

The development convention note at the top of Task 4.2 states:

> "Tasks 4.3–4.8 are standalone component tasks developed and tested in isolation via direct component rendering — no router context required."

This is incorrect for Task 4.5. Task 4.5 has an explicit `Modify: frontend/src/pages/LibraryPage.tsx` file entry, which requires `LibraryPage.tsx` to already exist. Task 4.5 therefore has a strict sequential dependency on Task 4.4. If a developer reads the convention note and works 4.5 in parallel with 4.4, the `Modify` step fails (file not found).

**Fix:** Amend the convention note:

> "Tasks 4.3, 4.6, 4.7a–d, and 4.8 are standalone and can be developed in parallel after Task 4.2. **Task 4.5 must follow Task 4.4** — it modifies `LibraryPage.tsx`, which is created in Task 4.4."

---

## 4. Questions for Clarification

**Q9:** Does `GET /api/users/me` (used in session hydration) exist in the Phase 3 implementation? The v2 review left this as Q5. The v3.0 plan incorporates the hydration call as a fact — confirm that Phase 3 Task 3.4 or equivalent created this endpoint, or add it as a dependency note.

**Q10:** What version of `@tanstack/react-query` is the target — v4 or v5? This is a binary decision that affects `useInfiniteQuery` API shape, `QueryClient` constructor options, and the error callback signatures. It must be declared explicitly and pinned in Task 4.1.

**Q11:** Does `GET /api/photos` return `thumbnailUrl` and `originalUrl` as pre-signed MinIO URLs in the list response? The plan asserts this ("The frontend does not make a separate request per photo to get URLs — they are returned in bulk with the paginated photo list"), but this is a significant backend contract that must be verified against the Phase 3 implementation. If Phase 3 does not currently return URLs in the list response, Task 4.4 will not work as specified.

**Q12:** What is the keyword-photo assignment interaction model? (See MI-21.) Does the assignment happen from the Keywords page or the Photo page?

---

## 5. Intra-Phase Dependency Map (v3.0)

All fixes from Reviews v1 and v2 are incorporated. New findings annotated with `[NEW]`.

```
4.1 (scaffold)
  ✓ Vite 6.3.5 pinned
  ✓ MSW v2 pinned @ 2.7.3
  ✓ shadcn-ui pinned @ 0.9.4
  ✓ ESLint react/no-danger configured
  ✓ Vite proxy configured
  [CI-11: @tanstack/react-query and zustand UNPINNED — breaking change risk]
  └── 4.2 (API client + auth store + ProtectedRoute + API types)
        ✓ bootstrapCsrf() implemented
        ✓ session hydration (GET /api/users/me) implemented
        ✓ isHydrating state in ProtectedRoute
        ✓ QueryCache + MutationCache 401 handlers
        ✓ ApiError, Photo, Album, Keyword, QuotaInfo, SearchResult, User types
        [CI-10: main.tsx startup — .finally() overwrites CSRF error HTML]
        [MI-19: isHydrating initial value not in store definition]
        │
        ├── 4.3 (LoginPage, RegisterPage)    [can parallel 4.6, 4.7a–d, 4.8]
        │       depends on: useAuth() from 4.2
        │       ✓ ?verified=true banner
        │       ✓ post-login redirect from location.state.from
        │       ✓ email verification documented as server-side
        │
        ├── 4.4 (LibraryPage + PhotoGrid + PhotoCard)    [can parallel 4.3, 4.6, 4.7a–d, 4.8]
        │       depends on: Photo, SearchResult types from 4.2
        │       ✓ useInfiniteQuery (not useQuery)
        │       ✓ page-number pagination (?page=0&size=50)
        │       ✓ LibraryPage placeholder for UploadDropzone
        │       [CI-11: useInfiniteQuery requires initialPageParam in TQ v5]
        │       [MI-18: PAGE_SIZE=50 hardcoded in two places — extract constant]
        │       [MI-20: 'range.endIndex' — virtualizer variable origin not defined]
        │       [MI-23: PhotoCard shows stale status after page refresh — no recovery polling]
        │
        │       └── 4.5 (UploadDropzone + useUpload)    [SEQUENTIAL — must follow 4.4]
        │               depends on: LibraryPage.tsx from 4.4 (Modify step)
        │               depends on: Phase 3 POST /api/photos (upload endpoint)
        │               depends on: Phase 3 GET /api/photos/{id}/status (polling endpoint)
        │               ✓ Modify: LibraryPage.tsx documented
        │               ✓ bounded polling (Phase 1: 3s×5, Phase 2: exponential, 10min timeout)
        │               ✓ failureReason mapped to user-readable strings
        │               [CI-12: all 8 test stubs have empty bodies — no assertions]
        │               [MI-24: plan's standalone convention is wrong for 4.5]
        │
        ├── 4.6 (PhotoPage + MetadataPanel)    [can parallel 4.3, 4.7a–d, 4.8]
        │       depends on: Photo, PhotoMetadata types from 4.2
        │       depends on: authStore.showGps from 4.2
        │       depends on: Phase 3 GET /api/photos/{id} [Q11: must return pre-signed URLs]
        │       depends on: Phase 3 GET /api/photos/{id}/metadata
        │       ✓ originalUrl staleTime 10min / gcTime 15min
        │       ✓ GPS absent from DOM (not CSS hidden) when showGps=false
        │       ✓ ESLint react/no-danger enforces XSS safe rendering
        │
        ├── 4.7a (KeywordsPage)    [can parallel 4.3, 4.4, 4.6, 4.7b–d, 4.8]
        │       depends on: Keyword type from 4.2
        │       [CI-12: ALL 5 test stubs are todo tests — no function body]
        │       [MI-21: photo assignment endpoint and UI model unspecified]
        │
        ├── 4.7b (AlbumsPage)    [can parallel 4.3, 4.4, 4.6, 4.7a/c/d, 4.8]
        │       depends on: Album type from 4.2
        │       ✓ album detail is inline panel — no /albums/:id route needed
        │       [CI-12: ALL 4 test stubs are todo tests — no function body]
        │
        ├── 4.7c (SearchPage)    [can parallel 4.3, 4.4, 4.6, 4.7a/b/d, 4.8]
        │       depends on: SearchResult type from 4.2
        │       ✓ saved searches in localStorage keyed by userId
        │       ✓ cross-user isolation tested
        │       [MI-22: search result pagination strategy unspecified]
        │
        ├── 4.7d (TrashPage)    [can parallel 4.3, 4.4, 4.6, 4.7a–c, 4.8]
        │       depends on: Photo type from 4.2
        │       [CI-12: ALL 3 test stubs are todo tests — no function body]
        │
        └── 4.8 (SettingsPage)    [can parallel 4.3, 4.4, 4.6, 4.7a–d]
                depends on: QuotaInfo, User types from 4.2
                depends on: authStore from 4.2 (GPS toggle updates store)
                depends on: Phase 3 GET /api/quota + PATCH /api/users/me
                ✓ loading skeleton, error state
                ✓ quota display format "X.X GB of Y.Y GB used"
                ✓ usedBytes ?? 0 null guard
                [CI-12: GPS toggle test 'calls PATCH ... and updates auth store' has no function body]
                └── 4.9 (Router — integration gate)
                        PREREQUISITE: 4.3, 4.4, 4.5, 4.6, 4.7a–d, 4.8 all complete
                        Note: 4.5 cannot start until 4.4 is complete
                        ✓ /share/:token deferred to Task 5.2
                        ✓ catch-all * → NotFoundPage
                        ✓ / → Navigate to /library
                        ✓ phase completion gate (build + lint + test + preview)
```

**Key dependency findings (new in v3.0):**
- Task 4.5 is the only task in 4.3–4.8 that is NOT standalone — it must follow Task 4.4. The plan's standalone convention is misleading.
- Tasks 4.3, 4.6, 4.7a–d, 4.8 are all genuinely parallel after 4.2.
- Task 4.9 is the integration gate for all of 4.3–4.8. Since 4.5 must follow 4.4, the critical path is: `4.1 → 4.2 → 4.4 → 4.5 → 4.9`.

---

## 6. Security Assessment

**CSRF:** Correctly implemented in v3.0. `bootstrapCsrf()` runs before any mutation. However, CI-10 reveals the failure path is silently broken — if the server is unreachable, the error is swallowed and the app renders. This is a denial-of-availability (server appears healthy to the user) rather than a CSRF security gap, but it should be fixed for correctness.

**XSS (EXIF rendering):** Correctly specified. React text nodes only. ESLint `react/no-danger` now configured (MI-17 fix). Defense-in-depth from Phase 3 Jsoup sanitization + React text nodes + ESLint rule is the correct layered approach.

**Session security:** `isHydrating` initial state gap (MI-19) is low-risk in practice (synchronous module load), but should be made explicit in the store definition to prevent future refactoring from introducing a window.

**Saved searches (localStorage):** Correctly keyed by user ID (`saved_searches_${userId}`). Tests for cross-user isolation are specified. Values contain only query terms — no sensitive data.

**Pre-signed URL scope:** Both `thumbnailUrl` (15min TTL, staleTime 10min on list query) and `originalUrl` (15min TTL, staleTime 10min on photo query) are now specified. Pre-signed URL generation happens server-side — correct.

**Zero trust alignment:** All routes default to protected via `ProtectedRoute`. Session hydration uses the server session cookie (HttpOnly) as the source of truth — Zustand holds only non-sensitive profile data. The 401 handler clears local state and forces re-authentication — correct. The CSRF error path needs the CI-10 fix to avoid silently failing open.

---

## 7. Final Recommendation

**Approve with changes.**

v3.0 is close to implementation-ready. One blocking structural bug (CI-10) and one high-risk version pin gap (CI-11) must be resolved before implementation begins. The incomplete TDD stubs (CI-12) must be fully specified to prevent hollow test coverage from undermining the quality guarantees that prior reviews were designed to enforce.

| Priority | Issue | Fix |
|----------|-------|-----|
| **Blocking** | CI-10: `.finally()` overwrites CSRF error | Replace promise chain with `async/await`; CSRF failure path returns before render |
| **High** | CI-11: `@tanstack/react-query` and `zustand` unpinned | Pin to exact versions; update `useInfiniteQuery` for TQ v5 if targeting v5 (`initialPageParam: 0`) |
| **High** | CI-12: TDD stubs in 4.7a, 4.7b, 4.7d, 4.8 GPS toggle | Add MSW handlers + `expect()` assertions to all remaining stubs |
| **Medium** | MI-21: Keyword-photo assignment unspecified | Clarify endpoint and UI model; add type to `types.ts` |
| **Medium** | MI-22: SearchPage pagination unspecified | Specify `useInfiniteQuery` or page controls; include search term in `queryKey` |
| **Medium** | MI-23: Stale status on page refresh | Add recovery polling for non-terminal photo status in `LibraryPage` or `PhotoCard` |
| **Medium** | MI-24: Parallelization claim wrong for Task 4.5 | Amend convention note: 4.5 must follow 4.4; all others are parallel |
| **Low** | MI-18: `PAGE_SIZE=50` hardcoded twice | Extract `const PAGE_SIZE = 50` |
| **Low** | MI-19: `isHydrating` initial state not in store | Set `isHydrating: true` in `create()` call |
| **Low** | MI-20: `range.endIndex` origin unclear | Specify `const range = rowVirtualizer.range` |
