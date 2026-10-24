CREATE TABLE orders (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expire_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_orders_expiration ON orders (status, expire_at);

CREATE TABLE order_items (
    order_id UUID NOT NULL REFERENCES orders (id),
    sku VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, sku)
);

CREATE TABLE order_sagas (
    order_id UUID PRIMARY KEY REFERENCES orders (id),
    status VARCHAR(32) NOT NULL,
    current_step VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory_stock (
    sku VARCHAR(100) PRIMARY KEY,
    on_hand INTEGER NOT NULL CHECK (on_hand >= 0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand)
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES orders (id),
    idempotency_key VARCHAR(240) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES orders (id),
    capture_key VARCHAR(240) NOT NULL UNIQUE,
    capture_status VARCHAR(32) NOT NULL,
    refund_key VARCHAR(300),
    refund_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX ix_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE notification_deliveries (
    event_id UUID PRIMARY KEY REFERENCES outbox_events (id),
    order_id UUID NOT NULL REFERENCES orders (id),
    notification_type VARCHAR(100) NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL
);

INSERT INTO inventory_stock (sku, on_hand, reserved)
VALUES ('DEMO-ITEM', 10000, 0);
