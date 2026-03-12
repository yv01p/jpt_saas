import { create } from 'zustand';
import type { User } from '../api/types';

interface AuthState {
  isHydrating: boolean;
  isAuthenticated: boolean;
  user: User | null;
  setAuth: (user: User) => void;
  clearAuth: () => void;
}

const useAuthStore = create<AuthState>((set) => ({
  isHydrating: true,
  isAuthenticated: false,
  user: null,
  setAuth: (user) => set({ isAuthenticated: true, user }),
  clearAuth: () => set({ isAuthenticated: false, user: null }),
}));

export default useAuthStore;
