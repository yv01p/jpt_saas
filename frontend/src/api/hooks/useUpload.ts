import { useState, useRef, useEffect, useCallback } from 'react';
import { apiFetch } from '../client';
import type { Photo, ProcessingStatus } from '../types';

interface StatusResponse {
  id: string;
  processingStatus: ProcessingStatus;
}

interface UploadState {
  uploadedPhoto: Photo | null;
  processingStatus: ProcessingStatus | null;
  isUploading: boolean;
  isPolling: boolean;
  timedOut: boolean;
  stillProcessing: boolean;
  error: string | null;
}

interface UseUploadReturn extends UploadState {
  upload: (file: File) => Promise<void>;
}

const POLL_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes
const STILL_PROCESSING_MS = 30 * 1000;  // 30 seconds
const TERMINAL_STATUSES: ProcessingStatus[] = ['DONE', 'FAILED'];

/**
 * Compute the delay before the next poll.
 * completedPolls is the number of polls already completed.
 * Phase 1 (polls 0-4 completed): next poll after 3s
 * Phase 2 (polls 5+ completed): exponential backoff capped at 60s
 */
function computeInterval(completedPolls: number): number {
  return Math.min(
    completedPolls < 5 ? 3000 : 3000 * Math.pow(2, completedPolls - 4),
    60_000
  );
}

export function useUpload(): UseUploadReturn {
  const [state, setState] = useState<UploadState>({
    uploadedPhoto: null,
    processingStatus: null,
    isUploading: false,
    isPolling: false,
    timedOut: false,
    stillProcessing: false,
    error: null,
  });

  const pollCount = useRef(0);
  const pollStart = useRef<number | null>(null);
  const pollTimeoutId = useRef<ReturnType<typeof setTimeout> | null>(null);
  const hardTimeoutId = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mountedRef = useRef(true);

  const cancelAllTimers = useCallback(() => {
    if (pollTimeoutId.current !== null) {
      clearTimeout(pollTimeoutId.current);
      pollTimeoutId.current = null;
    }
    if (hardTimeoutId.current !== null) {
      clearTimeout(hardTimeoutId.current);
      hardTimeoutId.current = null;
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      cancelAllTimers();
    };
  }, [cancelAllTimers]);

  const doPoll = useCallback(async (photoId: string) => {
    if (!mountedRef.current) return;
    pollTimeoutId.current = null;

    try {
      const status = await apiFetch<StatusResponse>(`/api/photos/${photoId}/status`);
      if (!mountedRef.current) return;

      const isStillProcessing =
        pollStart.current !== null &&
        Date.now() - pollStart.current >= STILL_PROCESSING_MS;

      const completedNow = pollCount.current + 1;
      pollCount.current = completedNow;

      if (TERMINAL_STATUSES.includes(status.processingStatus)) {
        // Cancel the hard timeout — we reached a terminal state naturally
        if (hardTimeoutId.current !== null) {
          clearTimeout(hardTimeoutId.current);
          hardTimeoutId.current = null;
        }
        setState((prev) => ({
          ...prev,
          processingStatus: status.processingStatus,
          isPolling: false,
          stillProcessing: false,
        }));
      } else {
        setState((prev) => ({
          ...prev,
          processingStatus: status.processingStatus,
          isPolling: true,
          stillProcessing: isStillProcessing,
        }));
        // Schedule next poll
        const interval = computeInterval(pollCount.current);
        pollTimeoutId.current = setTimeout(() => doPoll(photoId), interval);
      }
    } catch {
      if (!mountedRef.current) return;
      pollCount.current += 1;
      const interval = computeInterval(pollCount.current);
      pollTimeoutId.current = setTimeout(() => doPoll(photoId), interval);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const upload = useCallback(async (file: File) => {
    // Cancel any in-flight timers
    cancelAllTimers();
    pollCount.current = 0;
    pollStart.current = null;

    setState({
      uploadedPhoto: null,
      processingStatus: null,
      isUploading: true,
      isPolling: false,
      timedOut: false,
      stillProcessing: false,
      error: null,
    });

    try {
      const formData = new FormData();
      formData.append('file', file);

      const photo = await apiFetch<Photo>('/api/photos', {
        method: 'POST',
        body: formData,
      });

      if (!mountedRef.current) return;

      pollStart.current = Date.now();
      pollCount.current = 0;

      const isTerminal = TERMINAL_STATUSES.includes(photo.processingStatus);

      setState((prev) => ({
        ...prev,
        uploadedPhoto: photo,
        processingStatus: photo.processingStatus,
        isUploading: false,
        isPolling: !isTerminal,
      }));

      if (!isTerminal) {
        // Schedule hard timeout at exactly 10 minutes from now
        hardTimeoutId.current = setTimeout(() => {
          if (!mountedRef.current) return;
          // Cancel any pending poll
          if (pollTimeoutId.current !== null) {
            clearTimeout(pollTimeoutId.current);
            pollTimeoutId.current = null;
          }
          setState((prev) => ({
            ...prev,
            isPolling: false,
            timedOut: true,
            processingStatus: 'FAILED',
            error: 'processing_timeout',
          }));
        }, POLL_TIMEOUT_MS);

        // Start first poll immediately (0ms delay)
        pollTimeoutId.current = setTimeout(() => doPoll(photo.id), 0);
      }
    } catch (err: unknown) {
      if (!mountedRef.current) return;
      const message = err instanceof Error ? err.message : 'Upload failed';
      setState((prev) => ({
        ...prev,
        isUploading: false,
        error: message,
      }));
    }
  }, [cancelAllTimers, doPoll]);

  return { ...state, upload };
}
