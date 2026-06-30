-- V20: store task_results.result_payload as TEXT, not JSONB. The payload is UNTRUSTED agent
-- output; the Module 4 validation gate decides whether it is well-formed JSON in the app layer
-- (ValidationDomainService), then marks SPEC_VIOLATION + auto-refunds on failure. A jsonb column
-- rejects malformed JSON at INSERT time — before the gate can run — so the gate's invalid-JSON
-- path was unreachable (the agent callback errored with a DataIntegrityViolation instead).
-- result_payload is only ever stored/retrieved whole as a string; no SQL uses jsonb operators on it.
ALTER TABLE task_results ALTER COLUMN result_payload TYPE text USING result_payload::text;
