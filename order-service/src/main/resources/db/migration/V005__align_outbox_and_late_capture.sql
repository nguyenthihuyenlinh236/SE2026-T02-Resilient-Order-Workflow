ALTER TABLE orders
    ADD COLUMN failure_reason_code VARCHAR(32),
    ADD CONSTRAINT orders_failure_reason_valid CHECK
        (failure_reason_code IS NULL OR failure_reason_code IN ('INVENTORY_REJECTED','PAYMENT_FAILED'));

ALTER TABLE outbox_events
    ADD COLUMN command_id UUID,
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN producer VARCHAR(64) NOT NULL DEFAULT 'order-service';

UPDATE outbox_events e
SET correlation_id = o.correlation_id
FROM orders o
WHERE o.order_id = e.aggregate_id;

ALTER TABLE outbox_events
    ALTER COLUMN correlation_id SET NOT NULL,
    ADD CONSTRAINT outbox_correlation_not_blank CHECK (length(btrim(correlation_id)) > 0),
    ADD CONSTRAINT outbox_producer_valid CHECK (producer = 'order-service');

