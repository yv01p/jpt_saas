import { apiFetch, fetchCurrentUser } from '../client';
import useAuthStore from '../../stores/authStore';

export default function useAuth() {
  async function login(credentials: { email: string; password: string }) {
    await apiFetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials),
    });
    const user = await fetchCurrentUser();
    if (user) useAuthStore.getState().setAuth(user);
  }

  async function logout() {
    await apiFetch('/api/auth/logout', { method: 'POST' });
    useAuthStore.getState().clearAuth();
  }

  return { login, logout };
}
