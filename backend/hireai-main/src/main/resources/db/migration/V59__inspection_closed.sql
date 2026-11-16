-- An inspection with nothing left to sample (every Item failed its checks or was refunded, or the Task
-- ended) is CLOSED, a terminal state, instead of staying SAMPLING with no sample and no reviewer work.
ALTER TABLE task_inspections DROP CONSTRAINT task_inspections_status_check;
ALTER TABLE task_inspections ADD CONSTRAINT task_inspections_status_check
    CHECK (status IN ('SAMPLING', 'REWORKING', 'ACCEPTED', 'PRO_RATA', 'WAIVED', 'CLOSED'));

-- Heal the inspections already left open on a Task that has ended.
UPDATE task_inspections i SET status = 'CLOSED', updated_at = now()
WHERE i.status IN ('SAMPLING', 'REWORKING')
  AND EXISTS (SELECT 1 FROM tasks t WHERE t.id = i.task_id
              AND t.status IN ('RESOLVED', 'TIMED_OUT', 'SPEC_VIOLATION', 'FAILED', 'CANCELLED'));
