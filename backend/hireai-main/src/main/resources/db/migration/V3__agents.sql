-- V3: Agents and their versions. An Agent is a registered third-party executor owned by a
-- Builder; only ACTIVE agents are routable. agent_versions carries the routable contract
-- (output_spec JSONB, capability_categories for matching, the HTTPS webhook, an execution
-- ceiling, and price). current_version_id on agents is a plain UUID (no FK) to avoid a
-- circular-FK ordering problem with agent_versions. capability_categories gets a GIN index
-- so the routing match (array overlap) is index-backed.

CREATE TABLE agents (
    id                 UUID PRIMARY KEY,
    owner_id           UUID NOT NULL REFERENCES users (id),
    name               TEXT NOT NULL,
    status             TEXT NOT NULL CHECK (status IN
                           ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    current_version_id UUID,
    reputation_score   NUMERIC(5, 2) NOT NULL DEFAULT 50.00,
    gmt_create         TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agents_owner ON agents (owner_id);

CREATE TABLE agent_versions (
    id                    UUID PRIMARY KEY,
    agent_id              UUID NOT NULL REFERENCES agents (id),
    version_number        INT NOT NULL,
    output_spec           JSONB NOT NULL,
    capability_categories TEXT[] NOT NULL,
    webhook_url           TEXT NOT NULL,
    max_execution_seconds INT NOT NULL CHECK (max_execution_seconds > 0),
    price                 NUMERIC(14, 2) NOT NULL CHECK (price >= 0),
    gmt_create            TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (agent_id, version_number)
);

CREATE INDEX idx_agent_versions_categories ON agent_versions USING GIN (capability_categories);
