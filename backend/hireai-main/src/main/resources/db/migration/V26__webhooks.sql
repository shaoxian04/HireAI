-- V26__webhooks.sql — Phase 4 push webhooks (additive; money tables untouched).

CREATE TABLE client_webhook_subscriptions (
    id             UUID PRIMARY KEY,
    api_key_id     UUID NOT NULL REFERENCES api_keys(id),
    owner_id       UUID NOT NULL REFERENCES users(id),
    callback_url   TEXT NOT NULL,
    signing_secret TEXT NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);
-- At most one ACTIVE subscription per API key.
CREATE UNIQUE INDEX uq_webhook_sub_active_key ON client_webhook_subscriptions (api_key_id) WHERE active;
CREATE INDEX ix_webhook_sub_owner ON client_webhook_subscriptions (owner_id);

CREATE TABLE webhook_deliveries (
    id              UUID PRIMARY KEY,               -- doubles as the client-facing event_id
    task_id         UUID NOT NULL REFERENCES tasks(id),
    owner_id        UUID NOT NULL REFERENCES users(id),
    subscription_id UUID NOT NULL REFERENCES client_webhook_subscriptions(id),
    event_type      TEXT NOT NULL,                  -- task.completed | task.failed
    payload         TEXT NOT NULL,                  -- built/stored/sent whole as a string; never queried with jsonb operators (same rationale as V20 task_results.result_payload -> TEXT). Avoids @JdbcTypeCode(JSON) mapping.
    target_url      TEXT NOT NULL,
    status          TEXT NOT NULL,                  -- PENDING | DELIVERED | DEAD
    attempts        INT  NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    delivered_at    TIMESTAMPTZ
);
-- Sweeper claim key.
CREATE INDEX ix_webhook_deliveries_due ON webhook_deliveries (status, next_attempt_at);
CREATE INDEX ix_webhook_deliveries_owner ON webhook_deliveries (owner_id, created_at DESC);
CREATE INDEX ix_webhook_deliveries_task ON webhook_deliveries (task_id);
