-- V12: Optional human display name (from the signup form or the OAuth profile). Nullable;
-- OAuth-only accounts keep a null password_hash. Backfill the seeded demo accounts.

ALTER TABLE users ADD COLUMN display_name TEXT;

UPDATE users SET display_name = 'Demo Client'  WHERE email = 'client@hireai.local';
UPDATE users SET display_name = 'Demo Builder' WHERE email = 'builder@hireai.local';
