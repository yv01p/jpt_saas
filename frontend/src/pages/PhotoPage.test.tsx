import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { test, expect } from 'vitest';
import { server } from '../test/setup';
import { mockPhoto, mockUser, mockMetadata, mockMetadataWithGps } from '../test/factories';
import { QueryClientWrapper } from '../test/QueryClientWrapper';
import useAuthStore from '../stores/authStore';
import PhotoPage from './PhotoPage';
import MetadataPanel from '../components/MetadataPanel';

const PHOTO_ID = '550e8400-e29b-41d4-a716-446655440000';
const KEYWORD_ID = '770e8400-e29b-41d4-a716-446655440001';

test('MetadataPanel renders EXIF fields as text nodes', () => {
  const metadata = mockMetadata();
  render(<MetadataPanel metadata={metadata} />);
  expect(screen.getByText(metadata.exifData['Artist'])).toBeInTheDocument();
});

test('GPS fields are absent from DOM when showGps is false', () => {
  useAuthStore.setState({ user: { ...mockUser, showGps: false } });
  const metadata = mockMetadataWithGps();
  render(<MetadataPanel metadata={metadata} />);
  expect(screen.queryByText(/48\.8566/)).not.toBeInTheDocument();
});

test('GPS fields render as text when showGps is true', () => {
  useAuthStore.setState({ user: { ...mockUser, showGps: true } });
  const metadata = mockMetadataWithGps();
  render(<MetadataPanel metadata={metadata} />);
  expect(screen.getByText(/48\.8566/)).toBeInTheDocument();
});

test('assigning keyword to photo calls POST /api/photos/{id}/keywords/{keywordId}', async () => {
  let assignCalled = false;
  server.use(
    http.get(`/api/photos/${PHOTO_ID}`, () => HttpResponse.json(mockPhoto({ id: PHOTO_ID }))),
    http.get(`/api/photos/${PHOTO_ID}/keywords`, () => HttpResponse.json([])),
    http.get('/api/keywords', () => HttpResponse.json([
      { id: KEYWORD_ID, name: 'Animals', parent_id: null, children: [] }
    ])),
    http.post(`/api/photos/${PHOTO_ID}/keywords/${KEYWORD_ID}`, () => {
      assignCalled = true;
      return new HttpResponse(null, { status: 200 });
    }),
  );
  render(<PhotoPage photoId={PHOTO_ID} />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /add keyword/i }));
  await userEvent.click(screen.getByText('Animals'));
  expect(assignCalled).toBe(true);
});

test('removing keyword from photo calls DELETE /api/photos/{id}/keywords/{keywordId}', async () => {
  let removeCalled = false;
  server.use(
    http.get(`/api/photos/${PHOTO_ID}`, () => HttpResponse.json(mockPhoto({ id: PHOTO_ID }))),
    http.get(`/api/photos/${PHOTO_ID}/keywords`, () => HttpResponse.json([
      { id: KEYWORD_ID, name: 'Animals', parent_id: null, children: [] }
    ])),
    http.delete(`/api/photos/${PHOTO_ID}/keywords/${KEYWORD_ID}`, () => {
      removeCalled = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  render(<PhotoPage photoId={PHOTO_ID} />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /remove animals/i }));
  expect(removeCalled).toBe(true);
});
