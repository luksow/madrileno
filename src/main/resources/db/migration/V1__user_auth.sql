create table "user"(
    id UUID PRIMARY KEY,
    full_name TEXT,
    email TEXT,
    email_verified BOOLEAN NOT NULL,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    blocked_at TIMESTAMPTZ
);

create table user_auth(
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES "user" (id),
    provider TEXT NOT NULL,
    provider_user_id TEXT NOT NULL,
    credential TEXT NOT NULL,
    metadata JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

create unique index user_auth_provider_user_id_active_uniq
    on user_auth (provider, provider_user_id) WHERE deleted_at IS NULL;

create table refresh_token(
    id UUID PRIMARY KEY,
    family_id UUID NOT NULL,
    family_created_at TIMESTAMPTZ NOT NULL,
    secret_hash TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES "user" (id),
    user_agent TEXT NOT NULL,
    ip_address TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    successor_secret TEXT,
    deleted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);

create unique index refresh_token_secret_hash_uniq on refresh_token (secret_hash);

create index refresh_token_family_id_idx on refresh_token (family_id);

create index refresh_token_live_user_id_idx on refresh_token (user_id) WHERE used_at IS NULL AND deleted_at IS NULL;
