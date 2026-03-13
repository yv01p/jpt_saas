import { render, screen } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { test, expect } from 'vitest';
import { server } from '../test/setup';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClientWrapper } from '../test/QueryClientWrapper';
import { mockPhoto } from '../test/factories';
import SharePage from './SharePage';

const TOKEN = 'abc123';
const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440000';
const ALBUM_ID = '660e8400-e29b-41d4-a716-446655440001';
const SHARE_ID = '770e8400-e29b-41d4-a716-446655440002';

function renderSharePage() {
  return render(
    <QueryClientWrapper>
      <MemoryRouter initialEntries={[`/share/${TOKEN}`]}>
        <Routes>
          <Route path="/share/:token" element={<SharePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientWrapper>
  );
}

const mockPhotoShare = {
  share: {
    id: SHARE_ID,
    resource_type: 'photo',
    resource_id: PHOTO_ID,
    expires_at: null,
    include_gps: false,
    permissions: 'read',
    created_at: '2026-01-01T00:00:00Z',
  },
  photo: {
    id: PHOTO_ID,
    filename: 'test.jpg',
    caption: 'A lovely photo',
    title: 'My Photo Title',
    description: 'A description of the photo',
    size_bytes: 1024,
    taken_at: null,
    uploaded_at: '2026-01-01T00:00:00Z',
    processing_status: 'DONE',
    thumbnail_url: 'https://minio/thumb/test.jpg',
    original_url: 'https://minio/original/test.jpg',
  },
};

const mockAlbumShare = {
  share: {
    id: SHARE_ID,
    resource_type: 'album',
    resource_id: ALBUM_ID,
    expires_at: null,
    include_gps: false,
    permissions: 'read',
    created_at: '2026-01-01T00:00:00Z',
  },
  album: { id: ALBUM_ID },
};

test('shows loading state initially', async () => {
  server.use(
    http.get(`/api/share/${TOKEN}`, async () => {
      await delay('infinite');
      return HttpResponse.json({});
    }),
  );
  renderSharePage();
  expect(screen.getByText(/loading/i)).toBeInTheDocument();
});

test('shows error when share not found (404)', async () => {
  server.use(
    http.get(`/api/share/${TOKEN}`, () => new HttpResponse(null, { status: 404 })),
  );
  renderSharePage();
  expect(await screen.findByText(/share not found or has expired/i)).toBeInTheDocument();
});

test('renders photo share — shows img with correct src and metadata fields', async () => {
  server.use(
    http.get(`/api/share/${TOKEN}`, () => HttpResponse.json(mockPhotoShare)),
  );
  renderSharePage();
  const img = await screen.findByRole('img', { name: 'test.jpg' });
  expect(img).toHaveAttribute('src', 'https://minio/original/test.jpg');
  expect(screen.getByText('test.jpg')).toBeInTheDocument();
  expect(screen.getByText('A lovely photo')).toBeInTheDocument();
  expect(screen.getByText('My Photo Title')).toBeInTheDocument();
  expect(screen.getByText('A description of the photo')).toBeInTheDocument();
});

test('renders album share — fetches /photos and shows the photo grid', async () => {
  const albumPhoto = mockPhoto({ id: PHOTO_ID, filename: 'album-photo.jpg' });
  server.use(
    http.get(`/api/share/${TOKEN}`, () => HttpResponse.json(mockAlbumShare)),
    http.get(`/api/share/${TOKEN}/photos`, () =>
      HttpResponse.json({
        content: [albumPhoto],
        total_elements: 1,
        total_pages: 1,
        number: 0,
        size: 20,
      })
    ),
  );
  renderSharePage();
  expect(await screen.findByTestId('photo-grid-scroll-container')).toBeInTheDocument();
});

test('GPS hidden when includeGps is false', async () => {
  const shareWithGps = {
    ...mockPhotoShare,
    share: { ...mockPhotoShare.share, include_gps: false },
    photo: {
      ...mockPhotoShare.photo,
      gps_latitude: 48.8566,
      gps_longitude: 2.3522,
    },
  };
  server.use(
    http.get(`/api/share/${TOKEN}`, () => HttpResponse.json(shareWithGps)),
  );
  renderSharePage();
  await screen.findByRole('img', { name: 'test.jpg' });
  expect(screen.queryByText(/48\.8566/)).not.toBeInTheDocument();
  expect(screen.queryByText(/2\.3522/)).not.toBeInTheDocument();
});

test('GPS shown when includeGps is true', async () => {
  const shareWithGps = {
    ...mockPhotoShare,
    share: { ...mockPhotoShare.share, include_gps: true },
    photo: {
      ...mockPhotoShare.photo,
      gps_latitude: 48.8566,
      gps_longitude: 2.3522,
    },
  };
  server.use(
    http.get(`/api/share/${TOKEN}`, () => HttpResponse.json(shareWithGps)),
  );
  renderSharePage();
  expect(await screen.findByText(/48\.8566/)).toBeInTheDocument();
  expect(screen.getByText(/2\.3522/)).toBeInTheDocument();
});
