-- V6__add_used_bytes_non_negative_constraint.sql

-- Step 1: Repair any pre-existing negative values before constraining
UPDATE users SET used_bytes = 0 WHERE used_bytes < 0;

-- Step 2: Add constraint without validating existing rows (instant, no blocking table scan)
ALTER TABLE users
    ADD CONSTRAINT users_used_bytes_non_negative CHECK (used_bytes >= 0) NOT VALID;

-- Step 3: Validate existing rows with a weaker lock (allows concurrent reads)
ALTER TABLE users VALIDATE CONSTRAINT users_used_bytes_non_negative;
