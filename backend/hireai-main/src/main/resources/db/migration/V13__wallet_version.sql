-- V13: optimistic-lock version column on wallets.
-- Closes the concurrent-freeze last-writer-wins window (spec §6.1/§6.5 hardening):
-- two simultaneous balance updates on one wallet now collide on the version check,
-- and the loser retries instead of silently overwriting. Existing rows start at 0.
ALTER TABLE wallets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
