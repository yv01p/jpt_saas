import React, { useMemo } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { SearchResult } from '../api/types';
import { PAGE_SIZE } from '../api/constants';
import PhotoGrid from '../components/PhotoGrid';
import UploadDropzone from '../components/UploadDropzone';

function fetchPhotos({ page, size }: { page: number; size: number }): Promise<SearchResult> {
  return apiFetch(`/api/photos?page=${page}&size=${size}`);
}

export default function LibraryPage() {
  const {
    data,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery<SearchResult>({
    queryKey: ['photos'],
    queryFn: ({ pageParam }) => fetchPhotos({ page: pageParam as number, size: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page * PAGE_SIZE + lastPage.photos.length < lastPage.total
        ? lastPage.page + 1
        : undefined,
    staleTime: 10 * 60 * 1000,
    gcTime: 15 * 60 * 1000,
    refetchInterval: (query) => {
      const photos = query.state.data?.pages.flatMap((p) => p.photos) ?? [];
      return photos.some(
        (p) => p.processingStatus === 'PENDING' || p.processingStatus === 'PROCESSING'
      )
        ? 5_000
        : false;
    },
  });

  const photos = useMemo(
    () => data?.pages.flatMap((page) => page.photos) ?? [],
    [data]
  );

  return (
    <div>
      <UploadDropzone />
      <PhotoGrid
        photos={photos}
        onLoadMore={fetchNextPage}
        hasMore={!!hasNextPage && !isFetchingNextPage}
      />
    </div>
  );
}
