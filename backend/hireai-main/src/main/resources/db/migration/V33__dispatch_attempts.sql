-- #75: the append-only history of a task's dispatch attempts, one row per Agent version the
-- platform tried to hand the task to, with how that try ended. It makes failover auditable and is
-- read back by the task owner through GET /api/tasks/{id}/attempts.
--
-- task_id and agent_version_id are SOFT references (no FK), following reputation_events (V27) and
-- validation_reports (V16): the history must outlive any cleanup of the rows it points at, and an
-- append-only row can never be cascaded away.
-- The outcome set must stay in sync with com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome.
CREATE TABLE dispatch_attempts (
    id               UUID PRIMARY KEY,
    task_id          UUID        NOT NULL,
    agent_version_id UUID        NOT NULL,
    attempt_number   INT         NOT NULL CHECK (attempt_number >= 1),
    outcome          TEXT        NOT NULL CHECK (outcome IN ('ACCEPTED', 'UNREACHABLE', 'BUSY', 'UNAVAILABLE')),
    occurred_at      TIMESTAMPTZ NOT NULL,
    gmt_create       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_dispatch_attempts_task_attempt UNIQUE (task_id, attempt_number)
);

-- Append-only, exactly as ledger_entries (V1) and reputation_events (V27).
CREATE OR REPLACE FUNCTION dispatch_attempts_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'dispatch_attempts is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dispatch_attempts_no_update
    BEFORE UPDATE ON dispatch_attempts
    FOR EACH ROW EXECUTE FUNCTION dispatch_attempts_block_mutation();

CREATE TRIGGER trg_dispatch_attempts_no_delete
    BEFORE DELETE ON dispatch_attempts
    FOR EACH ROW EXECUTE FUNCTION dispatch_attempts_block_mutation();
