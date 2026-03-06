-- V5__fix_content_hash_unique_constraint.sql

ALTER TABLE photos DROP CONSTRAINT IF EXISTS photos_user_id_content_hash_key;
-- Also drop the old unique constraint from V1 that uses a different name:
ALTER TABLE photos DROP CONSTRAINT IF EXISTS uq_user_content_hash;
CREATE UNIQUE INDEX photos_user_content_hash_active_idx ON photos (user_id, content_hash) WHERE deleted_at IS NULL;

-- Fix processing_status: V1 created a lowercase CHECK constraint but @Enumerated(EnumType.STRING) stores uppercase.
-- Drop the old constraint and update all data to uppercase.
ALTER TABLE photos DROP CONSTRAINT IF EXISTS photos_processing_status_check;
UPDATE photos SET processing_status = UPPER(processing_status);
ALTER TABLE photos ALTER COLUMN processing_status SET DEFAULT 'PENDING';
ALTER TABLE photos ADD CONSTRAINT photos_processing_status_check
    CHECK (processing_status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED'));
