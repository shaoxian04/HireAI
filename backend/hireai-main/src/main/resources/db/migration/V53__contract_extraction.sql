-- #100 (spec S6): what requirement extraction made of a description-only (mode 1) submit. NULL when no
-- extraction ran: a submit with a structured Contract (mode 2), a direct booking, or a Task from before
-- this migration. Written once, in the submit transaction, by a targeted query (unmapped on TaskDO like
-- failure_detail) and frozen like tasks.contract. The Contract itself is NOT repeated here: the frozen
-- tasks.contract is the binding one.
--
-- Shape:
--   {"template": "image_generation", "version": 1,          -- the version the requirements were mapped against
--    "status": "EXTRACTED" | "UNAVAILABLE",                 -- UNAVAILABLE: the LLM could not answer, defaults used
--    "requirements": [
--      {"text": "512 x 512 pixels", "check": "IMAGE_DIMENSIONS", "params": {"width": 512, "height": 512}},
--      {"text": "It should feel friendly", "unmappedReason": "No automatic check fits it unambiguously, ..."}
--    ]}
-- Parameter values are natural JSON, as in tasks.contract (V39).
ALTER TABLE tasks ADD COLUMN contract_extraction JSONB;

CREATE OR REPLACE FUNCTION tasks_freeze_contract_extraction()
    RETURNS TRIGGER AS $$
BEGIN
    IF OLD.contract_extraction IS NOT NULL AND NEW.contract_extraction IS DISTINCT FROM OLD.contract_extraction THEN
        RAISE EXCEPTION 'A Task''s contract extraction is recorded at submit and cannot change';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tasks_freeze_contract_extraction
    BEFORE UPDATE OF contract_extraction ON tasks
    FOR EACH ROW EXECUTE FUNCTION tasks_freeze_contract_extraction();
