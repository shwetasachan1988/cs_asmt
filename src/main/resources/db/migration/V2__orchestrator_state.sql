-- V2: orchestrator persistence. Run state is a versioned JSON snapshot (optimistic locking);
-- audit events are append-only and hash-chained per run; artifacts are immutable versioned rows.
CREATE TABLE workflow_run (
    id          VARCHAR(40) PRIMARY KEY,
    scenario    VARCHAR(40)  NOT NULL,
    status      VARCHAR(24)  NOT NULL,
    version     BIGINT       NOT NULL,
    state_json  TEXT         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE audit_event (
    id          BIGSERIAL PRIMARY KEY,
    run_id      VARCHAR(40)  NOT NULL,
    seq         INT          NOT NULL,
    ts          TIMESTAMP WITH TIME ZONE NOT NULL,
    actor       VARCHAR(64)  NOT NULL,
    type        VARCHAR(40)  NOT NULL,
    node_id     VARCHAR(64),
    detail      TEXT         NOT NULL,
    prev_hash   VARCHAR(64)  NOT NULL,
    hash        VARCHAR(64)  NOT NULL,
    CONSTRAINT uq_audit_run_seq UNIQUE (run_id, seq)
);

CREATE TABLE artifact (
    id          VARCHAR(48)  PRIMARY KEY,
    run_id      VARCHAR(40)  NOT NULL,
    node_id     VARCHAR(64)  NOT NULL,
    type        VARCHAR(40)  NOT NULL,
    name        VARCHAR(160) NOT NULL,
    version     INT          NOT NULL,
    sha256      VARCHAR(64)  NOT NULL,
    content     TEXT         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_artifact_run ON artifact (run_id, node_id);
