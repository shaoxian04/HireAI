-- DeliverySnapshot (spec D24, #97): when a file result arrives the platform fetches the Agent's
-- link once, through the SSRF-guarded client, and freezes the bytes. Every later step (the
-- file_basic checks, the Client's view and download, arbitration) reads this copy, never the link.
--
-- Two tables:
--   delivery_snapshot_contents  the bytes, content-addressed by lowercase hex SHA-256. Private:
--                               served only by owner-checked endpoints. The DeliveryContentStore
--                               port owns it, so a move to object storage replaces this table only.
--                               Rows are never changed; DELETE stays possible for D32 retention
--                               (content goes, the hash in delivery_snapshots stays).
--   delivery_snapshots          one append-only row per task result (a revised result is a new
--                               result and gets its own row). sha256 is deliberately not a foreign
--                               key, so retiring the content never touches the record.

CREATE TABLE delivery_snapshot_contents (
    sha256     CHAR(64)    PRIMARY KEY CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    content    BYTEA       NOT NULL,
    size_bytes BIGINT      NOT NULL,
    stored_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT delivery_snapshot_contents_size CHECK (size_bytes = octet_length(content))
);

CREATE OR REPLACE FUNCTION delivery_snapshot_contents_block_update()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'delivery_snapshot_contents rows are immutable (content-addressed)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_delivery_snapshot_contents_block_update
    BEFORE UPDATE ON delivery_snapshot_contents
    FOR EACH ROW EXECUTE FUNCTION delivery_snapshot_contents_block_update();

CREATE TABLE delivery_snapshots (
    id                  UUID        PRIMARY KEY,
    task_id             UUID        NOT NULL REFERENCES tasks (id),
    task_result_id      UUID        NOT NULL UNIQUE,
    source_url          TEXT        NOT NULL,
    media_type          TEXT        NOT NULL,
    declared_media_type TEXT,
    size_bytes          BIGINT      NOT NULL CHECK (size_bytes >= 0),
    sha256              CHAR(64)    NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    fetched_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_delivery_snapshots_task ON delivery_snapshots (task_id);

CREATE OR REPLACE FUNCTION delivery_snapshots_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'delivery_snapshots is append-only: a DeliverySnapshot is frozen once taken';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_delivery_snapshots_block_mutation
    BEFORE UPDATE OR DELETE ON delivery_snapshots
    FOR EACH ROW EXECUTE FUNCTION delivery_snapshots_block_mutation();
