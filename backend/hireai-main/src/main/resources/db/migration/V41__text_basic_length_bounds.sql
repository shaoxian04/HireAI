-- #90 (spec #87 S1/D8): automated acceptance reads the frozen Contract, and text_basic@1 gains its
-- optional length bounds.
--
-- text_basic@1 shipped (V39) with no parameters. This adds two OPTIONAL ones in place rather than
-- publishing text_basic@2, because the addition changes the meaning of nothing already frozen:
--   * every existing Contract leaves both unset, and an unset parameter is not checked;
--   * every existing Offer term omits both, and an omitted parameter accepts only Contracts that
--     leave it unset (V39 header), so no Agent is held to a bound it did not name.
-- A new version would instead strand every TEXT Offer on @1 (an Offer covers only the versions it
-- names, D36). The immutability trigger is lifted for this one statement only; it stays in force for
-- every other change, and any later change to a Template's checks or bounds is a new version.
--
-- Comparisons (coverage semantics documented on ParameterComparison):
--   min_length  max: an Offer names the largest minimum it can meet  (Contract value <= bound).
--   max_length  min: an Offer names the smallest maximum it can meet (Contract value >= bound).
-- Length is counted in characters (Unicode code points) of the delivered payload.

ALTER TABLE contract_templates DISABLE TRIGGER trg_contract_templates_immutable;

UPDATE contract_templates
SET definition = definition
        || '{
  "description": "A text deliverable. Accepted automatically when the text is not blank and, if length bounds are set, within them.",
  "checks": ["FORMAT_TEXT_NON_EMPTY", "TEXT_MIN_LENGTH", "TEXT_MAX_LENGTH"],
  "parameters": [
    {"name": "min_length", "label": "Fewest characters",
     "description": "The fewest characters the text may have. An Offer names the largest minimum it can meet.",
     "comparison": "max", "bounds": {"minimum": 1, "maximum": 5000000}},
    {"name": "max_length", "label": "Most characters",
     "description": "The most characters the text may have. An Offer names the smallest maximum it can be held to.",
     "comparison": "min", "bounds": {"minimum": 1, "maximum": 5000000}}
  ]
}'::jsonb
WHERE template_key = 'text_basic' AND version = 1;

ALTER TABLE contract_templates ENABLE TRIGGER trg_contract_templates_immutable;
