-- V4: Routing & execution extensions for the Task aggregate (Module 3).
-- tasks gains the selected agent version (unconstrained UUID — no cross-context FK to
-- agent_versions, keeping the Agent and Task contexts independently deployable) and a
-- routing category. task_results stores the single result an Agent posts back per task;
-- result_payload is JSONB so the result shape can evolve without a migration.

ALTER TABLE tasks ADD COLUMN agent_version_id UUID;
ALTER TABLE tasks ADD COLUMN category TEXT;

CREATE TABLE task_results (
    id             UUID PRIMARY KEY,
    task_id        UUID NOT NULL UNIQUE REFERENCES tasks (id),
    result_payload JSONB NOT NULL,
    result_url     TEXT,
    agent_status   TEXT NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_create     TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_results_task ON task_results (task_id);
