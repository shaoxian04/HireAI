-- V27: Module 5 reputation (spec: docs/superpowers/specs/2026-08-12-module5-reputation-design.md,
-- binding: docs/adr/0003-two-component-shrinkage-reputation.md).
--
-- agents.reputation_score has been NUMERIC(5,2) frozen at 50.00 since V3 — written once at
-- registration and updated by nothing. The matcher weights it at 0.40, its largest factor, so
-- every candidate has contributed an identical 0.20 and reputation has ranked nothing. This
-- migration gives it a source.

-- 1) The append-only event stream (Invariant #2). task_id is a SOFT reference — no cross-context
--    FK — following validation_reports (V16) and api_key_task, so the Task aggregate is untouched.
--    quality says what the sample asserts; weight says how much it counts.
CREATE TABLE reputation_events (
    id          UUID PRIMARY KEY,
    agent_id    UUID NOT NULL REFERENCES agents (id),
    task_id     UUID,
    event_type  TEXT NOT NULL CHECK (event_type IN (
                    'TASK_ACCEPTED', 'SPEC_VIOLATION', 'EXECUTION_FAILED', 'EXECUTION_TIMEOUT',
                    'DISPUTE_WON', 'DISPUTE_PARTIAL', 'DISPUTE_LOST', 'RATING')),
    quality     NUMERIC(4,3) NOT NULL CHECK (quality BETWEEN 0 AND 1),
    weight      NUMERIC(4,3) NOT NULL CHECK (weight > 0),
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_reputation_events_agent_occurred ON reputation_events (agent_id, occurred_at DESC);

-- Emission is exactly-once per (task, outcome). A task can legitimately produce both a RELIABILITY
-- event and a later RATING, so the key includes the type.
CREATE UNIQUE INDEX uq_reputation_events_task_type
    ON reputation_events (task_id, event_type) WHERE task_id IS NOT NULL;

-- 2) Append-only, exactly as ledger_entries (V1). Corrections are compensating entries, never
--    edits — the whole point is that any score can be reconstructed and explained.
CREATE OR REPLACE FUNCTION reputation_events_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'reputation_events is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reputation_events_no_update
    BEFORE UPDATE ON reputation_events
    FOR EACH ROW EXECUTE FUNCTION reputation_events_block_mutation();

CREATE TRIGGER trg_reputation_events_no_delete
    BEFORE DELETE ON reputation_events
    FOR EACH ROW EXECUTE FUNCTION reputation_events_block_mutation();

-- 3) Running aggregates on agents. These make the score update O(1) on settlement instead of
--    re-reading an agent's whole event history. They are a DERIVED CACHE: reputation_events
--    remains the source of truth, and the reconciliation path replays the stream and asserts
--    these agree.
--
--    Defaults of 0 backfill every existing agent to zero events, which scores exactly the 50.00
--    they already hold — so nothing shifts on migration day until agents earn events.
ALTER TABLE agents
    ADD COLUMN reliability_sum    NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (reliability_sum >= 0),
    ADD COLUMN reliability_count  BIGINT        NOT NULL DEFAULT 0 CHECK (reliability_count >= 0),
    ADD COLUMN satisfaction_sum   NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (satisfaction_sum >= 0),
    ADD COLUMN satisfaction_count BIGINT        NOT NULL DEFAULT 0 CHECK (satisfaction_count >= 0);
