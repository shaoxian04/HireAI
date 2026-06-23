-- V9: Client review resolution. Set exactly once when a RESULT_RECEIVED task is
-- accepted or rejected (tasks.status -> RESOLVED). Settlement amounts are NOT stored
-- here -- the append-only ledger_entries (V1) is the money record (Invariant #2).
ALTER TABLE tasks
    ADD COLUMN resolution       TEXT NULL CHECK (resolution IN ('ACCEPTED', 'REJECTED')),
    ADD COLUMN resolved_at      TIMESTAMPTZ NULL,
    ADD COLUMN rejection_reason TEXT NULL CHECK (char_length(rejection_reason) <= 500);
