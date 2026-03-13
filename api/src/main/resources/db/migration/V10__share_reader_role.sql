-- NOTE: jpt_auth (V4) handles auth operations; share_reader is intentionally separate for least-privilege isolation
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'share_reader') THEN
        CREATE ROLE share_reader WITH LOGIN PASSWORD '${share_reader_password}' BYPASSRLS;
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO share_reader', current_database());
END
$$;
GRANT SELECT ON shares, photos, albums, album_photos, photo_metadata TO share_reader;
