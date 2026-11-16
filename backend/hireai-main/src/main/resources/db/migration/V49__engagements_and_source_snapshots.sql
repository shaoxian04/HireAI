-- #104 (spec S4: D29-D32, D44, ADR 0010): Engagements and SourceSnapshots.
--
-- An Engagement is a standing agreement between a buyer (client_id) and an Agent the buyer chose. It
-- records the buyer's consent to send that Agent confidential documents (consented_at) and an optional
-- Template variant (a Contract, canonical JSON), which the Agent's Builder must accept before the
-- Engagement is ACTIVE. Confidential documents exist only under an Engagement: the marketplace paths
-- (routed submit, the shortlist) stay public-only.
--
-- A SourceSnapshot is a document uploaded under an Engagement: upload only, content-hashed, immutable.
-- Its bytes live in the private content store (delivery_snapshot_contents, V44), shared with
-- DeliverySnapshots and keyed by SHA-256. Retention (D32): retention_started_at is stamped once no Task
-- using the snapshot has a window left open; retention_days later the content is deleted
-- (content_deleted_at) and the hash stays.
--
-- Every download through an Item's signed link is logged in source_downloads (append-only).
CREATE TABLE engagements (
    id               UUID        PRIMARY KEY,
    client_id        UUID        NOT NULL REFERENCES users (id),
    agent_id         UUID        NOT NULL REFERENCES agents (id),
    status           TEXT        NOT NULL CHECK (status IN ('AWAITING_AGENT_ACCEPTANCE', 'ACTIVE', 'ENDED')),
    consented_at     TIMESTAMPTZ NOT NULL,
    template_variant JSONB       NULL,
    agent_accepted_at TIMESTAMPTZ NULL,
    retention_days   INT         NOT NULL CHECK (retention_days BETWEEN 1 AND 30),
    created_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ NULL,
    CONSTRAINT engagements_accepted_needs_variant CHECK (agent_accepted_at IS NULL OR template_variant IS NOT NULL)
);

-- One open Engagement per buyer and Agent.
CREATE UNIQUE INDEX uq_engagements_open ON engagements (client_id, agent_id) WHERE status <> 'ENDED';
CREATE INDEX idx_engagements_agent ON engagements (agent_id);

-- The consent and the variant are what the Agent accepted: they never change.
CREATE OR REPLACE FUNCTION engagements_freeze_terms()
    RETURNS TRIGGER AS $$
BEGIN
    IF NEW.client_id <> OLD.client_id OR NEW.agent_id <> OLD.agent_id OR NEW.consented_at <> OLD.consented_at
        OR NEW.template_variant IS DISTINCT FROM OLD.template_variant
        OR NEW.retention_days <> OLD.retention_days OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'An Engagement''s parties, consent, variant and retention are frozen';
    END IF;
    IF OLD.status = 'ENDED' THEN
        RAISE EXCEPTION 'An ended Engagement never changes';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_engagements_freeze_terms
    BEFORE UPDATE ON engagements
    FOR EACH ROW EXECUTE FUNCTION engagements_freeze_terms();

CREATE TABLE source_snapshots (
    id                   UUID        PRIMARY KEY,
    engagement_id        UUID        NOT NULL REFERENCES engagements (id),
    client_id            UUID        NOT NULL REFERENCES users (id),
    filename             TEXT        NOT NULL CHECK (char_length(filename) BETWEEN 1 AND 200),
    media_type           TEXT        NOT NULL,
    declared_media_type  TEXT        NULL,
    size_bytes           BIGINT      NOT NULL CHECK (size_bytes > 0),
    sha256               CHAR(64)    NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    uploaded_at          TIMESTAMPTZ NOT NULL,
    retention_started_at TIMESTAMPTZ NULL,
    content_deleted_at   TIMESTAMPTZ NULL
);

CREATE INDEX idx_source_snapshots_engagement ON source_snapshots (engagement_id, uploaded_at DESC);
CREATE INDEX idx_source_snapshots_sha256 ON source_snapshots (sha256);
CREATE INDEX idx_source_snapshots_holding ON source_snapshots (id) WHERE content_deleted_at IS NULL;

-- Frozen once uploaded: only the retention fields move, and a retired snapshot never comes back.
CREATE OR REPLACE FUNCTION source_snapshots_freeze()
    RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'source_snapshots rows are never deleted: retention deletes the content, not the record';
    END IF;
    IF NEW.engagement_id <> OLD.engagement_id OR NEW.client_id <> OLD.client_id OR NEW.filename <> OLD.filename
        OR NEW.media_type <> OLD.media_type OR NEW.declared_media_type IS DISTINCT FROM OLD.declared_media_type
        OR NEW.size_bytes <> OLD.size_bytes OR NEW.sha256 <> OLD.sha256 OR NEW.uploaded_at <> OLD.uploaded_at THEN
        RAISE EXCEPTION 'A SourceSnapshot is frozen once uploaded';
    END IF;
    IF OLD.content_deleted_at IS NOT NULL THEN
        RAISE EXCEPTION 'A retired SourceSnapshot never changes';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_source_snapshots_freeze
    BEFORE UPDATE OR DELETE ON source_snapshots
    FOR EACH ROW EXECUTE FUNCTION source_snapshots_freeze();

-- An Item's input may be a SourceSnapshot (spec S4: each Item references one).
ALTER TABLE task_items ADD COLUMN source_snapshot_id UUID NULL REFERENCES source_snapshots (id);
CREATE INDEX idx_task_items_source ON task_items (source_snapshot_id) WHERE source_snapshot_id IS NOT NULL;

CREATE TABLE source_downloads (
    id                 UUID        PRIMARY KEY,
    source_snapshot_id UUID        NOT NULL REFERENCES source_snapshots (id),
    task_id            UUID        NOT NULL,
    item_id            UUID        NOT NULL REFERENCES task_items (id),
    item_no            INT         NOT NULL,
    outcome            TEXT        NOT NULL CHECK (outcome IN ('SERVED', 'EXPIRED', 'RETIRED')),
    remote_address     TEXT        NULL,
    user_agent         TEXT        NULL,
    created_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_source_downloads_snapshot ON source_downloads (source_snapshot_id, created_at);

CREATE OR REPLACE FUNCTION source_downloads_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'source_downloads is append-only (% refused)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_source_downloads_block_mutation
    BEFORE UPDATE OR DELETE ON source_downloads
    FOR EACH ROW EXECUTE FUNCTION source_downloads_block_mutation();
