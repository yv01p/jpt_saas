-- Worker needs SELECT on photo_metadata to use ON CONFLICT DO UPDATE
-- (PostgreSQL requires SELECT privilege to detect conflicts in INSERT ... ON CONFLICT DO UPDATE)
GRANT SELECT ON photo_metadata TO worker_db_user;
