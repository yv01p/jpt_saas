import React from 'react';
import type { PhotoMetadata } from '../api/types';
import useAuthStore from '../stores/authStore';

interface MetadataPanelProps {
  metadata: PhotoMetadata;
}

export default function MetadataPanel({ metadata }: MetadataPanelProps) {
  const showGps = useAuthStore((state) => state.user?.showGps ?? false);

  return (
    <div className="metadata-panel">
      <h3>EXIF Data</h3>
      <dl>
        {Object.entries(metadata.exifData).map(([key, value]) => (
          <div key={key}>
            <dt>{key}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {showGps && metadata.gpsLatitude !== undefined && metadata.gpsLongitude !== undefined && (
        <div className="gps-data">
          <h4>GPS</h4>
          <div>
            <span>Latitude: </span>
            <span>{metadata.gpsLatitude}</span>
          </div>
          <div>
            <span>Longitude: </span>
            <span>{metadata.gpsLongitude}</span>
          </div>
        </div>
      )}
    </div>
  );
}
