-- #91: why no Agent matched a routed task, in plain words (the blocking Contract parameter, the
-- Template version or the relevance floor, with Agent counts). Written by each no-match routing pass
-- while the task waits AWAITING_AGENT, and kept as the failure detail when the re-match sweeper ends it
-- NO_ELIGIBLE_AGENT. NULL = none recorded. Unmapped on TaskDO like client_deadline: written and read
-- through targeted queries.
ALTER TABLE tasks
    ADD COLUMN failure_detail VARCHAR(1000) NULL;
