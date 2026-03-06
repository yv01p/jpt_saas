-- V7__add_original_filename_to_photos.sql
ALTER TABLE photos ADD COLUMN IF NOT EXISTS original_filename VARCHAR(512);
