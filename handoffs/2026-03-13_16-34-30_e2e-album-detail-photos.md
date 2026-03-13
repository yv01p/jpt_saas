---
date: 2026-03-13T16:34:30-04:00
git_commit: 5de9426d64b9a45bfc737c8d9b06fd66af46ccb5
branch: master
repository: jpt_saas
topic: "Task 5.6 — E2E Final Stretch: AlbumDetail.photos crash"
tags: [handoff, session-transition, docker, playwright, arm64, e2e, spring-boot, frontend, albums]
status: in_progress
last_updated: 2026-03-13
type: implementation_handoff
---

# Handoff: E2E Step 10 — AlbumDetail.photos Undefined Crash

## 0. Executive Summary (TL;DR)

This session fixed all Spring Page / frontend type mismatches for `SearchResult`, `LibraryPage`, `SearchPage`, `PhotoPage`, `KeywordsPage`, and `TrashPage` — advancing the test from step 7 (library grid) through step 9 (keyword picker) and into step 10. The test now crashes at `AlbumsPage.tsx` because `albumDetail.photos` is `undefined` (the `GET /api/albums/{id}` endpoint returns only album metadata with no photos array), causing a render exception before the "Add Photo" button renders. The single next action is to guard `albumDetail.photos` with `?? []` and rebuild/deploy the frontend.

## 1. Technical State

**Active Working Set:**
- `frontend/src/pages/AlbumsPage.tsx:93` — `albumDetail.photos.map(...)` crashes when `photos` is `undefined`; needs `(albumDetail.photos ?? []).map(...)`
- `frontend/src/pages/AlbumsPage.tsx:49` — `removePhotoMutation` also uses `old.photos.filter(...)` — needs same guard
- `react-build/dist/` — bind-mounted into `jpt_saas-nginx-1:/usr/share/nginx/html` (NOT `react-build/` — this is the ci override); always copy `frontend/dist/*` here and `nginx -s reload`

**Current Errors / Blockers:**
```
Error: expect(received).toBeVisible() failed
Locator: getByRole('button', { name: 'Add Photo' })
Expected: visible
Timeout: 10000ms
Error: element(s) not found

  at /home/ubuntu/jpt_saas/frontend/e2e/full-journey.spec.ts:234:65
```

**Root cause:** `AlbumsPage` renders `{albumDetail.photos.map(...)}` BEFORE the "Add Photo" button. `albumDetail` is fetched from `GET /api/albums/{id}` which returns `{id, userId, name, createdAt, updatedAt}` — no `photos` field. Calling `.map()` on `undefined` throws and aborts the render before the button is reached.

**Environment:**
- Docker stack: ALL 7 services running and healthy
- Uncommitted changes: YES — see §4
- `react-build/dist/` contains the latest built JS (`index-gNGztYHk.js`) — deployed this session
- Nginx bind mount is `react-build/dist/` not `react-build/` (confirmed via `docker inspect jpt_saas-nginx-1`)
- Rate limit: will need flushing before next test run

## 2. Progress Tracker

| Task | Status | Location | Notes |
|------|--------|----------|-------|
| Fix `SearchResult.photos` → `content` | ✅ Complete | `frontend/src/api/types.ts:65` | `content`, `totalElements`, `number` |
| Fix `LibraryPage` to use `content` | ✅ Complete | `frontend/src/pages/LibraryPage.tsx:24` | 3 locations fixed |
| Fix `SearchPage` to use `content` | ✅ Complete | `frontend/src/pages/SearchPage.tsx:135` | 2 locations fixed |
| Fix `GET /api/keywords` Spring Page | ✅ Complete | `frontend/src/pages/PhotoPage.tsx:38` | `.then(r => r.content)` |
| Fix `GET /api/keywords` Spring Page (Search) | ✅ Complete | `frontend/src/pages/SearchPage.tsx:112` | `.then(r => r.content)` |
| Fix `GET /api/keywords` Spring Page (Keywords) | ✅ Complete | `frontend/src/pages/KeywordsPage.tsx:72` | `.then(r => r.content)` |
| Fix `Keyword.children` optional | ✅ Complete | `frontend/src/api/types.ts:48` | `children?: Keyword[]` |
| Fix `flattenKeywords` children null safety | ✅ Complete | `frontend/src/pages/SearchPage.tsx:49` | `kw.children ?? []` |
| Fix `KeywordsPage` children null safety | ✅ Complete | `frontend/src/pages/KeywordsPage.tsx:9` | `kw.children ?? []` |
| Fix `GET /api/albums` Spring Page | ✅ Complete | `frontend/src/pages/AlbumsPage.tsx:18` | `.then(r => r.content)` |
| Fix `GET /api/photos/trash` Spring Page | ✅ Complete | `frontend/src/pages/TrashPage.tsx:26` | `.then(r => r.content)` |
| Fix `albumDetail.photos` undefined crash | 🔄 In Progress | `frontend/src/pages/AlbumsPage.tsx:93` | Root cause of step 10 failure |
| Run E2E test successfully (all 17 steps) | ⏳ Pending | `frontend/` | After AlbumsPage fix |
| Check remaining pages for Spring Page issues | ⏳ Pending | `frontend/src/pages/SharePage.tsx` | SharePage untested yet |
| Commit all fixes | ⏳ Pending | — | After tests pass |
| Restart Caddy | ⏳ Pending | — | Last step |

## 3. Mental Model (Most Critical Section)

**The systematic Spring Page mismatch:**
Every API endpoint that returns a list uses Spring's `Page<T>`, which serializes as `{ content: [...], totalElements: N, number: N, ... }`. The frontend was written assuming flat arrays (`T[]`) or a custom `{photos, total, page}` shape. This session fixed all pages systematically. The pattern for each fix is:
```ts
// Before (broken):
queryFn: () => apiFetch('/api/something'),  // returns Page<T>
// After (fixed):
queryFn: () => apiFetch<{ content: T[] }>('/api/something').then((r) => r.content),
```

**The AlbumDetail problem:**
`GET /api/albums/{id}` returns `Album` entity: `{id, userId, name, createdAt, updatedAt}`. There is NO photos field and NO `/api/albums/{id}/photos` sub-endpoint in `AlbumController`. The `AlbumDetail extends Album { photos: Photo[] }` interface in the frontend is aspirational, not real.

The album-photo relationship is managed via `album_photos` join table, but the detail endpoint doesn't eagerly load photos. The fix options are:
1. **Frontend guard (no JAR rebuild):** Use `(albumDetail.photos ?? []).map(...)` so the component doesn't crash. The photo list will be empty after adding, but the "Add Photo" button renders. After the user adds a photo (step 10), `queryClient.invalidateQueries(['album', albumId])` refreshes the detail, but still won't include photos.
2. **API fix (requires JAR rebuild):** Add a `GET /api/albums/{id}/photos` endpoint or include photos in the `getAlbum` response.

Looking at the E2E test at step 10 (lines 230-244):
```ts
await page.goto('/albums');
await page.getByRole('button', { name: albumName }).click();
await expect(page.getByRole('button', { name: 'Add Photo' })).toBeVisible({ timeout: 10_000 });
await page.getByRole('button', { name: 'Add Photo' }).click();
await page.locator('#add-photo-id-input').fill(photoId);
await page.getByRole('button', { name: 'Confirm' }).click();
// Verify the photo appears in the album (the thumbnail img should appear)
await expect(page.locator(`img[alt="${photoFilename}"]`)).toBeVisible({ timeout: 10_000 });
```

The test expects `img[alt="${photoFilename}"]` after adding the photo. This means after `POST /api/albums/{id}/photos/{photoId}` succeeds, the album detail is invalidated and re-fetched, and the photo's thumbnail `img` should appear. For this to work, the album detail endpoint MUST return photos.

**So the real fix requires a JAR rebuild** — either:
- Add a `GET /api/albums/{id}/photos` endpoint returning `List<PhotoResponse>`
- Or add a "album detail with photos" DTO to `GET /api/albums/{id}`

BUT — the JAR was last rebuilt at ~15:39 and has been running. All Java changes from this session (CSRF handler, e2e profile, JwtService, PhotoController) are compiled into the running JAR. Adding new Java code WOULD require a rebuild. However: the test is currently failing due to a frontend crash (null pointer on `photos`), which prevents us from even confirming whether the photos-after-add flow works.

**Recommended approach:**
1. Add a `GET /api/albums/{id}/photos` endpoint to `AlbumController` returning `List<PhotoResponse>` (reusing the existing `toPhotoResponse` mapper)
2. Add a second query in `AlbumsPage.tsx` for `GET /api/albums/{selectedAlbumId}/photos` to populate `albumDetail.photos`
3. Rebuild the JAR (only Java changes, no Spring profile issues)
4. Rebuild and redeploy frontend

**Alternative (no JAR rebuild, may not work for img check):**
Guard `albumDetail.photos` with `?? []` so the component doesn't crash. After `addPhotoMutation.onSuccess`, the detail is invalidated and re-fetched. The re-fetch still won't return photos, so the `img[alt="..."]` check at line 244 will still fail. This approach is NOT sufficient.

**The `react-build/dist/` vs `react-build/` trap:**
`docker inspect jpt_saas-nginx-1` confirmed the bind mount is `react-build/dist/` not `react-build/`. There's also a `react-build/index.html` file at the `react-build/` root (a stale copy from a previous session). The nginx container reads from `react-build/dist/`. Always:
```bash
cp -r frontend/dist/* react-build/dist/
docker exec jpt_saas-nginx-1 nginx -s reload
```

**Previous session's dead ends — still apply:**
All dead ends from `handoffs/2026-03-13_16-15-03_e2e-searchresult-mismatch.md:118` still apply (MinIO localhost URL, missing GetBucketLocation, etc.).

**Dead Ends — Do Not Repeat:**

| Approach Tried | Why It Failed | Evidence |
|---------------|---------------|----------|
| `albumDetail.photos ?? []` guard only | Test still fails at `img[alt="..."]` line 244 — photos never appear after add because `GET /api/albums/{id}` doesn't return photos | `frontend/e2e/full-journey.spec.ts:244` |
| Copying build to `react-build/` root | Nginx mounts `react-build/dist/` not `react-build/` | `docker inspect jpt_saas-nginx-1` |
| All dead ends from prior sessions | Still apply — see previous handoff | `handoffs/2026-03-13_16-15-03_e2e-searchresult-mismatch.md:118` |

**Key Decisions Made This Session:**

| Decision | Rationale | Alternative Rejected |
|----------|-----------|---------------------|
| Fix Spring Page mismatches in frontend | No JAR rebuild; API returns correct data | Change API to return flat arrays — would need JAR rebuild |
| `Keyword.children` optional | API never returns `children` field (flat list, not tree) | Add `/subtree` endpoint for keywords — unnecessary for E2E |
| `GET /api/albums` uses `.then(r => r.content)` | Same pattern as all other paged endpoints | Dedicated `AlbumPage` type — overkill |

**Assumptions:**
- The `GET /api/albums/{id}/photos` fix will require Java code and a JAR rebuild
- `SharePage.tsx` may also have Spring Page issues (not yet tested — step 13 in E2E test)
- After JAR rebuild, all running migrations (V11-V13) are already applied to the DB and will be skipped by Flyway
- Rate limit bucket needs flushing before each test run

## 4. Delta — Changes Made This Session (Uncommitted)

**Modified files (this session adds to prior session changes):**
- `frontend/src/api/types.ts:65` — `SearchResult`: `photos`→`content`, `total`→`totalElements`, `page`→`number`; `Keyword.children` made optional (`children?: Keyword[]`)
- `frontend/src/pages/LibraryPage.tsx:24` — `lastPage.photos.length`→`lastPage.content.length`, `lastPage.total`→`lastPage.totalElements`, `lastPage.page`→`lastPage.number`; lines 30,40: `p.photos`→`p.content`, `page.photos`→`page.content`
- `frontend/src/pages/SearchPage.tsx:112` — keywords query `.then(r=>r.content)`; line 49: `kw.children??[]`; lines 135,143: `lastPage.content`, `p.content`
- `frontend/src/pages/PhotoPage.tsx:38` — keywords query `.then(r=>r.content)`
- `frontend/src/pages/KeywordsPage.tsx:9` — `removeKeywordById` uses `kw.children??[]`; line 48: guard on render; line 72: keywords query `.then(r=>r.content)`
- `frontend/src/pages/AlbumsPage.tsx:18` — albums query `.then(r=>r.content)`
- `frontend/src/pages/TrashPage.tsx:26` — trash query `.then(r=>r.content)`
- `react-build/index.html` — stale duplicate (root level); real served content is in `react-build/dist/`

**From prior sessions (still uncommitted):**
- `api/src/main/java/org/jphototagger/api/security/SecurityConfig.java:95`
- `api/src/main/java/org/jphototagger/api/security/JwtService.java:39`
- `api/src/main/java/org/jphototagger/api/controller/PhotoController.java:51`
- `docker-compose.ci.yml:68`
- `docker-compose.yml:180` and `docker-compose.yml:184`
- `frontend/e2e/full-journey.spec.ts:153`

**New untracked files (prior sessions):**
- `api/src/main/java/org/jphototagger/api/config/PrimaryDataSourceConfig.java:1`
- `api/src/main/resources/application-e2e.yml:1`
- `api/src/main/resources/application-test.yml:1`
- `api/src/main/resources/db/migration/V11__grant_updated_at_to_jpt_auth.sql:1`
- `api/src/main/resources/db/migration/V12__grant_remaining_columns_to_worker.sql:1`
- `api/src/main/resources/db/migration/V13__grant_select_photo_metadata_to_worker.sql:1`

**Running state:**
- JAR (`api/build/libs/app.jar`): built ~15:39 — does NOT include any new Java changes from this session
- DB migrations V11-V13 applied to running DB
- MinIO policies applied to running MinIO

## 5. Next Steps (Ordered — Do Not Skip Steps)

1. **Flush rate limit** (do first, every run):
   ```bash
   REDIS_PASS=$(grep REDIS_PASSWORD /home/ubuntu/jpt_saas/.env | cut -d= -f2) && docker exec jpt_saas-redis-1 redis-cli -a "$REDIS_PASS" DEL "rate:auth:172.19.0.1" 2>/dev/null
   ```
   Expected: `(integer) 1` or `0`

2. **Add `GET /api/albums/{albumId}/photos` endpoint** to `AlbumController.java`:
   - File: `api/src/main/java/org/jphototagger/api/controller/AlbumController.java:70` (insert after `addPhoto` method)
   - New endpoint: `@GetMapping("/{albumId}/photos")` returning `ResponseEntity<List<PhotoResponse>>`
   - Reuse `PhotoResponse` and the `toPhotoResponse` mapper from `PhotoController.java:52`
   - Service method: use `AlbumPhotoRepository.findByAlbumIdAndUserId(albumId, userId)` to get photo IDs, then fetch each photo
   - See `AlbumController.java:70`, `AlbumService.java:1`, `AlbumPhotoRepository.java:1`

3. **Add second query in `AlbumsPage.tsx`** to fetch photos:
   ```ts
   // Add after the albumDetail query (around line 27)
   const { data: albumPhotos = [] } = useQuery<Photo[]>({
     queryKey: ['album-photos', selectedAlbumId],
     queryFn: () => apiFetch(`/api/albums/${encodeURIComponent(selectedAlbumId!)}/photos`),
     enabled: selectedAlbumId !== null,
     staleTime: 5 * 60 * 1000,
   });
   ```
   Then replace `albumDetail.photos.map(...)` with `albumPhotos.map(...)` and update `addPhotoMutation.onSuccess` to invalidate `['album-photos', selectedAlbumId]`.

4. **Rebuild the JAR:**
   ```bash
   cd /home/ubuntu/jpt_saas/api && ./gradlew bootJar -x test 2>&1 | tail -10
   ```
   Expected: `BUILD SUCCESSFUL`
   Then restart the API container:
   ```bash
   docker-compose -f docker-compose.yml -f docker-compose.ci.yml up -d --no-deps --build api
   ```
   Wait for healthy:
   ```bash
   docker ps --filter name=jpt_saas-api-1 --format "{{.Status}}"
   ```
   Expected: `Up X minutes (healthy)`

5. **Rebuild and deploy frontend:**
   ```bash
   cd /home/ubuntu/jpt_saas/frontend && npm run build 2>&1 | tail -5
   cp -r /home/ubuntu/jpt_saas/frontend/dist/* /home/ubuntu/jpt_saas/react-build/dist/
   docker exec jpt_saas-nginx-1 nginx -s reload
   ```
   Verify: `curl -s http://localhost/ | grep 'src='` — should show new hash.

6. **Run E2E test:**
   ```bash
   REDIS_PASS=$(grep REDIS_PASSWORD /home/ubuntu/jpt_saas/.env | cut -d= -f2) && docker exec jpt_saas-redis-1 redis-cli -a "$REDIS_PASS" DEL "rate:auth:172.19.0.1" 2>/dev/null && cd /home/ubuntu/jpt_saas/frontend && npx playwright test 2>&1
   ```
   Expected: `1 passed`

7. **If a new step fails — parse trace:**
   ```bash
   cd /tmp && unzip -o /home/ubuntu/jpt_saas/frontend/test-results/full-journey-full-user-journey-chromium/trace.zip -d playwright-trace 2>/dev/null && python3 -c "
   import json
   with open('/tmp/playwright-trace/0-trace.network') as f:
       for line in f:
           d = json.loads(line)
           if d.get('type')=='resource-snapshot':
               req = d.get('snapshot',{}).get('request',{})
               resp = d.get('snapshot',{}).get('response',{})
               url = req.get('url','')
               if '/api/' in url or '8025' in url:
                   print(f\"{req.get('method')} {url[:100]} → {resp.get('status')}\")
   " 2>&1
   ```

8. **Watch for `SharePage.tsx` (step 13):** The share page might have similar Spring Page issues. Check `frontend/src/pages/SharePage.tsx:1` — if it calls any paged API endpoint, apply the same `.then(r => r.content)` fix.

9. **After tests pass — commit everything:**
   ```bash
   cd /home/ubuntu/jpt_saas
   git add docker-compose.ci.yml docker-compose.yml \
     frontend/package-lock.json \
     frontend/e2e/full-journey.spec.ts \
     frontend/src/api/types.ts \
     frontend/src/pages/LibraryPage.tsx \
     frontend/src/pages/SearchPage.tsx \
     frontend/src/pages/PhotoPage.tsx \
     frontend/src/pages/KeywordsPage.tsx \
     frontend/src/pages/AlbumsPage.tsx \
     frontend/src/pages/TrashPage.tsx \
     api/src/main/resources/application-test.yml \
     api/src/main/resources/application-e2e.yml \
     api/src/main/resources/db/migration/V11__grant_updated_at_to_jpt_auth.sql \
     api/src/main/resources/db/migration/V12__grant_remaining_columns_to_worker.sql \
     api/src/main/resources/db/migration/V13__grant_select_photo_metadata_to_worker.sql \
     api/src/main/java/org/jphototagger/api/security/SecurityConfig.java \
     api/src/main/java/org/jphototagger/api/security/JwtService.java \
     api/src/main/java/org/jphototagger/api/controller/PhotoController.java \
     api/src/main/java/org/jphototagger/api/controller/AlbumController.java \
     api/src/main/java/org/jphototagger/api/config/PrimaryDataSourceConfig.java
   git commit -m "fix: E2E stack — CSRF handler, e2e profile, datasource, upload endpoint, worker grants (V11-V13), MinIO policies, Spring Page mappings, album photos endpoint"
   ```

10. **Restart Caddy:**
    ```bash
    sudo systemctl start caddy
    ```

## 6. Artifacts & References

- **Plan**: `docs/plans/2026-02-25-saas-conversion-phase-5.md:885` — Task 5.6 spec
- **E2E test**: `frontend/e2e/full-journey.spec.ts:1`
- **AlbumController**: `api/src/main/java/org/jphototagger/api/controller/AlbumController.java:1`
- **AlbumService**: `api/src/main/java/org/jphototagger/api/service/AlbumService.java:1`
- **AlbumPhotoRepository**: `api/src/main/java/org/jphototagger/api/repository/AlbumPhotoRepository.java:1`
- **PhotoController** (reuse `toPhotoResponse`): `api/src/main/java/org/jphototagger/api/controller/PhotoController.java:52`
- **AlbumsPage**: `frontend/src/pages/AlbumsPage.tsx:1`
- **SharePage** (check for Spring Page issues): `frontend/src/pages/SharePage.tsx:1`
- **types.ts**: `frontend/src/api/types.ts:1`
- **Previous handoff**: `handoffs/2026-03-13_16-15-03_e2e-searchresult-mismatch.md:1`
