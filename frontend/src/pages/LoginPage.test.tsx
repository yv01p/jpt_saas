import { http, HttpResponse } from 'msw';
import { server } from '../test/setup';
import { mockUserWire } from '../test/factories';
import userEvent from '@testing-library/user-event';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import LoginPage from './LoginPage';
import RegisterPage from './RegisterPage';

test('LoginPage renders email/password fields and submit button', () => {
  render(<LoginPage />, { wrapper: MemoryRouter });
  expect(screen.getByRole('textbox', { name: /email/i })).toBeInTheDocument();
  expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
});

test('login form submits with correct credentials', async () => {
  // Use MSW to capture the request body — verifies the actual wire format sent to the API.
  // Do NOT use vi.mock('../api/hooks/useAuth') here: module mocking is file-scoped and would
  // break the redirect test below (which needs the real useAuth flow) and the render test above
  // (which needs useAuth() to return a non-null value). All tests in this file use MSW only (CI-25).
  let capturedBody: unknown;
  server.use(
    http.post('/api/auth/login', async ({ request }) => {
      capturedBody = await request.json();
      return HttpResponse.json({ message: 'Login successful' });
    }),
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
  );
  render(<LoginPage />, { wrapper: MemoryRouter });
  await userEvent.type(screen.getByLabelText(/email/i), 'a@b.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'password123');
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  await waitFor(() =>
    expect(capturedBody).toEqual({ email: 'a@b.com', password: 'password123' })
  );
});

test('LoginPage redirects to location.state.from after login', async () => {
  server.use(
    http.post('/api/auth/login', () => HttpResponse.json({ message: 'Login successful' })),
    http.get('/api/users/me', () => HttpResponse.json(mockUserWire)),
  );
  render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state: { from: '/photo/42' } }]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/photo/42" element={<div>photo page</div>} />
      </Routes>
    </MemoryRouter>
  );
  await userEvent.type(screen.getByLabelText(/email/i), 'a@b.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'password123');
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  expect(await screen.findByText('photo page')).toBeInTheDocument();
});

test('LoginPage renders "Your email has been verified" banner when ?verified=true', () => {
  render(
    <MemoryRouter initialEntries={['/login?verified=true']}>
      <LoginPage />
    </MemoryRouter>
  );
  expect(screen.getByText(/your email has been verified/i)).toBeInTheDocument();
});

test('RegisterPage enforces 12-character password minimum', async () => {
  render(<RegisterPage />, { wrapper: MemoryRouter });
  await userEvent.type(screen.getByLabelText(/email/i), 'user@example.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'short');
  await userEvent.click(screen.getByRole('button', { name: /register/i }));
  expect(screen.getByText(/at least 12 characters/i)).toBeInTheDocument();
});

test('RegisterPage shows success message after registration', async () => {
  server.use(
    http.post('/api/auth/register', () => new HttpResponse(null, { status: 204 })),
  );
  render(<RegisterPage />, { wrapper: MemoryRouter });
  await userEvent.type(screen.getByLabelText(/email/i), 'new@user.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'securepassword123');
  await userEvent.click(screen.getByRole('button', { name: /register/i }));
  expect(await screen.findByText(/check your email/i)).toBeInTheDocument();
});

test('LoginPage shows error message when login fails', async () => {
  server.use(
    http.post('/api/auth/login', () => new HttpResponse(null, { status: 401 })),
  );
  render(<LoginPage />, { wrapper: MemoryRouter });
  await userEvent.type(screen.getByLabelText(/email/i), 'bad@user.com');
  await userEvent.type(screen.getByLabelText(/password/i), 'wrongpassword123');
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  expect(await screen.findByText(/login failed|invalid|unauthorized/i)).toBeInTheDocument();
});
