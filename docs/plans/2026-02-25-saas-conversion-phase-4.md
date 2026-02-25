# JPhotoTagger SaaS Conversion — Phase 4: React Frontend

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert JPhotoTagger from a single-user Java Swing desktop app into a multi-user web SaaS application per the approved design (docs/plans/2026-02-24-saas-conversion-design.md).

**Architecture:** Spring Boot 3 REST API wrapping existing domain/metadata modules + React frontend + PostgreSQL + MinIO + Redis. Worker container handles image processing via Redis Streams. All services run in Docker Compose on a VPS.

**Tech Stack:** Java 21, Spring Boot 3, Gradle 8, PostgreSQL 16, MinIO, Redis 7, React 18, Vite, TanStack Query, Zustand, shadcn/ui, Tailwind, Flyway, Testcontainers, JUnit 5, Vitest, Playwright.

**Reference:** All design decisions, schemas, configurations, and security requirements are in `docs/plans/2026-02-24-saas-conversion-design.md` (v4.0). Read it before implementing any task.

**All phases:** See `docs/plans/2026-02-25-saas-conversion-index.md` for the full phase list.

---

### Task 4.1: Vite + React Project Scaffold

**Files:**
- Create: `frontend/` directory with Vite + React 18 + TypeScript

**Step 1: Initialize project**

```bash
cd frontend
npm create vite@latest . -- --template react-ts
npm install react-router-dom@6 @tanstack/react-query zustand
npm install @tanstack/react-virtual react-dropzone
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p
npx shadcn-ui@latest init
```

**Step 2: Configure Tailwind, shadcn/ui**

**Step 3: Set up directory structure**

```
frontend/src/
├── api/           — API client (TanStack Query hooks)
├── components/    — Reusable components
├── pages/         — Route pages
├── stores/        — Zustand stores
├── lib/           — Utilities
└── App.tsx        — Router setup
```

**Step 4: Verify dev server starts**

Run: `npm run dev`
Expected: Vite dev server running

**Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: React frontend scaffold — Vite, TanStack Query, Zustand, shadcn"
```

### Task 4.2: API Client & Auth Store

**Files:**
- Create: `frontend/src/api/client.ts`
- Create: `frontend/src/api/hooks/useAuth.ts`
- Create: `frontend/src/stores/authStore.ts`

**Step 1: Write failing test — auth hook**

```typescript
// frontend/src/api/hooks/useAuth.test.ts
test('login sets authenticated state', async () => { });
test('logout clears authenticated state', async () => { });
```

**Step 2: Implement API client**

- Axios/fetch wrapper with CSRF token header (`X-XSRF-TOKEN` from `XSRF-TOKEN` cookie)
- TanStack Query interceptor for global CSRF header

**Step 3: Implement auth store and hooks**

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: API client with CSRF, auth store, auth hooks"
```

### Task 4.3: Auth Pages — Login & Register

**Files:**
- Create: `frontend/src/pages/LoginPage.tsx`
- Create: `frontend/src/pages/RegisterPage.tsx`

**Step 1: Write test — login form submits**

**Step 2: Implement LoginPage**

Email/password form + Google/GitHub OAuth buttons.

**Step 3: Implement RegisterPage**

Sign up form with 12-char password minimum + email verification prompt.

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: login and registration pages"
```

### Task 4.4: Photo Grid — Library View

**Files:**
- Create: `frontend/src/pages/LibraryPage.tsx`
- Create: `frontend/src/components/PhotoGrid.tsx`
- Create: `frontend/src/components/PhotoCard.tsx`

**Step 1: Write test — photo grid renders thumbnails**

**Step 2: Implement PhotoGrid with TanStack Virtual**

Virtualized grid rendering only visible rows. Lazy-load thumbnails via pre-signed URLs.

**Step 3: Implement PhotoCard**

Thumbnail display + processing status indicator (pending/processing/done/failed).

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: virtualized photo grid with lazy thumbnails"
```

### Task 4.5: Upload Component

**Files:**
- Create: `frontend/src/components/UploadDropzone.tsx`
- Create: `frontend/src/api/hooks/useUpload.ts`

**Step 1: Write test — upload triggers API call and polls status**

**Step 2: Implement UploadDropzone**

React Dropzone with drag & drop. Progress indicator. Error handling for 409 (duplicate) and 413 (quota exceeded).

**Step 3: Implement useUpload hook**

Multipart upload + poll `/api/photos/{id}/status` every 3 seconds until `done` or `failed`.

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: upload dropzone with status polling"
```

### Task 4.6: Single Photo View + Metadata Panel

**Files:**
- Create: `frontend/src/pages/PhotoPage.tsx`
- Create: `frontend/src/components/MetadataPanel.tsx`

**Step 1: Write test — metadata panel displays EXIF data**

**Step 2: Implement PhotoPage**

Full-size photo view via pre-signed original URL. Metadata panel sidebar.

**Step 3: Implement MetadataPanel**

Display EXIF, IPTC, XMP data in organized tabs/sections. GPS display controlled by user setting.

**Step 4: Run tests, verify pass**

**Step 5: Commit**

```bash
git commit -m "feat: single photo view with metadata panel"
```

### Task 4.7: Keywords, Albums, Search, Trash Pages

**Files:**
- Create: `frontend/src/pages/KeywordsPage.tsx`
- Create: `frontend/src/pages/AlbumsPage.tsx`
- Create: `frontend/src/pages/SearchPage.tsx`
- Create: `frontend/src/pages/TrashPage.tsx`

**Step 1: Write tests for each page**

**Step 2: Implement KeywordsPage**

Hierarchical keyword tree. Add/edit/delete keywords. Assign keywords to photos.

**Step 3: Implement AlbumsPage**

Album list + album detail view. Add/remove photos from albums.

**Step 4: Implement SearchPage**

Full-text search + EXIF field search + keyword search. Saved searches.

**Step 5: Implement TrashPage**

List soft-deleted photos. Restore button. Retention window display.

**Step 6: Run tests, verify pass**

**Step 7: Commit**

```bash
git commit -m "feat: keywords, albums, search, trash pages"
```

### Task 4.8: Settings Page

**Files:**
- Create: `frontend/src/pages/SettingsPage.tsx`

**Step 1: Write test — settings displays quota**

**Step 2: Implement SettingsPage**

Account info, storage usage vs quota, linked OAuth accounts, GPS display preference.

**Step 3: Run tests, verify pass**

**Step 4: Commit**

```bash
git commit -m "feat: settings page with quota display"
```

### Task 4.9: Router Setup

**Files:**
- Modify: `frontend/src/App.tsx`

**Step 1: Configure all routes**

```typescript
<Routes>
  <Route path="/login" element={<LoginPage />} />
  <Route path="/register" element={<RegisterPage />} />
  <Route path="/library" element={<ProtectedRoute><LibraryPage /></ProtectedRoute>} />
  <Route path="/photo/:id" element={<ProtectedRoute><PhotoPage /></ProtectedRoute>} />
  <Route path="/keywords" element={<ProtectedRoute><KeywordsPage /></ProtectedRoute>} />
  <Route path="/albums" element={<ProtectedRoute><AlbumsPage /></ProtectedRoute>} />
  <Route path="/search" element={<ProtectedRoute><SearchPage /></ProtectedRoute>} />
  <Route path="/trash" element={<ProtectedRoute><TrashPage /></ProtectedRoute>} />
  <Route path="/settings" element={<ProtectedRoute><SettingsPage /></ProtectedRoute>} />
  <Route path="/share/:token" element={<SharePage />} />
  <Route path="/" element={<Navigate to="/library" />} />
</Routes>
```

**Step 2: Commit**

```bash
git commit -m "feat: React Router setup with all routes"
```

---

**Next Phase:** [Phase 5: Sharing & Polish](2026-02-25-saas-conversion-phase-5.md)
