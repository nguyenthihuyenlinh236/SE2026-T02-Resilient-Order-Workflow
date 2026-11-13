CREATE TABLE product_catalog (
    store_id VARCHAR(100) NOT NULL,
    sku VARCHAR(128) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    unit_price NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (store_id, sku),
    CONSTRAINT catalog_store_not_blank CHECK (length(btrim(store_id)) > 0),
    CONSTRAINT catalog_sku_not_blank CHECK (length(btrim(sku)) > 0),
    CONSTRAINT catalog_name_not_blank CHECK (length(btrim(product_name)) > 0),
    CONSTRAINT catalog_price_positive CHECK (unit_price > 0),
    CONSTRAINT catalog_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT catalog_timestamps_ordered CHECK (updated_at >= created_at)
);

CREATE TABLE orders (
    order_id VARCHAR(128) PRIMARY KEY,
    customer_id VARCHAR(128) NOT NULL,
    store_id VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    total_amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reservation_id VARCHAR(128) NOT NULL UNIQUE,
    correlation_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expire_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT orders_id_format CHECK (order_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$'),
    CONSTRAINT orders_customer_format CHECK (customer_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$'),
    CONSTRAINT orders_store_not_blank CHECK (length(btrim(store_id)) > 0),
    CONSTRAINT orders_status_valid CHECK (status IN ('PENDING','CONFIRMED','COMPENSATING','COMPENSATION_FAILED','EXPIRING','FAILED','EXPIRED')),
    CONSTRAINT orders_amount_positive CHECK (total_amount > 0),
    CONSTRAINT orders_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT orders_expiry_after_creation CHECK (expire_at > created_at),
    CONSTRAINT orders_version_nonnegative CHECK (version >= 0)
);

CREATE TABLE order_items (
    order_id VARCHAR(128) NOT NULL REFERENCES orders(order_id),
    sku VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(19,2) NOT NULL,
    PRIMARY KEY (order_id, sku),
    CONSTRAINT order_items_sku_not_blank CHECK (length(btrim(sku)) > 0),
    CONSTRAINT order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_items_price_positive CHECK (unit_price > 0)
);

CREATE TABLE idempotency_records (
    scope VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    request_payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    response_code INTEGER,
    response_payload JSONB,
    resource_id VARCHAR(128),
    order_id VARCHAR(128) REFERENCES orders(order_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    retain_until TIMESTAMPTZ,
    PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT idempotency_key_printable CHECK (idempotency_key ~ '^[!-~]+$'),
    CONSTRAINT idempotency_operation_valid CHECK (operation = 'CREATE_ORDER'),
    CONSTRAINT idempotency_status_valid CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    CONSTRAINT idempotency_completion CHECK (
        (status = 'IN_PROGRESS' AND response_code IS NULL AND response_payload IS NULL
            AND resource_id IS NULL AND order_id IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND response_code = 202 AND response_payload IS NOT NULL
            AND resource_id IS NOT NULL AND order_id = resource_id AND completed_at IS NOT NULL)
    )
);

CREATE INDEX orders_pending_expiry_idx ON orders(expire_at, order_id) WHERE status = 'PENDING';
