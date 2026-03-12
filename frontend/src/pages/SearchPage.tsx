import React, { useState, useEffect, useMemo } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { SearchResult, Keyword } from '../api/types';
import { PAGE_SIZE } from '../api/constants';
import useAuthStore from '../stores/authStore';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const EXIF_FIELDS = [
  'Artist',
  'Copyright',
  'Make',
  'Model',
  'Software',
  'ImageDescription',
  'UserComment',
  'DateTimeOriginal',
];

const SAVED_SEARCHES_KEY = (userId: string) => `saved_searches_${userId}`;

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface SearchFilters {
  field: string;
  keywordId: string;
}

interface SavedSearch {
  query: string;
  field?: string;
  keywordId?: string;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function flattenKeywords(keywords: Keyword[]): Keyword[] {
  const result: Keyword[] = [];
  function traverse(kws: Keyword[]) {
    for (const kw of kws) {
      result.push(kw);
      if (kw.children.length > 0) {
        traverse(kw.children);
      }
    }
  }
  traverse(keywords);
  return result;
}

function fetchSearch({
  q,
  filters,
  page,
  size,
}: {
  q: string;
  filters: SearchFilters;
  page: number;
  size: number;
}): Promise<SearchResult> {
  const params = new URLSearchParams({ q, page: String(page), size: String(size) });
  if (filters.field) params.set('field', filters.field);
  if (filters.keywordId) params.set('keywordId', filters.keywordId);
  return apiFetch<SearchResult>(`/api/search?${params}`);
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function SearchPage() {
  const user = useAuthStore((s) => s.user);

  // Controlled form state
  const [query, setQuery] = useState('');
  const [field, setField] = useState('');
  const [selectedKeywordId, setSelectedKeywordId] = useState('');

  // Submitted (committed) state — only updated on form submit
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [submittedFilters, setSubmittedFilters] = useState<SearchFilters>({
    field: '',
    keywordId: '',
  });

  // Saved searches loaded from localStorage for the current user
  const [savedSearches, setSavedSearches] = useState<SavedSearch[]>(() => {
    if (!user) return [];
    try {
      return JSON.parse(localStorage.getItem(SAVED_SEARCHES_KEY(user.id)) ?? '[]');
    } catch {
      return [];
    }
  });

  // Re-read saved searches when user changes (e.g., on mount after store hydrates)
  useEffect(() => {
    if (!user) {
      setSavedSearches([]);
      return;
    }
    try {
      const stored = JSON.parse(localStorage.getItem(SAVED_SEARCHES_KEY(user.id)) ?? '[]');
      setSavedSearches(stored);
    } catch {
      setSavedSearches([]);
    }
  }, [user?.id]);

  // Keywords query
  const { data: keywordsRaw = [] } = useQuery<Keyword[]>({
    queryKey: ['keywords'],
    queryFn: () => apiFetch('/api/keywords'),
    staleTime: 10 * 60 * 1000,
  });

  const keywords = useMemo(() => flattenKeywords(keywordsRaw), [keywordsRaw]);

  // Search query (infinite) — uses submitted values
  const searchEnabled =
    submittedQuery.length > 0 ||
    !!submittedFilters.keywordId ||
    !!submittedFilters.field;

  const { data: searchData } = useInfiniteQuery<SearchResult>({
    queryKey: ['search', submittedQuery, submittedFilters],
    queryFn: ({ pageParam }) =>
      fetchSearch({
        q: submittedQuery,
        filters: submittedFilters,
        page: pageParam as number,
        size: PAGE_SIZE,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page * PAGE_SIZE + lastPage.photos.length < lastPage.total
        ? lastPage.page + 1
        : undefined,
    enabled: searchEnabled,
    staleTime: 2 * 60 * 1000,
  });

  const photos = useMemo(
    () => searchData?.pages.flatMap((p) => p.photos) ?? [],
    [searchData],
  );

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmittedQuery(query);
    setSubmittedFilters({ field, keywordId: selectedKeywordId });
  }

  function handleSaveSearch() {
    if (!user) return;
    const entry: SavedSearch = { query: submittedQuery };
    if (submittedFilters.field) entry.field = submittedFilters.field;
    if (submittedFilters.keywordId) entry.keywordId = submittedFilters.keywordId;

    const key = SAVED_SEARCHES_KEY(user.id);
    let existing: SavedSearch[] = [];
    try {
      existing = JSON.parse(localStorage.getItem(key) ?? '[]');
    } catch {
      existing = [];
    }
    const updated = [...existing, entry];
    localStorage.setItem(key, JSON.stringify(updated));
    setSavedSearches(updated);
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <div className="search-page">
      <h1>Search</h1>

      <form onSubmit={handleSubmit}>
        {/* Full-text search input — role="searchbox" via type="search" */}
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search photos..."
        />

        {/* EXIF field filter */}
        <label htmlFor="exif-field-select">EXIF Field</label>
        <select
          id="exif-field-select"
          value={field}
          onChange={(e) => setField(e.target.value)}
        >
          <option value="">All fields</option>
          {EXIF_FIELDS.map((f) => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </select>

        {/* Keyword checkboxes */}
        {keywords.length > 0 && (
          <fieldset>
            <legend>Keywords</legend>
            {keywords.map((kw) => (
              <label key={kw.id}>
                <input
                  type="checkbox"
                  checked={selectedKeywordId === kw.id}
                  onChange={(e) =>
                    setSelectedKeywordId(e.target.checked ? kw.id : '')
                  }
                />
                {kw.name}
              </label>
            ))}
          </fieldset>
        )}

        <button type="submit">Search</button>
      </form>

      {/* Save search button — only available after a search has been submitted */}
      {searchEnabled && user && (
        <button type="button" onClick={handleSaveSearch}>
          Save Search
        </button>
      )}

      {/* Saved searches list */}
      {savedSearches.length > 0 && (
        <section aria-label="Saved searches">
          <h2>Saved Searches</h2>
          <ul>
            {savedSearches.map((s, i) => (
              <li key={i}>{s.query}</li>
            ))}
          </ul>
        </section>
      )}

      {/* Results */}
      {photos.length > 0 && (
        <section aria-label="Search results">
          <ul>
            {photos.map((photo) => (
              <li key={photo.id}>{photo.filename}</li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
