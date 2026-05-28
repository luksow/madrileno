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
    user_id UUID NOT NULL REFERENCES "user" (id),
    user_agent TEXT NOT NULL,
    ip_address TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ
);