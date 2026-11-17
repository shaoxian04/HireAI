-- Owner decision on #87 (revised 2026-11-16): no refund unless the Agent is shown wrong. An Item a check
-- sent to review that nobody judged before the review window closed is accepted and paid like any other,
-- so the UNREVIEWED status (V54) is retired. A row already UNREVIEWED was refunded in the append-only
-- ledger; relabelling it would misstate that, so the migration refuses to run while one exists.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM task_items WHERE status = 'UNREVIEWED') THEN
        RAISE EXCEPTION 'task_items rows with status UNREVIEWED exist; resolve them by hand before V56';
    END IF;
END $$;

ALTER TABLE task_items DROP CONSTRAINT IF EXISTS task_items_status_check;
ALTER TABLE task_items ADD CONSTRAINT task_items_status_check CHECK (status IN (
    'AWAITING_RESULT', 'PENDING_REVIEW', 'ACCEPTED', 'SPEC_VIOLATION', 'FAILED',
    'REJECTED', 'CONCEDED', 'PRO_RATA'));
