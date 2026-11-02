-- V1: core shortener schema (portable across PostgreSQL and H2 PostgreSQL-mode used in tests)
CREATE SEQUENCE link_seq START WITH 100000 INCREMENT BY 1;

CREATE TABLE short_link (
    id          BIGINT PRIMARY KEY,
    code        VARCHAR(16)   NOT NULL,
    target_url  VARCHAR(2048) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_short_link_code UNIQUE (code)
);

CREATE TABLE click_event (
    id          BIGSERIAL PRIMARY KEY,
    link_id     BIGINT        NOT NULL REFERENCES short_link (id),
    clicked_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    referrer    VARCHAR(512),
    user_agent  VARCHAR(256),
    visitor_hash VARCHAR(64)
);

CREATE INDEX idx_click_event_link_time ON click_event (link_id, clicked_at);
