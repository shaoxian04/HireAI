-- #96: loser-pays arbitration cost, the Appeal Fee and a Builder web appeal (spec D16/D18/D42/D43).

-- 1) Who accepted the arbitrator's proposed ruling, and who appealed it (once). Only a side the
--    ruling goes against may do either; the ruling settles once every such side has accepted.
ALTER TABLE disputes
    ADD COLUMN client_accepted_at  TIMESTAMPTZ,
    ADD COLUMN builder_accepted_at TIMESTAMPTZ,
    ADD COLUMN appealed_by         TEXT CHECK (appealed_by IN ('CLIENT', 'BUILDER')),
    ADD COLUMN appealed_at         TIMESTAMPTZ;

-- 2) Two ledger entry types for the loser's fee debits. Name-independent drop + re-add of the CHECK.
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
     WHERE conrelid = 'ledger_entries'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) ILIKE '%ESCROW_FREEZE%';
    IF c IS NOT NULL THEN
        EXECUTE 'ALTER TABLE ledger_entries DROP CONSTRAINT ' || quote_ident(c);
    END IF;
END $$;

ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_entry_type_check
    CHECK (entry_type IN ('TOPUP', 'ESCROW_FREEZE', 'PAYOUT', 'REFUND', 'COMMISSION', 'SPLIT',
                          'ARBITRATION_FEE', 'APPEAL_FEE'));

-- 3) How each dispute fee was borne: full cost, the 20%-of-price cap, what each side was charged and
--    what the platform covered. The charged amounts are also ledger entries; this row adds the part
--    no wallet carries (the platform's share), so a dispute's costs are reconstructable (Inv #2).
CREATE TABLE dispute_fees (
    id               UUID PRIMARY KEY,
    dispute_id       UUID           NOT NULL REFERENCES disputes (id),
    task_id          UUID           NOT NULL,
    fee_type         TEXT           NOT NULL CHECK (fee_type IN ('ARBITRATION_COST', 'APPEAL_FEE')),
    full_cost        NUMERIC(14, 2) NOT NULL CHECK (full_cost >= 0),
    capped_amount    NUMERIC(14, 2) NOT NULL CHECK (capped_amount >= 0),
    client_charged   NUMERIC(14, 2) NOT NULL CHECK (client_charged >= 0),
    builder_charged  NUMERIC(14, 2) NOT NULL CHECK (builder_charged >= 0),
    platform_covered NUMERIC(14, 2) NOT NULL CHECK (platform_covered >= 0),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (dispute_id, fee_type),
    CHECK (client_charged + builder_charged + platform_covered = full_cost)
);

CREATE OR REPLACE FUNCTION dispute_fees_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'dispute_fees is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dispute_fees_no_update
    BEFORE UPDATE ON dispute_fees
    FOR EACH ROW EXECUTE FUNCTION dispute_fees_block_mutation();

CREATE TRIGGER trg_dispute_fees_no_delete
    BEFORE DELETE ON dispute_fees
    FOR EACH ROW EXECUTE FUNCTION dispute_fees_block_mutation();
