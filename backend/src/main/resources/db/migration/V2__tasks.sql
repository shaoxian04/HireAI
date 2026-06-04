-- V2: Tasks. A task is the unit of work a client submits; its budget is frozen in
-- escrow at submission (see Wallet/ledger in V1). output_spec is the binding output
-- contract used later by validation and arbitration; stored as JSONB so the contract
-- shape can evolve without a migration. Only SUBMITTED is reachable in the current
-- slice; the rest of the status set is declared for forward-compatibility.

CREATE TABLE tasks (
    id            UUID PRIMARY KEY,
    client_id     UUID NOT NULL REFERENCES users (id),
    title         TEXT NOT NULL,
    description   TEXT NOT NULL,
    budget        NUMERIC(14, 2) NOT NULL CHECK (budget > 0),
    output_spec   JSONB NOT NULL,
    status        TEXT NOT NULL CHECK (status IN (
                      'SUBMITTED', 'ROUTING', 'IN_PROGRESS', 'SUBMITTED_FOR_REVIEW',
                      'VALIDATING', 'ACCEPTED', 'REJECTED', 'DISPUTED', 'SETTLED', 'CANCELLED')),
    gmt_create    TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_client_created ON tasks (client_id, gmt_create DESC);
