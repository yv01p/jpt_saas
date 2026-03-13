import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { ApiError } from '../api/types';
import type { Photo } from '../api/types';
import PhotoGrid from '../components/PhotoGrid';

interface ShareInfo {
  id: string;
  resourceType: 'photo' | 'album';
  resourceId: string;
  expiresAt: string | null;
  includeGps: boolean;
  permissions: string;
  createdAt: string;
}

interface SharedPhoto extends Photo {
  gpsLatitude?: number;
  gpsLongitude?: number;
}

interface ShareResponse {
  share: ShareInfo;
  photo?: SharedPhoto;
  album?: { id: string };
}

interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// SECURITY: Never use dangerouslySetInnerHTML for user-uploaded metadata
function SharedPhotoView({ photo, includeGps }: { photo: SharedPhoto; includeGps: boolean }) {
  return (
    <div>
      <img src={photo.originalUrl} alt={photo.filename} />
      <div>
        <p>{photo.filename}</p>
        {photo.caption != null && <p>{photo.caption}</p>}
        {photo.title != null && <p>{photo.title}</p>}
        {photo.description != null && <p>{photo.description}</p>}
        {includeGps && photo.gpsLatitude != null && (
          <p>Latitude: {photo.gpsLatitude}</p>
        )}
        {includeGps && photo.gpsLongitude != null && (
          <p>Longitude: {photo.gpsLongitude}</p>
        )}
      </div>
    </div>
  );
}

function SharedAlbumView({ token }: { token: string }) {
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch<PageResponse<Photo>>(`/api/share/${token}/photos?page=0&size=20`)
      .then((page) => {
        setPhotos(page.content);
        setLoading(false);
      })
      .catch(() => {
        setLoading(false);
      });
  }, [token]);

  if (loading) return <div>Loading album...</div>;

  return (
    <PhotoGrid
      photos={photos}
      onLoadMore={() => {}}
      hasMore={false}
    />
  );
}

export default function SharePage() {
  const { token } = useParams<{ token: string }>();
  const [shareResponse, setShareResponse] = useState<ShareResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!token) return;
    apiFetch<ShareResponse>(`/api/share/${token}`)
      .then((data) => {
        setShareResponse(data);
        setLoading(false);
      })
      .catch((err) => {
        if (
          err instanceof ApiError &&
          (err.status === 404 || err.status === 410 || err.status === 500)
        ) {
          setError(true);
        } else {
          setError(true);
        }
        setLoading(false);
      });
  }, [token]);

  if (loading) return <div>Loading...</div>;
  if (error || !shareResponse) return <p>Share not found or has expired.</p>;

  const { share } = shareResponse;

  if (share.resourceType === 'photo' && shareResponse.photo) {
    return (
      <SharedPhotoView
        photo={shareResponse.photo}
        includeGps={share.includeGps}
      />
    );
  }

  if (share.resourceType === 'album') {
    return <SharedAlbumView token={token!} />;
  }

  return <p>Share not found or has expired.</p>;
}
