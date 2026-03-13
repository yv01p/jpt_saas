import { test, expect } from '@playwright/test';
import path from 'path';
import fs from 'fs';
import os from 'os';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Extract XSRF-TOKEN from the browser's cookie jar for use as a request header. */
async function getXsrfToken(page: import('@playwright/test').Page): Promise<string> {
  const cookies = await page.context().cookies();
  const xsrf = cookies.find((c) => c.name === 'XSRF-TOKEN');
  return xsrf?.value ?? '';
}

// ---------------------------------------------------------------------------
// Test
// ---------------------------------------------------------------------------

test('full user journey', async ({ page, browser }) => {
  const testEmail = `e2e-${Date.now()}@example.com`;
  const testPassword = 'TestPassword123!';

  // -------------------------------------------------------------------------
  // Step 1: Clear stale MailPit messages from prior runs
  // -------------------------------------------------------------------------
  await page.request.delete('http://localhost:8025/api/v1/messages');

  // -------------------------------------------------------------------------
  // Step 2: Register a new user via UI
  // -------------------------------------------------------------------------
  await page.goto('/register');
  await page.locator('#email').fill(testEmail);
  await page.locator('#password').fill(testPassword);
  await page.getByRole('button', { name: 'Register' }).click();

  // Wait for success state
  await expect(page.getByText('Check your email')).toBeVisible({ timeout: 10_000 });

  // -------------------------------------------------------------------------
  // Step 3: Complete email verification via MailPit API
  // -------------------------------------------------------------------------
  // Poll for the email to arrive (MailPit may take a moment)
  let tokenMatch: RegExpMatchArray | null = null;
  await expect(async () => {
    const messagesRes = await page.request.get(
      `http://localhost:8025/api/v1/search?query=to:${testEmail}`
    );
    const messages = await messagesRes.json();
    expect(messages.messages).toBeTruthy();
    expect(messages.messages.length).toBeGreaterThan(0);

    const verifyEmail = messages.messages[0];
    const emailRes = await page.request.get(
      `http://localhost:8025/api/v1/message/${verifyEmail.ID}`
    );
    const emailData = await emailRes.json();

    // Extract token — match base64url tokens, 43 chars (SA-P5-2 F6 fix)
    tokenMatch = emailData.Text.match(/\/auth\/verify\?token=([A-Za-z0-9_-]{43})/);
    expect(tokenMatch).not.toBeNull();
  }).toPass({ timeout: 15_000, intervals: [1_000, 2_000, 3_000] });

  const verificationToken = tokenMatch![1];

  // Visit the app first so the CSRF cookie is set
  await page.goto('/');
  const csrfToken = await getXsrfToken(page);

  // Use POST with JSON body (C23 fix)
  const verifyRes = await page.request.post('/api/auth/verify', {
    data: { token: verificationToken },
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': csrfToken,
    },
  });
  expect(verifyRes.ok()).toBeTruthy();

  // -------------------------------------------------------------------------
  // Step 4: Login via UI
  // -------------------------------------------------------------------------
  await page.goto('/login');
  await page.locator('#email').fill(testEmail);
  await page.locator('#password').fill(testPassword);
  await page.getByRole('button', { name: 'Sign In' }).click();

  // After login redirects to /library
  await expect(page).toHaveURL(/\/library/, { timeout: 15_000 });

  // -------------------------------------------------------------------------
  // Step 5: Upload a JPEG photo via file input
  // -------------------------------------------------------------------------
  await page.goto('/library');

  // Create a minimal valid JPEG file in a temp directory
  const tmpDir = os.tmpdir();
  const photoFilename = `e2e-test-photo-${Date.now()}.jpg`;
  const photoPath = path.join(tmpDir, photoFilename);

  // Minimal valid JPEG (Start of Image + End of Image markers)
  const minimalJpeg = Buffer.from([
    0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01,
    0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xff, 0xdb, 0x00, 0x43,
    0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09,
    0x09, 0x08, 0x0a, 0x0c, 0x14, 0x0d, 0x0c, 0x0b, 0x0b, 0x0c, 0x19, 0x12,
    0x13, 0x0f, 0x14, 0x1d, 0x1a, 0x1f, 0x1e, 0x1d, 0x1a, 0x1c, 0x1c, 0x20,
    0x24, 0x2e, 0x27, 0x20, 0x22, 0x2c, 0x23, 0x1c, 0x1c, 0x28, 0x37, 0x29,
    0x2c, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1f, 0x27, 0x39, 0x3d, 0x38, 0x32,
    0x3c, 0x2e, 0x33, 0x34, 0x32, 0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x01,
    0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xff, 0xc4, 0x00, 0x1f, 0x00, 0x00,
    0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
    0x09, 0x0a, 0x0b, 0xff, 0xc4, 0x00, 0xb5, 0x10, 0x00, 0x02, 0x01, 0x03,
    0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7d,
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
    0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08,
    0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72,
    0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45,
    0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
    0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75,
    0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
    0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3,
    0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6,
    0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9,
    0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
    0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4,
    0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xff, 0xda, 0x00, 0x08, 0x01, 0x01,
    0x00, 0x00, 0x3f, 0x00, 0xfb, 0xd3, 0xff, 0xd9,
  ]);
  fs.writeFileSync(photoPath, minimalJpeg);

  // Set the file on the hidden input
  const fileInput = page.locator('[data-testid="dropzone-input"]');
  await fileInput.setInputFiles(photoPath);

  // Wait for upload to complete (look for "Upload complete!" message or photo in grid)
  await expect(page.getByText('Upload complete!')).toBeVisible({ timeout: 30_000 });

  // -------------------------------------------------------------------------
  // Step 6: Poll until photo processing_status === 'DONE'
  // -------------------------------------------------------------------------
  // Get the photo ID from the API
  await page.goto('/');
  const freshCsrf = await getXsrfToken(page);

  const photosRes = await page.request.get('/api/photos?page=0&size=20', {
    headers: { 'X-XSRF-TOKEN': freshCsrf },
  });
  const photosData = await photosRes.json();
  expect(photosData.photos).toBeTruthy();
  expect(photosData.photos.length).toBeGreaterThan(0);

  // Find our photo by filename
  const uploadedPhoto = photosData.photos.find(
    (p: { filename: string }) => p.filename === photoFilename
  ) ?? photosData.photos[0];
  const photoId: string = uploadedPhoto.id;

  // Poll until processing is DONE
  await expect(async () => {
    const response = await page.request.get(`/api/photos/${photoId}`, {
      headers: { 'X-XSRF-TOKEN': freshCsrf },
    });
    const photo = await response.json();
    expect(photo.processing_status).toBe('DONE');
  }).toPass({ timeout: 30_000, intervals: [1_000, 2_000, 5_000] });

  // -------------------------------------------------------------------------
  // Step 7: Verify photo appears in library grid
  // -------------------------------------------------------------------------
  await page.goto('/library');
  await expect(page.locator('[data-testid="photo-grid-scroll-container"]')).toBeVisible({
    timeout: 10_000,
  });
  // Photo thumbnail should appear with the filename as alt text
  await expect(page.locator(`img[alt="${photoFilename}"]`)).toBeVisible({ timeout: 10_000 });

  // -------------------------------------------------------------------------
  // Step 8: View photo metadata (navigate to /photo/{id})
  // -------------------------------------------------------------------------
  await page.goto(`/photo/${photoId}`);
  await expect(page.locator(`img[alt="${photoFilename}"]`)).toBeVisible({ timeout: 10_000 });

  // -------------------------------------------------------------------------
  // Step 9: Add a keyword to the photo
  // -------------------------------------------------------------------------
  // First create a keyword via API
  const csrfForKeyword = await getXsrfToken(page);
  const keywordRes = await page.request.post('/api/keywords', {
    data: { name: `e2e-keyword-${Date.now()}` },
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': csrfForKeyword,
    },
  });
  expect(keywordRes.ok()).toBeTruthy();
  const keywordData = await keywordRes.json();
  const keywordName: string = keywordData.name;

  // Reload the photo page to pick up the new keyword in the picker
  await page.goto(`/photo/${photoId}`);
  await expect(page.locator('h3', { hasText: 'Keywords' })).toBeVisible({ timeout: 10_000 });

  // Click "Add keyword" button to open the picker
  await page.getByRole('button', { name: 'Add keyword' }).click();

  // Click the keyword button in the picker
  await page.getByRole('button', { name: keywordName }).click();

  // Picker should close; keyword should appear in the assigned list
  await expect(page.getByText(keywordName)).toBeVisible({ timeout: 10_000 });

  // -------------------------------------------------------------------------
  // Step 10: Create an album and add the photo to it
  // -------------------------------------------------------------------------
  const csrfForAlbum = await getXsrfToken(page);
  const albumName = `e2e-album-${Date.now()}`;
  const albumRes = await page.request.post('/api/albums', {
    data: { name: albumName },
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': csrfForAlbum,
    },
  });
  expect(albumRes.ok()).toBeTruthy();

  // Navigate to Albums page and interact with the album
  await page.goto('/albums');
  // Click the album button to select it
  await page.getByRole('button', { name: albumName }).click();
  await expect(page.getByRole('button', { name: 'Add Photo' })).toBeVisible({ timeout: 10_000 });

  // Click "Add Photo" button
  await page.getByRole('button', { name: 'Add Photo' }).click();

  // Enter photo ID and confirm
  await page.locator('#add-photo-id-input').fill(photoId);
  await page.getByRole('button', { name: 'Confirm' }).click();

  // Verify the photo appears in the album (the thumbnail img should appear)
  await expect(page.locator(`img[alt="${photoFilename}"]`)).toBeVisible({ timeout: 10_000 });

  // -------------------------------------------------------------------------
  // Step 11: Search by keyword using keyword filter
  // -------------------------------------------------------------------------
  await page.goto('/search');
  // Wait for keyword checkboxes to load (loaded async from /api/keywords)
  const keywordCheckbox = page.getByRole('checkbox', { name: keywordName });
  await expect(keywordCheckbox).toBeVisible({ timeout: 10_000 });
  await keywordCheckbox.check();
  await page.getByRole('button', { name: 'Search' }).click();
  await expect(page.locator('section[aria-label="Search results"] ul li')).toBeVisible({ timeout: 10_000 });

  // -------------------------------------------------------------------------
  // Step 12: Create share link via API (no UI)
  // -------------------------------------------------------------------------
  const csrfForShare = await getXsrfToken(page);
  const shareRes = await page.request.post('/api/shares', {
    data: {
      resource_type: 'photo',
      resource_id: photoId,
      include_gps: false,
    },
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': csrfForShare,
    },
  });
  expect(shareRes.ok()).toBeTruthy();
  const shareData = await shareRes.json();
  const shareToken: string = shareData.token;
  expect(shareToken).toBeTruthy();

  // -------------------------------------------------------------------------
  // Step 13: Open share link in new anonymous browser context, verify photo visible
  // -------------------------------------------------------------------------
  const anonContext = await browser.newContext();
  const anonPage = await anonContext.newPage();
  await anonPage.goto(`/share/${shareToken}`);
  await expect(anonPage.locator(`img[alt="${photoFilename}"]`)).toBeVisible({ timeout: 15_000 });
  await anonContext.close();

  // -------------------------------------------------------------------------
  // Step 14: Delete photo via API (no UI)
  // -------------------------------------------------------------------------
  // Refresh CSRF token (we are in authenticated context still)
  await page.goto('/library');
  const csrfForDelete = await getXsrfToken(page);
  const deleteRes = await page.request.delete(`/api/photos/${photoId}`, {
    headers: { 'X-XSRF-TOKEN': csrfForDelete },
  });
  expect(deleteRes.ok()).toBeTruthy();

  // -------------------------------------------------------------------------
  // Step 15: Verify photo in trash (navigate to /trash, check filename visible)
  // -------------------------------------------------------------------------
  await page.goto('/trash');
  await expect(page.getByText(photoFilename)).toBeVisible({ timeout: 10_000 });

  // -------------------------------------------------------------------------
  // Step 16: Restore photo (use Restore button aria-label)
  // -------------------------------------------------------------------------
  await page.getByRole('button', { name: `Restore ${photoFilename}` }).click();

  // Trash should no longer list the photo (it was restored)
  await expect(page.getByText(photoFilename)).not.toBeVisible({ timeout: 10_000 });

  // -------------------------------------------------------------------------
  // Step 17: Verify photo back in library
  // -------------------------------------------------------------------------
  await page.goto('/library');
  await expect(page.locator('[data-testid="photo-grid-scroll-container"]')).toBeVisible({
    timeout: 10_000,
  });
  await expect(page.locator(`img[alt="${photoFilename}"]`)).toBeVisible({ timeout: 10_000 });

  // Cleanup temp file
  try {
    fs.unlinkSync(photoPath);
  } catch {
    // best effort
  }
});
