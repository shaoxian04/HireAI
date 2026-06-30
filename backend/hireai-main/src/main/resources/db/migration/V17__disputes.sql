-- V17__disputes.sql
-- Module 4 Phase 2: dispute records + the uniform reject-reason category on tasks.

CREATE TABLE disputes (
    id               UUID PRIMARY KEY,
    task_id          UUID NOT NULL UNIQUE,           -- soft ref to tasks (no FK; one dispute per task)
    raised_by        UUID NOT NULL,                  -- client user id
    reason_category  TEXT NOT NULL CHECK (reason_category IN ('A_MISMATCH','B_FACTUAL','C_INCOMPLETE')),
    status           TEXT NOT NULL CHECK (status IN ('OPEN','ARBITRATING','RULED','RESOLVED','ESCALATED')),
    correlation_id   TEXT NOT NULL,
    ruling_category  TEXT CHECK (ruling_category IN ('FULFILLED','PARTIALLY_FULFILLED','NOT_FULFILLED')),
    ruling_rationale TEXT,
    ruling_tier      INT,
    decided_by       TEXT CHECK (decided_by IN ('ARBITRATOR','FALLBACK')),
    resolved_at      TIMESTAMPTZ,
    gmt_create       TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_disputes_status ON disputes (status);

-- Uniform reject-reason category (A/B/C open a dispute; D is captured here only — no dispute row).
ALTER TABLE tasks ADD COLUMN reject_reason_category TEXT
    CHECK (reject_reason_category IN ('A_MISMATCH','B_FACTUAL','C_INCOMPLETE','D_CHANGED_MIND'));
