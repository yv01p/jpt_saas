import { Navigate, useLocation } from 'react-router-dom';
import useAuthStore from '../stores/authStore';

export default function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isHydrating, isAuthenticated } = useAuthStore();
  const location = useLocation();

  if (isHydrating) {
    return <div data-testid="hydration-spinner">Loading…</div>;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <>{children}</>;
}
