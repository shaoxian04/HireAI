-- An Item can be delivered more than once (the whole-Task rework, a revision after rejection), and
-- each attempt's file is frozen in its own append-only delivery_snapshots row. item_id ties every
-- attempt to its Item, so readers (the receipt) take the Item's latest snapshot while the earlier
-- ones stay on record. Null for a whole-Task result. Adding a column fires no row trigger; existing
-- rows keep item_id null and an Item's first result is still found by its task_result_id (the Item's id).
ALTER TABLE delivery_snapshots ADD COLUMN item_id UUID;

CREATE INDEX idx_delivery_snapshots_item ON delivery_snapshots (item_id, fetched_at DESC) WHERE item_id IS NOT NULL;
