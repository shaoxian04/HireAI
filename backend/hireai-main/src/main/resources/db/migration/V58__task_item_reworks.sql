-- Per-Item rework (spec S5): an Item whose result a deterministic check rejected goes back to the same
-- Agent with its failing checks, up to hireai.acceptance.max-reworks times, and is refunded only after
-- that. rework_count is how many times this Item has gone back; existing Items never did.
ALTER TABLE task_items ADD COLUMN rework_count INT NOT NULL DEFAULT 0;
ALTER TABLE task_items ADD CONSTRAINT task_items_rework_count_check CHECK (rework_count >= 0);
