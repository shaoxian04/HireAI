-- V21: add an append-only `dispute_rulings` child table (ruling history) and migrate any existing
-- inline ruling into it. The inline `disputes.ruling_*` columns are left in place here and dropped
-- in V22 (Task 3) AFTER the entity stops mapping them — keeping the schema and `DisputeDO` in step
-- so `ddl-auto: validate` never breaks mid-sequence. Lays the seam for a future tier-2 Administrator
-- override stored SEPARATELY from the arbitrator ruling (Invariant #2). Only ARBITRATOR/FALLBACK
-- write today. Hibernate `validate` tolerates the now-redundant inline columns until V22 removes them.
CREATE TABLE dispute_rulings (
    id          UUID PRIMARY KEY,
    dispute_id  UUID NOT NULL REFERENCES disputes (id),
    tier        INT  NOT NULL,
    decided_by  TEXT NOT NULL CHECK (decided_by IN ('ARBITRATOR', 'ADMINISTRATOR', 'FALLBACK')),
    category    TEXT NOT NULL CHECK (category IN ('FULFILLED', 'PARTIALLY_FULFILLED', 'NOT_FULFILLED')),
    rationale   TEXT,
    decided_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_create  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dispute_rulings_dispute ON dispute_rulings (dispute_id);

-- Append-only enforcement: any UPDATE or DELETE raises (mirror of ledger_entries in V1).
CREATE OR REPLACE FUNCTION dispute_rulings_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'dispute_rulings is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dispute_rulings_no_update
    BEFORE UPDATE ON dispute_rulings
    FOR EACH ROW EXECUTE FUNCTION dispute_rulings_block_mutation();

CREATE TRIGGER trg_dispute_rulings_no_delete
    BEFORE DELETE ON dispute_rulings
    FOR EACH ROW EXECUTE FUNCTION dispute_rulings_block_mutation();

-- Migrate any existing inline ruling into the child table (0 rows on a fresh DB; correct for seeded data).
INSERT INTO dispute_rulings (id, dispute_id, tier, decided_by, category, rationale, decided_at, gmt_create)
SELECT gen_random_uuid(), id, COALESCE(ruling_tier, 1), decided_by, ruling_category, ruling_rationale,
       COALESCE(resolved_at, gmt_create), gmt_create
FROM disputes
WHERE ruling_category IS NOT NULL;
