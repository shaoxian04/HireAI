-- #103 (spec S3: D3, D14, D22, D27): a Task holds 1..n Items.
--
-- A Task without task_items rows is today's single-deliverable Task: its one Item is the Task's own
-- result, and nothing about it changes. A multi-Item Task has 2..1000 rows, numbered from 1.
-- tasks.budget stays the escrow of record (the per-Item price × the Item count, frozen once at
-- submit), so every whole-Task refund path (timeout, cancel, failover, Agent failure) still returns
-- exactly what was frozen. task_items.price is each Item's share.
--
-- Each Item has its own small state machine and its own settlement: one task_item_settlements row
-- per settled Item (UNIQUE item_id is the backstop against settling an Item twice, as
-- settlements.task_id UNIQUE is for a Task). The money itself moves through ledger_entries, with
-- correlation_id 'settle-<task id>-item-<item no>'. A multi-Item Task writes no settlements row.
CREATE TABLE task_items (
    id             UUID        PRIMARY KEY,
    -- Soft reference (like validation_reports): Items are written with JDBC in the submit
    -- transaction, before JPA has flushed the tasks row.
    task_id        UUID        NOT NULL,
    item_no        INT         NOT NULL CHECK (item_no BETWEEN 1 AND 1000),
    input          TEXT        NOT NULL CHECK (char_length(input) BETWEEN 1 AND 5000),
    price          NUMERIC(14, 2) NOT NULL CHECK (price > 0),
    status         TEXT        NOT NULL CHECK (status IN (
                       'AWAITING_RESULT', 'PENDING_REVIEW', 'ACCEPTED', 'SPEC_VIOLATION', 'FAILED')),
    agent_status   TEXT        NULL,
    result_payload TEXT        NULL,
    result_url     TEXT        NULL,
    -- The Contract's checks as run on this Item's result: [{rule, passed, detail}].
    checks         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    auto_accepted  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_task_items_task_item_no UNIQUE (task_id, item_no)
);

CREATE TABLE task_item_settlements (
    id         UUID           PRIMARY KEY,
    task_id    UUID           NOT NULL,
    item_id    UUID           NOT NULL UNIQUE REFERENCES task_items (id),
    item_no    INT            NOT NULL,
    type       TEXT           NOT NULL CHECK (type IN ('ACCEPT', 'REJECT')),
    net        NUMERIC(14, 2) NOT NULL CHECK (net >= 0),
    commission NUMERIC(14, 2) NOT NULL CHECK (commission >= 0),
    refund     NUMERIC(14, 2) NOT NULL CHECK (refund >= 0),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_item_settlements_task ON task_item_settlements (task_id);

-- Settlement records are history (Invariant #2): corrections are compensating entries.
CREATE OR REPLACE FUNCTION task_item_settlements_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'task_item_settlements is append-only (% refused)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_item_settlements_no_update
    BEFORE UPDATE ON task_item_settlements
    FOR EACH ROW EXECUTE FUNCTION task_item_settlements_block_mutation();

CREATE TRIGGER trg_task_item_settlements_no_delete
    BEFORE DELETE ON task_item_settlements
    FOR EACH ROW EXECUTE FUNCTION task_item_settlements_block_mutation();

-- A finished multi-Item Task writes ONE reputation event, weight 1, quality = accepted fraction
-- (D22, D27). Keeps every value V42 (#95) allowed.
ALTER TABLE reputation_events DROP CONSTRAINT IF EXISTS reputation_events_event_type_check;
ALTER TABLE reputation_events ADD CONSTRAINT reputation_events_event_type_check
    CHECK (event_type IN (
        'TASK_ACCEPTED', 'SPEC_VIOLATION', 'EXECUTION_FAILED', 'EXECUTION_TIMEOUT',
        'DISPUTE_WON', 'DISPUTE_PARTIAL', 'DISPUTE_LOST', 'RATING', 'REJECTION_CONCEDED', 'ITEMS_SETTLED'));
