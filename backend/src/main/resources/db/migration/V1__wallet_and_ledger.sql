-- V1: Users (minimal), wallets, and the append-only credit ledger.
-- The ledger is the immutable audit trail for the settlement system; it must
-- never be UPDATEd or DELETEd. Append-only is enforced here at the DB layer so
-- no application bug can violate it.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT,
    role          TEXT NOT NULL CHECK (role IN ('CLIENT', 'BUILDER', 'ADMIN')),
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    gmt_create    TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE wallets (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL UNIQUE REFERENCES users (id),
    available_balance NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    escrow_balance    NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (escrow_balance >= 0),
    gmt_create        TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY,
    wallet_id       UUID NOT NULL REFERENCES wallets (id),
    entry_type      TEXT NOT NULL CHECK (entry_type IN
                        ('TOPUP', 'ESCROW_FREEZE', 'PAYOUT', 'REFUND', 'COMMISSION', 'SPLIT')),
    amount          NUMERIC(14, 2) NOT NULL,
    balance_after   NUMERIC(14, 2) NOT NULL,
    related_task_id UUID,
    correlation_id  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_wallet_created ON ledger_entries (wallet_id, created_at DESC);

-- Append-only enforcement: any UPDATE or DELETE on the ledger raises.
CREATE OR REPLACE FUNCTION ledger_entries_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_no_update
    BEFORE UPDATE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_block_mutation();

CREATE TRIGGER trg_ledger_no_delete
    BEFORE DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_block_mutation();

-- Seeded development user matching DevCurrentUserProvider.DEV_USER_ID.
INSERT INTO users (id, email, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'dev@hireai.local', 'CLIENT');
