# Phase 4 Critical Implementation Review — v5

**Plan reviewed:** `docs/plans/2026-02-25-saas-conversion-phase-4.md` (v5.0 — 2026-03-06)
**Previous reviews:** `...-critical-review-1.md` through `...-critical-review-4.md`
**Date:** 2026-03-06
**Reviewer:** Senior Staff Engineer (critical-implementation-review skill)

---

## Scope Note

All issues from reviews v1–v4 (CI-1 through CI-17, MI-1 through MI-30) are resolved in v5.0 per the changelog. The `camelizeKeys` transformer, filled test stubs, `REPLACE_ME` version sentinels, `hydrateSession()` extraction, `<Navigate replace />`, and fake-timer cleanup are all correctly applied. This review focuses exclusively on new findings in the v5.0 plan text, verified against the actual Phase 3 codebase.

---

## 1. Overall Assessment

v5.0 is a substantial improvement — the `camelizeKeys` transformer was the right architectural choice for CI-15, and the comprehensive test bodies for CI-13/CI-14 are well-constructed. The snake_case comments in 4.7d's TrashPage tests correctly document the wire-format-vs-component-interface distinction.

**However, codebase verification reveals five correctness issues that would cause runtime failures:**

1. **Every `id` field in the TypeScript types is `number`, but the backend uses `UUID` (serialised as a string)** — the entire ID model is wrong.
2. **`apiFetch` unconditionally calls `res.json()` on every 2xx response** — all DELETE mutations (204 No Content) crash with a JSON parse error.
3. **`apiFetch`'s header merge is silently overwritten by the `...options` spread** — the CSRF token is dropped whenever a caller passes custom headers, causing 403 on mutations.
4. **The login endpoint returns `{ "message": "Login successful" }`, not a user object** — the `useAuth.login()` test mock and the real API disagree on the response shape.
5. **`GET /api/photos/{id}/status` returns `{ "processing_status": "..." }`, but all 8 upload tests mock `{ status: "..." }`** — the polling hook will check the wrong field at runtime.

These are all verifiable against the Phase 3 codebase and cannot be worked around at implementation time without plan changes.

---

## 2. Critical Issues

### CI-18: All TypeScript `id` Fields Are `number` — Backend Uses `UUID` (String)

**Description:** The `Photo`, `User`, `Album`, `Keyword`, and `ShareToken` TypeScript interfaces all define `id: number`:

```typescript
// frontend/src/api/types.ts (Task 4.2 Step 2)
export interface Photo {
  id: number;
  // ...
}
export interface User {
  id: number;
  // ...
}
```

The Phase 3 backend uses `UUID` for all entity primary keys. `PhotoResponse.java` is:

```java
public record PhotoResponse(
    UUID id,           // ← Jackson serialises as "550e8400-e29b-41d4-a716-446655440000"
    String filename,
    // ...
)
```

The plan's own prerequisite block confirms this:

> `record UserResponse(UUID id, String email, @JsonProperty("show_gps") boolean showGps) {}`

Jackson serialises `UUID` as a JSON string, not a number. Every `photo.id` in the frontend will be a string at runtime but typed as `number`. This breaks:

- **Route parameters:** `/photo/:id` produces string UUIDs, but `PhotoPage` would parse them as numbers
- **Comparison logic:** `photo.id === parseInt(id)` fails — `parseInt("550e8400-...")` returns `550`
- **Test mocks:** All mocks use `id: 1`, `id: 2`, etc. — integers, not UUIDs
- **MSW route paths:** `http.get('/api/photos/1', ...)` — real URLs use UUIDs
- **Query keys:** `['photo', id]` — type inconsistency between route param (string) and stored value (typed as number)

**Why it matters:** This is a systemic type-safety failure that affects every entity reference in the frontend. TypeScript's type system provides false confidence — the code compiles but fails at runtime for every API call.

**Fix:** Change all `id` fields from `number` to `string` across all TypeScript interfaces:

```typescript
export interface Photo {
  id: string;  // UUID
  // ...
}
export interface User {
  id: string;  // UUID
  // ...
}
export interface Album {
  id: string;
  // ...
}
export interface Keyword {
  id: string;
  parentId: string | null;
  children: Keyword[];
}
export interface ShareToken {
  token: string;
  photoId: string;
  // ...
}
```

Update all test mocks to use UUID strings:

```typescript
const MOCK_UUID = '550e8400-e29b-41d4-a716-446655440000';
mockPhoto({ id: MOCK_UUID })
http.get(`/api/photos/${MOCK_UUID}`, ...)
```

Update `SearchResult.page` to remain `number` (it's a page index, not an entity ID).

Update the `SAVED_SEARCHES_KEY` function — it currently uses `saved_searches_${userId}` where `userId` is a number. With UUID strings, this becomes `saved_searches_550e8400-...` which is valid but long. Consider a hash or keep the full UUID.

---

### CI-19: `apiFetch` Crashes on 204 No Content — Every DELETE Mutation Fails

**Description:** The `apiFetch` wrapper unconditionally calls `res.json()` on every successful response:

```typescript
export async function apiFetch<T>(url: string, options?: RequestInit): Promise<T> {
  // ...
  if (!res.ok) throw new ApiError(res.status, await res.text());
  const json = await res.json();     // ← crashes on 204 No Content
  return camelizeKeys(json) as T;
}
```

HTTP 204 is a 2xx status (`res.ok === true`), so `apiFetch` doesn't throw. But the body is empty — `res.json()` throws `SyntaxError: Unexpected end of JSON input`.

The following endpoints return 204 No Content:
- `DELETE /api/keywords/{id}` (Task 4.7a)
- `DELETE /api/photos/{id}/keywords/{keywordId}` (Task 4.6)
- `DELETE /api/albums/{albumId}/photos/{photoId}` (Task 4.7b)

The test mocks correctly return `new HttpResponse(null, { status: 204 })`, but the `apiFetch` wrapper crashes before the mutation can complete. The test passes only because the MSW handler's `deleteCalled` flag is set before `res.json()` is called — the assertion checks the flag, not the mutation's success state. In production, every delete shows an error to the user.

**Why it matters:** Three feature pages (Keywords, Photo detail, Albums) have non-functional delete operations. The tests give a false green — they check that the request was sent, not that the response was processed.

**Fix:** Guard `res.json()` against empty responses:

```typescript
export async function apiFetch<T>(url: string, options?: RequestInit): Promise<T> {
  // ...
  if (!res.ok) throw new ApiError(res.status, await res.text());
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return undefined as T;
  }
  const json = await res.json();
  return camelizeKeys(json) as T;
}
```

Also update the delete mutation tests to assert on mutation completion state, not just the request flag:

```typescript
test('delete keyword calls DELETE /api/keywords/{id}', async () => {
  // ... existing setup ...
  await userEvent.click(await screen.findByRole('button', { name: /delete animals/i }));
  await waitFor(() => expect(deleteCalled).toBe(true));
  // Also verify: no error state rendered, keyword removed from list
  expect(screen.queryByText('Animals')).not.toBeInTheDocument();
});
```

---

### CI-20: `apiFetch` Header Merge Overwritten by `...options` Spread — CSRF Token Silently Dropped

**Description:** The `apiFetch` function merges headers then spreads the full options object:

```typescript
const res = await fetch(`${base}${url}`, {
  credentials: 'include',
  headers: { 'X-XSRF-TOKEN': csrfToken, ...options?.headers },   // ① merge CSRF + caller headers
  ...options,                                                      // ② overwrites headers if options.headers exists
});
```

JavaScript object literal evaluation is left-to-right — later properties overwrite earlier ones with the same key. When a caller passes `options.headers` (e.g., `{ headers: { 'Content-Type': 'application/json' }, method: 'PATCH', body: '...' }`), step ② replaces the merged headers from step ① with just `{ 'Content-Type': 'application/json' }`. The `X-XSRF-TOKEN` header is silently dropped.

This affects any mutation that sends a JSON body — the GPS toggle (`PATCH /api/users/me`), keyword create/edit (`POST/PUT /api/keywords`), and any future endpoint needing `Content-Type: application/json`.

**Why it matters:** CSRF protection is silently bypassed for specific mutations. The Spring Security CSRF filter rejects the request with 403, but the error looks like a generic auth failure — very difficult to debug.

**Fix:** Reverse the spread order so `credentials` and the merged `headers` always win:

```typescript
const res = await fetch(`${base}${url}`, {
  ...options,
  credentials: 'include',
  headers: {
    'X-XSRF-TOKEN': csrfToken,
    ...(options?.headers instanceof Headers
      ? Object.fromEntries(options.headers.entries())
      : options?.headers),
  },
});
```

This ensures `credentials: 'include'` cannot be overridden and the CSRF token is always present alongside any caller-provided headers.

---

### CI-21: Login Endpoint Returns `{ message: "..." }` — Test Mock Returns User Object

**Description:** The `useAuth.login()` test mocks the login endpoint returning a user object:

```typescript
test('login sets authenticated state', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ id: 1, email: 'a@b.com', showGps: false })));
  // ...
  expect(useAuthStore.getState().user).not.toBeNull();
});
```

The actual `AuthController.login()` endpoint returns:

```java
return ResponseEntity.ok(Map.of("message", "Login successful"));
```

The session is established via an HttpOnly cookie — the user object is never in the login response body. If `login()` sets `useAuthStore.user` from the login response, the store would contain `{ message: "Login successful" }` as the "user" — not a `User` object.

**Why it matters:** `useAuthStore.user.email`, `user.showGps`, and `user.id` are all `undefined` at runtime, breaking the email display, GPS toggle, saved search keying, and any other code that reads user properties.

**Fix:** The `login()` function must perform a two-step flow:

```typescript
// In useAuth hook
async function login(credentials: { email: string; password: string }) {
  await apiFetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(credentials),
  });
  // Login succeeded (session cookie set) — now fetch the user profile
  const user = await fetchCurrentUser();
  if (user) useAuthStore.getState().setAuth(user);
}
```

Update the test to mock both endpoints:

```typescript
test('login sets authenticated state', async () => {
  server.use(
    http.post('/api/auth/login', () => HttpResponse.json({ message: 'Login successful' })),
    http.get('/api/users/me', () =>
      HttpResponse.json({ id: '550e8400-...', email: 'a@b.com', show_gps: false })),
  );
  // ...
  expect(useAuthStore.getState().user?.email).toBe('a@b.com');
});
```

---

### CI-22: Status Polling Field Name Mismatch — `status` (Mocks) vs `processing_status` (API)

**Description:** All 8 Task 4.5 upload polling tests mock the status endpoint with a `status` key:

```typescript
http.get('/api/photos/1/status', () => HttpResponse.json({ status: 'done' }))
```

The actual `PhotoController.getPhotoStatus()` endpoint returns:

```java
return ResponseEntity.ok(Map.of(
    "id", photo.getId().toString(),
    "processing_status", photo.getProcessingStatus().name()
));
```

After `camelizeKeys` in `apiFetch`, the frontend receives `{ id: "...", processingStatus: "done" }`. But the test mocks deliver `{ status: "done" }` — `camelizeKeys` doesn't transform `status` (no underscores), so the mock response stays as `{ status: "done" }`.

The `useUpload` hook implementation must check one field name — either `status` (works in tests, fails in production) or `processingStatus` (works in production, fails in tests). There is no field name that works in both.

**Why it matters:** Upload polling — the most complex client-side logic in Phase 4 — either works in tests or in production, but not both. Whichever field name the implementor chooses, 8 tests are either false-green or false-red.

**Fix:** Update all 8 test mocks to use the real API wire format (snake_case):

```typescript
http.get('/api/photos/1/status', () =>
  HttpResponse.json({ id: '550e8400-...', processing_status: 'done' }))
```

After `camelizeKeys`, the component receives `{ id: '...', processingStatus: 'done' }`. The `useUpload` hook checks `response.processingStatus`. Both tests and production use the same field name.

Alternatively, add a Phase 3 prerequisite to simplify the status endpoint response:

> Modify `PhotoController.getPhotoStatus()` to return `{ "status": "done" }` instead of `{ "processing_status": "..." }` — a dedicated status endpoint does not need the `processing_` prefix used in the full photo response.

---

## 3. Minor Issues & Improvements

### MI-31: 13+ npm Packages Unpinned Despite Plan Convention

**Description:** The plan convention (line 136) states: *"Do not use `@latest` in any `npx` or `npm install` command. Pin all versions explicitly."* The CI-16 fix applied `REPLACE_ME` sentinels to TanStack Query, TanStack Virtual, and Zustand. All other packages remain unpinned:

```bash
# Task 4.1 Step 1 — unpinned:
npm install react-dropzone
npm install -D tailwindcss postcss autoprefixer

# Task 4.1 Step 2 — unpinned:
npm install -D eslint @typescript-eslint/parser @typescript-eslint/eslint-plugin eslint-plugin-react

# Task 4.1 testing deps — unpinned (except msw):
npm install -D @testing-library/react @testing-library/user-event vitest jsdom @vitest/coverage-v8
```

That's 13 packages installed without version pins. `react-router-dom@6` is a range (`>=6.0.0 <7.0.0`), not a pin.

**Fix:** Apply the same `REPLACE_ME` sentinel pattern used for TanStack/Zustand to all packages, or add version-lookup comments with links to each package's release page.

---

### MI-32: SettingsPage Tests 1–3 Missing Mock Setup — Comments, Not Code

**Description:** Task 4.8 tests 1–3 have assertion code but only comments for mock setup:

```typescript
test('renders loading skeleton while quota is undefined', () => {
  // mock useQuery returning { isLoading: true, data: undefined }    ← comment, not code
  render(<SettingsPage />);
  expect(screen.getByTestId('quota-skeleton')).toBeInTheDocument();
});
```

Without a `vi.mock()` or MSW handler, `useQuery` inside `SettingsPage` fails because there is no `QueryClientProvider` (no `wrapper` passed to `render`). The test crashes instead of passing vacuously, so this is not a hollow-stub issue — but the plan doesn't provide the actual mock code, leaving the implementation ambiguous.

**Fix:** Either:
- Convert to MSW-based tests with `QueryClientWrapper` (consistent with tests 4–5 in the same task):
  ```typescript
  test('renders loading skeleton while quota is loading', () => {
    server.use(http.get('/api/quota', async () => {
      await delay('infinite');  // MSW never responds → isLoading stays true
      return HttpResponse.json({});
    }));
    render(<SettingsPage />, { wrapper: QueryClientWrapper });
    expect(screen.getByTestId('quota-skeleton')).toBeInTheDocument();
  });
  ```
- Or provide explicit `vi.mock()` code for the module-mocking approach.

---

### MI-33: Task 4.5 Test Mocks Use camelCase Keys — Inconsistent with Wire Format

**Description:** Task 4.5 mocks return camelCase response bodies:

```typescript
HttpResponse.json({ id: 1, status: 'pending' })             // Task 4.5
HttpResponse.json({ status: 'failed', failureReason: '...' })  // Task 4.5
```

Task 4.7d (TrashPage) correctly uses snake_case to match the real API wire format:

```typescript
{ id: 1, filename: 'old.jpg', deleted_at: '2026-03-01T10:00:00Z' }  // Task 4.7d
```

Since MSW intercepts at the `fetch` level and `apiFetch` runs `camelizeKeys` on the response, mocks should use the same format as the real API (snake_case for fields with `@JsonProperty`). The Task 4.5 mocks work by accident — `camelizeKeys` is a no-op on keys without underscores — but they misrepresent the API contract and don't test the transform path.

**Fix:** Use snake_case in test mocks to match the real API, and document the convention explicitly: *"MSW mocks return raw API wire format (snake_case where applicable). The `camelizeKeys` transform in `apiFetch` converts them before component code sees them."*

---

### MI-34: `mockPhoto`, `mockUser`, `mockMetadata` Utilities Never Defined

**Description:** At least 15 tests reference `mockPhoto()`, `mockUser`, `mockMetadata`, and `mockMetadataWithGps` as test utilities. None are defined anywhere in the plan. The `mockPhoto` function accepts partial overrides (e.g., `mockPhoto({ id: 1, status: 'processing' })`) which implies a factory function with defaults — but no defaults are specified.

**Fix:** Add a test utilities file to Task 4.1 or 4.2:

```typescript
// frontend/src/test/factories.ts
export function mockPhoto(overrides: Partial<Photo> = {}): Photo {
  return {
    id: '550e8400-e29b-41d4-a716-446655440000',
    filename: 'test.jpg',
    thumbnailUrl: 'https://minio/thumb/test.jpg',
    originalUrl: 'https://minio/original/test.jpg',
    status: 'done',
    failureReason: null,
    caption: null,
    title: null,
    description: null,
    createdAt: '2026-01-01T00:00:00Z',
    deletedAt: null,
    ...overrides,
  };
}

export const mockUser: User = {
  id: '660e8400-e29b-41d4-a716-446655440000',
  email: 'test@example.com',
  showGps: false,
};
```

---

### MI-35: Request Body camelCase vs Backend `@JsonProperty` snake_case

**Description:** `apiFetch` transforms responses (snake_case → camelCase via `camelizeKeys`) but does NOT transform request bodies (camelCase → snake_case). The GPS toggle test sends:

```typescript
expect(capturedBody).toEqual({ showGps: true });
```

The plan specifies `UserResponse` with `@JsonProperty("show_gps")`. If the PATCH request DTO follows the same pattern, Jackson expects `show_gps` in the request body and silently ignores `showGps`. The toggle appears to succeed (200 response) but the value isn't updated.

Similarly, `PhotoResponse` fields like `processing_status`, `deleted_at`, etc. use `@JsonProperty` annotations. Any future PUT/PATCH endpoint that reuses these DTOs would have the same issue.

**Fix:** Document the convention explicitly — one of:

1. **Request DTOs must NOT use `@JsonProperty`** — accept the default camelCase field names from the frontend. This means response DTOs and request DTOs may use different naming for the same field.
2. **Add a `snakeifyKeys` transform** to `apiFetch` for request bodies with `Content-Type: application/json`.
3. **Use `@JsonAlias`** on backend DTOs to accept both formats: `@JsonProperty("show_gps") @JsonAlias("showGps")`.

Option 3 is the safest — it makes the backend accept both formats without breaking existing snake_case consumers.

---

### MI-36: `camelizeKeys` Recursively Transforms All Nested Object Keys

**Description:** The `camelizeKeys` function recursively transforms every key in every nested object. This includes the `exifData: Record<string, string>` map inside `PhotoMetadata`, whose keys are EXIF tag names like `"ISOSpeedRatings"`, `"FocalLength"`, `"GPSLatitude"`.

Currently safe: these EXIF tag names use PascalCase (no `_[a-z]` pattern), so `toCamelCase` is a no-op. However, if the metadata extractor or a future data source stores keys with underscores (e.g., `"gps_latitude"`, `"color_space"`), those would be silently mangled to `"gpsLatitude"`, `"colorSpace"` — changing the display label in the MetadataPanel.

**Fix:** Consider excluding known data-payload fields from the recursive transform:

```typescript
export function camelizeKeys(obj: unknown, depth = 0, maxDepth = 3): unknown {
  if (depth >= maxDepth) return obj;  // stop recursing into nested data payloads
  // ... existing logic with depth + 1
}
```

Or document the assumption: *"EXIF keys are PascalCase (from metadata-extractor). If a future data source uses snake_case keys, update `camelizeKeys` to skip the `exifData` field."*

---

## 4. Questions for Clarification

**Q16:** Is the `UserResponse` DTO intended to use `@JsonProperty("show_gps")` for the PATCH request body as well as the GET response? If yes, should the frontend send `show_gps` (adding a request-body transform), or should the backend accept `showGps` via `@JsonAlias`?

**Q17:** Should the `GET /api/photos/{id}/status` endpoint be simplified to return `{ "status": "done" }` instead of `{ "processing_status": "done", "id": "..." }`? The `id` is redundant (the caller already knows it from the URL), and `status` avoids the naming-transform issue.

**Q18:** Are `Album.id` and `Keyword.id` also UUIDs in the backend? The `AlbumController` and `KeywordController` use `UUID` in path parameters, confirming yes — but the TypeScript interfaces use `number`. Confirm this applies to all entity types.

---

## 5. Updated Dependency Map (v5.0)

All v4.0 fixes confirmed applied. New findings annotated `[NEW]`.

```
4.1 (scaffold)
  ✓ Vite 6.3.5 pinned
  ✓ MSW v2 @ 2.7.3 pinned
  ✓ shadcn-ui @ 0.9.4 pinned
  ✓ ESLint react/no-danger configured
  ✓ Vite proxy configured
  ✓ REPLACE_ME sentinels for TQ/TanStack Virtual/Zustand (CI-16 fixed)
  [MI-31: 13 other packages unpinned — react-dropzone, tailwindcss, vitest, etc.]
  [MI-34: mockPhoto/mockUser/mockMetadata test utilities never defined]
  └── 4.2 (API client + auth store + ProtectedRoute + API types)
        ✓ bootstrapCsrf() implemented
        ✓ async/await init() — CSRF failure aborts render (CI-10 fixed)
        ✓ hydrateSession() exported from client.ts (MI-26 fixed)
        ✓ isHydrating: true in store create() (MI-19 fixed)
        ✓ QueryCache + MutationCache 401 handlers with full test bodies (CI-14 fixed)
        ✓ camelizeKeys transformer (CI-15 fixed)
        ✓ Full API type definitions
        [CI-18: ALL id fields are number — backend uses UUID (string)]
        [CI-19: apiFetch crashes on 204 No Content — res.json() on empty body]
        [CI-20: apiFetch header spread overwrites CSRF token when caller passes headers]
        [CI-21: login endpoint returns { message }, not user — test mock wrong]
        [MI-35: request bodies not transformed camelCase→snake_case]
        [MI-36: camelizeKeys recursively transforms exifData keys]
        │
        ├── 4.3 (LoginPage, RegisterPage)    [can parallel 4.6, 4.7a–d, 4.8]
        │       ✓ Full test bodies including redirect (CI-14 fixed)
        │       ✓ ?verified=true banner
        │       ✓ 12-char password minimum
        │       [CI-21: login() must call fetchCurrentUser() after POST — needs 2 mocks]
        │
        ├── 4.4 (LibraryPage + PhotoGrid + PhotoCard)    [can parallel 4.3, 4.6, 4.7a–d, 4.8]
        │       ✓ useInfiniteQuery (TQ v5 API)
        │       ✓ PAGE_SIZE from api/constants.ts (MI-28 fixed)
        │       ✓ afterEach vi.useRealTimers() (MI-29 fixed)
        │       ✓ refetchInterval recovery polling
        │       [MI-32: 3 SettingsPage-style comment-only mocks in PhotoGrid tests (lines 613-633)]
        │
        │       └── 4.5 (UploadDropzone + useUpload)    [SEQUENTIAL — must follow 4.4]
        │               ✓ All 8 test stubs filled (CI-13 fixed)
        │               ✓ afterEach vi.useRealTimers()
        │               [CI-22: test mocks use { status } but API returns { processing_status }]
        │               [MI-33: mocks use camelCase but API returns snake_case]
        │
        ├── 4.6 (PhotoPage + MetadataPanel)    [can parallel 4.3, 4.7a–d, 4.8]
        │       ✓ keyword-photo assignment (MI-21 fixed)
        │       ✓ mockPhoto({ id: 1 }) numeric (MI-27 fixed)
        │       ✓ originalUrl staleTime 55min / gcTime 60min
        │       [CI-18: mock IDs are integers — should be UUID strings]
        │       [CI-19: DELETE /photos/{id}/keywords/{kid} returns 204 → apiFetch crash]
        │
        ├── 4.7a (KeywordsPage)    [can parallel 4.3, 4.4, 4.6, 4.7b–d, 4.8]
        │       ✓ Full test bodies (CI-12 fixed)
        │       [CI-19: DELETE /keywords/{id} returns 204 → apiFetch crash]
        │
        ├── 4.7b (AlbumsPage)    [can parallel 4.3, 4.4, 4.6, 4.7a/c/d, 4.8]
        │       ✓ Full test bodies (CI-12 fixed)
        │       [CI-19: DELETE /albums/{id}/photos/{pid} returns 204 → apiFetch crash]
        │
        ├── 4.7c (SearchPage)    [can parallel 4.3, 4.4, 4.6, 4.7a/b/d, 4.8]
        │       ✓ Full test bodies (CI-14 fixed)
        │       ✓ PAGE_SIZE from api/constants.ts (MI-28 fixed)
        │
        ├── 4.7d (TrashPage)    [can parallel 4.3, 4.4, 4.6, 4.7a–c, 4.8]
        │       ✓ snake_case wire format documented (CI-15 fixed)
        │       ✓ Full test bodies
        │
        └── 4.8 (SettingsPage)    [can parallel 4.3, 4.4, 4.6, 4.7a–d]
                ✓ GPS toggle test with MSW handler (CI-12 fixed)
                ✓ usedBytes floor guard test fixed (MI-25 fixed)
                [MI-32: tests 1–3 missing mock setup (comments, not code)]
                [MI-35: PATCH /api/users/me sends { showGps } but backend may expect { show_gps }]
                └── 4.9 (Router — integration gate)
                        ✓ <Navigate to="/library" replace /> (CI-17 fixed)
                        ✓ Back-button smoke test
                        ✓ catch-all * → NotFoundPage
```

---

## 6. Security Assessment

**CSRF:** The `apiFetch` correctly reads the CSRF cookie and adds the header. However, CI-20 (header spread order) means the CSRF header is silently dropped whenever a caller passes custom headers. This is a **security regression** — any JSON-body mutation (keyword create, GPS toggle, etc.) that passes `Content-Type: application/json` loses CSRF protection. The server-side CSRF filter catches it (403), so this is a denial-of-functionality bug rather than a CSRF bypass — but it's still a security-adjacent issue.

**Session security:** The 401 handlers, `isHydrating` guard, and `ProtectedRoute` are all correctly specified. The login flow (CI-21) doesn't affect session security — the session cookie is still set correctly by Spring Security. The bug is in the user profile display, not authentication.

**XSS:** Defense-in-depth maintained. `camelizeKeys` does not introduce XSS risk — it transforms keys, not values. EXIF values remain text-node-rendered.

**Data exposure:** The UUID-vs-number mismatch (CI-18) is not a security issue — UUIDs are unguessable, and the backend enforces ownership checks regardless of ID format.

**Zero trust alignment:** All protected routes wrapped in `ProtectedRoute`. `credentials: 'include'` in `apiFetch`. But CI-20 could cause `credentials` to be overridden by `...options` if a caller passes `credentials: 'omit'` — the current spread order lets `...options` override `credentials`. The fix for CI-20 (put `...options` first) also fixes this.

---

## 7. Final Recommendation

**Major revisions needed.**

v5.0 resolved all v4 issues comprehensively. However, codebase verification against the actual Phase 3 implementation revealed five new correctness bugs — all verifiable against existing code, not speculative.

| Priority | Issue | Fix |
|----------|-------|-----|
| **Blocking** | CI-18: `id: number` but backend uses UUID | Change all `id` fields to `string`; update all test mocks to use UUID strings |
| **Blocking** | CI-19: `apiFetch` crashes on 204 No Content | Guard `res.json()` — return `undefined` on 204 or empty content-length |
| **Blocking** | CI-20: Header spread overwrites CSRF token | Reverse spread order: `{ ...options, credentials, headers: { csrf, ...options.headers } }` |
| **Blocking** | CI-21: Login returns `{ message }`, not user | Two-step login: POST login → GET /api/users/me; update test to mock both |
| **High** | CI-22: Status polling field name mismatch | Update mocks to `{ processing_status: '...' }` or simplify endpoint to `{ status }` |
| **Medium** | MI-31: 13 packages unpinned | Apply REPLACE_ME or pin with version-lookup comments |
| **Medium** | MI-32: SettingsPage tests 1–3 mock setup is comments | Add actual mock code (MSW or vi.mock) |
| **Medium** | MI-35: Request body camelCase vs @JsonProperty snake_case | Document convention; recommend `@JsonAlias` on backend |
| **Low** | MI-33: Task 4.5 mocks use camelCase (work by accident) | Use snake_case for API fidelity |
| **Low** | MI-34: Test utilities never defined | Add `factories.ts` with `mockPhoto`, `mockUser`, `mockMetadata` |
| **Low** | MI-36: `camelizeKeys` recursively transforms `exifData` keys | Document assumption or add depth guard |
