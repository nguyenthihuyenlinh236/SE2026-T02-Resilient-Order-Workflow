CREATE TABLE order_drafts (
    client_id VARCHAR(128) NOT NULL,
    draft_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    payload JSONB NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (client_id, draft_id)
);

CREATE INDEX idx_order_drafts_saved_at ON order_drafts (saved_at);
