-- #110 (spec #87, D39, ADR 0009): retire tasks.output_spec. The frozen Contract is the single
-- binding contract (Hard Invariant #4); the app never reads or writes output_spec again.
--
-- 1. contract_from_output_spec(spec): the D39 mapping of an output spec onto a Contract, the SQL
--    mirror of OutputSpec#toContract (as offer_from_output_spec mirrors #toOwnOffer):
--    TEXT -> text_basic@1, FILE -> file_basic@1, JSON -> custom_json@1 with the spec's schema as
--    the `schema` parameter when it has a non-blank one.
-- 2. Every Task still without a Contract (submitted before V39) gets that mapping. This changes
--    nothing for them: since #90 the validation gate judged such a Task by validate(spec.toContract())
--    and routing/failover matched it by the same Contract, so D39's "in-flight Tasks finish under
--    their frozen output_spec and the old rules" holds — the old rules ARE this Contract. The freeze
--    trigger allows NULL -> value. Then tasks.contract becomes NOT NULL.
-- 3. acceptance_criteria: the Client's prose criteria, which map onto no Template parameter. They
--    move out of output_spec into their own column (intent evidence for the Client's review and the
--    arbitrator; read by extraction on a mode-1 submit; never an automated check).
-- 4. output_spec stays, nullable, as history: historical rows keep it readable, nothing reads it.
--    A Task's format/schema on the wire (WorkOrder, task API, arbitration) is derived from its
--    Contract and the Contract's Template.

CREATE OR REPLACE FUNCTION contract_from_output_spec(spec JSONB)
    RETURNS JSONB AS $$
DECLARE
    own_schema TEXT := spec ->> 'schema';
BEGIN
    CASE spec ->> 'format'
        WHEN 'TEXT' THEN
            RETURN '{"template":"text_basic","version":1,"params":{}}'::jsonb;
        WHEN 'FILE' THEN
            RETURN '{"template":"file_basic","version":1,"params":{}}'::jsonb;
        WHEN 'JSON' THEN
            IF own_schema IS NULL OR btrim(own_schema, E' \t\n\r') = '' THEN
                RETURN '{"template":"custom_json","version":1,"params":{}}'::jsonb;
            END IF;
            RETURN jsonb_build_object('template', 'custom_json', 'version', 1,
                    'params', jsonb_build_object('schema', own_schema));
        ELSE
            RAISE EXCEPTION 'No Template for output spec format %', spec ->> 'format';
    END CASE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

UPDATE tasks SET contract = contract_from_output_spec(output_spec) WHERE contract IS NULL;
ALTER TABLE tasks ALTER COLUMN contract SET NOT NULL;

ALTER TABLE tasks ADD COLUMN acceptance_criteria TEXT;
UPDATE tasks SET acceptance_criteria = output_spec ->> 'acceptanceCriteria'
    WHERE output_spec IS NOT NULL;

ALTER TABLE tasks ALTER COLUMN output_spec DROP NOT NULL;

COMMENT ON COLUMN tasks.output_spec IS
    'Retired by #110: history only, never read or written by the app. The binding contract is tasks.contract.';
COMMENT ON COLUMN tasks.acceptance_criteria IS
    'The Client''s prose acceptance criteria: intent evidence for review and arbitration, never an automated check.';
COMMENT ON COLUMN tasks.contract IS
    'The Contract frozen at submit: the single binding contract (Hard Invariant #4). Never changes once set.';
