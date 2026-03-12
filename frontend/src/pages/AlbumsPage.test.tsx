import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { test, expect } from 'vitest';
import { server } from '../test/setup';
import { mockPhoto } from '../test/factories';
import { QueryClientWrapper } from '../test/QueryClientWrapper';
import AlbumsPage from './AlbumsPage';

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
