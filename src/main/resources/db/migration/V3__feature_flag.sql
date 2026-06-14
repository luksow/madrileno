CREATE TABLE feature_flag (
    id             UUID        PRIMARY KEY,
    key            TEXT        NOT NULL UNIQUE,
    description    TEXT        NOT NULL,
    variant_type   TEXT        NOT NULL CHECK (variant_type IN ('Boolean', 'String', 'Int', 'Json')),
    enabled        BOOLEAN     NOT NULL,
    default_value  JSONB       NOT NULL,
    client_exposed BOOLEAN     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE feature_flag_rule (
    id          UUID        PRIMARY KEY,
    flag_id     UUID        NOT NULL REFERENCES feature_flag (id),
    position    INT         NOT NULL,
    description TEXT        NOT NULL,
    conditions  JSONB       NOT NULL,
    outcome     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (flag_id, position)
);

CREATE INDEX feature_flag_rule_flag_position_idx ON feature_flag_rule (flag_id, position);

CREATE TABLE feature_flag_segment (
    id          UUID        PRIMARY KEY,
    name        TEXT        NOT NULL UNIQUE,
    description TEXT        NOT NULL,
    conditions  JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE feature_flag_audit (
    id              UUID        PRIMARY KEY,
    flag_id         UUID        REFERENCES feature_flag (id),
    flag_key        TEXT        NOT NULL,
    actor           TEXT        NOT NULL,
    action          TEXT        NOT NULL CHECK (action IN ('Created', 'Updated', 'Deleted', 'Toggled')),
    before_snapshot JSONB,
    after_snapshot  JSONB,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX feature_flag_audit_flag_id_idx ON feature_flag_audit (flag_id, created_at DESC);
