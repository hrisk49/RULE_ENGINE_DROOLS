-- =============================================================================
--  Rule Engine library - Oracle schema (reference DDL)
-- =============================================================================
--  The library ships code-first entities. In a BANKING/production database the DBA
--  runs vetted DDL and the app runs with `spring.jpa.hibernate.ddl-auto: none`
--  (or `validate`). This script is that vetted DDL: run it once per service schema.
--
--  Tables:
--    RULE_DEFINITION    - the rules (one row per rule); stores SOURCE_JSON + DRL_TEXT
--    RULE_FIELD_CATALOG - the fields the Visual Rule Builder may use (the "palette")
--    FLOW_GROUP         - a flow -> its ordered agenda groups
--    REQUEST_FLOW_MAP   - a request DTO class -> its flow
--
--  Demo rows (3 request types, 4 rules, 7 catalog fields) are in sample-data-oracle.sql.
--  NOT auto-executed by the library (lives under db/oracle/, not at the classpath root).
-- =============================================================================

-- ---- RULE_DEFINITION : the rules (JSON source + generated/handwritten DRL) ----
--  A rule belongs to ONE group (AGENDA_GROUP). A flow pulls in groups, so a rule in a
--  shared group (e.g. COMMON) is reused by every flow that includes that group.
--  SOURCE_TYPE = 'BUILDER' -> SOURCE_JSON is set, DRL_TEXT is generated from it.
--  SOURCE_TYPE = 'DRL'     -> SOURCE_JSON is NULL, DRL_TEXT is hand-written.
--  The engine only ever compiles DRL_TEXT of rows where ACTIVE = 1.
CREATE TABLE RULE_DEFINITION (
    ID           NUMBER(19)     NOT NULL,
    RULE_NAME    VARCHAR2(255)  NOT NULL,
    AGENDA_GROUP VARCHAR2(255)  NOT NULL,
    SOURCE_TYPE  VARCHAR2(20)   DEFAULT 'DRL' NOT NULL,
    SOURCE_JSON  CLOB,
    DRL_TEXT     CLOB           NOT NULL,
    VERSION_NO   NUMBER(10)     DEFAULT 1 NOT NULL,
    STATUS       VARCHAR2(20)   DEFAULT 'DRAFT' NOT NULL,
    ACTIVE       NUMBER(1)      DEFAULT 0 NOT NULL,
    CREATED_BY   VARCHAR2(100),
    CREATED_AT   TIMESTAMP,
    UPDATED_AT   TIMESTAMP      NOT NULL,
    CONSTRAINT PK_RULE_DEFINITION PRIMARY KEY (ID),
    -- one row per rule VERSION (existing schemas: run migration-001-rule-versioning.sql)
    CONSTRAINT UK_RULE_DEFINITION_NAME_VER UNIQUE (RULE_NAME, VERSION_NO),
    CONSTRAINT CK_RULE_DEFINITION_ACTIVE CHECK (ACTIVE IN (0, 1)),
    CONSTRAINT CK_RULE_DEFINITION_STATUS CHECK (STATUS IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT CK_RULE_DEFINITION_SRC CHECK (SOURCE_TYPE IN ('DRL', 'BUILDER'))
);
-- At most one live (ACTIVE = 1) version per rule name; inactive rows map to NULL (not indexed).
CREATE UNIQUE INDEX UX_RULE_DEFINITION_ONE_ACTIVE ON RULE_DEFINITION (CASE WHEN ACTIVE = 1 THEN RULE_NAME END);
CREATE SEQUENCE RULE_DEFINITION_SEQ START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE INDEX IX_RULE_DEFINITION_GROUP ON RULE_DEFINITION (AGENDA_GROUP);

-- ---- RULE_FIELD_CATALOG : builder palette, scoped by flow --------------------
--  FLOW_NAME holds a specific flow (shows only for that flow) or 'COMMON' (shows in
--  every flow). The UI reads: WHERE FLOW_NAME IN (:flow, 'COMMON').
CREATE TABLE RULE_FIELD_CATALOG (
    ID             NUMBER(19)     NOT NULL,
    FIELD_KEY      VARCHAR2(200)  NOT NULL,
    FLOW_NAME      VARCHAR2(255)  NOT NULL,
    LABEL          VARCHAR2(200)  NOT NULL,
    DATA_TYPE      VARCHAR2(20)   NOT NULL,
    DRL_PATH       VARCHAR2(300)  NOT NULL,
    ALLOWED_OPS    VARCHAR2(200)  NOT NULL,
    ALLOWED_VALUES VARCHAR2(1000),
    CONSTRAINT PK_RULE_FIELD_CATALOG PRIMARY KEY (ID),
    CONSTRAINT CK_RULE_FIELD_TYPE CHECK (DATA_TYPE IN ('NUMBER', 'ENUM', 'BOOLEAN', 'STRING'))
);
CREATE SEQUENCE RULE_FIELD_CATALOG_SEQ START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE INDEX IX_RULE_FIELD_FLOW ON RULE_FIELD_CATALOG (FLOW_NAME);

-- ---- FLOW_GROUP : flow -> ordered agenda groups -----------------------------
CREATE TABLE FLOW_GROUP (
    ID           NUMBER(19)     NOT NULL,
    FLOW_NAME    VARCHAR2(255)  NOT NULL,
    AGENDA_GROUP VARCHAR2(255)  NOT NULL,
    ORDER_NO     NUMBER(10)     NOT NULL,
    CONSTRAINT PK_FLOW_GROUP PRIMARY KEY (ID)
);
CREATE SEQUENCE FLOW_GROUP_SEQ START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE INDEX IX_FLOW_GROUP_FLOW ON FLOW_GROUP (FLOW_NAME);

-- ---- REQUEST_FLOW_MAP : request DTO class -> flow ---------------------------
CREATE TABLE REQUEST_FLOW_MAP (
    ID        NUMBER(19)    NOT NULL,
    FACT_TYPE VARCHAR2(500) NOT NULL,
    FLOW_NAME VARCHAR2(255) NOT NULL,
    CONSTRAINT PK_REQUEST_FLOW_MAP PRIMARY KEY (ID),
    CONSTRAINT UK_REQUEST_FLOW_FACT UNIQUE (FACT_TYPE)
);
CREATE SEQUENCE REQUEST_FLOW_MAP_SEQ START WITH 1 INCREMENT BY 1 NOCACHE;
