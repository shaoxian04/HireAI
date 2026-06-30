-- V18: allow the PARTIALLY_ACCEPTED resolution (Module 4 SPLIT dispute outcome).
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
     WHERE conrelid = 'tasks'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) ILIKE '%resolution%';
    IF c IS NOT NULL THEN
        EXECUTE 'ALTER TABLE tasks DROP CONSTRAINT ' || quote_ident(c);
    END IF;
END $$;
ALTER TABLE tasks ADD CONSTRAINT tasks_resolution_check
    CHECK (resolution IN ('ACCEPTED', 'REJECTED', 'PARTIALLY_ACCEPTED'));
