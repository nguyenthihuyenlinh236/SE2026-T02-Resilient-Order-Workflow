-- Convert all sides of each relationship together; existing UUID text is unchanged.
ALTER TABLE inventory_operations DROP CONSTRAINT inventory_operations_order_id_fkey;
ALTER TABLE reservation_items DROP CONSTRAINT reservation_items_reservation_id_fkey;
ALTER TABLE reservations DROP CONSTRAINT reservations_order_id_fkey;

ALTER TABLE inventory_order_guards ALTER COLUMN order_id TYPE VARCHAR(128) USING order_id::text;
ALTER TABLE reservations ALTER COLUMN order_id TYPE VARCHAR(128) USING order_id::text;
ALTER TABLE reservations ALTER COLUMN reservation_id TYPE VARCHAR(128) USING reservation_id::text;
ALTER TABLE reservation_items ALTER COLUMN reservation_id TYPE VARCHAR(128) USING reservation_id::text;
ALTER TABLE inventory_operations ALTER COLUMN order_id TYPE VARCHAR(128) USING order_id::text;

ALTER TABLE reservations ADD CONSTRAINT reservations_order_id_fkey
    FOREIGN KEY (order_id) REFERENCES inventory_order_guards(order_id);
ALTER TABLE reservation_items ADD CONSTRAINT reservation_items_reservation_id_fkey
    FOREIGN KEY (reservation_id) REFERENCES reservations(reservation_id);
ALTER TABLE inventory_operations ADD CONSTRAINT inventory_operations_order_id_fkey
    FOREIGN KEY (order_id) REFERENCES inventory_order_guards(order_id);

ALTER TABLE inventory_order_guards ADD CONSTRAINT guard_order_identifier
    CHECK (order_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$');
ALTER TABLE reservations ADD CONSTRAINT reservation_identifier
    CHECK (reservation_id ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$');
-- Hashes, keys and command snapshots deliberately remain unchanged for replay.
