-- =============================================================================
--  Migration 001 - rule version history (Oracle)
-- =============================================================================
--  Run ONCE on an existing schema (created by schema-oracle.sql), with the service
--  stopped, before deploying the library version that stores one row per rule VERSION.
--
--  Before: one mutable row per rule name (UK_RULE_DEFINITION_NAME), VERSION_NO bumped in place.
--  After : one immutable row per (RULE_NAME, VERSION_NO); STATUS DRAFT / ACTIVE / ARCHIVED;
--          at most one ACTIVE = 1 row per rule name.
--  Existing rows keep their version number and stay live (ACTIVE = 1) or become ARCHIVED.
-- =============================================================================

-- 1. Drop the "one row per rule name" unique constraint.
ALTER TABLE RULE_DEFINITION DROP CONSTRAINT UK_RULE_DEFINITION_NAME;

-- 2. When each version was created.
ALTER TABLE RULE_DEFINITION ADD (CREATED_AT TIMESTAMP);
UPDATE RULE_DEFINITION SET CREATED_AT = UPDATED_AT WHERE CREATED_AT IS NULL;

-- 3. Statuses: soft-deleted rows (ACTIVE = 0, STATUS INACTIVE) are archived versions now.
UPDATE RULE_DEFINITION SET STATUS = 'ARCHIVED' WHERE ACTIVE = 0 AND STATUS <> 'DRAFT';
UPDATE RULE_DEFINITION SET STATUS = 'ACTIVE'   WHERE ACTIVE = 1;
ALTER TABLE RULE_DEFINITION MODIFY (STATUS DEFAULT 'DRAFT', ACTIVE DEFAULT 0);
ALTER TABLE RULE_DEFINITION ADD CONSTRAINT CK_RULE_DEFINITION_STATUS CHECK (STATUS IN ('DRAFT', 'ACTIVE', 'ARCHIVED'));

-- 4. One row per (rule, version) ...
ALTER TABLE RULE_DEFINITION ADD CONSTRAINT UK_RULE_DEFINITION_NAME_VER UNIQUE (RULE_NAME, VERSION_NO);

-- 5. ... and at most ONE live version per rule name (function-based unique index:
--    inactive rows map to NULL, which Oracle does not index).
CREATE UNIQUE INDEX UX_RULE_DEFINITION_ONE_ACTIVE
    ON RULE_DEFINITION (CASE WHEN ACTIVE = 1 THEN RULE_NAME END);

COMMIT;
