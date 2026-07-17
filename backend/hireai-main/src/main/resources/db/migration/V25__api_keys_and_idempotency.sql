-- V25: programmatic submission spine (spec: docs/superpowers/specs/2026-07-14-programmatic-spine-design.md).
-- Three additive tables. The money tables (wallets, ledger_entries, settlements) are untouched:
-- spend caps are an authorization READ computed from tasks, never a second ledger (Invariant #2).

-- 1) API keys. Only the SHA-256 hex hash and a short display prefix are stored — never the raw key.
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users (id),
    key_hash        TEXT NOT NULL UNIQUE,           -- hex SHA-256 of the raw key
    display_prefix  TEXT NOT NULL,                  -- e.g. hk_live_a1b2c3
    name            TEXT,
    spend_cap       NUMERIC(18,2),                  -- NULL = uncapped (max concurrent frozen escrow)
    daily_spend_cap NUMERIC(18,2),                  -- NULL = uncapped (max committed per rolling 24h)
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    last_used_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_user ON api_keys (user_id);

-- 2) Idempotency records. UNIQUE(owner_id, idempotency_key) is the concurrency arbiter: a duplicate
-- insert in the SAME transaction as the submit rolls the whole submit back (undoes the escrow
-- freeze — no double-freeze, Invariant #1). Mirrors settlements.task_id UNIQUE (V14).
CREATE TABLE idempotency_keys (
    id                  UUID PRIMARY KEY,
    owner_id            UUID NOT NULL,
    idempotency_key     TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,   -- SHA-256 of the normalized submit payload
    task_id             UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, idempotency_key)
);

-- 3) Attribution. One row per task submitted via a key. Soft task_id reference (like
-- validation_reports) keeps the Task aggregate untouched. Powers both spend-cap reads.
CREATE TABLE api_key_task (
    task_id     UUID PRIMARY KEY,
    api_key_id  UUID NOT NULL REFERENCES api_keys (id),
    budget      NUMERIC(18,2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_key_task_key ON api_key_task (api_key_id);
