import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { Photo, Keyword, PhotoMetadata } from '../api/types';
import MetadataPanel from '../components/MetadataPanel';

interface PhotoPageProps {
  photoId: string;
}

export default function PhotoPage({ photoId }: PhotoPageProps) {
  const queryClient = useQueryClient();
  const [showKeywordPicker, setShowKeywordPicker] = useState(false);

  const { data: photo } = useQuery<Photo>({
    queryKey: ['photo', photoId],
    queryFn: () => apiFetch(`/api/photos/${photoId}`),
    staleTime: 55 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
  });

  const { data: metadata } = useQuery<PhotoMetadata>({
    queryKey: ['photo-metadata', photoId],
    queryFn: () => apiFetch(`/api/photos/${photoId}/metadata`),
    staleTime: 55 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
  });

  const { data: assignedKeywords = [] } = useQuery<Keyword[]>({
    queryKey: ['photo-keywords', photoId],
    queryFn: () => apiFetch(`/api/photos/${photoId}/keywords`),
    staleTime: 5 * 60 * 1000,
  });

  const { data: allKeywords = [] } = useQuery<Keyword[]>({
    queryKey: ['keywords'],
    queryFn: () => apiFetch('/api/keywords'),
    enabled: showKeywordPicker,
    staleTime: 10 * 60 * 1000,
  });

  const assignKeyword = useMutation({
    mutationFn: (keywordId: string) =>
      apiFetch(`/api/photos/${photoId}/keywords/${keywordId}`, { method: 'POST' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['photo-keywords', photoId] });
      setShowKeywordPicker(false);
    },
  });

  const removeKeyword = useMutation({
    mutationFn: (keywordId: string) =>
      apiFetch(`/api/photos/${photoId}/keywords/${keywordId}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['photo-keywords', photoId] });
    },
  });

  return (
    <div className="photo-page">
      {photo && (
        <div className="photo-view">
          <img src={photo.originalUrl} alt={photo.filename} />
        </div>
      )}

      {metadata && (
        <aside className="metadata-sidebar">
          <MetadataPanel metadata={metadata} />
        </aside>
      )}

      <div className="keyword-panel">
        <h3>Keywords</h3>
        <ul>
          {assignedKeywords.map((kw) => (
            <li key={kw.id}>
              {kw.name}
              <button
                aria-label={`remove ${kw.name}`}
                onClick={() => removeKeyword.mutate(kw.id)}
              >
                &times;
              </button>
            </li>
          ))}
        </ul>
        <button
          aria-label="Add keyword"
          onClick={() => setShowKeywordPicker(true)}
        >
          Add keyword
        </button>
        {showKeywordPicker && (
          <div className="keyword-picker">
            <ul>
              {allKeywords.map((kw) => (
                <li key={kw.id}>
                  <button
                    type="button"
                    onClick={() => assignKeyword.mutate(kw.id)}
                  >
                    {kw.name}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}
