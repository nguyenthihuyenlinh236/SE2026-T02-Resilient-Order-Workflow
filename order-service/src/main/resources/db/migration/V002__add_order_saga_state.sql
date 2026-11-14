ALTER TABLE orders
    ADD COLUMN reservation_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CREATED',
    ADD COLUMN reservation_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN unknown_operation VARCHAR(16),
    ADD COLUMN payment_id VARCHAR(128),
    ADD COLUMN capture_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN capture_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN captured_amount NUMERIC(19,2),
    ADD COLUMN captured_currency VARCHAR(3),
    ADD COLUMN refund_id VARCHAR(128),
    ADD COLUMN refund_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN refund_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN failed_at TIMESTAMPTZ,
    ADD COLUMN expired_at TIMESTAMPTZ,
    ADD COLUMN terminal_at TIMESTAMPTZ,
    ADD CONSTRAINT orders_reservation_status_valid CHECK
        (reservation_status IN ('NOT_CREATED','RESERVED','RELEASED','REJECTED','UNKNOWN')),
    ADD CONSTRAINT orders_unknown_operation_valid CHECK
        ((reservation_status = 'UNKNOWN' AND unknown_operation IN ('RESERVE','RELEASE','CANCEL')) OR
         (reservation_status <> 'UNKNOWN' AND unknown_operation IS NULL)),
    ADD CONSTRAINT orders_capture_status_valid CHECK
        (capture_status IN ('NOT_STARTED','IN_PROGRESS','SUCCEEDED','FAILED','UNKNOWN')),
    ADD CONSTRAINT orders_refund_status_valid CHECK
        (refund_status IN ('NOT_REQUIRED','NOT_STARTED','IN_PROGRESS','SUCCEEDED','FAILED','UNKNOWN')),
    ADD CONSTRAINT orders_capture_details_valid CHECK
        ((captured_amount IS NULL AND captured_currency IS NULL) OR
         (captured_amount > 0 AND captured_currency ~ '^[A-Z]{3}$')),
    ADD CONSTRAINT orders_claim_fields_together CHECK
        ((claim_token IS NULL AND claimed_at IS NULL AND lease_until IS NULL) OR
         (claim_token IS NOT NULL AND claimed_at IS NOT NULL AND lease_until > claimed_at));

-- V001 could contain terminal rows created outside the Issue #26 API. Retain them and
-- use their last persisted timestamp; do not invent any remote-operation evidence.
UPDATE orders SET confirmed_at=updated_at, terminal_at=updated_at WHERE status='CONFIRMED';
UPDATE orders SET failed_at=updated_at, terminal_at=updated_at WHERE status='FAILED';
UPDATE orders SET expired_at=updated_at, terminal_at=updated_at WHERE status='EXPIRED';

ALTER TABLE orders ADD CONSTRAINT orders_terminal_fields_valid CHECK
    ((status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND terminal_at IS NOT NULL) OR
     (status = 'FAILED' AND failed_at IS NOT NULL AND terminal_at IS NOT NULL) OR
     (status = 'EXPIRED' AND expired_at IS NOT NULL AND terminal_at IS NOT NULL) OR
     (status NOT IN ('CONFIRMED','FAILED','EXPIRED') AND terminal_at IS NULL));

CREATE TABLE sagas (
    saga_id UUID PRIMARY KEY,
    order_id VARCHAR(128) NOT NULL UNIQUE REFERENCES orders(order_id),
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    branch VARCHAR(16) NOT NULL DEFAULT 'FORWARD',
    current_operation VARCHAR(16),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT sagas_status_valid CHECK (status IN
        ('RUNNING','RECONCILING','RECONCILIATION_REQUIRED','COMPENSATING','COMPENSATION_FAILED','COMPLETED')),
    CONSTRAINT sagas_branch_valid CHECK (branch IN ('FORWARD','FAILURE','EXPIRATION')),
    CONSTRAINT sagas_operation_valid CHECK
        (current_operation IS NULL OR current_operation IN ('RESERVE','CAPTURE','RELEASE','CANCEL','REFUND')),
    CONSTRAINT sagas_completion_valid CHECK
        ((status = 'COMPLETED' AND completed_at IS NOT NULL) OR status <> 'COMPLETED'),
    CONSTRAINT sagas_version_nonnegative CHECK (version >= 0)
);

CREATE TABLE saga_steps (
    step_id UUID PRIMARY KEY,
    saga_id UUID NOT NULL REFERENCES sagas(saga_id),
    operation VARCHAR(16) NOT NULL,
    command_id UUID NOT NULL UNIQUE,
    correlation_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL UNIQUE,
    request_payload JSONB NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    result_payload JSONB,
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    unknown_since TIMESTAMPTZ,
    query_count INTEGER NOT NULL DEFAULT 0,
    background_query_count INTEGER NOT NULL DEFAULT 0,
    next_query_at TIMESTAMPTZ,
    claim_token UUID,
    claimed_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT saga_steps_operation_valid CHECK (operation IN ('RESERVE','CAPTURE','RELEASE','CANCEL','REFUND')),
    CONSTRAINT saga_steps_correlation_not_blank CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT saga_steps_payload_hash_valid CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT saga_steps_status_valid CHECK (status IN ('NOT_STARTED','IN_PROGRESS','SUCCEEDED','FAILED','UNKNOWN')),
    CONSTRAINT saga_steps_attempt_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT saga_steps_query_count_valid CHECK (query_count BETWEEN 0 AND 30),
    CONSTRAINT saga_steps_background_count_nonnegative CHECK (background_query_count >= 0),
    CONSTRAINT saga_steps_claim_fields_together CHECK
        ((claim_token IS NULL AND claimed_at IS NULL AND lease_until IS NULL) OR
         (claim_token IS NOT NULL AND claimed_at IS NOT NULL AND lease_until > claimed_at)),
    CONSTRAINT saga_steps_completed_valid CHECK
        ((status IN ('SUCCEEDED','FAILED') AND completed_at IS NOT NULL) OR
         (status NOT IN ('SUCCEEDED','FAILED') AND completed_at IS NULL)),
    UNIQUE (saga_id, operation)
);

CREATE TABLE saga_attempts (
    attempt_id UUID PRIMARY KEY,
    step_id UUID NOT NULL REFERENCES saga_steps(step_id),
    kind VARCHAR(16) NOT NULL,
    sequence_no INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    outcome VARCHAR(64),
    error_code VARCHAR(64),
    error_message TEXT,
    CONSTRAINT saga_attempts_kind_valid CHECK (kind IN ('SEND','QUERY','BACKGROUND_QUERY')),
    CONSTRAINT saga_attempts_sequence_positive CHECK (sequence_no > 0),
    CONSTRAINT saga_attempts_time_ordered CHECK (finished_at IS NULL OR finished_at >= started_at),
    UNIQUE (step_id, kind, sequence_no)
);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL DEFAULT 'ORDER',
    aggregate_id VARCHAR(128) NOT NULL REFERENCES orders(order_id),
    schema_version INTEGER NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    publish_status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    last_error TEXT,
    CONSTRAINT outbox_event_type_valid CHECK (event_type IN ('OrderConfirmed','OrderFailed','OrderExpired')),
    CONSTRAINT outbox_aggregate_type_valid CHECK (aggregate_type = 'ORDER'),
    CONSTRAINT outbox_schema_positive CHECK (schema_version > 0),
    CONSTRAINT outbox_status_valid CHECK (publish_status IN ('NEW','PUBLISHED')),
    CONSTRAINT outbox_attempt_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT outbox_publish_valid CHECK
        ((publish_status = 'PUBLISHED' AND published_at IS NOT NULL) OR publish_status = 'NEW'),
    UNIQUE (aggregate_id, event_type)
);

CREATE INDEX sagas_unfinished_idx ON sagas(status, updated_at) WHERE status <> 'COMPLETED';
CREATE INDEX saga_steps_retry_idx ON saga_steps(next_attempt_at, step_id)
    WHERE status IN ('NOT_STARTED','IN_PROGRESS');
CREATE INDEX saga_steps_query_idx ON saga_steps(next_query_at, step_id)
    WHERE status IN ('UNKNOWN','IN_PROGRESS');
CREATE INDEX saga_steps_expired_lease_idx ON saga_steps(lease_until, step_id)
    WHERE claim_token IS NOT NULL;
CREATE INDEX orders_expired_claim_idx ON orders(lease_until, order_id) WHERE status = 'EXPIRING';
CREATE INDEX outbox_new_idx ON outbox_events(next_attempt_at, occurred_at) WHERE publish_status = 'NEW';

-- Existing Issue #26 rows have no known remote operations. Preserve that truth and
-- create only a resumable initial Saga/RESERVE intent from their immutable snapshots.
INSERT INTO sagas(saga_id, order_id, status, branch, current_operation, created_at, updated_at, completed_at)
SELECT gen_random_uuid(), order_id,
       CASE WHEN status IN ('CONFIRMED','FAILED','EXPIRED') THEN 'COMPLETED' ELSE 'RUNNING' END,
       CASE WHEN status IN ('FAILED','COMPENSATING','COMPENSATION_FAILED') THEN 'FAILURE'
            WHEN status IN ('EXPIRED','EXPIRING') THEN 'EXPIRATION' ELSE 'FORWARD' END,
       CASE WHEN status = 'PENDING' THEN 'RESERVE' ELSE NULL END,
       created_at, updated_at,
       CASE WHEN status IN ('CONFIRMED','FAILED','EXPIRED') THEN updated_at ELSE NULL END
FROM orders;

INSERT INTO saga_steps(step_id, saga_id, operation, command_id, correlation_id, idempotency_key,
                       request_payload, payload_hash, status, created_at, updated_at)
SELECT gen_random_uuid(), s.saga_id, 'RESERVE', gen_random_uuid(), o.correlation_id,
       'reserve:' || o.order_id || ':v1', snapshot.payload,
       encode(sha256(convert_to(snapshot.payload::text, 'UTF8')), 'hex'),
       'NOT_STARTED', o.created_at, o.updated_at
FROM orders o JOIN sagas s ON s.order_id = o.order_id
CROSS JOIN LATERAL (SELECT jsonb_build_object('schemaVersion', 1, 'orderId', o.order_id,
          'reservationId', o.reservation_id, 'storeId', o.store_id,
          'items', (SELECT jsonb_agg(jsonb_build_object('sku', i.sku, 'quantity', i.quantity) ORDER BY i.sku)
                    FROM order_items i WHERE i.order_id = o.order_id)) AS payload) snapshot
WHERE o.status = 'PENDING';
