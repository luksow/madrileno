CREATE TABLE __aggregates__ (
  id          UUID PRIMARY KEY,
  name        TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL,
  deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx___aggregates___deleted_at ON __aggregates__ (deleted_at) WHERE deleted_at IS NOT NULL;
