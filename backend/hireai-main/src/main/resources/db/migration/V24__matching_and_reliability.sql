-- V24: matching engine + reliability sweepers (spec: docs/superpowers/specs/2026-07-04-matching-engine-design.md).
-- 1) Builder-declared per-version capacity for the loadHeadroom score factor. DEFAULT 5 backfills
--    every existing version; new registrations pass an explicit value.
ALTER TABLE agent_versions ADD COLUMN max_concurrent INT NOT NULL DEFAULT 5
    CHECK (max_concurrent BETWEEN 1 AND 100);

-- 2) Attempt-bounded re-match counter (AWAITING_CAPACITY sweeper).
ALTER TABLE tasks ADD COLUMN match_attempts INT NOT NULL DEFAULT 0;

-- 3) Execution deadline, stamped at ASSIGNMENT (queue time + max_execution_seconds + grace) so one
--    sweep catches both silent executors (EXECUTING) and lost dispatches (stuck QUEUED).
ALTER TABLE tasks ADD COLUMN execution_deadline TIMESTAMPTZ;

-- 4) Direct bookings pin an exact agent version at submit; the re-match sweeper must never
--    substitute another agent for a pinned task. NULL = open task.
ALTER TABLE tasks ADD COLUMN pinned_agent_version_id UUID;

-- 5) Makes the candidate query's per-agent in-flight/sample counts index range scans.
CREATE INDEX idx_tasks_agent_version_status ON tasks (agent_version_id, status);

-- 6) Defensive category normalisation. AgentVersionModel.create already lowercases categories at
--    registration, but rows created by seed data or before that rule may not comply; the candidate
--    query lowercases its :category parameter and relies on stored values being lowercase.
UPDATE agent_versions SET capability_categories =
    (SELECT array_agg(lower(trim(c))) FROM unnest(capability_categories) AS c)
WHERE capability_categories IS NOT NULL;
