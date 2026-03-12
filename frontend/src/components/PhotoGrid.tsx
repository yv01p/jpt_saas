import { useEffect, useRef } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import type { Photo } from '../api/types';
import PhotoCard from './PhotoCard';

interface PhotoGridProps {
  photos: Photo[];
  onLoadMore: () => void;
  hasMore: boolean;
}

export default function PhotoGrid({ photos, onLoadMore, hasMore }: PhotoGridProps) {
  const parentRef = useRef<HTMLDivElement>(null);

  const rowVirtualizer = useVirtualizer({
    count: photos.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 200,
  });

  const range = rowVirtualizer.range ?? { startIndex: 0, endIndex: 0 };

  useEffect(() => {
    if (range.endIndex >= photos.length - 10 && hasMore && photos.length > 0) {
      onLoadMore();
    }
  }, [range.endIndex, photos.length, hasMore, onLoadMore]);

  const virtualItems = rowVirtualizer.getVirtualItems();

  return (
    <div
      data-testid="photo-grid-scroll-container"
      ref={parentRef}
      style={{ height: '100vh', overflow: 'auto' }}
    >
      <div
        style={{
          height: `${rowVirtualizer.getTotalSize()}px`,
          width: '100%',
          position: 'relative',
        }}
      >
        {virtualItems.map((virtualRow) => {
          const photo = photos[virtualRow.index];
          if (!photo) return null;
          return (
            <div
              key={virtualRow.key}
              data-index={virtualRow.index}
              ref={rowVirtualizer.measureElement}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${virtualRow.start}px)`,
              }}
            >
              <PhotoCard photo={photo} />
            </div>
          );
        })}
      </div>
    </div>
  );
}
