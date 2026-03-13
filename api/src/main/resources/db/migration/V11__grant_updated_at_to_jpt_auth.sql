-- V4 column-level UPDATE grant for jpt_auth on users was missing updated_at,
-- causing verifyEmail and updatePassword to fail with permission denied.
GRANT UPDATE (updated_at) ON users TO jpt_auth;
