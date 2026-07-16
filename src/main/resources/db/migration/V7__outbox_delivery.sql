CREATE TABLE outbox_delivery(
    event_id UUID NOT NULL REFERENCES domain_event (id),
    consumer TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending','completed','failed')),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX outbox_delivery_status_updated_idx ON outbox_delivery (status, updated_at);
