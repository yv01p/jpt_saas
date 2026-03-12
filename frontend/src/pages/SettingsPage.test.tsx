import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { test, expect } from 'vitest';
import { server } from '../test/setup';
import { QueryClientWrapper } from '../test/QueryClientWrapper';
import { mockUserWire, mockUser } from '../test/factories';
import useAuthStore from '../stores/authStore';
import SettingsPage from './SettingsPage';

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
