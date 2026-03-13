import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { Photo } from '../api/types';

const RETENTION_DAYS = 30;

function formatDate(isoString: string): string {
  return new Date(isoString).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });
}

function daysRemaining(deletedAt: string): number {
  const deletedMs = new Date(deletedAt).getTime();
  const elapsedDays = (Date.now() - deletedMs) / (1000 * 60 * 60 * 24);
  return Math.ceil(RETENTION_DAYS - elapsedDays);
}

export default function TrashPage() {
  const queryClient = useQueryClient();

  const { data: photos = [], isLoading, isError } = useQuery<Photo[]>({
    queryKey: ['trash'],
    queryFn: () => apiFetch<{ content: Photo[] }>('/api/photos/trash').then((r) => r.content),
    staleTime: 60 * 1000,
  });

  const restore = useMutation({
    mutationFn: (id: string) =>
      apiFetch(`/api/photos/${encodeURIComponent(id)}/restore`, { method: 'POST' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['trash'] });
    },
  });

  if (isLoading) return <div>Loading...</div>;
  if (isError) return <div>Failed to load trash.</div>;

  return (
    <div className="trash-page">
      <h1>Trash</h1>
      {photos.length === 0 ? (
        <p>No photos in trash.</p>
      ) : (
        <ul>
          {photos.map((photo) => {
            const remaining = photo.deletedAt ? daysRemaining(photo.deletedAt) : null;
            return (
              <li key={photo.id}>
                <span>{photo.filename}</span>
                {photo.deletedAt && (
                  <span> — Deleted {formatDate(photo.deletedAt)}</span>
                )}
                {remaining !== null && (
                  <span> — {remaining} days remaining</span>
                )}
                <button
                  type="button"
                  aria-label={`Restore ${photo.filename}`}
                  onClick={() => restore.mutate(photo.id)}
                >
                  Restore
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
