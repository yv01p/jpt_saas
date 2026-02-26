-- api/src/main/resources/db/migration/V1__core_schema.sql

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    oauth_provider VARCHAR(50),
    oauth_id VARCHAR(255),
    quota_bytes BIGINT NOT NULL DEFAULT 10737418240,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    CONSTRAINT users_no_nil_uuid CHECK (id != '00000000-0000-0000-0000-000000000000')
);

CREATE TABLE email_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('verify', 'reset')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX email_tokens_user_idx ON email_tokens (user_id, purpose);
CREATE INDEX email_tokens_expires_idx ON email_tokens (expires_at);

CREATE TABLE photos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    filename VARCHAR(512) NOT NULL,
    caption TEXT,
    title VARCHAR(512),
    description TEXT,
    storage_key VARCHAR(512),
    size_bytes BIGINT,
    content_hash VARCHAR(64),  -- SHA-256 hex
    taken_at TIMESTAMPTZ,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processing_status VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (processing_status IN ('pending', 'processing', 'done', 'failed')),
    deleted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('english',
            coalesce(filename, '') || ' ' ||
            coalesce(title, '') || ' ' ||
            coalesce(caption, '') || ' ' ||
            coalesce(description, '')
        )
    ) STORED,
    -- NOTE: UNIQUE allows multiple NULL content_hash per user (SQL NULL != NULL).
    -- This is intentional: photos in 'pending' status have no hash yet.
    -- Deduplication only applies after processing completes.
    CONSTRAINT uq_user_content_hash UNIQUE (user_id, content_hash)
);

-- No ON DELETE CASCADE on photos.user_id — intentional.
-- Application must clean up photos (and MinIO objects) before deleting a user.

CREATE INDEX photos_user_idx ON photos (user_id) WHERE deleted_at IS NULL;
CREATE INDEX photos_taken_at_idx ON photos (user_id, taken_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX photos_search_idx ON photos USING GIN (search_vector);
CREATE INDEX photos_deleted_idx ON photos (user_id, deleted_at) WHERE deleted_at IS NOT NULL;

-- Unique constraints needed by album_photos composite FKs — must precede album_photos
ALTER TABLE photos ADD CONSTRAINT photos_id_user_id_unique UNIQUE (id, user_id);

CREATE TABLE photo_metadata (
    photo_id UUID PRIMARY KEY REFERENCES photos(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    exif_data JSONB,
    iptc_data JSONB,
    xmp_data JSONB
);

CREATE INDEX photo_exif_gin ON photo_metadata USING GIN (exif_data);
CREATE INDEX photo_iptc_gin ON photo_metadata USING GIN (iptc_data);

CREATE TABLE keywords (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    -- No ON DELETE CASCADE: deleting a parent keyword requires children to be
    -- deleted or re-parented first. Application layer must handle tree restructuring.
    parent_id UUID REFERENCES keywords(id),
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_keyword_per_parent UNIQUE NULLS NOT DISTINCT (user_id, name, parent_id)
);

CREATE TABLE photo_keywords (
    photo_id UUID NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    keyword_id UUID NOT NULL REFERENCES keywords(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    PRIMARY KEY (photo_id, keyword_id)
);

CREATE TABLE albums (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

-- Unique constraint needed by album_photos composite FK — must precede album_photos
ALTER TABLE albums ADD CONSTRAINT albums_id_user_id_unique UNIQUE (id, user_id);

CREATE TABLE album_photos (
    album_id UUID NOT NULL,
    photo_id UUID NOT NULL,
    user_id UUID NOT NULL,
    PRIMARY KEY (album_id, photo_id),
    FOREIGN KEY (album_id, user_id) REFERENCES albums(id, user_id),
    FOREIGN KEY (photo_id, user_id) REFERENCES photos(id, user_id)
);

CREATE TABLE shares (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    resource_type VARCHAR(50) NOT NULL CHECK (resource_type IN ('photo', 'album')),
    resource_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ DEFAULT (now() + interval '30 days'),
    permissions VARCHAR(50) NOT NULL DEFAULT 'view',
    include_gps BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE saved_searches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    query_json JSONB NOT NULL CHECK (query_json IS NOT NULL AND query_json != '{}'::jsonb)
);
