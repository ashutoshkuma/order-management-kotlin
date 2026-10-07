-- ============================================================
-- Migration: V2 - Create delivery table
-- ============================================================
-- A Delivery is a distinct event from a Shipment.
-- A Shipment records that goods were dispatched.
-- A Delivery records that goods arrived — confirmed by the carrier.
--
-- Separating them gives a clean audit trail:
--   shipment row   → when we dispatched it
--   delivery row   → when the carrier confirmed arrival
--
-- UNIQUE (shipment_id) ensures one delivery record per shipment,
-- making the confirmDelivery() compensation path idempotent.
-- ============================================================

CREATE TABLE delivery (
    delivery_id   VARCHAR(50)  PRIMARY KEY,
    shipment_id   VARCHAR(50)  NOT NULL,
    order_id      VARCHAR(50)  NOT NULL,
    confirmed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- One delivery record per shipment — prevents duplicate delivery events
    CONSTRAINT uq_delivery_per_shipment UNIQUE (shipment_id)
);

CREATE INDEX idx_delivery_order_id ON delivery (order_id);

COMMENT ON TABLE delivery IS
    'Carrier delivery confirmation records. '
    'One row per shipment, created when the carrier confirms goods arrived. '
    'UNIQUE (shipment_id) makes confirmDelivery() idempotent.';