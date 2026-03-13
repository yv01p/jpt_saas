export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
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
  children?: Keyword[];
}

export interface ShareToken {
  id: string;
  token: string;
  resourceType: 'photo' | 'album';
  resourceId: string;
  expiresAt: string | null;
  includeGps: boolean;
  permissions: string;
  createdAt: string;
}

export interface SearchResult {
  content: Photo[];
  totalElements: number;
  number: number;
}
