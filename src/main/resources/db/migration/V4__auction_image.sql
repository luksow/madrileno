CREATE TABLE auction_image (
    id UUID PRIMARY KEY,
    auction_id UUID NOT NULL REFERENCES auction(id),
    storage_key TEXT NOT NULL UNIQUE,
    file_name TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    position INT NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    width INT,
    height INT,
    format TEXT,
    analyzed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX auction_image_auction_position_idx ON auction_image (auction_id, position) WHERE deleted_at IS NULL;

CREATE TABLE auction_image_variant (
    id UUID PRIMARY KEY,
    auction_image_id UUID NOT NULL REFERENCES auction_image(id) ON DELETE CASCADE,
    spec TEXT NOT NULL,
    storage_key TEXT NOT NULL UNIQUE,
    width INT NOT NULL,
    height INT NOT NULL,
    format TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (auction_image_id, spec)
);
