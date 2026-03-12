import type { User, Photo } from '../api/types';
import { camelizeKeys } from '../api/client';

const MOCK_PHOTO_ID = '550e8400-e29b-41d4-a716-446655440000';
const MOCK_USER_ID = '660e8400-e29b-41d4-a716-446655440000';

export function mockPhoto(overrides: Record<string, unknown> = {}) {
  return {
    id: MOCK_PHOTO_ID,
    filename: 'test.jpg',
    caption: null,
    title: null,
    description: null,
    size_bytes: 1024,
    taken_at: null,
    uploaded_at: '2026-01-01T00:00:00Z',
    updated_at: null,
    deleted_at: null,
    processing_status: 'DONE',
    thumbnail_url: 'https://minio/thumb/test.jpg',
    original_url: 'https://minio/original/test.jpg',
    ...overrides,
  };
}

export const mockUserWire = {
  id: MOCK_USER_ID,
  email: 'test@example.com',
  show_gps: false,
  quota_bytes: 10737418240,
  used_bytes: 0,
};

export const mockUser: User = {
  id: MOCK_USER_ID,
  email: 'test@example.com',
  showGps: false,
  quotaBytes: 10737418240,
  usedBytes: 0,
};

export function mockMetadata() {
  return {
    exifData: { Artist: 'Test Photographer', FocalLength: '50mm' },
  };
}

export function mockMetadataWithGps() {
  return {
    exifData: { Artist: 'Test Photographer' },
    gpsLatitude: 48.8566,
    gpsLongitude: 2.3522,
  };
}

export function mockPhotoApp(overrides: Partial<Photo> = {}): Photo {
  return { ...(camelizeKeys(mockPhoto()) as Photo), ...overrides };
}
