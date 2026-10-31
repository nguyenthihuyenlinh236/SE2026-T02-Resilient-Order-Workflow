CREATE TABLE inventory (
    product_id UUID PRIMARY KEY,
    product_name VARCHAR(255) NOT NULL,
    on_hand_quantity INTEGER NOT NULL CHECK (on_hand_quantity >= 0),
    reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    other_commitments INTEGER NOT NULL DEFAULT 0 CHECK (other_commitments >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (reserved_quantity::BIGINT + other_commitments <= on_hand_quantity)
);

-- All operations for an Order serialize here. inventory-02 will add cancellation.
CREATE TABLE inventory_order_guards (
    order_id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE reservations (
    reservation_id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES inventory_order_guards(order_id),
    reserve_key VARCHAR(255) NOT NULL UNIQUE,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('RESERVED', 'REJECTED', 'RELEASED')),
    rejection_reason VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL))
);

-- Snapshot the request for REJECTED as well, including unknown product IDs.
-- Actual inventory rows are checked and locked before any allocation succeeds.
CREATE TABLE reservation_items (
    reservation_id UUID NOT NULL REFERENCES reservations(reservation_id),
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (reservation_id, product_id)
);

CREATE INDEX reservations_status_updated_idx ON reservations(status, updated_at);
CREATE INDEX reservation_items_product_idx ON reservation_items(product_id);
