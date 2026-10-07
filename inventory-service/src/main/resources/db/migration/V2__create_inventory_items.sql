-- ============================================================
-- Migration: V2 - Create inventory_items + extend reservation
-- ============================================================
-- inventory_items is the stock ledger. Every reserve() call
-- decrements quantity_available and increments quantity_reserved.
-- Every release() call reverses that.
--
-- items_json on reservation stores which products/quantities were
-- reserved so the release() path knows exactly what to put back.
-- ============================================================

CREATE TABLE inventory_items (
    product_id          VARCHAR(50)  PRIMARY KEY,
    product_name        VARCHAR(255) NOT NULL,
    quantity_available  INT          NOT NULL DEFAULT 0,
    quantity_reserved   INT          NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_available_non_negative CHECK (quantity_available >= 0),
    CONSTRAINT chk_reserved_non_negative  CHECK (quantity_reserved  >= 0)
);

CREATE INDEX idx_inventory_items_available ON inventory_items (quantity_available);

-- Extend reservation to record which items (and quantities) were reserved.
-- Required so the release() compensation path knows what stock to put back.
-- Default '[]' keeps existing rows valid.
ALTER TABLE reservation
    ADD COLUMN items_json TEXT NOT NULL DEFAULT '[]';

COMMENT ON TABLE inventory_items IS
    'Stock ledger. quantity_available + quantity_reserved = total stock on hand. '
    'Never DELETE rows; set quantity_available=0 to mark out-of-stock.';

COMMENT ON COLUMN reservation.items_json IS
    'JSON array of {productId, quantity} objects captured at reserve time. '
    'Used by release() to decrement quantity_reserved and restore quantity_available.';