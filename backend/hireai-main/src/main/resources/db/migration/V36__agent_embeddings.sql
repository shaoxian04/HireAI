-- #84: each Agent's profile text (name + tagline + description) embedded for relevance matching.
-- pgvector provides the vector type (available on Supabase; local Postgres needs the
-- pgvector/pgvector:pg16 image). One row per Agent, replaced on every re-embed, so the table is
-- mutable (not an audit trail).
--
-- model tags the vector: vectors from different models are never compared, and a row whose model
-- differs from the configured one is re-embedded by the sweeper. text_hash is the SHA-256 hex of
-- the embedded text (com.hireai.domain.biz.offering.embedding.model.AgentProfileText), so a row
-- made from older profile text is recognisably stale. The dimension (1536, text-embedding-3-small)
-- must match AgentEmbeddingModel.DIMENSIONS. No ANN index: similarity is computed over the already
-- filtered candidate set.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE agent_embeddings (
    agent_id   UUID PRIMARY KEY REFERENCES agents (id) ON DELETE CASCADE,
    model      TEXT         NOT NULL CHECK (model <> ''),
    text_hash  TEXT         NOT NULL,
    embedding  vector(1536) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);
