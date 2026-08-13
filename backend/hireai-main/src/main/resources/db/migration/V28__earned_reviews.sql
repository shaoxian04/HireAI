-- V28: earned reviews (spec §9, issue #40). Reviews stop being fabricated and start being earned.
--
-- V7 seeded three invented 4-5* reviews for every agent, attributed to the demo client with
-- task_id NULL, because the rate-a-settled-task flow did not exist yet. It does now, and a
-- marketplace cannot credibly police reputation while shipping forged testimonials of its own.

-- 1) Purge every review not attached to a real task. This is exactly the V7 seed set: no other
--    write path has ever produced a NULL task_id, because none existed.
DELETE FROM reviews WHERE task_id IS NULL;

-- 2) The constraint V7's own comment deferred "until the real review flow lands". A review is
--    about a task, and a task carries at most one review — that pair is what makes the star
--    average honest, and it is what refuses a second rating at the database level rather than
--    trusting the application to remember.
ALTER TABLE reviews ALTER COLUMN task_id SET NOT NULL;
ALTER TABLE reviews ADD CONSTRAINT uq_reviews_task UNIQUE (task_id);
