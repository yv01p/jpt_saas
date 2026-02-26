-- NOTE: Password must be overridden in production via ALTER ROLE or Docker secrets.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'worker_db_user') THEN
        CREATE ROLE worker_db_user WITH LOGIN PASSWORD '${worker_db_user_password}';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO worker_db_user;

-- Worker reads photo job details
GRANT SELECT ON photos TO worker_db_user;

-- Worker writes extracted metadata
GRANT INSERT, UPDATE ON photo_metadata TO worker_db_user;

-- Worker updates only specific columns on photos
GRANT UPDATE (storage_key, content_hash, processing_status, size_bytes) ON photos TO worker_db_user;

-- Explicitly NOT granted: users, shares, keywords, albums, saved_searches, email_tokens
