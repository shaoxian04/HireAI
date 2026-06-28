-- V15: agent_versions lifecycle status + one-ACTIVE-per-agent.
-- Gives AgentVersion a real lifecycle (DRAFT/ACTIVE/DEPRECATED) so a builder can publish a new
-- version that supersedes the prior one: the old version is retained as history (DEPRECATED) and
-- the new one becomes the routable contract (ACTIVE). Existing single-version agents backfill to
-- ACTIVE via the column DEFAULT. (Spec §5 labels this V13; V13/V14 were taken by slices 2/3, so the
-- real next additive number is V15. V1-V14 are immutable.)
ALTER TABLE agent_versions
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'DEPRECATED'));

-- Exactly one ACTIVE version per agent (the current routable contract). Partial unique index;
-- publish-new-version demotes the prior ACTIVE to DEPRECATED BEFORE inserting the new ACTIVE row,
-- so the index never sees two ACTIVE rows for one agent.
CREATE UNIQUE INDEX uq_agent_versions_one_active
    ON agent_versions (agent_id) WHERE status = 'ACTIVE';
