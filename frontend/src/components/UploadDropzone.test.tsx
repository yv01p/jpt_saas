import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { vi, test, expect, afterEach } from 'vitest';

import { server } from '../test/setup';
import { mockPhoto } from '../test/factories';
import { QueryClientWrapper } from '../test/QueryClientWrapper';
import UploadDropzone from './UploadDropzone';

afterEach(() => {
  vi.useRealTimers();
});

test('HTTP 409 on upload shows duplicate photo message', async () => {
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json({ message: 'Conflict' }, { status: 409 })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
});

test('HTTP 413 on upload shows quota exceeded message', async () => {
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json({ message: 'Payload Too Large' }, { status: 413 })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  expect(await screen.findByText(/quota exceeded/i)).toBeInTheDocument();
});

test('drop triggers POST /api/photos', async () => {
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  let uploadCalled = false;
  server.use(
    http.post('/api/photos', () => {
      uploadCalled = true;
      return HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }));
    }),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () =>
      HttpResponse.json({ id: UPLOAD_ID, processing_status: 'DONE' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  expect(uploadCalled).toBe(true);
});

test('polls /api/photos/{id}/status after upload', async () => {
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  let pollCalled = false;
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () => {
      pollCalled = true;
      return HttpResponse.json({ id: UPLOAD_ID, processing_status: 'DONE' });
    }),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  await waitFor(() => expect(pollCalled).toBe(true));
});

test('terminal "done" status stops polling', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  let pollCount = 0;
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () => {
      pollCount++;
      return HttpResponse.json({ id: UPLOAD_ID, processing_status: 'DONE' });
    }),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  await vi.advanceTimersByTimeAsync(3_000);
  const countAfterDone = pollCount;
  await vi.advanceTimersByTimeAsync(10_000);
  expect(pollCount).toBe(countAfterDone); // no further polls after terminal state
});

test('terminal "FAILED" status renders error message', async () => {
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () =>
      HttpResponse.json({ id: UPLOAD_ID, processing_status: 'FAILED' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  expect(await screen.findByText(/processing failed/i)).toBeInTheDocument();
});

test('polling stops after 10-minute timeout and shows timeout message', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () =>
      HttpResponse.json({ id: UPLOAD_ID, processing_status: 'PROCESSING' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  await vi.advanceTimersByTimeAsync(10 * 60 * 1000 + 1_000);
  expect(screen.getByText(/processing timed out/i)).toBeInTheDocument();
});

test('polling uses exponential backoff after 5 polls', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  const callTimes: number[] = [];
  let pollCount = 0;
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () => {
      callTimes.push(Date.now());
      pollCount++;
      return HttpResponse.json({
        id: UPLOAD_ID,
        processing_status: pollCount >= 20 ? 'DONE' : 'PROCESSING',
      });
    }),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  // Advance through phase 1 (5 polls × 3s) and into phase 2
  await vi.advanceTimersByTimeAsync(3_000 * 5 + 20_000);
  expect(callTimes.length).toBeGreaterThanOrEqual(7);
  const phase1Interval = callTimes[1] - callTimes[0]; // ~3000ms
  const phase2Interval = callTimes[6] - callTimes[5]; // should be > 3000ms (exponential)
  expect(phase2Interval).toBeGreaterThan(phase1Interval);
});

test('polling stops when component unmounts', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  let pollCount = 0;
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () => {
      pollCount++;
      return HttpResponse.json({ id: UPLOAD_ID, processing_status: 'PROCESSING' });
    }),
  );
  const { unmount } = render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  await vi.advanceTimersByTimeAsync(3_000);
  expect(pollCount).toBeGreaterThan(0);
  unmount();
  const countAtUnmount = pollCount;
  await vi.advanceTimersByTimeAsync(30_000); // advance well past next poll interval
  expect(pollCount).toBe(countAtUnmount);    // no new polls after unmount
});

test('"still processing" message appears after 30 seconds in non-terminal state', async () => {
  vi.useFakeTimers();
  const UPLOAD_ID = '550e8400-e29b-41d4-a716-446655440001';
  server.use(
    http.post('/api/photos', () =>
      HttpResponse.json(mockPhoto({ id: UPLOAD_ID, processing_status: 'PENDING' }))),
    http.get(`/api/photos/${UPLOAD_ID}/status`, () =>
      HttpResponse.json({ id: UPLOAD_ID, processing_status: 'PROCESSING' })),
  );
  render(<UploadDropzone />, { wrapper: QueryClientWrapper });
  const file = new File(['bytes'], 'photo.jpg', { type: 'image/jpeg' });
  await userEvent.upload(screen.getByTestId('dropzone-input'), file);
  expect(screen.queryByText(/still processing/i)).not.toBeInTheDocument();
  await vi.advanceTimersByTimeAsync(30_000 + 100);
  expect(screen.getByText(/still processing/i)).toBeInTheDocument();
});
