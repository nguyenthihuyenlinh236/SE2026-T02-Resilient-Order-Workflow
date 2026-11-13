INSERT INTO product_catalog(store_id, sku, product_name, unit_price, currency, active)
VALUES ('STORE-001', 'SKU-001', 'Demo product 1', 125000.00, 'VND', TRUE),
       ('STORE-001', 'SKU-002', 'Demo product 2', 75000.00, 'VND', TRUE),
       ('STORE-001', 'SKU-INACTIVE', 'Inactive demo product', 50000.00, 'VND', FALSE)
ON CONFLICT (store_id, sku) DO NOTHING;
