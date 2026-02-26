-- api/src/main/resources/db/migration/V2__rls_policies.sql

-- Create application role for API connections (non-superuser)
-- NOTE: Password must be overridden in production via ALTER ROLE or Docker secrets.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jpt_app') THEN
        CREATE ROLE jpt_app WITH LOGIN PASSWORD '${jpt_app_password}';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO jpt_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jpt_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO jpt_app;

-- Auto-grant on future tables created by Flyway migrations
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jpt_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO jpt_app;

-- Fail-fast assertion: raises exception if app.current_user_id is nil UUID.
-- Phase 2 interceptor should call SELECT assert_user_context() after SET LOCAL.
CREATE FUNCTION assert_user_context() RETURNS void AS $$
BEGIN
    IF current_setting('app.current_user_id', true) IS NULL
       OR current_setting('app.current_user_id', true) = '00000000-0000-0000-0000-000000000000' THEN
        RAISE EXCEPTION 'app.current_user_id is not set (nil UUID or missing)';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Enable RLS on all tenant tables (including users and email_tokens)
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
ALTER TABLE email_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_tokens FORCE ROW LEVEL SECURITY;
ALTER TABLE photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE photo_metadata ENABLE ROW LEVEL SECURITY;
ALTER TABLE keywords ENABLE ROW LEVEL SECURITY;
ALTER TABLE photo_keywords ENABLE ROW LEVEL SECURITY;
ALTER TABLE albums ENABLE ROW LEVEL SECURITY;
ALTER TABLE album_photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE shares ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_searches ENABLE ROW LEVEL SECURITY;

-- RLS policies — direct user_id equality (no correlated subqueries)
-- NOTE: users and email_tokens RLS means login, registration, and email
-- verification flows (Phase 2) must use a privileged role that bypasses RLS.
CREATE POLICY tenant_users ON users
    USING (id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_email_tokens ON email_tokens
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_photos ON photos
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_photo_metadata ON photo_metadata
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_keywords ON keywords
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_photo_keywords ON photo_keywords
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_albums ON albums
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_album_photos ON album_photos
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- NOTE: Public share-link validation (unauthenticated) requires a privileged
-- code path that bypasses RLS. See Phase 2 share endpoint design.
CREATE POLICY tenant_shares ON shares
    USING (user_id = current_setting('app.current_user_id')::uuid);

CREATE POLICY tenant_saved_searches ON saved_searches
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- Force RLS on table owners too (so even superuser-like roles are restricted)
ALTER TABLE photos FORCE ROW LEVEL SECURITY;
ALTER TABLE photo_metadata FORCE ROW LEVEL SECURITY;
ALTER TABLE keywords FORCE ROW LEVEL SECURITY;
ALTER TABLE photo_keywords FORCE ROW LEVEL SECURITY;
ALTER TABLE albums FORCE ROW LEVEL SECURITY;
ALTER TABLE album_photos FORCE ROW LEVEL SECURITY;
ALTER TABLE shares FORCE ROW LEVEL SECURITY;
ALTER TABLE saved_searches FORCE ROW LEVEL SECURITY;
