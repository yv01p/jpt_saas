import React from 'react';
import type { PhotoMetadata } from '../api/types';
import useAuthStore from '../stores/authStore';

interface MetadataPanelProps {
  metadata: PhotoMetadata;
}

type Section = 'GPS' | 'XMP' | 'IPTC' | 'EXIF';

function sectionFor(key: string): Section {
  const upper = key.toUpperCase();
  if (upper.startsWith('GPS:') || upper.startsWith('GPS')) return 'GPS';
  if (upper.startsWith('XMP:')) return 'XMP';
  if (upper.startsWith('IPTC:')) return 'IPTC';
  return 'EXIF';
}

const SECTION_ORDER: Section[] = ['EXIF', 'IPTC', 'XMP', 'GPS'];

export default function MetadataPanel({ metadata }: MetadataPanelProps) {
  const showGps = useAuthStore((state) => state.user?.showGps ?? false);

  // Group exifData entries into sections
  const sections: Record<Section, Array<[string, string]>> = {
    EXIF: [],
    IPTC: [],
    XMP: [],
    GPS: [],
  };

  for (const [key, value] of Object.entries(metadata.exifData)) {
    sections[sectionFor(key)].push([key, value]);
  }

  return (
    <div className="metadata-panel">
      {SECTION_ORDER.map((section) => {
        if (section === 'GPS' && !showGps) return null;
        const entries = sections[section];
        if (entries.length === 0) return null;
        return (
          <section key={section} className={`metadata-section metadata-section--${section.toLowerCase()}`}>
            <h4>{section}</h4>
            <dl>
              {entries.map(([key, value]) => (
                <div key={key}>
                  <dt>{key}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          </section>
        );
      })}

      {showGps && metadata.gpsLatitude !== undefined && metadata.gpsLatitude !== null &&
       metadata.gpsLongitude !== undefined && metadata.gpsLongitude !== null && (
        <section className="metadata-section metadata-section--gps-coords">
          <h4>GPS Coordinates</h4>
          <dl>
            <div>
              <dt>Latitude</dt>
              <dd>{metadata.gpsLatitude}</dd>
            </div>
            <div>
              <dt>Longitude</dt>
              <dd>{metadata.gpsLongitude}</dd>
            </div>
          </dl>
        </section>
      )}
    </div>
  );
}
