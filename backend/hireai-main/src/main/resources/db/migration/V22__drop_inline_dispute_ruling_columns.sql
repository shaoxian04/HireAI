-- V22: drop the inline ruling columns from `disputes`. Their data was copied into the append-only
-- `dispute_rulings` child table in V21, and `DisputeDO` stopped mapping them in the Phase-3b cutover,
-- so they are dead. `disputes` keeps `status` + `resolved_at`; the effective ruling is the
-- highest-tier `dispute_rulings` row.
ALTER TABLE disputes
    DROP COLUMN ruling_category,
    DROP COLUMN ruling_rationale,
    DROP COLUMN ruling_tier,
    DROP COLUMN decided_by;
