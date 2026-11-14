-- #85: cache of task-text embeddings for relevance matching, keyed by (text hash, model). The match
-- preview, the routed submit and every later re-match of the same title + description share one vector,
-- so a booking or submit after a preview makes no second provider call. Only the SHA-256 hex of the
-- text is stored (com.hireai.domain.biz.offering.embedding.model.TaskText), never the text itself.
-- Rows are never updated: a new model gets its own row. No ANN index: similarity is computed over the
-- already filtered candidate set.
CREATE TABLE text_embeddings (
    text_hash TEXT         NOT NULL,
    model     TEXT         NOT NULL CHECK (model <> ''),
    embedding vector(1536) NOT NULL,
    gmt_create TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (text_hash, model)
);
