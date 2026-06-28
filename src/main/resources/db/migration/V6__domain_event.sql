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
