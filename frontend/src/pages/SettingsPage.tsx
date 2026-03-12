import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { User } from '../api/types';
import useAuthStore from '../stores/authStore';

export default function SettingsPage() {
  const queryClient = useQueryClient();

  const { data: user, isLoading, isError } = useQuery<User>({
    queryKey: ['currentUser'],
    queryFn: () => apiFetch<User>('/api/users/me'),
    staleTime: 5 * 60 * 1000,
  });

  const mutation = useMutation({
    mutationFn: (body: { showGps: boolean }) =>
      apiFetch<User>('/api/users/me', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }),
    onSuccess: (responseUser) => {
      useAuthStore.getState().setAuth(responseUser);
      queryClient.setQueryData(['currentUser'], responseUser);
    },
  });

  if (isLoading) return <div data-testid="quota-skeleton">Loading...</div>;
  if (isError)   return <p>Could not load storage info — try refreshing.</p>;

  const usedBytes = Math.max(0, user?.usedBytes ?? 0);
  const limitBytes = user?.quotaBytes ?? 0;
  const usedGB  = (usedBytes  / 1e9).toFixed(1);
  const limitGB = (limitBytes / 1e9).toFixed(1);

  const showGps = user?.showGps ?? false;

  return (
    <div className="settings-page">
      <h1>Settings</h1>

      <section>
        <h2>Storage</h2>
        <p>{usedGB} GB of {limitGB} GB used</p>
      </section>

      <section>
        <h2>Preferences</h2>
        <label>
          <input
            type="checkbox"
            checked={showGps}
            onChange={(e) => mutation.mutate({ showGps: e.target.checked })}
            aria-label="Show GPS"
          />
          Show GPS
        </label>
      </section>
    </div>
  );
}
