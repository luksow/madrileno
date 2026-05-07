CREATE TABLE auction_image (
    id UUID PRIMARY KEY,
    auction_id UUID NOT NULL REFERENCES auction(id),
    storage_key TEXT NOT NULL UNIQUE,
    file_name TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    position INT NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX auction_image_auction_position_idx ON auction_image (auction_id, position) WHERE deleted_at IS NULL;
