-- #105 (spec S8: D4, D6, D17', D19, D21): sampling inspection of multi-Item Tasks.
--
-- A web-channel multi-Item Task is inspected by sampling: a lot-size -> sample-size table (ISO 2859-1,
-- AQL 2.5, double sampling) sets the sample, drawn after delivery from a recorded seed; the buyer's
-- reviewer judges the sampled Items; a failed sample draws a second, then reworks the whole Task once
-- and resamples, then settles pro rata. All money still moves through ledger_entries (Invariant #2).

-- New Item states: REJECTED (on its rejection response path), CONCEDED (refunded after the Agent
-- conceded), PRO_RATA (paid price x (1 - error rate), the rest refunded).
ALTER TABLE task_items DROP CONSTRAINT IF EXISTS task_items_status_check;
ALTER TABLE task_items ADD CONSTRAINT task_items_status_check CHECK (status IN (
    'AWAITING_RESULT', 'PENDING_REVIEW', 'ACCEPTED', 'SPEC_VIOLATION', 'FAILED',
    'REJECTED', 'CONCEDED', 'PRO_RATA'));
-- The gross a PRO_RATA Item was paid; NULL for every other state.
ALTER TABLE task_items ADD COLUMN paid_amount NUMERIC(14, 2) NULL CHECK (paid_amount >= 0);
ALTER TABLE task_items ADD CONSTRAINT task_items_paid_amount_pro_rata
    CHECK ((status = 'PRO_RATA') = (paid_amount IS NOT NULL));

-- A pro-rata Item settles as a SPLIT: net + commission to the builder's side, the rest refunded.
ALTER TABLE task_item_settlements DROP CONSTRAINT IF EXISTS task_item_settlements_type_check;
ALTER TABLE task_item_settlements ADD CONSTRAINT task_item_settlements_type_check
    CHECK (type IN ('ACCEPT', 'REJECT', 'SPLIT'));

-- One row per sampled Task: the level the buyer chose (within the Offer) and where it stands.
CREATE TABLE task_inspections (
    task_id          UUID        PRIMARY KEY,
    inspection_level TEXT        NOT NULL CHECK (inspection_level IN ('I', 'II', 'III')),
    round            SMALLINT    NOT NULL CHECK (round IN (1, 2)),
    status           TEXT        NOT NULL CHECK (status IN ('SAMPLING', 'REWORKING', 'ACCEPTED', 'PRO_RATA', 'WAIVED')),
    error_rate       NUMERIC(5, 4) NULL CHECK (error_rate BETWEEN 0 AND 1),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'PRO_RATA') = (error_rate IS NOT NULL))
);

-- Every sample drawn (D6): the seed and the Items are fixed when drawn; the decision is recorded once.
CREATE TABLE sample_draws (
    id                 UUID        PRIMARY KEY,
    task_id            UUID        NOT NULL REFERENCES task_inspections (task_id),
    round              SMALLINT    NOT NULL CHECK (round IN (1, 2)),
    stage              SMALLINT    NOT NULL CHECK (stage IN (1, 2)),
    seed               BIGINT      NOT NULL,
    code_letter        CHAR(1)     NOT NULL,
    lot_size           INT         NOT NULL CHECK (lot_size >= 1),
    first_sample_size  INT         NOT NULL CHECK (first_sample_size >= 1),
    second_sample_size INT         NOT NULL CHECK (second_sample_size >= 0),
    first_accept       INT         NOT NULL,
    first_reject       INT         NOT NULL,
    second_accept      INT         NOT NULL,
    second_reject      INT         NOT NULL,
    full_inspection    BOOLEAN     NOT NULL,
    item_nos           INT[]       NOT NULL CHECK (cardinality(item_nos) >= 1),
    drawn_at           TIMESTAMPTZ NOT NULL,
    defects            INT         NULL CHECK (defects >= 0),
    decision           TEXT        NULL CHECK (decision IN ('ACCEPT_LOT', 'DRAW_SECOND', 'REWORK', 'PRO_RATA')),
    decided_at         TIMESTAMPTZ NULL,
    UNIQUE (task_id, round, stage),
    CHECK ((decision IS NULL) = (decided_at IS NULL) AND (decision IS NULL) = (defects IS NULL))
);

-- A draw is evidence: only its decision may be filled in, once; it is never deleted.
CREATE OR REPLACE FUNCTION sample_draws_guard()
    RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'sample_draws is append-only (DELETE refused)';
    END IF;
    IF OLD.decision IS NOT NULL
       OR NEW.seed <> OLD.seed OR NEW.item_nos <> OLD.item_nos OR NEW.round <> OLD.round
       OR NEW.stage <> OLD.stage OR NEW.lot_size <> OLD.lot_size OR NEW.drawn_at <> OLD.drawn_at
       OR NEW.task_id <> OLD.task_id THEN
        RAISE EXCEPTION 'sample_draws: only an undecided draw''s decision may be recorded';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sample_draws_guard_update BEFORE UPDATE ON sample_draws
    FOR EACH ROW EXECUTE FUNCTION sample_draws_guard();
CREATE TRIGGER trg_sample_draws_guard_delete BEFORE DELETE ON sample_draws
    FOR EACH ROW EXECUTE FUNCTION sample_draws_guard();

-- The buyer's reviewer's judgements (D4: the ground truth is a person, never an LLM). Append-only.
CREATE TABLE item_judgements (
    id              UUID        PRIMARY KEY,
    task_id         UUID        NOT NULL,
    item_id         UUID        NOT NULL REFERENCES task_items (id),
    item_no         INT         NOT NULL,
    round           SMALLINT    NOT NULL CHECK (round IN (1, 2)),
    passed          BOOLEAN     NOT NULL,
    reason_category TEXT        NULL CHECK (reason_category IN ('A_MISMATCH', 'B_FACTUAL', 'C_INCOMPLETE')),
    reason          TEXT        NULL CHECK (char_length(reason) <= 500),
    sampled         BOOLEAN     NOT NULL,
    judged_by       UUID        NULL,
    judged_at       TIMESTAMPTZ NOT NULL,
    CHECK (passed = (reason_category IS NULL))
);

CREATE INDEX idx_item_judgements_task ON item_judgements (task_id, judged_at);

CREATE OR REPLACE FUNCTION item_judgements_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'item_judgements is append-only (% refused)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_item_judgements_no_update BEFORE UPDATE ON item_judgements
    FOR EACH ROW EXECUTE FUNCTION item_judgements_block_mutation();
CREATE TRIGGER trg_item_judgements_no_delete BEFORE DELETE ON item_judgements
    FOR EACH ROW EXECUTE FUNCTION item_judgements_block_mutation();

-- A rejected Item's rejection response path (D17'), one row per round, like task_rejections (V42).
CREATE TABLE item_rejections (
    id                      UUID PRIMARY KEY,
    task_id                 UUID        NOT NULL,
    item_id                 UUID        NOT NULL REFERENCES task_items (id),
    item_no                 INT         NOT NULL,
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
    UNIQUE (item_id, round),
    CHECK (decision IS NULL OR response = 'STAND_BY'),
    CHECK ((response = 'STAND_BY') = (decision_deadline IS NOT NULL))
);

CREATE INDEX idx_item_rejections_task ON item_rejections (task_id);
CREATE INDEX idx_item_rejections_response_open ON item_rejections (response_deadline) WHERE response IS NULL;
CREATE INDEX idx_item_rejections_decision_open ON item_rejections (decision_deadline)
    WHERE response = 'STAND_BY' AND decision IS NULL;
