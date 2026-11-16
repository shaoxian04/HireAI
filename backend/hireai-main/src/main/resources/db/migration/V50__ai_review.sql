-- AI Review (#107, spec S10, D5, D37, D38).
-- The criteria and the frozen fee live in the Task's Contract (tasks.contract -> 'aiReview'); this
-- table records what became of them. The fee itself only moves through the ledger: frozen at submit
-- (ESCROW_FREEZE, correlation ai-review-<task>), then charged (AI_REVIEW_FEE, out of escrow) once a
-- verdict exists, or refunded (REFUND) when the reviewer failed or nothing was ever reviewed.

-- task_id is a soft reference, like task_items.task_id (V47): the row is inserted by JDBC in the
-- submit transaction, before JPA flushes the tasks row.
CREATE TABLE ai_reviews (
    task_id         UUID PRIMARY KEY,
    fee             NUMERIC(12, 2) NOT NULL CHECK (fee > 0),
    status          VARCHAR(16) NOT NULL
        CHECK (status IN ('FROZEN', 'REQUESTED', 'COMPLETED', 'FAILED', 'RELEASED')),
    -- true on the API/MCP channel, where the verdict settles the Task and is final (D38).
    decisive        BOOLEAN NOT NULL DEFAULT FALSE,
    attempts        INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    -- [{criterion, passed, reason}] in the Contract's criteria order; set once COMPLETED.
    verdicts        JSONB,
    ruling_category VARCHAR(32)
        CHECK (ruling_category IS NULL OR ruling_category IN ('FULFILLED', 'PARTIALLY_FULFILLED', 'NOT_FULFILLED')),
    model           VARCHAR(128),
    failure_reason  VARCHAR(1000),
    created_at      TIMESTAMPTZ NOT NULL,
    requested_at    TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    CHECK (status <> 'COMPLETED' OR (verdicts IS NOT NULL AND ruling_category IS NOT NULL))
);

CREATE INDEX ix_ai_reviews_open ON ai_reviews (status, requested_at)
    WHERE status IN ('FROZEN', 'REQUESTED');

-- An API result whose decisive AI Review gave no usable verdict waits for the Client's review like the
-- other results no machine could pass (V48: ANOMALY, UNDECIDED). It is never settled by the failure.
ALTER TABLE tasks DROP CONSTRAINT IF EXISTS tasks_review_reason_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_review_reason_check
    CHECK (review_reason IN ('ANOMALY', 'UNDECIDED', 'AI_REVIEW_UNAVAILABLE'));

-- The fee leaves escrow to the platform, like commission. Keeps every value V45 (#96) allowed.
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS ledger_entries_entry_type_check;
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_entry_type_check
    CHECK (entry_type IN ('TOPUP', 'ESCROW_FREEZE', 'PAYOUT', 'REFUND', 'COMMISSION', 'SPLIT',
                          'ARBITRATION_FEE', 'APPEAL_FEE', 'AI_REVIEW_FEE'));

-- A decisive verdict scores the Agent like the ruling it maps to. Keeps every value V47 (#103) allowed.
ALTER TABLE reputation_events DROP CONSTRAINT IF EXISTS reputation_events_event_type_check;
ALTER TABLE reputation_events ADD CONSTRAINT reputation_events_event_type_check
    CHECK (event_type IN (
        'TASK_ACCEPTED', 'SPEC_VIOLATION', 'EXECUTION_FAILED', 'EXECUTION_TIMEOUT',
        'DISPUTE_WON', 'DISPUTE_PARTIAL', 'DISPUTE_LOST', 'RATING', 'REJECTION_CONCEDED', 'ITEMS_SETTLED',
        'AI_REVIEW_PASSED', 'AI_REVIEW_PARTIAL', 'AI_REVIEW_FAILED'));
