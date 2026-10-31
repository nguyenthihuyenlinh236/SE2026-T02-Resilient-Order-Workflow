-- Keep V001 immutable: existing local demo databases can upgrade in place.
ALTER TABLE inventory ADD COLUMN sku VARCHAR(128);
ALTER TABLE inventory ADD COLUMN store_id VARCHAR(100) NOT NULL DEFAULT 'DEMO-STORE';
UPDATE inventory SET sku = CASE product_id::text
    WHEN '00000000-0000-4000-8000-000000000001' THEN 'DEMO-ITEM'
    WHEN '00000000-0000-4000-8000-000000000002' THEN 'DEMO-EMPTY'
    ELSE product_id::text END;
ALTER TABLE inventory ALTER COLUMN sku SET NOT NULL;
ALTER TABLE inventory ADD CONSTRAINT inventory_store_sku_unique UNIQUE (store_id, sku);
ALTER TABLE inventory ADD CONSTRAINT inventory_sku_nonblank CHECK (btrim(sku) <> '');
ALTER TABLE inventory ADD CONSTRAINT inventory_store_nonblank CHECK (btrim(store_id) <> '');

ALTER TABLE reservations ADD COLUMN store_id VARCHAR(100) NOT NULL DEFAULT 'DEMO-STORE';
ALTER TABLE reservations ADD CONSTRAINT reservations_store_nonblank CHECK (btrim(store_id) <> '');

ALTER TABLE reservation_items ADD COLUMN sku VARCHAR(128);
UPDATE reservation_items ri SET sku = COALESCE(
    (SELECT i.sku FROM inventory i WHERE i.product_id = ri.product_id), ri.product_id::text);
ALTER TABLE reservation_items DROP CONSTRAINT reservation_items_pkey;
ALTER TABLE reservation_items ALTER COLUMN product_id DROP NOT NULL;
-- A rejected request for an unknown SKU has no internal inventory reference.
UPDATE reservation_items ri SET product_id = NULL
WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.product_id = ri.product_id);
ALTER TABLE reservation_items ALTER COLUMN sku SET NOT NULL;
ALTER TABLE reservation_items ADD PRIMARY KEY (reservation_id, sku);
ALTER TABLE reservation_items ADD CONSTRAINT reservation_items_inventory_fk
    FOREIGN KEY (product_id) REFERENCES inventory(product_id);
