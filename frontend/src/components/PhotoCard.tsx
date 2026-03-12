import React from 'react';
import type { Photo } from '../api/types';

interface PhotoCardProps {
  photo: Photo;
}

function statusLabel(status: Photo['processingStatus']): string | null {
  if (status === 'PROCESSING') return 'Processing...';
  if (status === 'PENDING') return 'Pending...';
  if (status === 'FAILED') return 'Failed';
  return null;
}

export default React.memo(function PhotoCard({ photo }: PhotoCardProps) {
  const label = statusLabel(photo.processingStatus);

  return (
    <div className="relative overflow-hidden rounded bg-gray-100">
      <img
        src={photo.thumbnailUrl}
        alt={photo.filename}
        className="h-full w-full object-cover"
      />
      {label && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/40 text-sm font-medium text-white">
          {label}
        </div>
      )}
    </div>
  );
});
