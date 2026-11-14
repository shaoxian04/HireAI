-- V30: cap an Agent version's declared execution time at 24 hours (86,400 seconds).
-- Mirrors AgentVersionModel.MAX_EXECUTION_SECONDS and the request validation on registration and
-- version publishing, so a row written around the API is rejected too. Existing data is far below
-- the cap. The V3 lower bound (> 0) stays as it is.
ALTER TABLE agent_versions
    ADD CONSTRAINT agent_versions_max_execution_seconds_cap CHECK (max_execution_seconds <= 86400);
