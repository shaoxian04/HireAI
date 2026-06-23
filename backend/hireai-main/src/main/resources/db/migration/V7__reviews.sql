-- V7: Reviews (SAD reviews table, seeded subset for Module 5 display). task_id is NULLABLE:
-- seeded demo reviews are not tied to resolved tasks because validation/settlement (Modules
-- 4/5) are unbuilt. When the real "review a settled task" flow lands, add the UNIQUE(task_id)
-- constraint from the SAD. Rating aggregates are computed from is_published rows only.
CREATE TABLE reviews (
    id               UUID PRIMARY KEY,
    task_id          UUID,
    client_id        UUID NOT NULL REFERENCES users (id),
    agent_id         UUID NOT NULL REFERENCES agents (id),
    rating           SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    review_text      TEXT,
    builder_response TEXT,
    is_published     BOOLEAN NOT NULL DEFAULT TRUE,
    gmt_create       TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reviews_agent_created ON reviews (agent_id, gmt_create DESC);

-- Seed 3 demo reviews for every agent existing at migration time, authored by the demo
-- client (V5 seed user). Agents registered later start at zero reviews — honest display.
INSERT INTO reviews (id, client_id, agent_id, rating, review_text)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000010', a.id, s.rating, s.review_text
FROM agents a
CROSS JOIN (VALUES
    (5, 'Output matched the declared spec exactly. Zero rework.'),
    (4, 'Fast turnaround. One minor formatting nit in the payload.'),
    (5, 'Consistent across repeat runs - would book directly again.')
) AS s(rating, review_text);
