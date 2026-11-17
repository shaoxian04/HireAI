-- #88 (spec #87 S1, ADR 0009): Template -> Offer -> Contract, introduced beside the output spec
-- with no change in behaviour. Validation and arbitration still read output_spec.
--
-- 1. contract_templates: the platform-authored, versioned Templates. Seeded and changed only by
--    Flyway (or a future admin publication). A published version's definition is immutable: only
--    its status may change (ACTIVE -> RETIRED), so a frozen Contract always means the same thing.
-- 2. agent_versions.offer: every version's Offer. Existing versions are backfilled from their
--    output_spec by the D39 mapping; a row inserted without an Offer gets the same mapping from a
--    trigger. The app writes the Offer itself (OutputSpec#toOwnOffer mirrors this mapping).
-- 3. tasks.contract: the Contract a Task freezes at submit. Tasks submitted before this migration
--    keep contract NULL and finish under their frozen output_spec; once set, a contract never changes.
--
-- JSON shapes (written by AcceptanceJsonMapper):
--   Contract: {"template":"custom_json","version":1,"params":{"schema":"{...}"}}
--   Offer:    {"terms":[{"template":"custom_json","version":1,"params":{"schema":{"oneOf":["{...}"]}}}]}
--   Offer ranges: {"bound":n} (max/min), {"oneOf":[...]} or {"any":true} (subset/enum),
--                 {"acceptsTrue":bool} (flag). A parameter an Offer term omits is accepted only
--                 when the Contract leaves it unset.

CREATE TABLE contract_templates (
    template_key TEXT        NOT NULL CHECK (template_key ~ '^[a-z][a-z0-9_]{0,63}$'),
    version      INT         NOT NULL CHECK (version >= 1),
    status       TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    definition   JSONB       NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (template_key, version)
);

CREATE OR REPLACE FUNCTION contract_templates_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'contract_templates rows are never deleted; retire the version instead';
    END IF;
    IF NEW.template_key IS DISTINCT FROM OLD.template_key
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.definition IS DISTINCT FROM OLD.definition
            OR NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION 'A published Template version is immutable; publish a new version instead';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_contract_templates_immutable
    BEFORE UPDATE OR DELETE ON contract_templates
    FOR EACH ROW EXECUTE FUNCTION contract_templates_block_mutation();

-- The generic Templates (D39). The checks are Check Library entry names; the first of each list is
-- what ValidationDomainServiceImpl runs today. file_basic's FILE_MEDIA_TYPE and FILE_MAX_BYTES are
-- reserved for the DeliverySnapshot slice (#97) and apply only when the parameter is set.
-- rulingSettlement is the fraction of the price the Builder's side receives (before commission)
-- under each ruling, matching SettlementPolicy today.
INSERT INTO contract_templates (template_key, version, definition) VALUES
('text_basic', 1, '{
  "title": "Plain text",
  "description": "A text deliverable. Accepted automatically when the text is not blank.",
  "outputShape": "TEXT",
  "checks": ["FORMAT_TEXT_NON_EMPTY"],
  "parameters": [],
  "expectationMode": "NONE",
  "rulingSettlement": {"FULFILLED": 1, "PARTIALLY_FULFILLED": 0.5, "NOT_FULFILLED": 0}
}'),
('custom_json', 1, '{
  "title": "JSON with your schema",
  "description": "A JSON deliverable. Accepted automatically when it parses and, if a schema is given, conforms to it.",
  "outputShape": "JSON",
  "checks": ["FORMAT_JSON_PARSEABLE", "SCHEMA_MATCH"],
  "parameters": [
    {"name": "schema", "label": "JSON Schema",
     "description": "The JSON Schema the result must conform to. An Offer lists the schemas it accepts, or accepts any.",
     "comparison": "enum", "bounds": {"maxLength": 5000}}
  ],
  "expectationMode": "NONE",
  "rulingSettlement": {"FULFILLED": 1, "PARTIALLY_FULFILLED": 0.5, "NOT_FULFILLED": 0}
}'),
('file_basic', 1, '{
  "title": "File",
  "description": "A file delivered by HTTPS link. Accepted automatically when the link is HTTPS.",
  "outputShape": "FILE",
  "checks": ["FORMAT_FILE_HTTPS_URL", "FILE_MEDIA_TYPE", "FILE_MAX_BYTES"],
  "parameters": [
    {"name": "max_bytes", "label": "Largest file size",
     "description": "The largest file, in bytes, the Client accepts. An Offer names the smallest limit it can be held to.",
     "comparison": "min", "bounds": {"minimum": 1, "maximum": 104857600}},
    {"name": "media_types", "label": "Accepted file types",
     "description": "The file types the Client accepts. An Offer lists the types it can deliver and covers a Contract whose types are all among them.",
     "comparison": "subset",
     "bounds": {"allowedValues": ["application/json", "application/pdf", "application/zip", "image/jpeg",
                                  "image/png", "image/webp", "text/csv", "text/plain"]}}
  ],
  "expectationMode": "NONE",
  "rulingSettlement": {"FULFILLED": 1, "PARTIALLY_FULFILLED": 0.5, "NOT_FULFILLED": 0}
}');

-- The D39 mapping from an output spec to its equivalent Offer. Mirrors OutputSpec#toOwnOffer:
-- TEXT -> text_basic@1, FILE -> file_basic@1, JSON -> custom_json@1 accepting only the Agent's own
-- schema (or, with a blank schema, only Contracts that set none).
CREATE OR REPLACE FUNCTION offer_from_output_spec(spec JSONB)
    RETURNS JSONB AS $$
DECLARE
    own_schema TEXT := spec ->> 'schema';
BEGIN
    CASE spec ->> 'format'
        WHEN 'TEXT' THEN
            RETURN '{"terms":[{"template":"text_basic","version":1,"params":{}}]}'::jsonb;
        WHEN 'FILE' THEN
            RETURN '{"terms":[{"template":"file_basic","version":1,"params":{}}]}'::jsonb;
        WHEN 'JSON' THEN
            IF own_schema IS NULL OR btrim(own_schema, E' \t\n\r') = '' THEN
                RETURN '{"terms":[{"template":"custom_json","version":1,"params":{}}]}'::jsonb;
            END IF;
            RETURN jsonb_build_object('terms', jsonb_build_array(jsonb_build_object(
                    'template', 'custom_json', 'version', 1,
                    'params', jsonb_build_object('schema',
                            jsonb_build_object('oneOf', jsonb_build_array(own_schema))))));
        ELSE
            RAISE EXCEPTION 'No Template for output spec format %', spec ->> 'format';
    END CASE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

ALTER TABLE agent_versions ADD COLUMN offer JSONB;
UPDATE agent_versions SET offer = offer_from_output_spec(output_spec);
ALTER TABLE agent_versions ALTER COLUMN offer SET NOT NULL;

CREATE OR REPLACE FUNCTION agent_versions_default_offer()
    RETURNS TRIGGER AS $$
BEGIN
    IF NEW.offer IS NULL THEN
        NEW.offer := offer_from_output_spec(NEW.output_spec);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_agent_versions_default_offer
    BEFORE INSERT ON agent_versions
    FOR EACH ROW EXECUTE FUNCTION agent_versions_default_offer();

ALTER TABLE tasks ADD COLUMN contract JSONB;

CREATE OR REPLACE FUNCTION tasks_freeze_contract()
    RETURNS TRIGGER AS $$
BEGIN
    IF OLD.contract IS NOT NULL AND NEW.contract IS DISTINCT FROM OLD.contract THEN
        RAISE EXCEPTION 'A Task''s Contract is frozen at submit and cannot change';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tasks_freeze_contract
    BEFORE UPDATE OF contract ON tasks
    FOR EACH ROW EXECUTE FUNCTION tasks_freeze_contract();
