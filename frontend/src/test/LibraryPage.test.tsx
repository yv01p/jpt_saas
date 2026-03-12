import { render, screen, waitFor, act } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { vi, test, expect, beforeEach, afterEach, describe } from 'vitest';
import type { Virtualizer } from '@tanstack/react-virtual';

import { server } from './setup';
import { mockPhoto, mockPhotoApp } from './factories';
import { QueryClientWrapper } from './QueryClientWrapper';
import { queryClient } from '../api/client';
import { QueryClientProvider } from '@tanstack/react-query';

// --- Module-level mock for useVirtualizer (ESM-compatible) ---
type MockConfig = {
  getVirtualItems: () => { key: number; index: number; start: number }[];
  getTotalSize: () => number;
  range: { startIndex: number; endIndex: number };
  measureElement: ReturnType<typeof vi.fn>;
};

let virtualizerConfig: MockConfig = {
  getVirtualItems: () => [],
  getTotalSize: () => 0,
  range: { startIndex: 0, endIndex: -1 },
  measureElement: vi.fn(),
};

vi.mock('@tanstack/react-virtual', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-virtual')>();
  return {
    ...actual,
    useVirtualizer: () => virtualizerConfig as unknown as Virtualizer<HTMLDivElement, Element>,
  };
});

import PhotoGrid from '../components/PhotoGrid';
import PhotoCard from '../components/PhotoCard';
import LibraryPage from '../pages/LibraryPage';

function LibraryPageWrapper({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

function makeDefaultVirtualizerConfig(photoCount: number): MockConfig {
  const items = Array.from({ length: Math.min(photoCount, 5) }, (_, i) => ({
    key: i,
    index: i,
    start: i * 200,
  }));
  return {
    getVirtualItems: () => items,
    getTotalSize: () => photoCount * 200,
    range: { startIndex: 0, endIndex: Math.min(photoCount - 1, 4) },
    measureElement: vi.fn(),
  };
}

beforeEach(() => {
  queryClient.clear();
  // Reset to a default that renders nothing (no virtual items)
  virtualizerConfig = {
    getVirtualItems: () => [],
    getTotalSize: () => 0,
    range: { startIndex: 0, endIndex: -1 },
    measureElement: vi.fn(),
  };
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('PhotoGrid', () => {
  test('PhotoGrid renders thumbnail images for mocked photo data', () => {
    const photos = [
      mockPhotoApp({ id: '550e8400-e29b-41d4-a716-446655440001' }),
      mockPhotoApp({ id: '550e8400-e29b-41d4-a716-446655440002' }),
    ];
    virtualizerConfig = makeDefaultVirtualizerConfig(photos.length);
    render(<PhotoGrid photos={photos} onLoadMore={() => {}} hasMore={false} />, {
      wrapper: QueryClientWrapper,
    });
    expect(screen.getAllByRole('img')).toHaveLength(2);
  });

  test('PhotoGrid renders photos inside virtual row wrappers', () => {
    const photos = Array.from({ length: 100 }, (_, i) =>
      mockPhotoApp({ id: `550e8400-e29b-41d4-a716-${String(i).padStart(12, '0')}` })
    );
    virtualizerConfig = makeDefaultVirtualizerConfig(photos.length);
    render(<PhotoGrid photos={photos} onLoadMore={vi.fn()} hasMore={false} />, {
      wrapper: QueryClientWrapper,
    });
    expect(screen.getByTestId('photo-grid-scroll-container')).toBeInTheDocument();
    const virtualRows = document.querySelectorAll('[data-index]');
    expect(virtualRows.length).toBeGreaterThan(0);
    expect(virtualRows.length).toBeLessThanOrEqual(photos.length);
  });
});

describe('PhotoCard', () => {
  test('thumbnailUrl is rendered as img src', () => {
    const photo = mockPhotoApp({ thumbnailUrl: 'https://minio/thumb/1.jpg' });
    render(<PhotoCard photo={photo} />, { wrapper: QueryClientWrapper });
    expect(screen.getByRole('img')).toHaveAttribute('src', 'https://minio/thumb/1.jpg');
  });
});

describe('LibraryPage', () => {
  test('photo list query has staleTime of 10 minutes', async () => {
    server.use(
      http.get('/api/photos', () => HttpResponse.json({ photos: [], total: 0, page: 0 }))
    );
    render(<LibraryPage />, { wrapper: LibraryPageWrapper });
    await waitFor(() => {
      const state = queryClient.getQueryState(['photos']);
      expect(state?.dataUpdatedAt).toBeDefined();
    });
    const cache = queryClient.getQueryCache().find({ queryKey: ['photos'] });
    expect((cache?.options as Record<string, unknown>)?.staleTime).toBe(10 * 60 * 1000);
  });

  test('fetches next page when virtual range reaches end of loaded photos', async () => {
    let fetchCount = 0;
    server.use(
      http.get('/api/photos', () => {
        fetchCount++;
        return HttpResponse.json({ photos: [mockPhoto()], total: 200, page: fetchCount - 1 });
      })
    );

    // endIndex: 0, photos.length: 1 => 0 >= 1-10 = -9 => true, and hasNextPage is true
    virtualizerConfig = {
      getVirtualItems: () => [],
      getTotalSize: () => 200,
      range: { startIndex: 0, endIndex: 0 },
      measureElement: vi.fn(),
    };

    render(<LibraryPage />, { wrapper: LibraryPageWrapper });
    await waitFor(() => expect(fetchCount).toBeGreaterThan(1));
  });

  test('does not fetch next page when all photos are loaded', async () => {
    let fetchCount = 0;
    server.use(
      http.get('/api/photos', () => {
        fetchCount++;
        return HttpResponse.json({
          photos: Array.from({ length: 3 }, (_, i) =>
            mockPhoto({ id: `550e8400-e29b-41d4-a716-${String(i).padStart(12, '0')}` })
          ),
          total: 3,
          page: 0,
        });
      })
    );

    // endIndex: 2, photos.length: 3 => 2 >= 3-10 = -7 => true, but hasMore: false blocks load
    virtualizerConfig = {
      getVirtualItems: () => [
        { key: 0, index: 0, start: 0 },
        { key: 1, index: 1, start: 200 },
        { key: 2, index: 2, start: 400 },
      ],
      getTotalSize: () => 600,
      range: { startIndex: 0, endIndex: 2 },
      measureElement: vi.fn(),
    };

    render(<LibraryPage />, { wrapper: LibraryPageWrapper });
    await screen.findAllByRole('img');
    const countAfterLoad = fetchCount;
    await new Promise((r) => setTimeout(r, 100));
    expect(fetchCount).toBe(countAfterLoad);
  });

  test('photo list refetches every 5s when any photo has non-terminal status', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let fetchCount = 0;
    server.use(
      http.get('/api/photos', () => {
        fetchCount++;
        return HttpResponse.json({
          photos: [mockPhoto({ processing_status: 'PROCESSING' })],
          total: 1,
          page: 0,
        });
      })
    );

    // Render with items so "Processing..." text appears
    virtualizerConfig = {
      getVirtualItems: () => [{ key: 0, index: 0, start: 0 }],
      getTotalSize: () => 200,
      range: { startIndex: 0, endIndex: 0 },
      measureElement: vi.fn(),
    };

    render(<LibraryPage />, { wrapper: LibraryPageWrapper });
    await screen.findByText(/processing/i);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    expect(fetchCount).toBeGreaterThan(1);
  });
});
