-- V14: per-task settlement records. The append-only ledger remains the money truth;
-- this table is an auditable record of each settlement decision (accept / reject / split).
-- task_id is a soft reference (no cross-context FK) — Ledger stays independent of Task.
CREATE TABLE settlements (
    id           UUID PRIMARY KEY,
    task_id      UUID NOT NULL UNIQUE,
    type         TEXT NOT NULL CHECK (type IN ('ACCEPT', 'REJECT', 'SPLIT')),
    net          NUMERIC(14, 2) NOT NULL,
    commission   NUMERIC(14, 2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_create   TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified TIMESTAMPTZ NOT NULL DEFAULT now()
);
