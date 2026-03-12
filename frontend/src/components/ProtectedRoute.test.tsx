import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import ProtectedRoute from './ProtectedRoute';
import useAuthStore from '../stores/authStore';
import { mockUser } from '../test/factories';

beforeEach(() => {
  useAuthStore.setState({ isHydrating: false, isAuthenticated: false, user: null });
});

function renderWithRoutes(initialPath = '/protected') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/protected"
          element={
            <ProtectedRoute>
              <div>secret</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<div>login page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

test('ProtectedRoute renders spinner while isHydrating is true', () => {
  useAuthStore.setState({ isHydrating: true, isAuthenticated: false });
  renderWithRoutes();
  expect(screen.getByTestId('hydration-spinner')).toBeInTheDocument();
  expect(screen.queryByText('secret')).not.toBeInTheDocument();
});

test('unauthenticated user is redirected to /login', () => {
  useAuthStore.setState({ isHydrating: false, isAuthenticated: false });
  renderWithRoutes();
  expect(screen.queryByText('secret')).not.toBeInTheDocument();
  expect(screen.getByText('login page')).toBeInTheDocument();
});

test('authenticated user sees children', () => {
  useAuthStore.setState({ isHydrating: false, isAuthenticated: true, user: mockUser });
  renderWithRoutes();
  expect(screen.getByText('secret')).toBeInTheDocument();
});
