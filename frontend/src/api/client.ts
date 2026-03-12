import { ApiError } from './types';
import type { User } from './types';
import useAuthStore from '../stores/authStore';
import { QueryClient, QueryCache, MutationCache } from '@tanstack/react-query';

export async function bootstrapCsrf(): Promise<void> {
  await fetch('/api/csrf', { credentials: 'include' });
}

function toCamelCase(key: string): string {
  return key.replace(/_([a-z])/g, (_, letter: string) => letter.toUpperCase());
}

export function camelizeKeys(obj: unknown): unknown {
  if (Array.isArray(obj)) return obj.map(camelizeKeys);
  if (obj !== null && typeof obj === 'object') {
    return Object.fromEntries(
      Object.entries(obj as Record<string, unknown>).map(([k, v]) => {
        const newKey = toCamelCase(k);
        const newVal = newKey === 'exifData' ? v : camelizeKeys(v);
        return [newKey, newVal];
      })
    );
  }
  return obj;
}

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

export async function apiFetch<T>(url: string, options?: RequestInit): Promise<T> {
  const base = import.meta.env.VITE_API_BASE_URL ?? '';
  const csrfToken = document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1] ?? '';

  let processedOptions = options;
  if (options?.body && typeof options.body === 'string') {
    try {
      const parsed = JSON.parse(options.body);
      processedOptions = { ...options, body: JSON.stringify(snakeifyKeys(parsed)) };
    } catch {
      // Not JSON — leave body unchanged
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
  try {
    return await apiFetch<User>('/api/users/me');
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) return null;
    throw err;
  }
}

export async function hydrateSession(): Promise<void> {
  try {
    const user = await fetchCurrentUser();
    if (user) useAuthStore.getState().setAuth(user);
  } catch {
    // Network error — leave isAuthenticated false
  } finally {
    useAuthStore.setState({ isHydrating: false });
  }
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error) => {
      if (error instanceof ApiError && error.status === 401) {
        useAuthStore.getState().clearAuth();
        window.location.replace('/login');
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
