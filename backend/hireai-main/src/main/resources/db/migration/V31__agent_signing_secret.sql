-- V31: every Agent gets an AgentSigningSecret (an HMAC key shared by the platform and the Agent).
-- Stored recoverably, exactly like webhook_subscriptions.signing_secret (V26): the platform must
-- recompute the HMAC, so it cannot be hashed. NULL for Agents registered before this migration;
-- their Builder issues one by rotating. Written only by a targeted UPDATE (never by the full-row
-- agent save) and never returned by a read endpoint.
ALTER TABLE agents
    ADD COLUMN signing_secret TEXT NULL;
