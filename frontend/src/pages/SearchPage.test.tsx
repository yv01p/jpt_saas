import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { test, expect, afterEach } from 'vitest';
import { server } from '../test/setup';
import { QueryClientWrapper } from '../test/QueryClientWrapper';
import useAuthStore from '../stores/authStore';
import SearchPage from './SearchPage';

afterEach(() => {
  useAuthStore.setState({ user: null, isAuthenticated: false, isHydrating: false });
});

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
