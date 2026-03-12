import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { test, expect } from 'vitest';
import { server } from '../test/setup';
import { QueryClientWrapper } from '../test/QueryClientWrapper';
import KeywordsPage from './KeywordsPage';

test('hierarchical keyword tree renders from API data', async () => {
  const KW1 = '770e8400-e29b-41d4-a716-446655440001';
  const KW2 = '770e8400-e29b-41d4-a716-446655440002';
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([
      { id: KW1, name: 'Animals', parent_id: null, children: [
        { id: KW2, name: 'Dogs', parent_id: KW1, children: [] }
      ]}
    ]))
  );
  render(<KeywordsPage />, { wrapper: QueryClientWrapper });
  expect(await screen.findByText('Animals')).toBeInTheDocument();
  expect(screen.getByText('Dogs')).toBeInTheDocument();
});

test('add keyword calls POST /api/keywords with correct parent', async () => {
  const KW1 = '770e8400-e29b-41d4-a716-446655440001';
  const KW2 = '770e8400-e29b-41d4-a716-446655440002';
  let capturedBody: unknown;
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([{ id: KW1, name: 'Animals', parent_id: null, children: [] }])),
    http.post('/api/keywords', async ({ request }) => {
      capturedBody = await request.json();
      return HttpResponse.json({ id: KW2, name: 'Dogs', parent_id: KW1, children: [] });
    }),
  );
  render(<KeywordsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /add child keyword/i }));
  await userEvent.type(screen.getByLabelText(/keyword name/i), 'Dogs');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  // snakeifyKeys transforms outgoing body: { name: 'Dogs', parent_id: KW1 }
  expect(capturedBody).toEqual({ name: 'Dogs', parent_id: KW1 });
});

test('edit keyword calls PUT /api/keywords/{id}', async () => {
  const KW1 = '770e8400-e29b-41d4-a716-446655440001';
  let capturedBody: unknown;
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([{ id: KW1, name: 'Animals', parent_id: null, children: [] }])),
    http.put(`/api/keywords/${KW1}`, async ({ request }) => {
      capturedBody = await request.json();
      return HttpResponse.json({ id: KW1, name: 'Fauna', parent_id: null, children: [] });
    }),
  );
  render(<KeywordsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /edit animals/i }));
  await userEvent.clear(screen.getByLabelText(/keyword name/i));
  await userEvent.type(screen.getByLabelText(/keyword name/i), 'Fauna');
  await userEvent.click(screen.getByRole('button', { name: /save/i }));
  expect(capturedBody).toEqual({ name: 'Fauna', parent_id: null });
});

test('delete keyword calls DELETE /api/keywords/{id} and removes from list', async () => {
  const KW1 = '770e8400-e29b-41d4-a716-446655440001';
  let deleteCalled = false;
  server.use(
    http.get('/api/keywords', () => HttpResponse.json([{ id: KW1, name: 'Animals', parent_id: null, children: [] }])),
    http.delete(`/api/keywords/${KW1}`, () => {
      deleteCalled = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  render(<KeywordsPage />, { wrapper: QueryClientWrapper });
  await userEvent.click(await screen.findByRole('button', { name: /delete animals/i }));
  await waitFor(() => expect(deleteCalled).toBe(true));
  // Verify UI state after mutation completes (CI-19 fix)
  expect(screen.queryByText('Animals')).not.toBeInTheDocument();
});
