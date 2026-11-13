CREATE TABLE captures (
    payment_id VARCHAR(128) PRIMARY KEY,
    order_id VARCHAR(128) NOT NULL UNIQUE,
    idempotency_key VARCHAR(512) NOT NULL UNIQUE,
    payload_hash CHAR(64) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'VND'),
    status VARCHAR(32) NOT NULL CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    mock_transaction_id UUID UNIQUE,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT captures_order_identifier CHECK (order_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$'),
    CONSTRAINT captures_terminal_fields CHECK (
        (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND mock_transaction_id IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND failure_code IS NOT NULL)
        OR (status IN ('IN_PROGRESS', 'UNKNOWN') AND completed_at IS NULL AND failure_code IS NULL)
    )
);

CREATE INDEX captures_status_updated_idx ON captures(status, updated_at);
CREATE INDEX captures_completed_at_idx ON captures(completed_at);
