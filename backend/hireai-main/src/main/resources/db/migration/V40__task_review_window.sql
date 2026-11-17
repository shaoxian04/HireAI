-- #94: the Client review window (spec D10/D41). review_deadline is stamped when the result passes
-- validation and the task enters PENDING_REVIEW (web channel only; API tasks auto-settle at once).
-- The review-window sweeper auto-accepts a PENDING_REVIEW task once the deadline passes and sets
-- review_auto_accepted, so the Client can see the outcome was not their own click. Both columns are
-- unmapped on TaskDO, like execution_deadline. A NULL deadline means the task never had a window
-- (rows from before #94, programmatic tasks), so the sweeper never touches it.
ALTER TABLE tasks
    ADD COLUMN review_deadline      TIMESTAMPTZ NULL,
    ADD COLUMN review_auto_accepted BOOLEAN     NOT NULL DEFAULT FALSE;

CREATE INDEX idx_tasks_review_deadline ON tasks (review_deadline)
    WHERE status = 'PENDING_REVIEW' AND review_deadline IS NOT NULL;
