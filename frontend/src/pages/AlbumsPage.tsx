import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { Album, Photo } from '../api/types';

export default function AlbumsPage() {
  const queryClient = useQueryClient();
  const [selectedAlbumId, setSelectedAlbumId] = useState<string | null>(null);
  const [showAddPhoto, setShowAddPhoto] = useState(false);
  const [addPhotoId, setAddPhotoId] = useState('placeholder');

  const { data: albums = [], isPending: albumsLoading, isError: albumsError } = useQuery<Album[]>({
    queryKey: ['albums'],
    queryFn: () => apiFetch<{ content: Album[] }>('/api/albums').then((r) => r.content),
    staleTime: 10 * 60 * 1000,
  });

  const { data: albumDetail } = useQuery<Album>({
    queryKey: ['album', selectedAlbumId],
    queryFn: () => apiFetch(`/api/albums/${encodeURIComponent(selectedAlbumId!)}`),
    enabled: selectedAlbumId !== null,
    staleTime: 5 * 60 * 1000,
  });

  const { data: albumPhotos = [] } = useQuery<Photo[]>({
    queryKey: ['album-photos', selectedAlbumId],
    queryFn: () => apiFetch(`/api/albums/${encodeURIComponent(selectedAlbumId!)}/photos`),
    enabled: selectedAlbumId !== null,
    staleTime: 5 * 60 * 1000,
  });

  const addPhotoMutation = useMutation({
    mutationFn: ({ albumId, photoId }: { albumId: string; photoId: string }) =>
      apiFetch(`/api/albums/${encodeURIComponent(albumId)}/photos/${encodeURIComponent(photoId)}`, {
        method: 'POST',
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['album-photos', selectedAlbumId] });
      setShowAddPhoto(false);
      setAddPhotoId('placeholder');
    },
  });

  const removePhotoMutation = useMutation({
    mutationFn: ({ albumId, photoId }: { albumId: string; photoId: string }) =>
      apiFetch(`/api/albums/${encodeURIComponent(albumId)}/photos/${encodeURIComponent(photoId)}`, {
        method: 'DELETE',
      }),
    onSuccess: (_data, { albumId, photoId }) => {
      queryClient.setQueryData<Photo[]>(['album-photos', albumId], (old) =>
        old ? old.filter((p) => p.id !== photoId) : [],
      );
    },
  });

  function handleAlbumClick(albumId: string) {
    setSelectedAlbumId(albumId);
    setShowAddPhoto(false);
    setAddPhotoId('placeholder');
  }

  function handleAddPhotoConfirm() {
    if (!selectedAlbumId) return;
    addPhotoMutation.mutate({ albumId: selectedAlbumId, photoId: addPhotoId.trim() });
  }

  function handleRemovePhoto(photoId: string) {
    if (!selectedAlbumId) return;
    removePhotoMutation.mutate({ albumId: selectedAlbumId, photoId });
  }

  if (albumsLoading) return <p>Loading...</p>;
  if (albumsError) return <p>Failed to load albums.</p>;

  return (
    <div className="albums-page">
      <h1>Albums</h1>
      <ul>
        {albums.map((album) => (
          <li key={album.id}>
            <button
              type="button"
              onClick={() => handleAlbumClick(album.id)}
            >
              {album.name}
            </button>
          </li>
        ))}
      </ul>

      {selectedAlbumId && albumDetail && (
        <div className="album-detail">
          <h2>{albumDetail.name}</h2>
          <div className="album-photos">
            {albumPhotos.map((photo) => (
              <div key={photo.id} className="album-photo">
                <img src={photo.thumbnailUrl} alt={photo.filename} />
                <button
                  type="button"
                  aria-label={`remove ${photo.filename}`}
                  onClick={() => handleRemovePhoto(photo.id)}
                >
                  Remove
                </button>
              </div>
            ))}
          </div>
          <div className="add-photo-section">
            {!showAddPhoto ? (
              <button
                type="button"
                onClick={() => setShowAddPhoto(true)}
              >
                Add Photo
              </button>
            ) : (
              <div>
                <label htmlFor="add-photo-id-input">Photo ID</label>
                <input
                  id="add-photo-id-input"
                  type="text"
                  value={addPhotoId}
                  onChange={(e) => setAddPhotoId(e.target.value)}
                  placeholder="Enter photo ID"
                />
                <button
                  type="button"
                  onClick={handleAddPhotoConfirm}
                >
                  Confirm
                </button>
                <button
                  type="button"
                  onClick={() => { setShowAddPhoto(false); setAddPhotoId('placeholder'); }}
                >
                  Cancel
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
