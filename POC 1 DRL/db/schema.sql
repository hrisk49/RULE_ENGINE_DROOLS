-- PostgreSQL schema for the Drools rule-engine POC (3 flows: transfer, loan, closure).
-- Library tables + this POC's RE_* business tables. Hibernate ddl-auto=update creates the
-- same objects from the entities; this script is the manual equivalent.
--   psql -U postgres -d ruleengine -f db/schema.sql

-- ============================ LIBRARY TABLES =================================

CREATE SEQUENCE rule_definition_seq START WITH 1 INCREMENT BY 1;
-- One row per rule VERSION: (rule_name, version_no) is unique, status DRAFT / ACTIVE / ARCHIVED,
-- and at most one version per rule name is active (the live one). Existing databases: run
-- db/migrations/001_rule_versioning.sql instead.
CREATE TABLE rule_definition (
    id           BIGINT        NOT NULL PRIMARY KEY,
    rule_name    VARCHAR(255)  NOT NULL,
    agenda_group VARCHAR(255)  NOT NULL,
    source_type  VARCHAR(20)   NOT NULL DEFAULT 'DRL',
    source_json  TEXT,
    drl_text     TEXT          NOT NULL,
    version_no   INTEGER       NOT NULL DEFAULT 1,
    status       VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    active       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by   VARCHAR(100),
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL,
    CONSTRAINT uk_rule_definition_name_ver UNIQUE (rule_name, version_no)
);
CREATE UNIQUE INDEX ux_rule_definition_one_active ON rule_definition (rule_name) WHERE active;
CREATE INDEX ix_rule_definition_group ON rule_definition (agenda_group);

CREATE SEQUENCE rule_field_catalog_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE rule_field_catalog (
    id             BIGINT        NOT NULL PRIMARY KEY,
    field_key      VARCHAR(200)  NOT NULL,
    flow_name      VARCHAR(255)  NOT NULL,
    label          VARCHAR(200)  NOT NULL,
    data_type      VARCHAR(20)   NOT NULL,
    drl_path       VARCHAR(300)  NOT NULL,
    allowed_ops    VARCHAR(200)  NOT NULL,
    allowed_values VARCHAR(1000)
);
CREATE INDEX ix_rule_field_flow ON rule_field_catalog (flow_name);

CREATE SEQUENCE flow_group_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE flow_group (
    id           BIGINT       NOT NULL PRIMARY KEY,
    flow_name    VARCHAR(255) NOT NULL,
    agenda_group VARCHAR(255) NOT NULL,
    order_no     INTEGER      NOT NULL
);
CREATE INDEX ix_flow_group_flow ON flow_group (flow_name);

CREATE SEQUENCE request_flow_map_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE request_flow_map (
    id        BIGINT       NOT NULL PRIMARY KEY,
    fact_type VARCHAR(500) NOT NULL UNIQUE,
    flow_name VARCHAR(255) NOT NULL
);

-- ============================ BUSINESS TABLES ================================

CREATE SEQUENCE re_user_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE re_user (
    id           BIGINT      NOT NULL PRIMARY KEY,
    user_id      VARCHAR(64) NOT NULL UNIQUE,
    kyc_verified BOOLEAN     NOT NULL DEFAULT FALSE,
    full_name    VARCHAR(128)
);

CREATE SEQUENCE re_user_limit_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE re_user_limit (
    id               BIGINT         NOT NULL PRIMARY KEY,
    user_id          VARCHAR(64)    NOT NULL,
    transaction_mode VARCHAR(32),
    dr_cr_type       VARCHAR(8),
    limit_amount     NUMERIC(18,2)  NOT NULL
);

CREATE SEQUENCE re_product_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE re_product (
    id             BIGINT      NOT NULL PRIMARY KEY,
    source_account VARCHAR(64) NOT NULL UNIQUE,
    dr_res         INTEGER
);

CREATE SEQUENCE re_user_block_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE re_user_block (
    id       BIGINT      NOT NULL PRIMARY KEY,
    user_id  VARCHAR(64) NOT NULL UNIQUE,
    blocked  BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE SEQUENCE re_customer_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE re_customer (
    id           BIGINT      NOT NULL PRIMARY KEY,
    user_id      VARCHAR(64) NOT NULL UNIQUE,
    credit_score INTEGER     NOT NULL,
    full_name    VARCHAR(128)
);

CREATE SEQUENCE re_account_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE re_account (
    id         BIGINT         NOT NULL PRIMARY KEY,
    account_no VARCHAR(64)    NOT NULL UNIQUE,
    balance    NUMERIC(18,2)  NOT NULL,
    status     VARCHAR(16)
);
