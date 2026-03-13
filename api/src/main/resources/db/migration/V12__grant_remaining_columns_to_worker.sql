-- Hibernate's full-entity save (JpaRepository.save) issues UPDATE photos SET ALL_COLUMNS.
-- V3 only granted UPDATE on the columns the worker *logically* needs; the remaining
-- columns must also be granted to allow the full UPDATE statement to execute.
-- @DynamicUpdate on the Photo entity would eliminate the need for this, but that
-- requires a JAR rebuild. Granting all columns here is the minimal path.
GRANT UPDATE (caption, deleted_at, description, filename, original_filename,
              taken_at, title, updated_at, user_id)
    ON photos TO worker_db_user;
