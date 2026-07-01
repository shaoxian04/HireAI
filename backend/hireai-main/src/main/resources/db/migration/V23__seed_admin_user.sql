-- V23: Seed the platform administrator (dispute backstop + read-only dashboard). There is no public
-- path to the ADMIN role (self-register grants CLIENT only), so the admin is provisioned here.
-- password_hash is the same BCrypt hash of DemoPass123! seeded for the demo users in V5.
-- Fixed UUID (…0002; …0001 is the dev user, …0010/…0011 the demo client/builder) → idempotent.

INSERT INTO users (id, email, password_hash, is_active, display_name) VALUES
    ('00000000-0000-0000-0000-000000000002', 'admin@hireai.local',
     '$2a$10$x2486D5qTVSOkjdaS71oiuJZqFgGx2xK.Y//oon.LpOf6Ox4cEoN6', true, 'Platform Admin');

INSERT INTO user_roles (user_id, role) VALUES
    ('00000000-0000-0000-0000-000000000002', 'ADMIN');
