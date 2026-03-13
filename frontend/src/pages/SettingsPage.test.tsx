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
    http.get('/api/shares', () => HttpResponse.json({ content: [], total_elements: 0, total_pages: 0, number: 0, size: 20 })),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText('2.3 GB of 10.0 GB used')).toBeInTheDocument();
});

test('usedBytes floor guard: never renders negative storage value', async () => {
  server.use(
    http.get('/api/users/me', () => HttpResponse.json({
      ...mockUserWire, used_bytes: -1, quota_bytes: 10_000_000_000,
    })),
    http.get('/api/shares', () => HttpResponse.json({ content: [], total_elements: 0, total_pages: 0, number: 0, size: 20 })),
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
    http.get('/api/shares', () => HttpResponse.json({ content: [], total_elements: 0, total_pages: 0, number: 0, size: 20 })),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('checkbox', { name: /show gps/i }));
  // snakeifyKeys transforms outgoing body: { show_gps: true }
  expect(capturedBody).toEqual({ show_gps: true });
  expect(useAuthStore.getState().user?.showGps).toBe(true);
});

// ---- Manage Shares section ----

const SHARE_ID = '770e8400-e29b-41d4-a716-446655440002';
const RESOURCE_ID = '550e8400-e29b-41d4-a716-446655440000';

const mockShareWire = {
  id: SHARE_ID,
  resource_type: 'photo',
  resource_id: RESOURCE_ID,
  expires_at: null,
  include_gps: false,
  permissions: 'read',
  created_at: '2026-01-01T00:00:00Z',
};

const mockSharesPageWire = {
  content: [mockShareWire],
  total_elements: 1,
  total_pages: 1,
  number: 0,
  size: 20,
};

test('shows "Loading shares..." while shares are loading', async () => {
  server.use(
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
    http.get('/api/shares', async () => {
      await delay('infinite');
      return HttpResponse.json({});
    }),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  // Wait for user profile to load (outer skeleton gone)
  await screen.findByText(/gb of/i);
  // Now shares section should show loading state
  expect(screen.getByText(/loading shares/i)).toBeInTheDocument();
});

test('shows "No active shares." when list is empty', async () => {
  server.use(
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
    http.get('/api/shares', () =>
      HttpResponse.json({ content: [], total_elements: 0, total_pages: 0, number: 0, size: 20 })
    ),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText(/no active shares/i)).toBeInTheDocument();
});

test('renders share item with resource type, creation date, expiry', async () => {
  server.use(
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
    http.get('/api/shares', () => HttpResponse.json(mockSharesPageWire)),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  // Resource type
  expect(await screen.findByText(/photo/i)).toBeInTheDocument();
  // Creation date: '2026-01-01T00:00:00Z' formatted as toLocaleDateString()
  const createdAt = new Date('2026-01-01T00:00:00Z').toLocaleDateString();
  expect(screen.getByText(new RegExp(createdAt.replace(/\//g, '\\/')))).toBeInTheDocument();
  // Expiry: null → "Never"
  expect(screen.getByText(/never/i)).toBeInTheDocument();
});

test('Revoke button calls DELETE and refreshes list', async () => {
  let deleteCalled = false;
  server.use(
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
    http.get('/api/shares', () => HttpResponse.json(mockSharesPageWire)),
    http.delete(`/api/shares/${SHARE_ID}`, () => {
      deleteCalled = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  render(<SettingsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /revoke/i }));
  expect(deleteCalled).toBe(true);
});
