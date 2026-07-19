CREATE TABLE domain_event(
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX domain_event_type_id_idx ON domain_event (event_type, id);
CREATE INDEX domain_event_aggregate_idx ON domain_event (aggregate_type, aggregate_id);

CREATE TABLE outbox_delivery(
    event_id UUID NOT NULL REFERENCES domain_event (id),
    consumer TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('Pending','Completed','Failed')),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX outbox_delivery_status_updated_idx ON outbox_delivery (status, updated_at);
