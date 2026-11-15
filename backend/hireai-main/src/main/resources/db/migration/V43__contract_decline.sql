-- #93 (D2/D33): ContractDecline. An Agent refuses one specific Contract by answering the WorkOrder
-- with HTTP 422 and {"decline":"CONTRACT","reason":...}. It is a dispatch-attempt outcome of its
-- own (never confused with a busy decline), carries the Agent's reason, and ends a pinned task
-- with its own FailureReason. Value sets must stay in sync with DispatchOutcome and FailureReason.
--
-- dispatch_attempts stays append-only: ADD COLUMN and constraint changes fire no row trigger, and
-- the new column is written only on INSERT.

ALTER TABLE dispatch_attempts DROP CONSTRAINT dispatch_attempts_outcome_check;
ALTER TABLE dispatch_attempts ADD CONSTRAINT dispatch_attempts_outcome_check
    CHECK (outcome IN ('ACCEPTED', 'UNREACHABLE', 'BUSY', 'UNAVAILABLE', 'CONTRACT_DECLINED'));

-- The Agent's own words, already trimmed and capped by DispatchAttemptModel. Only a contract
-- decline has one, and it is optional even then.
ALTER TABLE dispatch_attempts
    ADD COLUMN decline_reason TEXT NULL
        CONSTRAINT dispatch_attempts_decline_reason_check CHECK (
            decline_reason IS NULL
            OR (outcome = 'CONTRACT_DECLINED' AND char_length(decline_reason) BETWEEN 1 AND 500));

-- The public decline rate is counted per Agent across its versions.
CREATE INDEX idx_dispatch_attempts_version_outcome ON dispatch_attempts (agent_version_id, outcome);

ALTER TABLE tasks DROP CONSTRAINT tasks_failure_reason_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_failure_reason_check
    CHECK (failure_reason IS NULL OR failure_reason IN (
        'AGENT_UNREACHABLE',
        'AGENT_BUSY',
        'AGENT_UNAVAILABLE',
        'NO_ALTERNATIVE_AGENT',
        'NO_ELIGIBLE_AGENT',
        'DISPATCH_ABANDONED',
        'EXECUTION_TIMEOUT',
        'AGENT_REPORTED_FAILURE',
        'SPEC_VIOLATION',
        'CONTRACT_DECLINED'
    ));
