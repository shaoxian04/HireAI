-- #74: why a task ended without a delivered result. Set once by the terminal transition
-- (TaskModel); NULL on live and successful tasks, and on tasks that ended before this migration.
-- The value set must stay in sync with com.hireai.domain.biz.task.enums.FailureReason.
ALTER TABLE tasks
    ADD COLUMN failure_reason TEXT NULL
        CONSTRAINT tasks_failure_reason_check CHECK (failure_reason IS NULL OR failure_reason IN (
            'AGENT_UNREACHABLE',
            'AGENT_BUSY',
            'AGENT_UNAVAILABLE',
            'NO_ALTERNATIVE_AGENT',
            'NO_ELIGIBLE_AGENT',
            'DISPATCH_ABANDONED',
            'EXECUTION_TIMEOUT',
            'AGENT_REPORTED_FAILURE',
            'SPEC_VIOLATION'
        ));
