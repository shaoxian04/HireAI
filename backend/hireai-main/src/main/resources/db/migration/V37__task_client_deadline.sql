-- #83: the Client's absolute deadline for a task, fixed at submit from the optional deadlineSeconds.
-- NULL = no deadline. Every Agent selection (initial routing, each failover hop) admits only Agents
-- with now + max_execution_seconds + grace <= client_deadline. Unmapped on TaskDO like
-- execution_deadline: written once at submit and read by routing through targeted queries.
ALTER TABLE tasks
    ADD COLUMN client_deadline TIMESTAMPTZ NULL;
