import React, { useCallback } from 'react';
import { useDropzone } from 'react-dropzone';
import { useUpload } from '../api/hooks/useUpload';

export default function UploadDropzone() {
  const {
    upload,
    isUploading,
    isPolling,
    processingStatus,
    timedOut,
    stillProcessing,
    error,
  } = useUpload();

  const onDrop = useCallback(
    (acceptedFiles: File[]) => {
      if (acceptedFiles.length > 0) {
        upload(acceptedFiles[0]);
      }
    },
    [upload]
  );

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: { 'image/*': [] },
    multiple: false,
  });

  const isFailed = processingStatus === 'FAILED' || timedOut;
  const isDone = processingStatus === 'DONE' && !timedOut;

  const getStatusMessage = () => {
    if (error === 'processing_timeout' || timedOut) {
      return 'Processing timed out — try re-uploading. If the problem persists, contact support.';
    }
    if (processingStatus === 'FAILED') {
      return 'Processing failed — try re-uploading. If the problem persists, contact support.';
    }
    if (error === 'duplicate') {
      return 'This photo already exists in your library.';
    }
    if (error === 'quota_exceeded') {
      return 'Upload failed — storage quota exceeded.';
    }
    if (error) {
      return 'Upload failed — please try again.';
    }
    return null;
  };

  const statusMessage = getStatusMessage();

  return (
    <div>
      <div
        {...getRootProps()}
        style={{
          border: '2px dashed #ccc',
          borderRadius: 8,
          padding: 24,
          textAlign: 'center',
          cursor: 'pointer',
          background: isDragActive ? '#f0f8ff' : '#fafafa',
        }}
      >
        <input {...getInputProps()} data-testid="dropzone-input" />
        {isDragActive ? (
          <p>Drop the photo here...</p>
        ) : (
          <p>Drag & drop a photo here, or click to select</p>
        )}
      </div>

      {isUploading && (
        <p>Uploading...</p>
      )}

      {(isPolling || (processingStatus && !isFailed && !isDone)) && !isUploading && (
        <p>Processing...</p>
      )}

      {stillProcessing && !isFailed && (
        <p>Still processing — large files may take a few minutes.</p>
      )}

      {isDone && (
        <p>Upload complete!</p>
      )}

      {statusMessage && (
        <p role="alert">{statusMessage}</p>
      )}
    </div>
  );
}
