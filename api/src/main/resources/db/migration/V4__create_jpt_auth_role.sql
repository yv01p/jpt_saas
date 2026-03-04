-- Create privileged auth role for authentication operations (login, registration,
-- email verification, OAuth2). This role bypasses RLS so it can access users and
-- email_tokens tables without tenant filtering.
-- NOTE: Password must be overridden in production via ALTER ROLE or Docker secrets.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jpt_auth') THEN
        CREATE ROLE jpt_auth WITH LOGIN PASSWORD '${jpt_auth_password}' BYPASSRLS;
    END IF;

    -- Use current_database() so this works in both production (jpt) and Testcontainers (testdb)
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO jpt_auth', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO jpt_auth;

-- Column-level grants — principle of least privilege
GRANT SELECT, INSERT ON users TO jpt_auth;
GRANT UPDATE (password_hash, failed_login_attempts, locked_until, email_verified,
              oauth_provider, oauth_id) ON users TO jpt_auth;
GRANT SELECT, INSERT, DELETE ON email_tokens TO jpt_auth;

GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO jpt_auth;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE ON SEQUENCES TO jpt_auth;
