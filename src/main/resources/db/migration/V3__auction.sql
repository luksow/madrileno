CREATE TABLE auction(
    id UUID PRIMARY KEY,
    seller_id UUID NOT NULL REFERENCES "user" (id),
    wine_name TEXT NOT NULL,
    vintage INT NOT NULL,
    color TEXT NOT NULL,
    region TEXT NOT NULL,
    appellation TEXT NOT NULL,
    producer_name TEXT NOT NULL,
    bottle_size TEXT NOT NULL,
    bottle_count INT NOT NULL,
    description TEXT,
    starting_price NUMERIC NOT NULL,
    currency TEXT NOT NULL,
    status TEXT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE bid(
    id UUID PRIMARY KEY,
    auction_id UUID NOT NULL REFERENCES auction (id),
    bidder_id UUID NOT NULL REFERENCES "user" (id),
    amount NUMERIC NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX bid_auction_id_amount_idx ON bid (auction_id, amount DESC);
CREATE INDEX bid_bidder_id_idx ON bid (bidder_id);
CREATE INDEX auction_seller_id_idx ON auction (seller_id);
CREATE INDEX auction_status_ends_at_active_idx ON auction (status, ends_at) WHERE deleted_at IS NULL;
