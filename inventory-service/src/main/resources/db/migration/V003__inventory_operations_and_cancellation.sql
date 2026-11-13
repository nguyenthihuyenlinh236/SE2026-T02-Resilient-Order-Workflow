ALTER TABLE inventory_order_guards
    ADD COLUMN cancellation_recorded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD CONSTRAINT guard_cancellation_time CHECK (cancellation_recorded = (cancelled_at IS NOT NULL));
ALTER TABLE reservations ADD COLUMN released_at TIMESTAMPTZ;

-- A completed command snapshot is immutable even when its resource changes.
CREATE TABLE inventory_operations (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES inventory_order_guards(order_id),
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('RESERVE', 'RELEASE', 'CANCEL')),
    payload_hash CHAR(64) NOT NULL,
    response_code INTEGER NOT NULL CHECK (response_code = 200),
    response_payload JSONB NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (order_id, operation)
);

-- #28 has not released resources yet. Only backfill outcomes supported by data;
-- do not invent an original RESERVED response from an already RELEASED row.
INSERT INTO inventory_operations(idempotency_key, order_id, operation, payload_hash,
                                 response_code, response_payload, completed_at)
SELECT r.reserve_key, r.order_id, 'RESERVE', r.payload_hash, 200,
       jsonb_build_object('reservationId', r.reservation_id, 'orderId', r.order_id,
           'storeId', r.store_id, 'reserveKey', r.reserve_key, 'status', r.status,
           'rejectionReason', r.rejection_reason,
           'items', COALESCE((SELECT jsonb_agg(jsonb_build_object('sku', i.sku, 'quantity', i.quantity) ORDER BY i.sku)
                              FROM reservation_items i WHERE i.reservation_id = r.reservation_id), '[]'::jsonb),
           'createdAt', to_char(r.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')),
       r.created_at
FROM reservations r WHERE r.status IN ('RESERVED', 'REJECTED');

CREATE INDEX inventory_operations_completed_idx ON inventory_operations(completed_at);
