-- V10: Move roles to a many-to-many join table (dual-capability RBAC). Backfill from the
-- legacy single-role column, then retire it. V1/V5 are not edited (Flyway checksums); they
-- still write users.role, which this migration reads before dropping.

CREATE TABLE user_roles (
    user_id    UUID NOT NULL REFERENCES users (id),
    role       TEXT NOT NULL CHECK (role IN ('CLIENT', 'BUILDER', 'ADMIN')),
    gmt_create TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

INSERT INTO user_roles (user_id, role)
SELECT id, role FROM users;

ALTER TABLE users DROP COLUMN role;
