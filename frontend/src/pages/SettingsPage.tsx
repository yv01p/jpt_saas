import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { User, ShareToken } from '../api/types';
import useAuthStore from '../stores/authStore';

interface SharesPage {
  content: ShareToken[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

function ManageSharesSection() {
  const queryClient = useQueryClient();

  const { data: sharesPage, isLoading: sharesLoading } = useQuery<SharesPage>({
    queryKey: ['shares'],
    queryFn: () => apiFetch<SharesPage>('/api/shares'),
  });

  const revokeMutation = useMutation({
    mutationFn: (id: string) =>
      apiFetch<void>(`/api/shares/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['shares'] });
    },
  });

  if (sharesLoading) return <p>Loading shares...</p>;

  const shares = sharesPage?.content ?? [];

  if (shares.length === 0) return <p>No active shares.</p>;

  return (
    <ul>
      {shares.map((share) => (
        <li key={share.id}>
          <span>{share.resourceType}</span>
          {' — '}
          <span>{new Date(share.createdAt).toLocaleDateString()}</span>
          {' — Expires: '}
          <span>{share.expiresAt ? new Date(share.expiresAt).toLocaleDateString() : 'Never'}</span>
          {' '}
          <button onClick={() => revokeMutation.mutate(share.id)}>Revoke</button>
        </li>
      ))}
    </ul>
  );
}

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

      <section>
        <h2>Manage Shares</h2>
        <ManageSharesSection />
      </section>
    </div>
  );
}
