import '@testing-library/jest-dom/vitest';
import { beforeAll, afterEach, afterAll } from 'vitest';
import { setupServer } from 'msw/node';

// Node.js 25 exposes a global `localStorage` that requires `--localstorage-file`
// to work. In a jsdom test environment the real storage lives on `window.localStorage`.
// We replace the broken global with a simple in-memory Map-backed implementation so
// that tests (and production-like component code) can call localStorage.setItem/getItem
// without hitting the Node.js restriction.
if (typeof localStorage === 'object' && typeof localStorage.setItem !== 'function') {
  const store = new Map<string, string>();
  const memStorage: Storage = {
    get length() { return store.size; },
    key(index: number) { return [...store.keys()][index] ?? null; },
    getItem(key: string) { return store.get(key) ?? null; },
    setItem(key: string, value: string) { store.set(key, value); },
    removeItem(key: string) { store.delete(key); },
    clear() { store.clear(); },
  };
  // @ts-expect-error -- intentionally replacing Node.js 25's broken global
  globalThis.localStorage = memStorage;
}

export const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
