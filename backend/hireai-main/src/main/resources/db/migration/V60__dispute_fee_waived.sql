-- Owner decision on #87: when the AI arbitrator produced no ruling (it failed and the dispute went to the
-- admin backstop, or the refund fallback), nobody pays the arbitration cost. The fee row still records the
-- cost (the platform covers it) and says why nothing was charged. Adding a column fires no row trigger.
ALTER TABLE dispute_fees ADD COLUMN waived_reason TEXT
    CHECK (waived_reason IS NULL OR (waived_reason = 'NO_AI_RULING' AND client_charged = 0 AND builder_charged = 0));
