-- V29: rename the task status AWAITING_CAPACITY to AWAITING_AGENT (#73). The state means a routed
-- task found no eligible Agent yet; capacity (an Agent declining as busy) never causes it.
-- Drop the status CHECK first (name-independent, mirroring V19), rewrite existing rows, then
-- re-add the CHECK with the new value only.
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

UPDATE tasks SET status = 'AWAITING_AGENT' WHERE status = 'AWAITING_CAPACITY';

ALTER TABLE tasks ADD CONSTRAINT tasks_status_check
    CHECK (status IN (
        'SUBMITTED', 'QUEUED', 'EXECUTING', 'RESULT_RECEIVED', 'PENDING_REVIEW',
        'DISPUTED', 'RESOLVED', 'AWAITING_AGENT', 'TIMED_OUT', 'SPEC_VIOLATION',
        'FAILED', 'CANCELLED'));
