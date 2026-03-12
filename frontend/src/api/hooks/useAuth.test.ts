import { renderHook, act, waitFor } from '@testing-library/react';
import { useQuery, useMutation, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import React from 'react';
import { server } from '../../test/setup';
import { QueryClientWrapper } from '../../test/QueryClientWrapper';
import { bootstrapCsrf, hydrateSession, camelizeKeys, queryClient } from '../client';
import useAuth from './useAuth';
import useAuthStore from '../../stores/authStore';
import { ApiError } from '../types';
import { mockUserWire, mockUser } from '../../test/factories';

beforeEach(() => {
  useAuthStore.setState({ isHydrating: true, isAuthenticated: false, user: null });
  queryClient.clear();
});

test('csrf bootstrap fetches /api/csrf before app renders', async () => {
  const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValueOnce(new Response());
  await bootstrapCsrf();
  expect(fetchSpy).toHaveBeenCalledWith('/api/csrf', { credentials: 'include' });
  fetchSpy.mockRestore();
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
  server.use(http.post('/api/auth/logout', () => new HttpResponse(null, { status: 204 })));
  useAuthStore.setState({ isAuthenticated: true, user: mockUser });
  const { result } = renderHook(() => useAuth(), { wrapper: QueryClientWrapper });
  await act(async () => result.current.logout());
  expect(useAuthStore.getState().isAuthenticated).toBe(false);
  expect(useAuthStore.getState().user).toBeNull();
});

// Uses the real queryClient from client.ts to verify the actual QueryCache 401 handler wiring
function RealQueryClientWrapper({ children }: { children: React.ReactNode }) {
  return React.createElement(QueryClientProvider, { client: queryClient }, children);
}

describe('401 redirect handling', () => {
  let replaceMock: ReturnType<typeof vi.fn>;
  let originalLocation: Location;

  beforeEach(() => {
    replaceMock = vi.fn();
    originalLocation = window.location;
    Object.defineProperty(window, 'location', { value: { ...originalLocation, replace: replaceMock }, writable: true });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { value: originalLocation, writable: true });
  });

  test('401 response clears auth store and redirects to /login', async () => {
    useAuthStore.setState({ isAuthenticated: true, isHydrating: false, user: mockUser });
    server.use(http.get('/api/photos', () => new HttpResponse(null, { status: 401 })));

    queryClient.setDefaultOptions({ queries: { retry: false } });

    const { result } = renderHook(
      () => useQuery({
        queryKey: ['photos'],
        queryFn: async () => {
          const r = await fetch('/api/photos');
          if (!r.ok) throw new ApiError(r.status, '');
          return r.json();
        },
      }),
      { wrapper: RealQueryClientWrapper }
    );
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(replaceMock).toHaveBeenCalledWith('/login');
  });

  test('401 on mutation clears auth store and redirects to /login', async () => {
    useAuthStore.setState({ isAuthenticated: true, isHydrating: false, user: mockUser });
    server.use(http.post('/api/photos', () => new HttpResponse(null, { status: 401 })));

    queryClient.setDefaultOptions({ mutations: { retry: false } });

    const { result } = renderHook(
      () => useMutation({
        mutationFn: async () => {
          const r = await fetch('/api/photos', { method: 'POST' });
          if (!r.ok) throw new ApiError(r.status, '');
          return r.json();
        },
      }),
      { wrapper: RealQueryClientWrapper }
    );
    await act(async () => result.current.mutate());
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(replaceMock).toHaveBeenCalledWith('/login');
  });
});

// Session hydration tests
test('page refresh with valid session restores auth store from GET /api/users/me', async () => {
  server.use(http.get('/api/users/me', () => HttpResponse.json(mockUserWire)));
  await hydrateSession();
  expect(useAuthStore.getState().isAuthenticated).toBe(true);
  expect(useAuthStore.getState().user?.email).toBe('test@example.com');
  expect(useAuthStore.getState().isHydrating).toBe(false);
});

test('page refresh with no session leaves auth store unauthenticated', async () => {
  server.use(http.get('/api/users/me', () => new HttpResponse(null, { status: 401 })));
  await hydrateSession();
  expect(useAuthStore.getState().isAuthenticated).toBe(false);
  expect(useAuthStore.getState().isHydrating).toBe(false);
});

// camelizeKeys exifData test (SA4-F6)
test('camelizeKeys does not transform keys inside exifData', () => {
  const wire = { exif_data: { GPS_Altitude: '100 m', Artist: 'Test' } };
  const result = camelizeKeys(wire) as Record<string, unknown>;
  const exif = result['exifData'] as Record<string, unknown>;
  expect(exif['GPS_Altitude']).toBe('100 m');
  expect(exif['Artist']).toBe('Test');
});
