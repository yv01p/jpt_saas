export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export type ProcessingStatus = 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED';

export interface User {
  id: string;
  email: string;
  showGps: boolean;
  quotaBytes: number;
  usedBytes: number;
}

export interface Photo {
  id: string;
  filename: string;
  thumbnailUrl: string;
  originalUrl: string;
  processingStatus: ProcessingStatus;
  caption: string | null;
  title: string | null;
  description: string | null;
  sizeBytes: number;
  takenAt: string | null;
  uploadedAt: string;
  updatedAt: string | null;
  deletedAt: string | null;
}

export interface PhotoMetadata {
  exifData: Record<string, string>;
  gpsLatitude?: number;
  gpsLongitude?: number;
}

export interface Album {
  id: string;
  name: string;
  photoCount: number;
}

export interface Keyword {
  id: string;
  name: string;
  parentId: string | null;
  children: Keyword[];
}

export interface ShareToken {
  token: string;
  photoId: string;
  expiresAt: string | null;
}

export interface SearchResult {
  photos: Photo[];
  total: number;
  page: number;
}
