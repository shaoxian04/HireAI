-- V5: Seed two demo users (CLIENT + BUILDER) with BCrypt password hashes and wallets, so the
-- secured demo can log in (default profile enforces JWT; there is no register endpoint in this slice).
-- Fixed UUIDs make this run-once and idempotent under Flyway's single-apply guarantee.
-- Demo password (documented, throwaway accounts only): DemoPass123!
-- The hash below is a BCrypt hash of that password, generated at build time (see plan Task 12a).

INSERT INTO users (id, email, password_hash, role, is_active) VALUES
    ('00000000-0000-0000-0000-000000000010', 'client@hireai.local',
     '$2a$10$x2486D5qTVSOkjdaS71oiuJZqFgGx2xK.Y//oon.LpOf6Ox4cEoN6', 'CLIENT', true),
    ('00000000-0000-0000-0000-000000000011', 'builder@hireai.local',
     '$2a$10$x2486D5qTVSOkjdaS71oiuJZqFgGx2xK.Y//oon.LpOf6Ox4cEoN6', 'BUILDER', true);

-- Wallets: the client wallet is funded so the escrow demo (task submit -> freeze) works end-to-end.
INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES
    ('00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000010', 1000.00, 0.00),
    ('00000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000011', 0.00, 0.00);
