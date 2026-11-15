-- #95: the rejection response path (spec D20/D22). A Client's A/B/C rejection no longer opens
-- arbitration at once: the Agent may revise once, concede or stand by (2h), and after a stand-by the
-- Client accepts or requests arbitration (24h). Silence counts as standing by / as acceptance.

-- 1) Two task statuses. Name-independent drop + re-add of the status CHECK, mirroring V29.
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
     WHERE conrelid = 'tasks'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) ILIKE '%SUBMITTED%';
    IF c IS NOT NULL THEN
        EXECUTE 'ALTER TABLE tasks DROP CONSTRAINT ' || quote_ident(c);
    END IF;
END $$;

ALTER TABLE tasks ADD CONSTRAINT tasks_status_check
    CHECK (status IN (
        'SUBMITTED', 'QUEUED', 'EXECUTING', 'RESULT_RECEIVED', 'PENDING_REVIEW',
        'AWAITING_AGENT_RESPONSE', 'AWAITING_CLIENT_DECISION',
        'DISPUTED', 'RESOLVED', 'AWAITING_AGENT', 'TIMED_OUT', 'SPEC_VIOLATION',
        'FAILED', 'CANCELLED'));

-- 2) A concession is a zero-quality Reliability sample (D22), with its own event type.
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
     WHERE conrelid = 'reputation_events'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) ILIKE '%TASK_ACCEPTED%';
    IF c IS NOT NULL THEN
        EXECUTE 'ALTER TABLE reputation_events DROP CONSTRAINT ' || quote_ident(c);
    END IF;
END $$;

ALTER TABLE reputation_events ADD CONSTRAINT reputation_events_event_type_check
    CHECK (event_type IN (
        'TASK_ACCEPTED', 'SPEC_VIOLATION', 'EXECUTION_FAILED', 'EXECUTION_TIMEOUT',
        'DISPUTE_WON', 'DISPUTE_PARTIAL', 'DISPUTE_LOST', 'RATING', 'REJECTION_CONCEDED'));

-- 3) One row per A/B/C rejection of a task (a "round"). A second round exists only after a revision,
--    and may not be revised again. notice_* track delivery of the signed rejection notice to the
--    Agent's webhook (retried by the rejection-window sweeper while the response window is open).
--    replaced_result_* keep the result a revision replaced, as evidence for a later arbitration.
CREATE TABLE task_rejections (
    id                      UUID PRIMARY KEY,
    task_id                 UUID        NOT NULL REFERENCES tasks (id),
    round                   INT         NOT NULL CHECK (round >= 1),
    reason_category         TEXT        NOT NULL CHECK (reason_category IN ('A_MISMATCH', 'B_FACTUAL', 'C_INCOMPLETE')),
    reason                  TEXT        CHECK (char_length(reason) <= 500),
    rejected_at             TIMESTAMPTZ NOT NULL,
    response_deadline       TIMESTAMPTZ NOT NULL,
    revision_allowed        BOOLEAN     NOT NULL,
    response                TEXT        CHECK (response IN ('REVISE', 'CONCEDE', 'STAND_BY')),
    response_reason         TEXT        CHECK (char_length(response_reason) <= 500),
    response_silent         BOOLEAN     NOT NULL DEFAULT FALSE,
    responded_at            TIMESTAMPTZ,
    decision_deadline       TIMESTAMPTZ,
    decision                TEXT        CHECK (decision IN ('ACCEPT', 'ARBITRATE')),
    decision_silent         BOOLEAN     NOT NULL DEFAULT FALSE,
    decided_at              TIMESTAMPTZ,
    replaced_result_payload TEXT,
    replaced_result_url     TEXT,
    notice_sent_at          TIMESTAMPTZ,
    notice_attempts         INT         NOT NULL DEFAULT 0,
    gmt_create              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (task_id, round),
    CHECK (decision IS NULL OR response = 'STAND_BY'),
    CHECK ((response = 'STAND_BY') = (decision_deadline IS NOT NULL))
);

CREATE INDEX idx_task_rejections_response_open ON task_rejections (response_deadline) WHERE response IS NULL;
CREATE INDEX idx_task_rejections_decision_open ON task_rejections (decision_deadline)
    WHERE response = 'STAND_BY' AND decision IS NULL;
