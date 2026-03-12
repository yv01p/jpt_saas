import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { test, expect, vi } from 'vitest';
import { server } from '../test/setup';
import { QueryClientWrapper } from '../test/QueryClientWrapper';
import { mockPhoto } from '../test/factories';
import TrashPage from './TrashPage';

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
  // Freeze time so component's Date.now() matches test's Date.now()
  const now = new Date('2026-03-10T12:00:00Z');
  vi.useFakeTimers();
  vi.setSystemTime(now);

  // Photo deleted 5 days ago — 25 days remaining in a 30-day retention window
  const deleted_at = new Date(now.getTime() - 5 * 24 * 60 * 60 * 1000).toISOString();
  const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440001';
  server.use(
    http.get('/api/photos/trash', () => HttpResponse.json([
      mockPhoto({ id: PHOTO_ID, filename: 'old.jpg', deleted_at })
    ]))
  );
  render(<TrashPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText(/25 days remaining/i)).toBeInTheDocument();

  vi.useRealTimers();
});
