-- API/Event Contract #39 allows idempotency keys up to 512 characters.
ALTER TABLE reservations ALTER COLUMN reserve_key TYPE VARCHAR(512);
ALTER TABLE inventory_operations ALTER COLUMN idempotency_key TYPE VARCHAR(512);
