-- V8: Index tasks.agent_version_id. The builder-stats and catalogue aggregates join
-- tasks -> agent_versions on this column (V4 added it without an index); without this,
-- every stats request full-scans tasks as volume grows.
CREATE INDEX idx_tasks_agent_version ON tasks (agent_version_id);
