-- V11: External identity links (OAuth). A table (not columns on users) so a second provider
-- is additive later. provider_subject is the provider's stable 'sub'.

CREATE TABLE user_identities (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users (id),
    provider         TEXT NOT NULL,
    provider_subject TEXT NOT NULL,
    email_at_link    TEXT,
    gmt_create       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_user_identities_user ON user_identities (user_id);
