-- V19: widen the tasks status CHECK to include DISPUTED. Module 4 Phase 2 added the DISPUTED
-- lifecycle status to TaskStatus (PENDING_REVIEW → DISPUTED → RESOLVED) but no migration updated
-- the V2 constraint, so any task transition to DISPUTED was rejected at the DB
-- (violates check constraint "tasks_status_check"). Name-independent drop + re-add, mirroring V18.
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
        'DISPUTED', 'RESOLVED', 'AWAITING_CAPACITY', 'TIMED_OUT', 'SPEC_VIOLATION',
        'FAILED', 'CANCELLED'));
