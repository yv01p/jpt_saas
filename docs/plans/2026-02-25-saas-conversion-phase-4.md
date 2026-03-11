# JPhotoTagger SaaS Conversion — Phase 4: React Frontend

> **Document Version:** v8.0 — 2026-03-10 (see [Change Log](#change-log) for revision history)
> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

### Task 4.0: Backend Prerequisites for Frontend

> **Why this is in Phase 4:** Phase 3 is already implemented and shipped. These backend additions are required by the frontend but were not anticipated during Phase 3. Rather than retroactively modifying Phase 3, they are scoped as a prerequisite task within Phase 4. All backend changes must be complete and tested before any frontend task begins.

> **Security requirements (SA4):**
> - **SA4-F1 — GPS filtering (BLOCK):** GPS data must be filtered server-side in `PhotoMetadataService` before the response is sent. Frontend DOM suppression is defence-in-depth only. See Step 6.
> - **SA4-F4 — Cookie attributes:** Verify the Spring Security config sets `SameSite=Strict` on the CSRF cookie (`repo.setCookieSameSite("Strict")`) and enables `ForwardedHeaderFilter` (or `server.forward-headers-strategy: framework`) so the `Secure` flag is set automatically when running behind a TLS-terminating reverse proxy. The session cookie (`JSESSIONID`) must also carry `Secure` and `SameSite=Strict`.
> - **SA4-F5 — Redis password:** `REDIS_PASSWORD` must be set in all environment profiles (dev, staging, prod) with no empty-string fallback. Use a secrets manager (Kubernetes Secrets, HashiCorp Vault) rather than plain env vars. The `application.yml` files for both `api` and `worker` use `${REDIS_PASSWORD}` (no default) — a missing env var causes a startup failure with a clear error, which is correct.
> - **SA4-F3 — Error response:** `api/src/main/resources/application.yml` must include `server.error.include-stacktrace: never` and `server.error.include-message: never` to prevent internal details from reaching clients. This is already applied to the source file.

**Files:**
- Create: `api/src/main/java/.../controller/UserController.java`
- Create: `api/src/main/java/.../dto/UserResponse.java`
- Create: `api/src/main/java/.../dto/UpdateUserRequest.java`
- Create: `api/src/main/resources/db/migration/V<next>__add_show_gps_to_users.sql`
- Modify: `api/src/main/java/.../entity/User.java` — add `showGps` field
- Modify: `api/src/main/java/.../dto/PhotoResponse.java` — add `thumbnailUrl` and `originalUrl` fields
- Modify: `api/src/main/java/.../controller/PhotoController.java` — generate pre-signed URLs for photo responses
- Modify: `api/src/main/java/.../service/PhotoMetadataService.java` — strip GPS fields when `showGps = false` (SA4-F1)

**Step 1: Add `showGps` column to `users` table**

Flyway migration:

```sql
ALTER TABLE users ADD COLUMN show_gps BOOLEAN NOT NULL DEFAULT FALSE;
```

Add corresponding field to `User.java`:

```java
@Column(name = "show_gps", nullable = false)
private boolean showGps = false;

public boolean isShowGps() { return showGps; }
public void setShowGps(boolean showGps) { this.showGps = showGps; }
```

**Step 2: Create `UserResponse` DTO**

```java
public record UserResponse(
    UUID id,
    String email,
    @JsonProperty("show_gps") boolean showGps,
    @JsonProperty("quota_bytes") long quotaBytes,
    @JsonProperty("used_bytes") long usedBytes
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getId(), user.getEmail(), user.isShowGps(),
            user.getQuotaBytes(), user.getUsedBytes());
    }
}
```

**Step 3: Create `UpdateUserRequest` DTO**

Request DTOs do NOT use `@JsonProperty` — they accept the default camelCase field names from the frontend. This is intentional: `apiFetch` applies `snakeifyKeys` to outgoing request bodies (see Task 4.2), converting `showGps` → `show_gps` on the wire. The backend DTO accepts the wire format field name directly via column mapping, not `@JsonProperty`.

Wait — this creates an inconsistency. Let me clarify the convention:

> **Convention — request body wire format:** `apiFetch` applies `snakeifyKeys` to all outgoing JSON request bodies. The wire format is snake_case in both directions (request and response). Backend request DTOs use `@JsonProperty` annotations matching the snake_case wire format, just like response DTOs.

```java
public record UpdateUserRequest(
    @JsonProperty("show_gps") Boolean showGps
) {}
```

**Step 4: Create `UserController`**

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUser(
            @AuthenticationPrincipal UUID userId,
            @RequestBody UpdateUserRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (request.showGps() != null) {
            user.setShowGps(request.showGps());
        }
        userRepository.save(user);
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
```

**Step 5: Add `thumbnailUrl` and `originalUrl` to `PhotoResponse`**

Modify `PhotoResponse.java` — add two new fields:

```java
public record PhotoResponse(
    UUID id,
    String filename,
    // ... existing fields ...
    @JsonProperty("processing_status") String processingStatus,
    @JsonProperty("thumbnail_url") String thumbnailUrl,
    @JsonProperty("original_url") String originalUrl
) { /* ... */ }
```

Modify `PhotoController` to inject `StorageService` and generate pre-signed URLs when constructing `PhotoResponse`. Thumbnail TTL: 15 minutes. Original TTL: 1 hour.

**Step 6: Add server-side GPS filtering to `PhotoMetadataService` (SA4-F1)**

**Security requirement:** GPS data must be filtered at the API boundary, not only at the frontend render layer. The frontend DOM suppression in MetadataPanel (Task 4.6) is defence-in-depth only — it does not prevent GPS coordinates from appearing in the network response visible via browser DevTools.

Modify `PhotoMetadataService.getMetadata(UUID userId, UUID photoId)`:

```java
// Retrieve the authenticated user's GPS preference
User user = userRepository.findById(userId)
    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

// Build metadata response
PhotoMetadataResponse response = buildMetadataResponse(photo);

// Strip GPS fields if the user has disabled GPS display
if (!user.isShowGps()) {
    response = response.withoutGps();  // removes gpsLatitude, gpsLongitude, and all "GPS:*" exifData keys
}
return response;
```

`PhotoMetadataResponse.withoutGps()` returns a new instance with `gpsLatitude` and `gpsLongitude` set to `null` and all keys matching `GPS:*` removed from `exifData`. The key prefix check must be case-insensitive to cover both `GPS:` and `Gps:` variants.

**Step 7: Write tests**

- `GET /api/users/me` returns authenticated user profile (200)
- `GET /api/users/me` returns 401 when unauthenticated
- `PATCH /api/users/me` with `{ "show_gps": true }` updates and returns updated user
- `GET /api/photos` response includes `thumbnail_url` and `original_url`
- `GET /api/photos/{id}/metadata` with `showGps=false` — response must not include `gpsLatitude`, `gpsLongitude`, or any `GPS:*` key in `exifData` (SA4-F1)
- `GET /api/photos/{id}/metadata` with `showGps=true` — GPS fields are present

**Step 8: Commit**

```bash
git commit -m "feat: UserController with GET/PATCH /api/users/me, show_gps migration, photo pre-signed URLs, server-side GPS filtering"
```

---

### Task 4.1: Vite + React Project Scaffold

**Files:**
- Create: `frontend/` directory with Vite + React 18 + TypeScript

**Step 1: Initialize project**

```bash
cd frontend
# Verify current stable Vite version at https://github.com/vitejs/vite/releases, then pin:
npm create vite@6.3.5 . -- --template react-ts
# Verify current stable patch versions before running:
# https://github.com/TanStack/query/releases
# https://github.com/TanStack/virtual/releases
# https://github.com/pmndrs/zustand/releases
# Before running, look up the current stable patch versions at the links above and replace
# each REPLACE_ME with the exact version (e.g. @5.67.2). Do NOT run with REPLACE_ME — npm
# will reject them immediately, forcing the implementor to pin before installing.
# Verify current stable versions before running:
# https://github.com/remix-run/react-router/releases
# https://github.com/react-dropzone/react-dropzone/releases
# https://github.com/tailwindlabs/tailwindcss/releases
# https://github.com/postcss/postcss/releases
# https://github.com/postcss/autoprefixer/releases
npm install react-router-dom@REPLACE_ME \
  @tanstack/react-query@REPLACE_ME \
  @tanstack/react-virtual@REPLACE_ME \
  zustand@REPLACE_ME \
  react-dropzone@REPLACE_ME
npm install -D tailwindcss@REPLACE_ME postcss@REPLACE_ME autoprefixer@REPLACE_ME
npx tailwindcss init -p
npx shadcn-ui@0.9.4 init   # Pin version — do NOT use @latest (CI-1 pattern: pinned per SA2-F3). Verify current stable at implementation time.
```

Also install testing dependencies:

```bash
# Verify current stable versions before running:
# https://github.com/testing-library/react-testing-library/releases
# https://github.com/testing-library/user-event/releases
# https://github.com/vitest-dev/vitest/releases
# https://github.com/nicolo-ribaudo/jest-light-runner (jsdom — use latest stable)
# https://github.com/nicolo-ribaudo/jest-light-runner (@vitest/coverage-v8 — matches vitest version)
npm install -D @testing-library/react@REPLACE_ME @testing-library/user-event@REPLACE_ME msw@2.7.3 vitest@REPLACE_ME jsdom@REPLACE_ME @vitest/coverage-v8@REPLACE_ME
```

Pin `msw` to `2.7.3` (or verify current stable MSW v2.x at implementation time). All test handlers use MSW v2 syntax throughout this phase — `http.post`, `HttpResponse.json` — never the MSW v1 `rest`/`req/res/ctx` API.

**Step 2: Configure Tailwind, shadcn/ui, Vite proxy, ESLint, and env files**

Add proxy to `vite.config.ts`:

```typescript
server: {
  proxy: {
    '/api':    { target: 'http://localhost:8080', changeOrigin: true },
    '/oauth2': { target: 'http://localhost:8080', changeOrigin: true },
    '/login':  { target: 'http://localhost:8080', changeOrigin: true },
    '/logout': { target: 'http://localhost:8080', changeOrigin: true },
  }
}
```

Create `frontend/.env.example`:
```
VITE_API_BASE_URL=https://api.yourdomain.com
```

Add `frontend/.env.development` and `frontend/.env*.local` to `.gitignore`. In development the Vite proxy handles routing — no `VITE_API_BASE_URL` needed locally.

Install ESLint:

```bash
# Verify current stable versions before running:
# https://github.com/eslint/eslint/releases
# https://github.com/typescript-eslint/typescript-eslint/releases
# https://github.com/jsx-eslint/eslint-plugin-react/releases
npm install -D eslint@REPLACE_ME @typescript-eslint/parser@REPLACE_ME @typescript-eslint/eslint-plugin@REPLACE_ME eslint-plugin-react@REPLACE_ME
```

Configure `eslint.config.js` (flat config — Vite 6 default) with:

```javascript
rules: {
  'react/no-danger': 'error',
}
```

This enforces the XSS safety requirement from Task 4.6 at the tooling level, not just as a convention.

**Step 3: Set up directory structure and scripts**

```
frontend/src/
├── api/           — API client, hooks, types, shared constants (constants.ts)
├── components/    — Reusable components
├── pages/         — Route pages
├── stores/        — Zustand stores
├── lib/           — Utilities
└── App.tsx        — Router setup
```

Add to `package.json` scripts:

```json
"scripts": {
  "typecheck": "tsc --noEmit",
  "lint":      "eslint src",
  "test":      "vitest run",
  "test:watch": "vitest",
  "coverage":  "vitest run --coverage"
}
```

Configure `vitest.config.ts` with `jsdom` environment and `globals: true`.

**Step 4: Verify scaffold**

```bash
npm run dev      # Dev server starts on port 5173
npm run build    # TypeScript compiles, Vite bundles successfully — catches compile errors early
npm run lint     # Zero ESLint errors (no-danger rule active)
npm run test     # Test runner configured (zero tests = OK at scaffold stage)
```

**Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: React frontend scaffold — Vite, TanStack Query, Zustand, shadcn, ESLint"
```

`frontend/package-lock.json` must be included in the commit for deterministic `npm ci` installs in CI.

> **Convention for this phase:** Do not use `@latest` in any `npx` or `npm install` command. Pin all versions explicitly.

---

### Task 4.2: API Client, Auth Store, Types, and ProtectedRoute

**Files:**
- Create: `frontend/src/api/client.ts`
- Create: `frontend/src/api/types.ts`
- Create: `frontend/src/api/hooks/useAuth.ts`
- Create: `frontend/src/stores/authStore.ts`
- Create: `frontend/src/components/ProtectedRoute.tsx`
- Modify: `frontend/src/main.tsx` — CSRF bootstrap + session hydration before React renders

> **Development convention:** Tasks 4.3, 4.6, 4.7a–d, and 4.8 are standalone component tasks developed and tested in isolation via direct component rendering — no router context required. **Task 4.5 must follow Task 4.4** — it modifies `LibraryPage.tsx` which is created in 4.4. The critical path through this phase is: `4.0 → 4.1 → 4.2 → 4.4 → 4.5 → 4.9`. Task 4.9 wires all pages together at the end of the phase.

> **Prerequisite — Task 4.0 must be complete:** The session hydration call (`fetchCurrentUser()`) requires `GET /api/users/me`, the GPS toggle requires `PATCH /api/users/me`, and the photo grid requires `thumbnail_url`/`original_url` in photo responses. All of these are created in Task 4.0.

> **Backend configuration requirement (MI-30) — CSRF cookie must be non-HttpOnly:** The `X-XSRF-TOKEN` fetch wrapper (Step 3) reads the `XSRF-TOKEN` cookie from JavaScript. This only works if the cookie is **not** marked `HttpOnly`. Use `CookieCsrfTokenRepository.withHttpOnlyFalse()` in the Spring Security config. The session cookie (`JSESSIONID`) must remain `HttpOnly`. This is the intended dual-cookie pattern: one HttpOnly session cookie (server-readable only) + one non-HttpOnly CSRF cookie (JS-readable only). If the CSRF cookie is accidentally set HttpOnly (e.g., by a security scanner recommendation), all POST/PUT/DELETE mutations silently fail with 403.
>
> **SA4-F4 — Cookie security attributes:** Also set `SameSite=Strict` on the CSRF cookie: `repo.setCookieSameSite("Strict")`. Enable `ForwardedHeaderFilter` (or `server.forward-headers-strategy: framework` in `application.yml`) so Spring detects HTTPS behind a reverse proxy and sets the `Secure` flag automatically on both cookies. Without this, cookies transmit in plaintext if the backend sees HTTP from the proxy. Verify `Secure` is present on all cookies in staging before any production deployment.

**Step 1: Write failing tests**

```typescript
// frontend/src/api/hooks/useAuth.test.ts
import { http, HttpResponse } from 'msw';

test('csrf bootstrap fetches /api/csrf before app renders', async () => {
  const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValueOnce(new Response());
  await bootstrapCsrf();
  expect(fetchSpy).toHaveBeenCalledWith('/api/csrf', { credentials: 'include' });
});

test('login sets authenticated state', async () => {
  server.use(
    http.post('/api/auth/login', () => HttpResponse.json({ message: 'Login successful' })),
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
  );
  const { result } = renderHook(() => useAuth(), { wrapper: QueryClientWrapper });
  await act(async () => result.current.login({ email: 'test@example.com', password: 'password' }));
  expect(useAuthStore.getState().user).not.toBeNull();
  expect(useAuthStore.getState().isAuthenticated).toBe(true);
  expect(useAuthStore.getState().user?.email).toBe('test@example.com');
});

test('logout clears authenticated state', async () => {
  useAuthStore.setState({ isAuthenticated: true, user: mockUser });
  const { result } = renderHook(() => useAuth(), { wrapper: QueryClientWrapper });
  await act(async () => result.current.logout());
  expect(useAuthStore.getState().isAuthenticated).toBe(false);
  expect(useAuthStore.getState().user).toBeNull();
});

test('401 response clears auth store and redirects to /login', async () => {
  const replaceSpy = vi.spyOn(window.location, 'replace').mockImplementation(() => {});
  useAuthStore.setState({ isAuthenticated: true, isHydrating: false, user: mockUser });
  server.use(http.get('/api/photos', () => new HttpResponse(null, { status: 401 })));
  const { result } = renderHook(
    () => useQuery({
      queryKey: ['photos'],
      queryFn: () => fetch('/api/photos').then(r => {
        if (!r.ok) throw new ApiError(r.status, '');
        return r.json();
      }),
    }),
    { wrapper: QueryClientWrapper }
  );
  await waitFor(() => expect(result.current.isError).toBe(true));
  expect(useAuthStore.getState().isAuthenticated).toBe(false);
  expect(replaceSpy).toHaveBeenCalledWith('/login');
});

test('401 on mutation clears auth store and redirects to /login', async () => {
  const replaceSpy = vi.spyOn(window.location, 'replace').mockImplementation(() => {});
  useAuthStore.setState({ isAuthenticated: true, isHydrating: false, user: mockUser });
  server.use(http.post('/api/photos', () => new HttpResponse(null, { status: 401 })));
  const { result } = renderHook(
    () => useMutation({
      mutationFn: () => fetch('/api/photos', { method: 'POST' }).then(r => {
        if (!r.ok) throw new ApiError(r.status, '');
        return r.json();
      }),
    }),
    { wrapper: QueryClientWrapper }
  );
  await act(async () => result.current.mutate());
  await waitFor(() => expect(result.current.isError).toBe(true));
  expect(useAuthStore.getState().isAuthenticated).toBe(false);
  expect(replaceSpy).toHaveBeenCalledWith('/login');
});

// Session hydration tests
test('page refresh with valid session restores auth store from GET /api/users/me', async () => {
  server.use(http.get('/api/users/me', () => HttpResponse.json(mockUserWire)));
  await hydrateSession();
  expect(useAuthStore.getState().isAuthenticated).toBe(true);
  expect(useAuthStore.getState().user?.email).toBe('a@b.com');
});

test('page refresh with no session leaves auth store unauthenticated', async () => {
  server.use(http.get('/api/users/me', () => new HttpResponse(null, { status: 401 })));
  await hydrateSession();
  expect(useAuthStore.getState().isAuthenticated).toBe(false);
});

// frontend/src/components/ProtectedRoute.test.tsx
test('ProtectedRoute renders spinner while isHydrating is true', () => {
  useAuthStore.setState({ isHydrating: true, isAuthenticated: false });
  render(<ProtectedRoute><div>secret</div></ProtectedRoute>, { wrapper: MemoryRouter });
  expect(screen.getByTestId('hydration-spinner')).toBeInTheDocument();
  expect(screen.queryByText('secret')).not.toBeInTheDocument();
});

test('unauthenticated user is redirected to /login', () => {
  useAuthStore.setState({ isHydrating: false, isAuthenticated: false });
  render(<ProtectedRoute><div>secret</div></ProtectedRoute>, { wrapper: MemoryRouter });
  expect(screen.queryByText('secret')).not.toBeInTheDocument();
});

test('authenticated user sees children', () => {
  useAuthStore.setState({ isHydrating: false, isAuthenticated: true, user: mockUser });
  render(<ProtectedRoute><div>secret</div></ProtectedRoute>, { wrapper: MemoryRouter });
  expect(screen.getByText('secret')).toBeInTheDocument();
});
```

**Step 2: Define API types**

```typescript
// frontend/src/api/types.ts

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export type ProcessingStatus = 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED';

export interface User {
  id: string;       // UUID
  email: string;
  showGps: boolean;
  quotaBytes: number;
  usedBytes: number;
}

export interface Photo {
  id: string;       // UUID
  filename: string;
  thumbnailUrl: string;
  originalUrl: string;
  processingStatus: ProcessingStatus;
  caption: string | null;
  title: string | null;
  description: string | null;
  sizeBytes: number;
  takenAt: string | null;
  uploadedAt: string;
  updatedAt: string | null;
  deletedAt: string | null;   // null for active photos; ISO-8601 string when soft-deleted
}

export interface PhotoMetadata {
  exifData: Record<string, string>;
  // GPS fields only present when showGps === true
  gpsLatitude?: number;
  gpsLongitude?: number;
}

export interface Album {
  id: string;       // UUID
  name: string;
  photoCount: number;
}

export interface Keyword {
  id: string;       // UUID
  name: string;
  parentId: string | null;
  children: Keyword[];
}

export interface ShareToken {
  token: string;
  photoId: string;  // UUID
  expiresAt: string | null;
}

export interface SearchResult {
  photos: Photo[];
  total: number;
  page: number;
}
```

All TanStack Query hooks must use these types as their generic parameter (e.g., `useQuery<Photo[]>(...)`). No `any` types in API hooks.

> **Note:** `QuotaInfo` was removed as a separate interface — quota fields (`quotaBytes`, `usedBytes`) are now part of `User`, returned by `GET /api/users/me`. The `Photo` interface uses `processingStatus` (not `status`) to match the backend `PhotoResponse` field name after `camelizeKeys` transforms `processing_status`. The `failureReason` field was removed — it does not exist in `PhotoResponse.java`.

**Test factories** — used by 15+ tests across Tasks 4.2–4.8. Defined here in Task 4.2 so they are available to all subsequent tasks:

```typescript
// frontend/src/test/factories.ts
// Factories return API wire format — the exact JSON the backend sends.
// MSW handlers use these directly. camelizeKeys in apiFetch handles the conversion
// before component code sees the data.

const MOCK_PHOTO_ID = '550e8400-e29b-41d4-a716-446655440000';
const MOCK_USER_ID = '660e8400-e29b-41d4-a716-446655440000';

export function mockPhoto(overrides: Record<string, unknown> = {}) {
  return {
    id: MOCK_PHOTO_ID,
    filename: 'test.jpg',
    caption: null,
    title: null,
    description: null,
    size_bytes: 1024,
    taken_at: null,
    uploaded_at: '2026-01-01T00:00:00Z',
    updated_at: null,
    deleted_at: null,
    processing_status: 'DONE',
    thumbnail_url: 'https://minio/thumb/test.jpg',
    original_url: 'https://minio/original/test.jpg',
    ...overrides,
  };
}

// Wire format — for MSW handlers
export const mockUserWire = {
  id: MOCK_USER_ID,
  email: 'test@example.com',
  show_gps: false,
  quota_bytes: 10737418240,
  used_bytes: 0,
};

// Post-transform — for useAuthStore.setState() calls (matches User interface)
export const mockUser: User = {
  id: MOCK_USER_ID,
  email: 'test@example.com',
  showGps: false,
  quotaBytes: 10737418240,
  usedBytes: 0,
};

export function mockMetadata() {
  return {
    exifData: { Artist: 'Test Photographer', FocalLength: '50mm' },
  };
}

export function mockMetadataWithGps() {
  return {
    exifData: { Artist: 'Test Photographer' },
    gpsLatitude: 48.8566,
    gpsLongitude: 2.3522,
  };
}

// Post-transform — for component tests that render <PhotoCard photo={...}> or <PhotoGrid photos={[...]}>
// directly. Components receive Photo (camelCase); passing mockPhoto() (snake_case) directly causes
// thumbnailUrl, uploadedAt, etc. to be undefined at render time (CI-24).
export function mockPhotoApp(overrides: Partial<Photo> = {}): Photo {
  return { ...(camelizeKeys(mockPhoto()) as Photo), ...overrides };
}
```

> **Convention — MSW mock wire format:** MSW mocks return raw API wire format (field names matching `@JsonProperty` annotations, e.g., `processing_status`, `deleted_at`, `thumbnail_url`). The `camelizeKeys` transform in `apiFetch` converts them to camelCase before component code sees them. This ensures tests exercise the same transform path as production. EXIF keys are PascalCase or may contain underscores (from metadata-extractor / ExifTool) — `camelizeKeys` does not recurse into `exifData` (SA4-F6). Add a test to verify this:
>
> ```typescript
> test('camelizeKeys does not transform keys inside exifData', () => {
>   const wire = { exif_data: { GPS_Altitude: '100 m', Artist: 'Test' } };
>   const result = camelizeKeys(wire) as Record<string, unknown>;
>   const exif = result['exifData'] as Record<string, unknown>;
>   expect(exif['GPS_Altitude']).toBe('100 m');  // underscore key preserved
>   expect(exif['Artist']).toBe('Test');
> });
> ```
>
> **Convention — component test mock format:** Use `mockPhotoApp()` (camelCase, post-transform) whenever passing a photo directly to a React component as a prop. Use `mockPhoto()` (snake_case, wire format) only in MSW handler responses.

**Step 3: Implement API client**

```typescript
// frontend/src/api/client.ts

export async function bootstrapCsrf(): Promise<void> {
  // GET /api/csrf triggers Spring Security's CsrfFilter to set the XSRF-TOKEN cookie.
  // This must complete before any POST/PUT/DELETE is made — including the login request.
  await fetch('/api/csrf', { credentials: 'include' });
}

// Recursively converts snake_case keys to camelCase.
// Applied to every API response so TypeScript interfaces can use camelCase throughout.
// Example: { deleted_at: '...', thumbnail_url: '...' } → { deletedAt: '...', thumbnailUrl: '...' }
// This is necessary because the Spring Boot backend serialises Java field names to snake_case JSON.
function toCamelCase(key: string): string {
  return key.replace(/_([a-z])/g, (_, letter) => letter.toUpperCase());
}

export function camelizeKeys(obj: unknown): unknown {
  if (Array.isArray(obj)) return obj.map(camelizeKeys);
  if (obj !== null && typeof obj === 'object') {
    return Object.fromEntries(
      Object.entries(obj as Record<string, unknown>).map(([k, v]) => {
        const newKey = toCamelCase(k);
        // SA4-F6: exifData keys are opaque metadata strings (PascalCase, may contain underscores).
        // Do not recurse into this dictionary — transformation corrupts keys like GPS_Altitude.
        const newVal = newKey === 'exifData' ? v : camelizeKeys(v);
        return [newKey, newVal];
      })
    );
  }
  return obj;
}

// Converts camelCase keys to snake_case for outgoing request bodies.
// Symmetric with camelizeKeys — ensures the API wire format is snake_case in both directions.
function toSnakeCase(key: string): string {
  return key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
}

export function snakeifyKeys(obj: unknown): unknown {
  if (Array.isArray(obj)) return obj.map(snakeifyKeys);
  if (obj !== null && typeof obj === 'object') {
    return Object.fromEntries(
      Object.entries(obj as Record<string, unknown>).map(([k, v]) => [
        toSnakeCase(k),
        snakeifyKeys(v),
      ])
    );
  }
  return obj;
}

// Central fetch wrapper — adds CSRF header, base URL, error handling, key transforms.
// All API hooks must use apiFetch instead of calling fetch() directly.
// - Outgoing JSON bodies are transformed via snakeifyKeys (camelCase → snake_case).
// - Incoming JSON responses are transformed via camelizeKeys (snake_case → camelCase).
// - The CSRF token is always included and cannot be overridden by caller options.
// - 204 No Content responses return undefined (no JSON body to parse).
export async function apiFetch<T>(url: string, options?: RequestInit): Promise<T> {
  const base = import.meta.env.VITE_API_BASE_URL ?? '';
  const csrfToken = document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1] ?? '';

  // Transform outgoing JSON body to snake_case
  let processedOptions = options;
  if (options?.body && typeof options.body === 'string') {
    try {
      const parsed = JSON.parse(options.body);
      processedOptions = { ...options, body: JSON.stringify(snakeifyKeys(parsed)) };
    } catch {
      // Not JSON — leave body unchanged (e.g., FormData for file uploads)
    }
  }

  const res = await fetch(`${base}${url}`, {
    ...processedOptions,
    credentials: 'include',
    headers: {
      'X-XSRF-TOKEN': csrfToken,
      ...(processedOptions?.headers instanceof Headers
        ? Object.fromEntries(processedOptions.headers.entries())
        : processedOptions?.headers),
    },
  });
  if (!res.ok) {
    // SA4-F3: truncate to prevent memory pressure and limit information leakage.
    // Backend is configured with server.error.include-stacktrace/message: never, but
    // this truncation provides defence-in-depth at the client layer.
    const body = await res.text();
    const safeMessage = body.length > 200 ? body.slice(0, 200) + '…' : body;
    throw new ApiError(res.status, safeMessage);
  }
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return undefined as T;
  }
  const json = await res.json();
  return camelizeKeys(json) as T;
}

export async function fetchCurrentUser(): Promise<User | null> {
  // Returns the authenticated user from the active session cookie, or null on 401.
  // Uses raw fetch (not apiFetch) because a 401 here is an expected non-error state.
  const res = await fetch('/api/users/me', { credentials: 'include' });
  if (res.status === 401) return null;
  if (!res.ok) throw new ApiError(res.status, 'Failed to fetch current user');
  return camelizeKeys(await res.json()) as User;
}

// Extracted from init() for testability. Called from main.tsx before React renders.
export async function hydrateSession(): Promise<void> {
  try {
    const user = await fetchCurrentUser();
    if (user) useAuthStore.getState().setAuth(user);
  } catch {
    // Network error — leave isAuthenticated false. ProtectedRoute will redirect to /login.
  } finally {
    useAuthStore.setState({ isHydrating: false });
  }
}
```

- `apiFetch` handles `X-XSRF-TOKEN` header (read from `XSRF-TOKEN` cookie), `VITE_API_BASE_URL` prefix, `ApiError` on non-2xx, `snakeifyKeys` on outgoing JSON bodies, `camelizeKeys` on incoming responses, and 204 No Content handling. All API hooks use `apiFetch`.
- In development the Vite proxy handles routing — `VITE_API_BASE_URL` is empty and `fetch('/api/...')` is proxied to `localhost:8080`.
- Throw `ApiError` on non-2xx responses (using the status code from the response)
- Configure `QueryClient` with `QueryCache` and `MutationCache` 401 handlers:

```typescript
const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error) => {
      if (error instanceof ApiError && error.status === 401) {
        useAuthStore.getState().clearAuth();
        window.location.replace('/login');  // imperative — outside React tree
      }
    },
  }),
  mutationCache: new MutationCache({
    onError: (error) => {
      if (error instanceof ApiError && error.status === 401) {
        useAuthStore.getState().clearAuth();
        window.location.replace('/login');
      }
    },
  }),
});
```

Both `QueryCache` and `MutationCache` require the handler — a session can expire during a mutation (e.g., saving metadata) as easily as during a query. `window.location.replace` is correct here (not `navigate`) because the handler fires outside the React component tree and also clears browser history so the back button does not return to a broken page.

**Step 4: Implement auth store and hooks**

The auth store has three fields:

```typescript
interface AuthState {
  isHydrating: boolean;     // true from app startup until GET /api/users/me resolves
  isAuthenticated: boolean;
  user: User | null;
  setAuth: (user: User) => void;
  clearAuth: () => void;
}
```

`isHydrating` is initialised to `true` in the store's `create()` call — not set externally from `main.tsx`. This makes the store self-documenting and eliminates a window between module load and an external `setState` call where the value could briefly be wrong:

```typescript
// frontend/src/stores/authStore.ts
const useAuthStore = create<AuthState>((set) => ({
  isHydrating: true,      // true from startup until GET /api/users/me resolves
  isAuthenticated: false,
  user: null,
  setAuth: (user) => set({ isAuthenticated: true, user }),
  clearAuth: () => set({ isAuthenticated: false, user: null }),
}));
```

`isHydrating` is set to `false` in `main.tsx`'s `finally` block (see Step 6) after hydration resolves, whether it succeeds with a user or with a 401. This ensures `ProtectedRoute` never redirects prematurely during startup.

The `useAuth` hook implements a **two-step login flow** — the login endpoint returns `{ message: "Login successful" }` (not a user object), so the hook must fetch the user profile separately:

```typescript
// frontend/src/api/hooks/useAuth.ts
function useAuth() {
  async function login(credentials: { email: string; password: string }) {
    // Step 1: POST login — establishes JWT cookie
    await apiFetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials),
    });
    // Step 2: Fetch user profile — JWT cookie now set
    const user = await fetchCurrentUser();
    if (user) useAuthStore.getState().setAuth(user);
  }

  async function logout() {
    await apiFetch('/api/auth/logout', { method: 'POST' });
    useAuthStore.getState().clearAuth();
  }

  return { login, logout };
}
```

**Step 5: Implement ProtectedRoute**

Behavior:
- `isHydrating === true` → render `<div data-testid="hydration-spinner">` (spinner) — hydration in progress; do not redirect yet
- `!isAuthenticated` → `<Navigate to="/login" replace state={{ from: location }} />`
- `isAuthenticated` → render `children`

The `state={{ from: location }}` preserves the attempted URL for post-login redirect. `LoginPage` must read `location.state?.from` and redirect there after successful login (see Task 4.3).

**Step 6: Update main.tsx — CSRF bootstrap + session hydration before render**

Use `async/await` for explicit, non-ambiguous control flow. The `return` after the CSRF error message prevents the React app from ever rendering when the server is unreachable — a promise-chain `.finally()` cannot do this because it runs regardless of rejection:

```typescript
// frontend/src/main.tsx
async function init() {
  // Step 1: CSRF bootstrap — abort and show error if server is unreachable.
  // The return here is critical: it prevents ReactDOM.render from being called.
  try {
    await bootstrapCsrf();
  } catch {
    document.getElementById('root')!.innerHTML =
      '<p style="padding:2rem">Unable to connect to the server. Please refresh the page.</p>';
    return;
  }

  // Step 2: Session hydration — defined in client.ts for testability.
  // isHydrating starts true in the store (see authStore.ts) — no setState needed here.
  await hydrateSession();

  // Step 3: Render — only reached if CSRF bootstrap succeeded.
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode><App /></React.StrictMode>
  );
}

init();
```

**Step 7: Run tests, verify pass**

**Step 8: Commit**

```bash
git commit -m "feat: API client with CSRF bootstrap, session hydration, 401 handler, auth store, ProtectedRoute, API types"
```

---

### Task 4.3: Auth Pages — Login & Register

**Files:**
- Create: `frontend/src/pages/LoginPage.tsx`
- Create: `frontend/src/pages/RegisterPage.tsx`

**Step 1: Write failing tests**

```typescript
test('LoginPage renders email/password fields and submit button', () => {
  render(<LoginPage />, { wrapper: MemoryRouter });
  expect(screen.getByRole('textbox', { name: /email/i })).toBeInTheDocument();
  expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
});

test('login form submits with correct credentials', async () => {
  // Use MSW to capture the request body — verifies the actual wire format sent to the API.
  // Do NOT use vi.mock('../api/hooks/useAuth') here: module mocking is file-scoped and would
  // break the redirect test below (which needs the real useAuth flow) and the render test above
  // (which needs useAuth() to return a non-null value). All tests in this file use MSW only (CI-25).
  let capturedBody: unknown;
  server.use(
    http.post('/api/auth/login', async ({ request }) => {
      capturedBody = await request.json();
      return HttpResponse.json({ message: 'Login successful' });
    }),
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
  );
  render(<LoginPage />, { wrapper: MemoryRouter });
  await userEvent.type(screen.getByLabelText(/email/i), 'a@b.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'password123');
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  await waitFor(() =>
    expect(capturedBody).toEqual({ email: 'a@b.com', password: 'password123' })
  );
});

test('LoginPage redirects to location.state.from after login', async () => {
  server.use(
    http.post('/api/auth/login', () => HttpResponse.json({ message: 'Login successful' })),
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
  );
  render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state: { from: '/photo/42' } }]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/photo/42" element={<div>photo page</div>} />
      </Routes>
    </MemoryRouter>
  );
  await userEvent.type(screen.getByLabelText(/email/i), 'a@b.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'password123');
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  expect(await screen.findByText('photo page')).toBeInTheDocument();
});

test('LoginPage renders "Your email has been verified" banner when ?verified=true', () => {
  render(
    <MemoryRouter initialEntries={['/login?verified=true']}>
      <LoginPage />
    </MemoryRouter>
  );
  expect(screen.getByText(/your email has been verified/i)).toBeInTheDocument();
});

test('RegisterPage enforces 12-character password minimum', async () => {
  render(<RegisterPage />, { wrapper: MemoryRouter });
  await userEvent.type(screen.getByLabelText(/password/i), 'short');
  await userEvent.click(screen.getByRole('button', { name: /register/i }));
  expect(screen.getByText(/at least 12 characters/i)).toBeInTheDocument();
});
```

**Step 2: Implement LoginPage**

Email/password form + Google/GitHub OAuth buttons. OAuth is a full server-side redirect flow — Spring Security handles it; no client IDs required in the frontend. After successful login, read `location.state?.from` and redirect there; fall back to `/library`.

Read `useSearchParams` for `?verified=true` and render a confirmation banner: `"Your email has been verified. Please sign in."` This banner is shown after Spring Boot redirects from the server-side email verification handler.

**Step 3: Implement RegisterPage**

Sign up form with 12-char password minimum + email verification prompt.

**Email verification flow:** Email verification is handled entirely server-side by Spring Boot. The verification link in the email targets `GET /api/verify-email?token=...` (or similar Spring endpoint). Spring validates the token, marks the account verified, and redirects to `/login?verified=true`. `LoginPage` reads this query param and renders the confirmation banner. No `/verify-email` frontend route is needed — the flow completes before the React app is involved.

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: login and registration pages"
```

---

### Task 4.4: Photo Grid — Library View

> **Prerequisite — Task 4.0 must be complete:** `thumbnailUrl` and `originalUrl` fields are added to `PhotoResponse` in Task 4.0.

**Files:**
- Create: `frontend/src/api/constants.ts` — shared pagination constant used by LibraryPage and SearchPage
- Create: `frontend/src/pages/LibraryPage.tsx`
- Create: `frontend/src/components/PhotoGrid.tsx`
- Create: `frontend/src/components/PhotoCard.tsx`

**Step 1: Write failing tests**

```typescript
// Required for useVirtualizer spy in scroll trigger tests (CI-23)
import * as ReactVirtual from '@tanstack/react-virtual';

test('PhotoGrid renders thumbnail images for mocked photo data', () => {
  // Use mockPhotoApp() — PhotoGrid accepts Photo (camelCase). mockPhoto() is snake_case wire
  // format and would cause thumbnailUrl to be undefined at render time (CI-24).
  const photos = [
    mockPhotoApp({ id: '550e8400-e29b-41d4-a716-446655440001' }),
    mockPhotoApp({ id: '550e8400-e29b-41d4-a716-446655440002' }),
  ];
  render(<PhotoGrid photos={photos} onLoadMore={() => {}} hasMore={false} />);
  expect(screen.getAllByRole('img')).toHaveLength(2);
});

test('PhotoGrid renders photos inside virtual row wrappers', () => {
  // JSDOM has no layout engine — container height is always 0, so asserting rendered count
  // < total is vacuously true (0 < 100). Instead verify TanStack Virtual is wired up:
  // the scroll container exists and virtual rows carry data-index attributes (CI-23).
  const photos = Array.from({ length: 100 }, (_, i) =>
    mockPhotoApp({ id: `550e8400-e29b-41d4-a716-${String(i).padStart(12, '0')}` })
  );
  render(<PhotoGrid photos={photos} onLoadMore={vi.fn()} hasMore={false} />);
  expect(screen.getByTestId('photo-grid-scroll-container')).toBeInTheDocument();
  // useVirtualizer assigns data-index to each virtual row element it renders
  const virtualRows = document.querySelectorAll('[data-index]');
  expect(virtualRows.length).toBeGreaterThan(0);
  expect(virtualRows.length).toBeLessThanOrEqual(photos.length);
});

test('thumbnailUrl is rendered as img src', () => {
  // mockPhotoApp() returns camelCase Photo matching component interface.
  // Passing mockPhoto() (snake_case) directly causes photo.thumbnailUrl to be undefined (CI-24).
  const photo = mockPhotoApp({ thumbnailUrl: 'https://minio/thumb/1.jpg' });
  render(<PhotoCard photo={photo} />);
  expect(screen.getByRole('img')).toHaveAttribute('src', 'https://minio/thumb/1.jpg');
});

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

test('fetches next page when virtual range reaches end of loaded photos', async () => {
  // useVirtualizer is mocked to return a controlled range — JSDOM has no layout engine so
  // scroll position cannot be driven through real DOM events (CI-23).
  // range.endIndex=0, photos.length=1: 0 >= (1-10)=-9 → true, hasNextPage=true → fetchNextPage fires.
  let fetchCount = 0;
  server.use(
    http.get('/api/photos', () => {
      fetchCount++;
      return HttpResponse.json({ photos: [mockPhoto()], total: 200, page: fetchCount - 1 });
    })
  );
  vi.spyOn(ReactVirtual, 'useVirtualizer').mockReturnValue({
    getVirtualItems: () => [],
    getTotalSize:    () => 200,
    range:           { startIndex: 0, endIndex: 0 },
    measureElement:  vi.fn(),
  } as unknown as ReturnType<typeof ReactVirtual.useVirtualizer>);

  render(<LibraryPage />, { wrapper: QueryClientWrapper });
  await waitFor(() => expect(fetchCount).toBeGreaterThan(1));
});

test('does not fetch next page when all photos are loaded', async () => {
  // total === loaded count → getNextPageParam returns undefined → hasNextPage=false.
  // Even with range.endIndex at the end, the hasNextPage guard prevents fetchNextPage (CI-23).
  let fetchCount = 0;
  server.use(
    http.get('/api/photos', () => {
      fetchCount++;
      return HttpResponse.json({
        photos: Array.from({ length: 3 }, (_, i) =>
          mockPhoto({ id: `550e8400-e29b-41d4-a716-${String(i).padStart(12, '0')}` })
        ),
        total: 3, page: 0,
      });
    })
  );
  vi.spyOn(ReactVirtual, 'useVirtualizer').mockReturnValue({
    getVirtualItems: () => [],
    getTotalSize:    () => 600,
    range:           { startIndex: 0, endIndex: 2 },
    measureElement:  vi.fn(),
  } as unknown as ReturnType<typeof ReactVirtual.useVirtualizer>);

  render(<LibraryPage />, { wrapper: QueryClientWrapper });
  await screen.findAllByRole('img');
  const countAfterLoad = fetchCount;
  await new Promise(r => setTimeout(r, 100));
  expect(fetchCount).toBe(countAfterLoad);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks(); // reset useVirtualizer spy between tests
});

test('photo list refetches every 5s when any photo has non-terminal status', async () => {
  vi.useFakeTimers();
  let fetchCount = 0;
  server.use(
    http.get('/api/photos', () => {
      fetchCount++;
      return HttpResponse.json({
        photos: [mockPhoto({ processing_status: 'PROCESSING' })],
        total: 1, page: 0,
      });
    })
  );
  render(<LibraryPage />, { wrapper: QueryClientWrapper });
  await screen.findByText(/processing/i);
  await vi.advanceTimersByTimeAsync(5_000);
  expect(fetchCount).toBeGreaterThan(1);
});
```

**Step 2: Implement PhotoGrid with TanStack Virtual**

Virtualized grid rendering only visible rows.

**Pre-signed URL and caching spec:**
- `thumbnailUrl` is a pre-signed MinIO URL (15-minute expiry) included in the `GET /api/photos` list response. The frontend does **not** make a separate request per photo to get URLs — they are returned in bulk with the paginated photo list, eliminating the N+1 problem.
- The backend uses page-number pagination: `GET /api/photos?page=0&size=50`. `SearchResult.page` is the zero-based page number returned.
- Use `useInfiniteQuery` — not `useQuery` — to load photos page by page:

```typescript
// Shared constant — defined in api/constants.ts and imported here:
//   export const PAGE_SIZE = 50;
// Also imported by SearchPage. A single change keeps pagination consistent across both views.
import { PAGE_SIZE } from '../api/constants';

useInfiniteQuery<SearchResult>({
  queryKey: ['photos'],
  queryFn: ({ pageParam }) => fetchPhotos({ page: pageParam as number, size: PAGE_SIZE }),
  initialPageParam: 0,                      // Required in TanStack Query v5
  getNextPageParam: (lastPage) =>
    lastPage.page * PAGE_SIZE + lastPage.photos.length < lastPage.total
      ? lastPage.page + 1
      : undefined,
  staleTime: 10 * 60 * 1000,  // 10 min — triggers refresh before 15-min thumbnail URL expiry
  gcTime:    15 * 60 * 1000,  // 15 min — keep in memory for scroll-back
  // Recovery polling: if any loaded photo is non-terminal, refetch the list every 5s.
  // Handles the case where the user refreshes the page while a photo is still processing.
  // useUpload handles real-time polling during active upload sessions — this is the fallback.
  refetchInterval: (query) => {
    const photos = query.state.data?.pages.flatMap(p => p.photos) ?? [];
    return photos.some(p => p.processingStatus === 'PENDING' || p.processingStatus === 'PROCESSING')
      ? 5_000
      : false;
  },
})
```

The 5-minute buffer between `staleTime` and thumbnail URL expiry handles clock skew and slow refetches on long sessions.

**Flattening pages for PhotoGrid:**

```typescript
const photos = data?.pages.flatMap(page => page.photos) ?? [];
```

`PhotoGrid` receives the flat `photos` array plus `onLoadMore` and `hasMore` props. It does not know about pagination internals.

**Infinite scroll trigger:** TanStack Virtual's virtualizer tracks the last rendered row index. When the virtualizer's last rendered index approaches the end of the loaded `photos` array (e.g., within 10 rows), call `fetchNextPage()`. `rowVirtualizer.range` is typed as `{ startIndex: number; endIndex: number } | null` in TanStack Virtual v3 — use a fallback to avoid a null-deref on the first render before any items are measured:

```typescript
const rowVirtualizer = useVirtualizer({
  count: photos.length,
  getScrollElement: () => parentRef.current,
  estimateSize: () => 200,  // estimated row height in px
});

const range = rowVirtualizer.range ?? { startIndex: 0, endIndex: 0 };

useEffect(() => {
  if (range.endIndex >= photos.length - 10 && hasNextPage && !isFetchingNextPage) {
    fetchNextPage();
  }
}, [range.endIndex, photos.length, hasNextPage, isFetchingNextPage]);
```

**Note on UploadDropzone:** `LibraryPage` renders a placeholder `{/* UploadDropzone — integrated in Task 4.5 */}` at this stage. The full integration is completed in Task 4.5.

**Step 3: Implement PhotoCard**

Thumbnail display + processing status indicator (pending/processing/done/failed).

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: virtualized photo grid with paginated infinite scroll"
```

---

### Task 4.5: Upload Component

**Files:**
- Create: `frontend/src/components/UploadDropzone.tsx`
- Create: `frontend/src/api/hooks/useUpload.ts`
- Modify: `frontend/src/pages/LibraryPage.tsx` — replace `{/* UploadDropzone placeholder */}` with `<UploadDropzone />`

**Step 1: Write failing tests**

```typescript
afterEach(() => {
  vi.useRealTimers();
});

test('drop triggers POST /api/photos', async () => {
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  let uploadCalled = false;
  server.use(
    http.post('/api/photos', () => {
      uploadCalled = true;
      return HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }));
    }),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () =>
      HttpResponse.json({ id: UPLOAD_ID, processing_status: 'DONE' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  expect(uploadCalled).toBe(true);
});

test('polls /api/photos/{id}/status after upload', async () => {
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  let pollCalled = false;
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () => {
      pollCalled = true;
      return HttpResponse.json({ id: UPLOAD_ID, processing_status: 'DONE' });
    }),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  await waitFor(() => expect(pollCalled).toBe(true));
});

test('terminal "done" status stops polling', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  let pollCount = 0;
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () => {
      pollCount++;
      return HttpResponse.json({ id: UPLOAD_ID, processing_status: 'DONE' });
    }),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  await vi.advanceTimersByTimeAsync(3_000);
  const countAfterDone = pollCount;
  await vi.advanceTimersByTimeAsync(10_000);
  expect(pollCount).toBe(countAfterDone); // no further polls after terminal state
});

test('terminal "FAILED" status renders error message', async () => {
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () =>
      HttpResponse.json({ id: UPLOAD_ID, processing_status: 'FAILED' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  expect(await screen.findByText(/processing failed/i)).toBeInTheDocument();
});

test('polling stops after 10-minute timeout and shows timeout message', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () =>
      HttpResponse.json({ id: UPLOAD_ID, processing_status: 'PROCESSING' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  await vi.advanceTimersByTimeAsync(10 * 60 * 1000 + 1_000);
  expect(screen.getByText(/processing timed out/i)).toBeInTheDocument();
});

test('polling uses exponential backoff after 5 polls', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  const callTimes: number[] = [];
  let pollCount = 0;
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () => {
      callTimes.push(Date.now());
      pollCount++;
      return HttpResponse.json({
        id: UPLOAD_ID,
        processing_status: pollCount >= 20 ? 'DONE' : 'PROCESSING',
      });
    }),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  // Advance through phase 1 (5 polls × 3s) and into phase 2
  await vi.advanceTimersByTimeAsync(3_000 * 5 + 20_000);
  expect(callTimes.length).toBeGreaterThanOrEqual(7);
  const phase1Interval = callTimes[1] - callTimes[0]; // ~3000ms
  const phase2Interval = callTimes[6] - callTimes[5]; // should be > 3000ms (exponential)
  expect(phase2Interval).toBeGreaterThan(phase1Interval);
});

test('polling stops when component unmounts', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  let pollCount = 0;
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () => {
      pollCount++;
      return HttpResponse.json({ id: UPLOAD_ID, processing_status: 'PROCESSING' });
    }),
  );
  const { unmount } = render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  await vi.advanceTimersByTimeAsync(3_000);
  expect(pollCount).toBeGreaterThan(0);
  unmount();
  const countAtUnmount = pollCount;
  await vi.advanceTimersByTimeAsync(30_000); // advance well past next poll interval
  expect(pollCount).toBe(countAtUnmount);    // no new polls after unmount
});

test('"still processing" message appears after 30 seconds in non-terminal state', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () =>
      HttpResponse.json({ id: UPLOAD_ID, processing_status: 'PROCESSING' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  expect(screen.queryByText(/still processing/i)).not.toBeInTheDocument();
  await vi.advanceTimersByTimeAsync(30_000 + 100);
  expect(screen.getByText(/still processing/i)).toBeInTheDocument();
});
```

**Step 2: Implement UploadDropzone**

React Dropzone with drag & drop. Progress indicator. Error handling for 409 (duplicate) and 413 (quota exceeded).

After 30 seconds still in `pending`/`processing`, display secondary message: `"Still processing — large files may take a few minutes."`

**Step 3: Implement useUpload hook**

Multipart upload + poll `/api/photos/{id}/status` with the following polling spec:

- **Phase 1 (polls 1–5):** 3-second fixed interval — fast feedback for normal processing
- **Phase 2 (polls 6+):** Exponential backoff, capped at 60 seconds:
  ```typescript
  const interval = Math.min(
    pollCount.current < 5 ? 3000 : 3000 * Math.pow(2, pollCount.current - 4),
    60_000
  );
  ```
- **Hard timeout:** 10 minutes from upload completion. On timeout, treat as terminal `FAILED` with a client-side timeout message (no server field needed — the timeout is detected client-side).
- **Component unmount:** Set `enabled: false` on terminal states (`DONE`, `FAILED`, timeout) — TanStack Query stops refetching automatically.
- Track `pollCount` and `pollStart` in `useRef` (not state) to avoid re-renders.

**FAILED status handling (SA2-F2):** When `processingStatus` transitions to `FAILED`, display an actionable message. Note: `PhotoResponse` does not include a `failureReason` field — the status endpoint returns only `{ id, processing_status }`. Display a generic but helpful message:

- `"Processing failed — try re-uploading. If the problem persists, contact support."`
- For client-side timeout (`processing_timeout`): `"Processing timed out — try re-uploading. If the problem persists, contact support."`

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: upload dropzone with status polling, backoff, and timeout"
```

---

### Task 4.6: Single Photo View + Metadata Panel

> **Phase 3 prerequisite — keyword-photo assignment endpoints do not yet exist:** The `photo_keywords` table, `PhotoKeyword` entity, and `PhotoKeywordRepository` all exist in Phase 3, but no controller endpoints expose them. Before implementing the keyword assignment UI in this task, add these endpoints (can be done as part of Task 4.0 or as a separate backend task):
>
> - `Modify: api/src/main/java/.../service/PhotoService.java` — add three methods:
>   - `List<Keyword> listKeywordsForPhoto(UUID userId, UUID photoId)` — JPQL query joining `photo_keywords`
>   - `void addKeywordToPhoto(UUID userId, UUID photoId, UUID keywordId)` — validates ownership, then `photoKeywordRepo.save(new PhotoKeyword(photoId, keywordId, userId))`
>   - `void removeKeywordFromPhoto(UUID userId, UUID photoId, UUID keywordId)` — calls `photoKeywordRepo.deleteByPhotoIdAndKeywordIdAndUserId(...)`
> - `Modify: api/src/main/java/.../controller/PhotoController.java` — add three endpoints:
>   - `GET  /api/photos/{id}/keywords` → `List<Keyword>` (200)
>   - `POST /api/photos/{id}/keywords/{keywordId}` → `200 OK`
>   - `DELETE /api/photos/{id}/keywords/{keywordId}` → `204 No Content`
>
> No new migration, entity, or repository is needed — all DB infrastructure already exists.

**Files:**
- Create: `frontend/src/pages/PhotoPage.tsx`
- Create: `frontend/src/components/MetadataPanel.tsx`

**Step 1: Write failing tests**

```typescript
test('MetadataPanel renders EXIF fields as text nodes', () => {
  render(<MetadataPanel metadata={mockMetadata} />);
  expect(screen.getByText(mockMetadata.exifData['Artist'])).toBeInTheDocument();
  // Static analysis / lint rule must also enforce no dangerouslySetInnerHTML usage
});

test('GPS fields are absent from DOM when showGps is false', () => {
  useAuthStore.setState({ user: { ...mockUser, showGps: false } });
  render(<MetadataPanel metadata={mockMetadataWithGps} />);
  expect(screen.queryByText(/48\.8566/)).not.toBeInTheDocument();
});

test('GPS fields render as text when showGps is true', () => {
  useAuthStore.setState({ user: { ...mockUser, showGps: true } });
  render(<MetadataPanel metadata={mockMetadataWithGps} />);
  expect(screen.getByText(/48\.8566/)).toBeInTheDocument();
});

test('assigning keyword to photo calls POST /api/photos/{id}/keywords/{keywordId}', async () => {
  const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440000';
  const KEYWORD_ID = '770e8400-e29b-41d4-a716-446655440001';
  let assignCalled = false;
  server.use(
    http.get(`/api/photos/${PHOTO_ID}`, () => HttpResponse.json(mockPhoto({ id: PHOTO_ID }))),
    http.get(`/api/photos/${PHOTO_ID}/keywords`, () => HttpResponse.json([])),
    http.get('/api/keywords', () => HttpResponse.json([
      { id: KEYWORD_ID, name: 'Animals', parent_id: null, children: [] }
    ])),
    http.post(`/api/photos/${PHOTO_ID}/keywords/${KEYWORD_ID}`, () => {
      assignCalled = true;
      return new HttpResponse(null, { status: 200 });
    }),
  );
  render(<PhotoPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /add keyword/i }));
  await userEvent.click(screen.getByText('Animals'));
  expect(assignCalled).toBe(true);
});

test('removing keyword from photo calls DELETE /api/photos/{id}/keywords/{keywordId}', async () => {
  const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440000';
  const KEYWORD_ID = '770e8400-e29b-41d4-a716-446655440001';
  let removeCalled = false;
  server.use(
    http.get(`/api/photos/${PHOTO_ID}`, () => HttpResponse.json(mockPhoto({ id: PHOTO_ID }))),
    http.get(`/api/photos/${PHOTO_ID}/keywords`, () => HttpResponse.json([
      { id: KEYWORD_ID, name: 'Animals', parent_id: null, children: [] }
    ])),
    http.delete(`/api/photos/${PHOTO_ID}/keywords/${KEYWORD_ID}`, () => {
      removeCalled = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  render(<PhotoPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /remove animals/i }));
  expect(removeCalled).toBe(true);
});
```

**Step 2: Implement PhotoPage**

Full-size photo view via pre-signed original URL. Metadata panel sidebar. Keyword assignment panel: shows keywords currently assigned to this photo (`GET /api/photos/{id}/keywords`), with an "Add keyword" button that opens a picker populated from `GET /api/keywords`. Selecting a keyword calls `POST /api/photos/{id}/keywords/{keywordId}`; a remove button beside each assigned keyword calls `DELETE /api/photos/{id}/keywords/{keywordId}`.

**Pre-signed `originalUrl` TTL:** Pre-signed original URLs have a **1-hour** expiry (`StorageService.ORIGINAL_EXPIRY_SECONDS = 3600`) — longer than thumbnails (15 min). Set `staleTime` to 55 minutes so the query refetches before the URL expires, and `gcTime` to 60 minutes to keep it in memory while the user is on the page:

```typescript
useQuery<Photo>({
  queryKey: ['photo', id],
  queryFn: () => fetchPhoto(id),
  staleTime: 55 * 60 * 1000,  // 55 min — refresh before 1-hour original URL expiry
  gcTime:    60 * 60 * 1000,  // 60 min — keep in memory for back-navigation
})
```

**Step 3: Implement MetadataPanel**

Display EXIF, IPTC, XMP data in organized tabs/sections.

**GPS data flow:**

```typescript
const showGps = useAuthStore((state) => state.user?.showGps ?? false);
```

- `showGps === false` → GPS fields (`gpsLatitude`, `gpsLongitude`, `GPS:GPSAreaInformation`) are **not rendered at all** — completely absent from the DOM, not hidden with CSS. Absent from DOM means they cannot be exposed via browser dev tools, copy-paste of page content, or accessibility tools.
- `showGps === true` → GPS fields render as plain text nodes.
- When the user updates the GPS toggle in SettingsPage (Task 4.8), `authStore` is updated on success — `MetadataPanel` re-renders reactively without a page reload.

> **SA4-F1 — Defence-in-depth only:** The DOM suppression above is the second layer. The primary security gate is server-side: `PhotoMetadataService.getMetadata()` strips GPS fields from the API response when `showGps=false` (see Task 4.0 Step 6). GPS coordinates must never reach the browser when `showGps=false` — the frontend suppression does not prevent exposure via the Network tab, React Query cache, or browser extensions.

**Security requirement — safe EXIF rendering (SA2-F1):** All fields rendered from `photo_metadata.exif_data` JSONB — including but not limited to `UserComment`, `ImageDescription`, `Artist`, `Copyright`, `XMP:Description`, `XMP:Title`, `XMP:Rights`, `IPTC:Keywords`, `IPTC:ObjectName`, and `GPS:GPSAreaInformation` — must be rendered exclusively via React text nodes (`{value}`). Never use `dangerouslySetInnerHTML`, a Markdown renderer, or `.innerHTML` for any EXIF field, regardless of source. This applies equally to the `caption`, `title`, and `description` fields on the `photos` object. Phase 3 sanitizes all EXIF string values at write time (Jsoup strip on every string field in `exif_data`), but defense-in-depth requires the render path to be safe independently. The ESLint `react/no-danger` rule (configured in Task 4.1) enforces this at the tooling level.

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: single photo view with metadata panel"
```

---

### Task 4.7a: Keywords Page

**Files:**
- Create: `frontend/src/pages/KeywordsPage.tsx`

**Step 1: Write failing tests**

```typescript
test('hierarchical keyword tree renders from API data', async () => {
  const KW1 = '770e8400-e29b-41d4-a716-446655440001';
  const KW2 = '770e8400-e29b-41d4-a716-446655440002';
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([
      { id: KW1, name: 'Animals', parent_id: null, children: [
        { id: KW2, name: 'Dogs', parent_id: KW1, children: [] }
      ]}
    ]))
  );
  render(<KeywordsPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText('Animals')).toBeInTheDocument();
  expect(screen.getByText('Dogs')).toBeInTheDocument();
});

test('add keyword calls POST /api/keywords with correct parent', async () => {
  const KW1 = '770e8400-e29b-41d4-a716-446655440001';
  const KW2 = '770e8400-e29b-41d4-a716-446655440002';
  let capturedBody: unknown;
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([{ id: KW1, name: 'Animals', parent_id: null, children: [] }])),
    http.post('/api/keywords', async ({ request }) => {
      capturedBody = await request.json();
      return HttpResponse.json({ id: KW2, name: 'Dogs', parent_id: KW1, children: [] });
    }),
  );
  render(<KeywordsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /add child keyword/i }));
  await userEvent.type(screen.getByLabelText(/keyword name/i), 'Dogs');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  // snakeifyKeys transforms outgoing body: { name: 'Dogs', parent_id: KW1 }
  expect(capturedBody).toEqual({ name: 'Dogs', parent_id: KW1 });
});

test('edit keyword calls PUT /api/keywords/{id}', async () => {
  const KW1 = '770e8400-e29b-41d4-a716-446655440001';
  let capturedBody: unknown;
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([{ id: KW1, name: 'Animals', parent_id: null, children: [] }])),
    http.put(`/api/keywords/${KW1}`, async ({ request }) => {
      capturedBody = await request.json();
      return HttpResponse.json({ id: KW1, name: 'Fauna', parent_id: null, children: [] });
    }),
  );
  render(<KeywordsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /edit animals/i }));
  await userEvent.clear(screen.getByLabelText(/keyword name/i));
  await userEvent.type(screen.getByLabelText(/keyword name/i), 'Fauna');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  expect(capturedBody).toEqual({ name: 'Fauna', parent_id: null });
});

test('delete keyword calls DELETE /api/keywords/{id} and removes from list', async () => {
  const KW1 = '770e8400-e29b-41d4-a716-446655440001';
  let deleteCalled = false;
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([{ id: KW1, name: 'Animals', parent_id: null, children: [] }])),
    http.delete(`/api/keywords/${KW1}`, () => {
      deleteCalled = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  render(<KeywordsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /delete animals/i }));
  await waitFor(() => expect(deleteCalled).toBe(true));
  // Verify UI state after mutation completes (CI-19 fix)
  expect(screen.queryByText('Animals')).not.toBeInTheDocument();
});
```

**Step 2: Implement KeywordsPage**

Hierarchical keyword tree. Add/edit/delete keywords. Keyword-photo assignment is handled from the photo detail view — see Task 4.6.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: keywords page — hierarchical tree CRUD"
```

---

### Task 4.7b: Albums Page

**Files:**
- Create: `frontend/src/pages/AlbumsPage.tsx`

**Step 1: Write failing tests**

```typescript
test('album list renders from API data', async () => {
  const ALB1 = '880e8400-e29b-41d4-a716-446655440001';
  const ALB2 = '880e8400-e29b-41d4-a716-446655440002';
  server.use(
    http.get('/api/albums', () => HttpResponse.json([
      { id: ALB1, name: 'Vacation 2025', photo_count: 12 },
      { id: ALB2, name: 'Family', photo_count: 5 },
    ]))
  );
  render(<AlbumsPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText('Vacation 2025')).toBeInTheDocument();
  expect(screen.getByText('Family')).toBeInTheDocument();
});

test('album detail shows member photos', async () => {
  const ALB1 = '880e8400-e29b-41d4-a716-446655440001';
  const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440010';
  server.use(
    http.get('/api/albums', () => HttpResponse.json([{ id: ALB1, name: 'Vacation 2025', photo_count: 1 }])),
    http.get(`/api/albums/${ALB1}`, () => HttpResponse.json({ id: ALB1, name: 'Vacation 2025', photo_count: 1,
      photos: [mockPhoto({ id: PHOTO_ID, filename: 'beach.jpg' })] })),
  );
  render(<AlbumsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByText('Vacation 2025'));
  expect(await screen.findByAltText('beach.jpg')).toBeInTheDocument();
});

test('add photo to album calls POST /api/albums/{albumId}/photos/{photoId}', async () => {
  // Note: the endpoint uses photoId as a path parameter — no request body.
  const ALB1 = '880e8400-e29b-41d4-a716-446655440001';
  let addCalled = false;
  server.use(
    http.get('/api/albums', () => HttpResponse.json([{ id: ALB1, name: 'Vacation 2025', photo_count: 0 }])),
    http.get(`/api/albums/${ALB1}`, () => HttpResponse.json({ id: ALB1, name: 'Vacation 2025', photo_count: 0, photos: [] })),
    http.post(`/api/albums/${ALB1}/photos/:photoId`, () => {
      addCalled = true;
      return new HttpResponse(null, { status: 200 });
    }),
  );
  render(<AlbumsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByText('Vacation 2025'));
  await userEvent.click(await screen.findByRole('button', { name: /add photo/i }));
  await userEvent.click(await screen.findByRole('button', { name: /confirm/i }));
  expect(addCalled).toBe(true);
});

test('remove photo from album calls DELETE /api/albums/{albumId}/photos/{photoId}', async () => {
  const ALB1 = '880e8400-e29b-41d4-a716-446655440001';
  const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440010';
  let deleteCalled = false;
  server.use(
    http.get('/api/albums', () => HttpResponse.json([{ id: ALB1, name: 'Vacation 2025', photo_count: 1 }])),
    http.get(`/api/albums/${ALB1}`, () => HttpResponse.json({ id: ALB1, name: 'Vacation 2025', photo_count: 1,
      photos: [mockPhoto({ id: PHOTO_ID, filename: 'beach.jpg' })] })),
    http.delete(`/api/albums/${ALB1}/photos/${PHOTO_ID}`, () => {
      deleteCalled = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  render(<AlbumsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByText('Vacation 2025'));
  await userEvent.click(await screen.findByRole('button', { name: /remove beach\.jpg/i }));
  await waitFor(() => expect(deleteCalled).toBe(true));
  // Verify UI state after mutation completes (CI-19 fix)
  expect(screen.queryByAltText('beach.jpg')).not.toBeInTheDocument();
});
```

**Step 2: Implement AlbumsPage**

Album list + album detail view. Add/remove photos from albums.

**Album detail is an inline panel — no separate route.** Clicking an album in the list expands or replaces the list view with the album's member photos within the same component. This avoids a full navigation, keeps the album list visible for context, and requires no `/albums/:id` route in Task 4.9. The test `'album detail shows member photos'` tests this inline behavior.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: albums page — list, inline detail, membership management"
```

---

### Task 4.7c: Search Page

**Files:**
- Create: `frontend/src/pages/SearchPage.tsx`

**Step 1: Write failing tests**

```typescript
test('full-text search fires on submit', async () => {
  let capturedQuery: string | null = null;
  server.use(
    http.get('/api/search', ({ request }) => {
      capturedQuery = new URL(request.url).searchParams.get('q');
      return HttpResponse.json({ photos: [], total: 0, page: 0 });
    }),
  );
  render(<SearchPage />, { wrapper: QueryClientWrapper });
  await userEvent.type(screen.getByRole('searchbox'), 'sunset');
  await userEvent.click(screen.getByRole('button', { name: /search/i }));
  await waitFor(() => expect(capturedQuery).toBe('sunset'));
});

test('EXIF field filter applies to results', async () => {
  let capturedParams: URLSearchParams | null = null;
  server.use(
    http.get('/api/search', ({ request }) => {
      capturedParams = new URL(request.url).searchParams;
      return HttpResponse.json({ photos: [], total: 0, page: 0 });
    }),
  );
  render(<SearchPage />, { wrapper: QueryClientWrapper });
  await userEvent.type(screen.getByRole('searchbox'), 'Paris');
  await userEvent.selectOptions(screen.getByLabelText(/exif field/i), 'Artist');
  await userEvent.click(screen.getByRole('button', { name: /search/i }));
  await waitFor(() => expect(capturedParams?.get('field')).toBe('Artist'));
});

test('keyword search filters results', async () => {
  const KW_ID = '770e8400-e29b-41d4-a716-446655440005';
  let capturedParams: URLSearchParams | null = null;
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([
      { id: KW_ID, name: 'Animals', parent_id: null, children: [] }
    ])),
    http.get('/api/search', ({ request }) => {
      capturedParams = new URL(request.url).searchParams;
      return HttpResponse.json({ photos: [], total: 0, page: 0 });
    }),
  );
  render(<SearchPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('checkbox', { name: /animals/i }));
  await userEvent.click(screen.getByRole('button', { name: /search/i }));
  await waitFor(() => expect(capturedParams?.get('keywordId')).toBe(KW_ID));
});

test('saved search is stored in localStorage keyed by user ID', async () => {
  const USER_ID = '660e8400-e29b-41d4-a716-446655440042';
  useAuthStore.setState({ isAuthenticated: true, isHydrating: false,
    user: { id: USER_ID, email: 'a@b.com', showGps: false, quotaBytes: 10737418240, usedBytes: 0 } });
  server.use(
    http.get('/api/search', () => HttpResponse.json({ photos: [], total: 0, page: 0 })),
  );
  render(<SearchPage />, { wrapper: QueryClientWrapper });
  await userEvent.type(screen.getByRole('searchbox'), 'sunset');
  await userEvent.click(screen.getByRole('button', { name: /search/i }));
  await userEvent.click(screen.getByRole('button', { name: /save search/i }));
  const saved = JSON.parse(localStorage.getItem(`saved_searches_${USER_ID}`) ?? '[]');
  expect(saved).toContainEqual(expect.objectContaining({ query: 'sunset' }));
  localStorage.clear();
});
test('saved search is not visible to a different user ID', () => {
  const USER_ID_A = '660e8400-e29b-41d4-a716-446655440042';
  const USER_ID_B = '660e8400-e29b-41d4-a716-446655440099';
  localStorage.setItem(`saved_searches_${USER_ID_A}`, JSON.stringify([{ query: 'sunset' }]));
  useAuthStore.setState({ user: { id: USER_ID_B, email: 'other@b.com', showGps: false, quotaBytes: 10737418240, usedBytes: 0 } });
  render(<SearchPage />, { wrapper: QueryClientWrapper });
  expect(screen.queryByText('sunset')).not.toBeInTheDocument();
  localStorage.clear();
});
test('saved search re-applies on next visit by reading localStorage on mount', async () => {
  const USER_ID = '660e8400-e29b-41d4-a716-446655440042';
  useAuthStore.setState({ user: { id: USER_ID, email: 'a@b.com', showGps: false, quotaBytes: 10737418240, usedBytes: 0 } });
  localStorage.setItem(`saved_searches_${USER_ID}`, JSON.stringify([{ query: 'mountains' }]));
  server.use(
    http.get('/api/search', () => HttpResponse.json({ photos: [], total: 0, page: 0 })),
  );
  render(<SearchPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText('mountains')).toBeInTheDocument();
  localStorage.clear();
});
```

**Step 2: Implement SearchPage**

Full-text search + EXIF field search + keyword search. Saved searches.

**Pagination strategy:** Use `useInfiniteQuery` for consistency with LibraryPage. The search term and filters must be included in the query key so that different searches cache independently and do not pollute the `['photos']` library cache:

```typescript
// Shared constant — imported from api/constants.ts (same value used in LibraryPage).
import { PAGE_SIZE } from '../api/constants';

useInfiniteQuery<SearchResult>({
  queryKey: ['search', query, filters],   // unique cache entry per query+filters combination
  queryFn: ({ pageParam }) =>
    fetchSearch({ q: query, filters, page: pageParam as number, size: PAGE_SIZE }),
  initialPageParam: 0,
  getNextPageParam: (lastPage) =>
    lastPage.page * PAGE_SIZE + lastPage.photos.length < lastPage.total
      ? lastPage.page + 1
      : undefined,
  enabled: query.length > 0,   // do not fire on empty query
  staleTime: 2 * 60 * 1000,    // 2 min — search results stale faster than the library view
})
```

**Saved search persistence:** Use `localStorage` keyed by user ID to survive page refreshes while preventing cross-user data leakage on shared browsers:

```typescript
const SAVED_SEARCHES_KEY = (userId: string) => `saved_searches_${userId}`;
```

Saved search values contain only query terms (strings, EXIF field names, keyword IDs) — no photo data or sensitive values are stored. On mount, `SearchPage` reads saved searches for the current user's ID from `localStorage`. On save, it writes to the same key.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: search page — full-text, EXIF, keyword, saved searches (localStorage by userId)"
```

---

### Task 4.7d: Trash Page

**Files:**
- Create: `frontend/src/pages/TrashPage.tsx`

**Step 1: Write failing tests**

```typescript
// Trash endpoint is GET /api/photos/trash (not /api/trash).
// Restore returns 200 OK (not 204).
// The API returns snake_case JSON (deleted_at, thumbnail_url, etc.). The camelizeKeys
// transformer in apiFetch converts these to camelCase before components see them.
// MSW handlers return raw snake_case to match real API responses — the transformer runs
// as part of the useQuery fetch, so components receive deletedAt (camelCase) correctly.

test('soft-deleted photos render with deletion date', async () => {
  const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440001';
  server.use(
    http.get('/api/photos/trash', () => HttpResponse.json([
      // Raw API response — camelizeKeys transforms deleted_at → deletedAt
      mockPhoto({ id: PHOTO_ID, filename: 'old.jpg', deleted_at: '2026-03-01T10:00:00Z' })
    ]))
  );
  render(<TrashPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText('old.jpg')).toBeInTheDocument();
  expect(screen.getByText(/march 1, 2026/i)).toBeInTheDocument();
});

test('restore button calls POST /api/photos/{id}/restore', async () => {
  const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440001';
  let restoreCalled = false;
  server.use(
    http.get('/api/photos/trash', () => HttpResponse.json([
      mockPhoto({ id: PHOTO_ID, filename: 'old.jpg', deleted_at: '2026-03-01T10:00:00Z' })
    ])),
    http.post(`/api/photos/${PHOTO_ID}/restore`, () => {
      restoreCalled = true;
      return new HttpResponse(null, { status: 200 });
    }),
  );
  render(<TrashPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /restore old\.jpg/i }));
  expect(restoreCalled).toBe(true);
});

test('retention window displays correctly', async () => {
  // Photo deleted 5 days ago — 25 days remaining in a 30-day retention window
  const deleted_at = new Date(Date.now() - 5 * 24 * 60 * 60 * 1000).toISOString();
  const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440001';
  server.use(
    http.get('/api/photos/trash', () => HttpResponse.json([
      mockPhoto({ id: PHOTO_ID, filename: 'old.jpg', deleted_at })
    ]))
  );
  render(<TrashPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText(/25 days remaining/i)).toBeInTheDocument();
});
```

**Step 2: Implement TrashPage**

List soft-deleted photos. Restore button. Retention window display.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: trash page — list, restore, retention display"
```

---

### Task 4.8: Settings Page

**Files:**
- Create: `frontend/src/pages/SettingsPage.tsx`

**Step 1: Write failing tests**

```typescript
test('renders loading skeleton while user profile is loading', () => {
  server.use(http.get('/api/users/me', async () => {
    await delay('infinite');  // MSW never responds → isLoading stays true
    return HttpResponse.json({});
  }));
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  expect(screen.getByTestId('quota-skeleton')).toBeInTheDocument();
});

test('renders error state when user profile fetch fails', async () => {
  server.use(
    http.get('/api/users/me', () => new HttpResponse(null, { status: 500 })),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText(/could not load storage info/i)).toBeInTheDocument();
});

test('renders "X GB of Y GB used" when user profile is loaded', async () => {
  server.use(
    http.get('/api/users/me', () => HttpResponse.json({
      ...mockUserWire, used_bytes: 2_300_000_000, quota_bytes: 10_000_000_000,
    })),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText('2.3 GB of 10.0 GB used')).toBeInTheDocument();
});

test('usedBytes floor guard: never renders negative storage value', async () => {
  server.use(
    http.get('/api/users/me', () => HttpResponse.json({
      ...mockUserWire, used_bytes: -1, quota_bytes: 10_000_000_000,
    })),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText('0.0 GB of 10.0 GB used')).toBeInTheDocument();
});

test('GPS toggle calls PATCH /api/users/me and updates auth store', async () => {
  let capturedBody: unknown;
  useAuthStore.setState({ isAuthenticated: true, isHydrating: false, user: mockUser });
  server.use(
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
    http.patch('/api/users/me', async ({ request }) => {
      capturedBody = await request.json();
      return HttpResponse.json({ ...mockUserWire, show_gps: true });
    }),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('checkbox', { name: /show gps/i }));
  // snakeifyKeys transforms outgoing body: { show_gps: true }
  expect(capturedBody).toEqual({ show_gps: true });
  expect(useAuthStore.getState().user?.showGps).toBe(true);
});
```

**Step 2: Implement SettingsPage**

Account info, storage usage vs quota, linked OAuth accounts, GPS display preference.

> **Data source:** Quota and user info come from `GET /api/users/me` (created in Task 4.0), not a separate `/api/quota` endpoint. The `User` interface includes `quotaBytes` and `usedBytes`.

**Loading and error states:**

```typescript
if (isLoading) return <QuotaSkeleton />;            // shimmer placeholder matching quota meter dimensions
if (isError)   return <p>Could not load storage info — try refreshing.</p>;
// Only reach display logic when user profile is defined
```

**Quota display (SA2-F4):**

```typescript
const usedBytes = Math.max(0, user?.usedBytes ?? 0)
const limitBytes = user?.quotaBytes ?? 0
const usedGB  = (usedBytes  / 1e9).toFixed(1)
const limitGB = (limitBytes / 1e9).toFixed(1)
// Renders: "2.3 GB of 10.0 GB used"
```

The `?.usedBytes ?? 0` guard handles `undefined` during initial load or on API failure. `Math.max(0, ...)` is defense-in-depth against transient negative values from the API; correctness is enforced at the Phase 3 write path (`GREATEST(0, ...)` on all decrement paths + `CHECK (used_bytes >= 0)` DB constraint).

**GPS toggle:** On `PATCH /api/users/me` success (with `snakeifyKeys`-transformed body `{ "show_gps": true }`), update `authStore` with the new `showGps` value from the response — `MetadataPanel` re-renders reactively without a page reload.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: settings page with quota display"
```

---

### Task 4.9: Router Setup

> **Prerequisite:** Tasks 4.0, 4.3, 4.4, 4.5, 4.6, 4.7a, 4.7b, 4.7c, 4.7d, and 4.8 must all be complete before starting this task. This is the integration step — not an incremental feature. Run the full test suite after completing it before committing.

> **Note:** The `/share/:token` route is **not** included here. It is added in Task 5.2 alongside `SharePage` creation, with a corresponding `Modify: frontend/src/App.tsx` file entry.

**Files:**
- Modify: `frontend/src/App.tsx`

**Step 1: Configure all routes**

```typescript
<Routes>
  <Route path="/login"    element={<LoginPage />} />
  <Route path="/register" element={<RegisterPage />} />
  <Route path="/library"  element={<ProtectedRoute><LibraryPage /></ProtectedRoute>} />
  <Route path="/photo/:id" element={<ProtectedRoute><PhotoPage /></ProtectedRoute>} />
  <Route path="/keywords" element={<ProtectedRoute><KeywordsPage /></ProtectedRoute>} />
  <Route path="/albums"   element={<ProtectedRoute><AlbumsPage /></ProtectedRoute>} />
  <Route path="/search"   element={<ProtectedRoute><SearchPage /></ProtectedRoute>} />
  <Route path="/trash"    element={<ProtectedRoute><TrashPage /></ProtectedRoute>} />
  <Route path="/settings" element={<ProtectedRoute><SettingsPage /></ProtectedRoute>} />
  <Route path="/"         element={<Navigate to="/library" replace />} />
  <Route path="*"         element={<NotFoundPage />} />
</Routes>
```

`NotFoundPage` is a minimal component: a "Page not found" message and a link back to `/library`. It can be defined in `App.tsx` or as a small dedicated file — no new task needed.

**Step 2: Smoke test navigation**

```bash
npm run dev
```

Manually verify:
- `/` redirects to `/library`
- `/library` renders PhotoGrid
- `/login` renders login form
- Unauthenticated access to `/library` redirects to `/login`
- Back button after redirect from `/library` to `/login` lands at `/login` (not `/library`)
- Back button from `/library` skips over `/` — lands at the page before `/library`, not stuck in a redirect loop (`replace` prop ensures this)
- Unknown path (e.g., `/does-not-exist`) renders `NotFoundPage`

**Step 3: Phase completion gate**

```bash
npm run build    # Zero TypeScript errors across the entire frontend
npm run lint     # Zero ESLint errors (no-danger rule passes)
npm run test     # All tests pass
npm run preview  # Production build serves correctly
```

Phase 4 is not complete until all four commands exit 0. Do not commit until all pass.

**SA4-F4 — Cookie verification (before production deployment):** Confirm `Secure` is present on `JSESSIONID` and `XSRF-TOKEN` cookies in the staging environment. Use browser DevTools → Application → Cookies to verify attributes.

**SA4-F7 — Security headers (before production deployment):** Configure the following at the nginx/reverse-proxy layer. These are deployment-time requirements, not frontend code:

```nginx
# Content Security Policy
add_header Content-Security-Policy "
  default-src 'self';
  script-src 'self';
  style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob: https://<minio-domain>;
  connect-src 'self' https://<api-domain>;
  frame-ancestors 'none';
  object-src 'none';
  base-uri 'self';
" always;

# Additional security headers
add_header X-Frame-Options "DENY" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

Add CSP violation reporting (`report-uri` / `report-to`) to detect bypass attempts in production.

**Step 4: Commit**

```bash
git commit -m "feat: React Router setup with all routes and 404 catch-all"
```

---

**Next Phase:** [Phase 5: Sharing & Polish](2026-02-25-saas-conversion-phase-5.md)

> **Phase 5 note:** Task 5.2 must add `Modify: frontend/src/App.tsx` to its file list, and include a step that adds `<Route path="/share/:token" element={<SharePage />} />` to the router alongside `SharePage` creation.

---

## Change Log

### v8.0 — 2026-03-10

Applied 8 findings from Security Audit SA4 (`docs/plans/2026-03-10-saas-conversion-phase-4-security-audit-1.md`).

**BLOCK fixes (required before implementation begins):**

- **SA4-F1 (BLOCK — GPS Privacy):** Added server-side GPS filtering as a required implementation step in Task 4.0. `PhotoMetadataService.getMetadata()` must strip `gpsLatitude`, `gpsLongitude`, and all `GPS:*` exifData keys before returning when `!user.isShowGps()`. Added `PhotoMetadataService.java` to Task 4.0 Files list. Added two tests to Task 4.0 Step 7. Added defence-in-depth note to Task 4.6 Step 3 clarifying that DOM suppression is the second layer only. Frontend prerequisite comment updated.
- **SA4-F2 (BLOCK — Dockerfile ENTRYPOINT injection):** Removed `ENV JAVA_OPTS` and replaced `sh -c "java $JAVA_OPTS -jar app.jar"` shell-expansion ENTRYPOINT with exec-form `["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]` in `worker/Dockerfile`. Eliminates command injection via environment variable override. No plan text change needed (Dockerfile is not specified in the plan).

**Medium fixes:**

- **SA4-F3 (apiFetch verbose error body):** Updated `apiFetch` in Task 4.2 Step 3 to truncate error response body to 200 characters before storing in `ApiError.message`. Added `server.error.include-stacktrace: never` and `server.error.include-message: never` to `api/src/main/resources/application.yml`. Both changes applied. Documented in Task 4.0 security requirements block.

**Low fixes:**

- **SA4-F4 (Cookie Secure/SameSite not specified):** Added `SameSite=Strict` and `Secure` cookie requirements to Task 4.2 MI-30 note and Task 4.0 security requirements block. Added cookie verification checklist item to Task 4.9 Step 3.
- **SA4-F5 (Redis empty-password default):** Removed `:` (empty-string default) from `${REDIS_PASSWORD:}` in both `worker/src/main/resources/application.yml` and `api/src/main/resources/application.yml`. Worker now fails fast at startup if `REDIS_PASSWORD` is not set. Documented in Task 4.0 security requirements block.
- **SA4-F6 (camelizeKeys corrupts EXIF keys):** Updated `camelizeKeys` in Task 4.2 Step 3 to exempt the `exifData` dictionary from recursive key transformation. Added test verifying underscore-containing EXIF keys (e.g., `GPS_Altitude`) are preserved. Updated MSW convention note.
- **SA4-F7 (No CSP specified):** Added nginx security headers block (CSP, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`) to Task 4.9 Step 3 phase completion gate as a pre-production deployment requirement.
- **SA4-F8 (Null lastModified skips recency guard):** Changed `OrphanReconciliationScheduler.java` null check from `!= null && isAfter(cutoff)` to `== null || isAfter(cutoff)` — null is now treated as "recent enough to skip" (conservative safe default). Added `WARN` log when `lastModified()` is null. No plan text change needed.

### v7.0 — 2026-03-10

Applied issues CI-23, CI-24, CI-25 from Phase 4 Critical Implementation Review v6 (`docs/plans/2026-02-25-saas-conversion-phase-4-critical-review-6.md`).

**Critical fixes:**

- **CI-23 (Blocking):** Replaced all 4 comment-only TDD stubs in Task 4.4 with complete test bodies. (1) Viewport test: replaced vacuous pixel-count assertion (JSDOM has no layout engine; `0 < 100` is trivially true) with structural assertion — verifies `photo-grid-scroll-container` testid exists and `[data-index]` virtual row elements are rendered by TanStack Virtual. (2) staleTime test: added MSW handler + `queryClient.getQueryCache().find()` assertion for `staleTime: 10 * 60 * 1000`. (3) fetchNextPage trigger test: mocks `useVirtualizer` via `vi.spyOn(ReactVirtual, 'useVirtualizer')` to return `range: { startIndex: 0, endIndex: 0 }` with a 1-photo / 200-total response — endIndex satisfies `>= photos.length - 10` and `hasNextPage=true`, triggering `fetchNextPage`. (4) no-fetchNextPage test: same mock with 3-photo / total=3 response — `hasNextPage=false` so `fetchNextPage` is not called despite range being at the end. Added `import * as ReactVirtual from '@tanstack/react-virtual'` to test file header. Added `vi.restoreAllMocks()` to `afterEach` to reset the spy between tests.
- **CI-24 (Blocking):** Fixed `PhotoCard` thumbnail test — replaced `mockPhoto()` (snake_case wire format) with `mockPhotoApp()` (camelCase post-transform) so `photo.thumbnailUrl` is defined at render time. Renamed test from `'thumbnail_url from API response is used directly as img src'` to `'thumbnailUrl is rendered as img src'`. Also fixed the adjacent `PhotoGrid` render test (first test in Task 4.4) which had the same wire-format bug — changed `mockPhoto()` to `mockPhotoApp()`. Added `mockPhotoApp(overrides: Partial<Photo> = {}): Photo` factory to `frontend/src/test/factories.ts`: applies `camelizeKeys` to base `mockPhoto()` then spreads camelCase overrides — both steps are required (v6 review proposed factory omitted the override spread). Added convention note distinguishing `mockPhoto()` (MSW responses) from `mockPhotoApp()` (component props).
- **CI-25 (Blocking):** Replaced `vi.mocked(useAuth).mockReturnValue(...)` in Task 4.3 `'login form submits with correct credentials'` test with an MSW-based approach — captures the request body via `http.post('/api/auth/login', async ({ request }) => { capturedBody = await request.json(); ... })` and asserts `capturedBody` equals `{ email: 'a@b.com', password: 'password123' }`. This eliminates the need for `vi.mock('../api/hooks/useAuth')` entirely. Adding `vi.mock` was rejected because it is file-scoped: it would break the render test (auto-mocked `useAuth` returns undefined → crash on destructure) and the redirect test (real `useAuth` flow required for the navigation assertion) without extensive `beforeEach` mock setup and `vi.importActual` workarounds. The MSW approach is strictly stronger — it verifies the actual HTTP wire format rather than a mock call argument, and maintains a consistent single testing strategy (MSW only) across all five tests in the file.

### v6.0 — 2026-03-06

Applied all issues from Phase 4 Critical Implementation Review v5 (`docs/plans/2026-02-25-saas-conversion-phase-4-critical-review-5.md`).

**New task:**

- **Task 4.0 (Backend Prerequisites):** Created new task for backend changes required by the frontend that were not anticipated during Phase 3. Includes: `show_gps` column migration, `User.java` field addition, `UserController` with `GET/PATCH /api/users/me`, `UserResponse` and `UpdateUserRequest` DTOs, and `thumbnail_url`/`original_url` fields in `PhotoResponse`. Previously these were listed as "Phase 3 prerequisites" in Tasks 4.2 and 4.4; now consolidated into a single prerequisite task. Updated critical path to `4.0 → 4.1 → 4.2 → 4.4 → 4.5 → 4.9`.

**Critical fixes:**

- **CI-18 (Blocking):** Changed all TypeScript `id` fields from `number` to `string` (UUID). Updated all entity interfaces (`User`, `Photo`, `Album`, `Keyword`, `ShareToken`). Updated all test mocks to use UUID strings. Updated all MSW route paths to use UUID path parameters. Updated `SAVED_SEARCHES_KEY` type from `number` to `string`.
- **CI-19 (Blocking):** Added 204 No Content guard to `apiFetch` — returns `undefined` instead of crashing on `res.json()`. Affects DELETE endpoints for keywords, photos, album memberships, and saved searches. Strengthened delete test assertions to verify UI state after mutation completes (not just request flag).
- **CI-20 (Blocking):** Reversed `apiFetch` spread order — `{ ...options, credentials, headers: { csrf, ...options.headers } }` ensures CSRF token and `credentials: 'include'` are never overridden. Added `Headers` object handling.
- **CI-21 (Blocking):** Implemented two-step login flow — `POST /api/auth/login` establishes JWT cookie, then `GET /api/users/me` fetches user profile. Updated login test to mock both endpoints. Added `useAuth` hook implementation with explicit two-step flow.
- **CI-22 (High):** Fixed all 8 Task 4.5 upload test mocks to use API wire format (`processing_status` instead of `status`, `DONE`/`PENDING`/`PROCESSING`/`FAILED` instead of lowercase). Added wire format convention note. MI-33 folded into this fix.

**Minor fixes:**

- **MI-31 (Medium):** Applied `REPLACE_ME` version sentinels to all 13 previously unpinned packages: `react-dropzone`, `tailwindcss`, `postcss`, `autoprefixer`, `eslint`, `@typescript-eslint/parser`, `@typescript-eslint/eslint-plugin`, `eslint-plugin-react`, `@testing-library/react`, `@testing-library/user-event`, `vitest`, `jsdom`, `@vitest/coverage-v8`. Changed `react-router-dom@6` to `@REPLACE_ME`. Added release page links for each.
- **MI-32 (Medium):** Replaced comment-only mock setup in SettingsPage tests 1–3 with actual MSW-based tests using `delay('infinite')` for loading state, MSW 500 handler for error state, and `mockUserWire` for data state. All tests now use `{ wrapper: QueryClientWrapper }`.
- **MI-34 (Medium):** Added `frontend/src/test/factories.ts` with `mockPhoto()`, `mockUser`, `mockUserWire`, `mockMetadata()`, and `mockMetadataWithGps()` test factories. Wire format factories (`mockPhoto`, `mockUserWire`) for MSW handlers; post-transform factory (`mockUser`) for `useAuthStore.setState()` calls.
- **MI-35 (Medium):** Added `snakeifyKeys` transform to `apiFetch` for outgoing JSON request bodies. Wire format is now consistently snake_case in both directions. Backend request DTOs use `@JsonProperty` matching snake_case wire format. Updated GPS toggle test assertion from `{ showGps: true }` to `{ show_gps: true }`. Updated keyword create/edit test assertions similarly. Documented the convention in Task 4.0.
- **MI-36 (Low):** Documented assumption: EXIF keys are PascalCase (from metadata-extractor) and are not transformed by `camelizeKeys`. No code change needed.

**Other changes:**

- Removed `QuotaInfo` interface — quota fields (`quotaBytes`, `usedBytes`) are now part of `User`, returned by `GET /api/users/me`.
- Removed `failureReason` from `Photo` interface — field does not exist in `PhotoResponse.java`.
- Changed `ProcessingStatus` values to uppercase (`'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED'`) to match backend enum `name()` output.
- Updated `Photo` interface fields to match actual `PhotoResponse.java`: renamed `status` → `processingStatus`, `createdAt` → `uploadedAt`, added `sizeBytes`, `takenAt`, `updatedAt`.
- SettingsPage now fetches quota from `GET /api/users/me` instead of a non-existent `/api/quota` endpoint.
- All test mocks updated to use API wire format consistently (snake_case for `@JsonProperty` fields).

### v5.1 — 2026-03-06

Residual fixes from v5.0 verification of Critical Implementation Review v4.

- **Task 4.7c:** Replaced comment-only `'saved search is stored in localStorage keyed by user ID'` test with complete body — MSW handler, render, user interactions (type query, click search, click save), `localStorage.getItem` assertion, and cleanup.
- **Task 4.8:** Fixed `'renders loading skeleton'` test — added `{ wrapper: QueryClientWrapper }` and documented intentional missing handler. Fixed `'renders error state'` test — added MSW handler returning 500, added `{ wrapper: QueryClientWrapper }`, changed to `async` with `findByText` for async error state.

### v5.0 — 2026-03-06

Applied all issues from Phase 4 Critical Implementation Review v4 (`docs/plans/2026-02-25-saas-conversion-phase-4-critical-review-4.md`).

**Critical fixes:**

- **CI-13 (Blocking):** Replaced all 8 hollow `{ ... }` stubs in Task 4.5 with complete test bodies. Each test now has MSW handlers, `render()`, and `expect()` assertions covering: POST upload trigger, status polling, done/failed terminal states, 10-minute timeout, exponential backoff verification, unmount cleanup, and 30-second still-processing message. Added `afterEach(() => { vi.useRealTimers(); })` to prevent fake-timer pollution across tests.
- **CI-14 (Blocking):** Replaced 8 additional hollow stubs missed by the CI-12 fix: 2 in Task 4.2 (401 query handler, 401 mutation handler), 1 in Task 4.3 (post-login redirect to `location.state.from`), and 5 in Task 4.7c (full-text search, EXIF filter, keyword filter, user-isolation saved search, localStorage-on-mount saved search). All now have MSW handlers and `expect()` assertions.
- **CI-15 (Blocking):** Added `toCamelCase`, `camelizeKeys`, and `apiFetch` to Task 4.2 Step 3 (`client.ts`). All API responses are now run through `camelizeKeys` before TypeScript code sees them, resolving the runtime `undefined` bug for `deletedAt`, `thumbnailUrl`, `failureReason`, and all other snake_case fields from the Spring Boot backend. `fetchCurrentUser` updated to use `camelizeKeys`. Task 4.7d test comments updated to explain the snake_case wire format vs camelCase component interface; raw `mockPhoto()` calls replaced with inline snake_case objects matching real API shape.
- **CI-16 (High):** Replaced `5.x.x` / `3.x.x` npm wildcards in Task 4.1 Step 1 with `REPLACE_ME` placeholders. npm rejects `@REPLACE_ME` at install time, making accidental un-pinned installs impossible. Added instruction comment directing implementor to the linked release pages.
- **CI-17 (High):** Added `replace` prop to `<Navigate to="/library" replace />` in Task 4.9 Step 1. Without `replace`, pressing back from `/library` redirects to `/` which immediately redirects back, trapping the user. Updated Task 4.9 smoke test to explicitly verify back-button behavior from the root redirect.

**Minor fixes:**

- **MI-25 (Medium):** Fixed Task 4.8 `usedBytes` floor guard test — added `server.use()` MSW handler returning `usedBytes: -1`, added `wrapper: QueryClientWrapper`, changed `getByText` (sync) to `findByText` (async). The previous version was stuck in loading state.
- **MI-26 (Medium):** Extracted inline hydration try/catch/finally from `init()` in `main.tsx` into a new exported `hydrateSession()` function in `client.ts`. Tests call `await hydrateSession()` directly without running `init()`. `main.tsx` now calls `await hydrateSession()` in one line.
- **MI-27 (Low):** Fixed `mockPhoto({ id: '1' })` → `mockPhoto({ id: 1 })` in Task 4.6 keyword assignment and removal tests. Fixed `{ id: 'k1' }` → `{ id: 1 }` in keyword mock data. MSW route paths remain string-based (`/api/photos/1/keywords/1`) — only the response object types are corrected.
- **MI-28 (Low):** Added `frontend/src/api/constants.ts` to Task 4.4 Files section. Updated Task 4.4 and Task 4.7c `PAGE_SIZE` references to import from `../api/constants`. Updated Task 4.1 Step 3 directory structure comment for `api/`.
- **MI-29 (Low):** Added `afterEach(() => { vi.useRealTimers(); })` to Task 4.4 and Task 4.5 test blocks. Prevents fake-timer state from leaking into subsequent tests when a timer-using test fails before reaching `vi.useRealTimers()`.
- **MI-30 (Low):** Added backend configuration requirement callout to Task 4.2 prerequisite block documenting `CookieCsrfTokenRepository.withHttpOnlyFalse()`. If the CSRF cookie is accidentally HttpOnly, all POST/PUT/DELETE mutations silently fail with 403.

### v4.0 — 2026-03-06

Applied all issues from Phase 4 Critical Implementation Review v3 (`docs/plans/2026-02-25-saas-conversion-phase-4-critical-review-3.md`). Additional fixes from codebase verification (confirmed against Phase 3 implementation).

**Critical fixes:**

- **CI-10 (Blocking):** Replaced the two-snippet promise chain in `main.tsx` (Step 6, Task 4.2) with a single `async/await init()` function. The `return` after CSRF failure error HTML prevents `ReactDOM.render` from executing — a `.finally()` block cannot do this. CSRF failure path now correctly aborts rendering.
- **CI-11 (High):** Pinned `@tanstack/react-query@5.x.x`, `@tanstack/react-virtual@3.x.x`, and `zustand@5.x.x` in Task 4.1 Step 1. Updated `useInfiniteQuery` in Task 4.4 to TQ v5 API: added `initialPageParam: 0` (required in v5), removed `= 0` default from `pageParam`.
- **CI-12 (High):** Replaced all hollow TDD stubs (todo tests) with concrete test bodies across Tasks 4.7a, 4.7b, 4.7d, and 4.8 GPS toggle. Each test now has MSW handlers and `expect()` assertions.

**Minor fixes:**

- **MI-18 (Low):** Extracted `const PAGE_SIZE = 50` in Task 4.4 `useInfiniteQuery`. Both `queryFn` and `getNextPageParam` now reference the constant.
- **MI-19 (Low):** Specified `isHydrating: true` as the initial value in the `create()` call in `authStore.ts` (Task 4.2 Step 4). Removed the now-redundant `useAuthStore.setState({ isHydrating: true })` from `main.tsx`.
- **MI-20 (Low):** Added complete virtualizer reference in Task 4.4: `const rowVirtualizer = useVirtualizer({...})` and `const range = rowVirtualizer.range ?? { startIndex: 0, endIndex: 0 }`. Null guard added for first-render before items are measured.
- **MI-21 (Medium):** Moved keyword-photo assignment from Task 4.7a to Task 4.6. Added Phase 3 prerequisite block to Task 4.6 documenting the three new endpoints (`GET/POST/DELETE /api/photos/{id}/keywords[/{keywordId}]`) and service methods needed. Removed assignment stub from Task 4.7a. Added two concrete tests to Task 4.6.
- **MI-22 (Medium):** Specified `useInfiniteQuery` as SearchPage pagination strategy in Task 4.7c. Query key includes `[query, filters]` so searches cache independently. Added `enabled: query.length > 0` guard and `staleTime: 2min`.
- **MI-23 (Medium):** Added `refetchInterval` function to `useInfiniteQuery` in Task 4.4: polls every 5s when any loaded photo has non-terminal status (`pending`/`processing`), otherwise disabled. Added recovery polling test to Task 4.4.
- **MI-24 (Medium):** Amended development convention note (Task 4.2): "Task 4.5 must follow Task 4.4". Added critical path: `4.1 → 4.2 → 4.4 → 4.5 → 4.9`.

**Codebase verification fixes (Phase 3 confirmed):**

- **Q9:** `GET /api/users/me` does not exist in Phase 3. Added Phase 3 prerequisite block to Task 4.2 documenting the required endpoint and `UserResponse` DTO.
- **Q11:** `thumbnailUrl`/`originalUrl` not in `PhotoResponse`. Added Phase 3 prerequisite block to Task 4.4 documenting the required DTO and controller changes.
- **Original URL TTL:** Corrected Task 4.6 `['photo', id]` query TTL — originals expire in 1 hour (`StorageService.ORIGINAL_EXPIRY_SECONDS = 3600`), not 15 minutes. Updated to `staleTime: 55min / gcTime: 60min`.
- **Trash endpoint:** Corrected Task 4.7d tests from `/api/trash` to `/api/photos/trash` (actual `PhotoController` mapping).
- **Restore status:** Corrected Task 4.7d restore test from `204` to `200 OK` (actual controller response).
- **Keyword edit verb:** Corrected Task 4.7a edit test from `PATCH` to `PUT` (`KeywordController` uses `@PutMapping`).
- **Album add photo:** Corrected Task 4.7b add test — `POST /api/albums/{albumId}/photos/{photoId}` uses photoId as path parameter, no request body (`AlbumController` confirmed).
- **`deletedAt` in Photo type:** Added `deletedAt: string | null` to the `Photo` TypeScript interface in `types.ts` (Task 4.2 Step 2). Required by TrashPage to display deletion date and retention window.

### v3.0 — 2026-03-06

Applied all issues from Phase 4 Critical Implementation Review v2 (`docs/plans/2026-02-25-saas-conversion-phase-4-critical-review-2.md`).

**Critical fixes:**

- **CI-6 (High):** Added session hydration to `main.tsx`: `bootstrapCsrf()` → `fetchCurrentUser()` → `setAuth(user)` → render. Added `isHydrating: boolean` field to auth store. `ProtectedRoute` now renders a spinner (not a redirect) while `isHydrating` is true. Added `fetchCurrentUser()` to `client.ts`. Added 3 hydration tests to Task 4.2.
- **CI-7 (High):** Replaced `npm create vite@latest` with `npm create vite@6.3.5` (verify current stable at implementation time). Consistent with plan's pin-everything convention.
- **CI-8 (Blocking):** Updated all MSW handler syntax from v1 (`rest.post`, `req/res/ctx`) to v2 (`http.post`, `HttpResponse.json`). Pinned `msw@2.7.3` in Task 4.1 testing dependency block. Added note: all handlers in this phase use MSW v2 syntax.
- **CI-9 (High):** Replaced `useQuery<Photo[]>` with `useInfiniteQuery<SearchResult>` in Task 4.4. Documented page-number pagination model (`?page=0&size=50`). Added `data.pages.flatMap()` for PhotoGrid. Specified TanStack Virtual infinite scroll trigger (`range.endIndex` watching). Added 2 pagination tests.

**Minor fixes:**

- **MI-11 (Medium):** Added `{/* UploadDropzone — integrated in Task 4.5 */}` placeholder note to Task 4.4. Added `Modify: frontend/src/pages/LibraryPage.tsx` to Task 4.5 file list.
- **MI-12 (Low):** Added `<Route path="*" element={<NotFoundPage />} />` as last route in Task 4.9. Added smoke test for unknown path. Updated commit message.
- **MI-13 (Medium):** Documented album detail as inline panel in Task 4.7b — no `/albums/:id` route needed. Updated commit message.
- **MI-14 (Medium):** Specified `localStorage` keyed by user ID as saved search persistence mechanism in Task 4.7c. Added 3 persistence tests. Documented key format: `saved_searches_${userId}`.
- **MI-15 (Low):** Added `staleTime: 10min` / `gcTime: 15min` to `['photo', id]` query in Task 4.6 with rationale (same 15-min pre-signed URL TTL as thumbnails).
- **MI-16 (Low):** Documented server-side email verification in Task 4.3 Step 3. Added `LoginPage` `?verified=true` banner requirement and test. No `/verify-email` frontend route needed.
- **MI-17 (Medium):** Added ESLint setup to Task 4.1 Step 2 (install, `eslint.config.js` flat config, `react/no-danger: error`). Added `"lint": "eslint src"` to `package.json` scripts in Task 4.1 Step 3. Added `npm run lint` to Task 4.1 Step 4 verification and Task 4.9 phase completion gate.

### v2.0 — 2026-03-06

Applied all issues from Phase 4 Critical Implementation Review (`docs/plans/2026-02-25-saas-conversion-phase-4-critical-review-1.md`).

**Critical fixes:**

- **CI-1 (Blocking):** Removed `/share/:token` route from Task 4.9. Added Phase 5 note — route is added in Task 5.2 alongside `SharePage`. Phase 4 now builds without a forward dependency on Phase 5.
- **CI-2 (High):** Replaced all empty TDD stubs with concrete test assertions across Tasks 4.2–4.8. Added testing dependency installation block (`@testing-library/react`, `@testing-library/user-event`, `msw`, `vitest`, `jsdom`) to Task 4.1. Added `vitest.config.ts` setup requirement.
- **CI-3 (Blocking):** Added `ProtectedRoute` component to Task 4.2 with explicit redirect behavior (`<Navigate to="/login" replace state={{ from: location }} />`), post-login redirect contract in Task 4.3 (`location.state?.from`), and test coverage for authenticated and unauthenticated cases.
- **CI-4 (Blocking):** Added `bootstrapCsrf()` function to Task 4.2 `client.ts`. Added `main.tsx` step calling `bootstrapCsrf()` before `ReactDOM.createRoot()`, with an error fallback for bootstrap failure. Noted backend requirement: verify `GET /api/csrf` public endpoint exists.
- **CI-5 (High):** Added full polling bounds to `useUpload` in Task 4.5: Phase 1 (3s × 5 polls), Phase 2 (exponential backoff capped at 60s), 10-minute hard timeout with `"processing_timeout"` reason, component-unmount stop via `enabled: false`, and 30-second UX message for in-progress uploads.

**High/medium fixes:**

- **MI-1 (Medium):** Pinned `shadcn-ui` to `@0.9.4` (verify at implementation time). Added `package-lock.json` commit requirement. Added phase-wide convention: no `@latest` in any command.
- **MI-2 (Medium):** Split Task 4.7 into four independent tasks — 4.7a (Keywords), 4.7b (Albums), 4.7c (Search), 4.7d (Trash) — each with its own TDD cycle and commit. Task 4.9 prerequisite updated accordingly.
- **MI-3 (Medium):** Added Vite proxy config for `/api`, `/oauth2`, `/login`, `/logout` to Task 4.1 Step 2. Added `.env.example` creation and `.gitignore` entries. Noted `VITE_API_BASE_URL` usage pattern for production builds.
- **MI-4 (Medium):** Added `frontend/src/api/types.ts` to Task 4.2 with `Photo`, `Album`, `Keyword`, `QuotaInfo`, `ShareToken`, `SearchResult`, `ProcessingStatus`, `User`, `PhotoMetadata`, and `ApiError`. Mandated typed generics in all hooks.
- **MI-5:** Specified GPS data flow: `showGps` from `authStore` (no additional API call), GPS fields absent from DOM (not CSS-hidden) when false, reactive update on settings toggle without page reload.
- **MI-6:** Specified 15-minute pre-signed URL expiry, `staleTime: 10min` / `gcTime: 15min` on photo list query. Clarified bulk URL return in list response (no N+1 per-photo URL requests).
- **MI-7 (Medium):** Added `QueryCache` and `MutationCache` 401 handlers to Task 4.2 `client.ts` using `window.location.replace('/login')`. Both caches covered. `ApiError` class added to `types.ts`.
- **MI-8:** Fixed quota guard to `quota?.usedBytes ?? 0`. Added loading skeleton and error state to SettingsPage. Added explicit display format (`"X.X GB of Y.Y GB used"`).
- **MI-9:** Added development convention note to Task 4.2 (tasks 4.3–4.8 are standalone). Added prerequisite note and smoke test steps to Task 4.9.
- **MI-10:** Added `npm run build` and `npm run test` to Task 4.1 Step 4 verification. Added phase-completion gate block to Task 4.9 Step 3. Added `typecheck`, `test`, `test:watch`, `coverage` scripts to Task 4.1 `package.json` setup.

### v1.0 — 2026-02-25

Initial plan.
