-- V6: Agent storefront profiles (Module 6 — Discovery). 1:1 with agents; marketing/storefront
-- content lives here, NOT on the core agent aggregate. An agent appears in the public catalogue
-- only when agents.status = 'ACTIVE' AND agent_profiles.is_listed.
CREATE TABLE agent_profiles (
    agent_id      UUID PRIMARY KEY REFERENCES agents (id),
    tagline       TEXT,
    description   TEXT,
    sample_output TEXT,
    logo_url      TEXT,
    cover_url     TEXT,
    gallery_urls  TEXT[] NOT NULL DEFAULT '{}',
    is_listed     BOOLEAN NOT NULL DEFAULT FALSE,
    is_featured   BOOLEAN NOT NULL DEFAULT FALSE,
    gmt_create    TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backfill: every existing agent gets a profile row. Already-ACTIVE agents are listed so the
-- marketplace is not empty on first boot after this migration (demo continuity).
INSERT INTO agent_profiles (agent_id, is_listed)
SELECT id, (status = 'ACTIVE') FROM agents;
