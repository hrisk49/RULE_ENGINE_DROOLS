-- =============================================================================
--  Migration 001 - rule version history (PostgreSQL)
-- =============================================================================
--  Run ONCE on an existing database, with the service stopped, before starting the
--  rule-engine library version that stores one row per rule VERSION:
--      psql -U postgres -d ruleengine -f db/migrations/001_rule_versioning.sql
--
--  Before: one mutable row per rule name (RULE_NAME unique), version_no bumped in place.
--  After : one immutable row per (rule_name, version_no); status DRAFT / ACTIVE / ARCHIVED;
--          at most one active row per rule name.
--  Existing rows keep their version number and stay live (active) or become ARCHIVED (deleted).
--  Safe to re-run.
-- =============================================================================
BEGIN;

-- 1. Drop the "one row per rule name" unique constraint, whatever it is called
--    (schema.sql names it rule_definition_rule_name_key; Hibernate-created tables use uk_...).
DO $$
DECLARE c record;
BEGIN
    FOR c IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class t ON t.oid = con.conrelid
        WHERE t.relname = 'rule_definition'
          AND con.contype = 'u'
          AND array_length(con.conkey, 1) = 1
          AND con.conkey[1] = (SELECT attnum FROM pg_attribute WHERE attrelid = t.oid AND attname = 'rule_name')
    LOOP
        EXECUTE format('ALTER TABLE rule_definition DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

-- 2. When each version was created.
ALTER TABLE rule_definition ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;
UPDATE rule_definition SET created_at = updated_at WHERE created_at IS NULL;

-- 3. Statuses: soft-deleted rows (active = false, status INACTIVE) are archived versions now.
UPDATE rule_definition SET status = 'ARCHIVED' WHERE active = FALSE AND status <> 'DRAFT';
UPDATE rule_definition SET status = 'ACTIVE'   WHERE active = TRUE;
ALTER TABLE rule_definition ALTER COLUMN status SET DEFAULT 'DRAFT';
ALTER TABLE rule_definition ALTER COLUMN active SET DEFAULT FALSE;

-- 4. One row per (rule, version) ...
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_rule_definition_name_ver') THEN
        ALTER TABLE rule_definition ADD CONSTRAINT uk_rule_definition_name_ver UNIQUE (rule_name, version_no);
    END IF;
END $$;

-- 5. ... and at most ONE live version per rule name.
CREATE UNIQUE INDEX IF NOT EXISTS ux_rule_definition_one_active ON rule_definition (rule_name) WHERE active;

COMMIT;
