-- V16: per-(task, attempt) automated validation outcome.
-- task_id is a soft reference (no cross-context FK) — Adjudication stays independent of Task.
CREATE TABLE validation_reports (
    id           UUID PRIMARY KEY,
    task_id      UUID NOT NULL,
    attempt_no   INT  NOT NULL DEFAULT 1,
    verdict      TEXT NOT NULL CHECK (verdict IN ('PASS', 'FAIL')),
    checks       JSONB NOT NULL,
    gmt_create   TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (task_id, attempt_no)
);
