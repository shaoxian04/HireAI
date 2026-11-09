-- V34: routed failover (#78) can hand one task to up to 3 Agents, and each Agent that was
-- unreachable after the full retry schedule earns its own EXECUTION_FAILED. Emission stays
-- exactly-once, now per (task, Agent, outcome) instead of per (task, outcome). Every existing row
-- satisfies the new key, since the old one was stricter.
DROP INDEX IF EXISTS uq_reputation_events_task_type;

CREATE UNIQUE INDEX uq_reputation_events_task_agent_type
    ON reputation_events (task_id, agent_id, event_type) WHERE task_id IS NOT NULL;
