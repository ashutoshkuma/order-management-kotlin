-- V4: Add UUID-keyed demo products so order-service (which uses UUID productIds) can reserve stock
INSERT INTO inventory_items (product_id, product_name, quantity_available, quantity_reserved)
VALUES
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'Laptop Pro 15in',      100, 0),
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', 'Wireless Mouse',        500, 0),
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', 'USB-C Hub',             200, 0),
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'Mechanical Keyboard',   150, 0),
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a15', 'Monitor 27in',           75, 0)
ON CONFLICT (product_id) DO NOTHING;