-- Opt-in demo profile only; never overwrite existing stock on restart.
INSERT INTO inventory(product_id, product_name, on_hand_quantity)
VALUES ('00000000-0000-4000-8000-000000000001', 'Demo product', 10),
       ('00000000-0000-4000-8000-000000000002', 'Out of stock product', 0)
ON CONFLICT (product_id) DO NOTHING;
