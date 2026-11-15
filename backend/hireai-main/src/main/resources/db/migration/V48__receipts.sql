-- V48: Signed receipts (spec S11, D40). One receipt per settled Item: a single-deliverable Task's
-- settlement (item_no NULL) or one Item of a multi-Item Task (item_no 1..n). The body is the exact
-- RFC 8785 text that was signed, kept as TEXT (jsonb would re-format it and break the signature).
-- Receipts are evidence, never inputs: nothing in HireAI reads them to decide anything.

CREATE TABLE receipts (
    id         UUID        PRIMARY KEY,
    task_id    UUID        NOT NULL REFERENCES tasks (id),
    item_no    INT         CHECK (item_no IS NULL OR item_no >= 1),
    key_id     TEXT        NOT NULL CHECK (length(key_id) BETWEEN 1 AND 100),
    body       TEXT        NOT NULL,
    signature  TEXT        NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL
);

-- At most one receipt per settled subject; the issuer inserts with ON CONFLICT DO NOTHING.
CREATE UNIQUE INDEX uq_receipts_subject ON receipts (task_id, COALESCE(item_no, 0));

-- A receipt is signed history (Invariant #2 spirit): never changed, never removed.
CREATE OR REPLACE FUNCTION receipts_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'receipts is append-only (% refused)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_receipts_no_update
    BEFORE UPDATE ON receipts
    FOR EACH ROW EXECUTE FUNCTION receipts_block_mutation();

CREATE TRIGGER trg_receipts_no_delete
    BEFORE DELETE ON receipts
    FOR EACH ROW EXECUTE FUNCTION receipts_block_mutation();
