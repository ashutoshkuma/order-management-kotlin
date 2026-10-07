-- ============================================================
-- Migration: V2 - Create refund table
-- ============================================================
-- Refunds are separate from transactions for two reasons:
--   1. A refund is a distinct financial event with its own audit trail
--   2. UNIQUE (original_transaction_id) enforces one refund per charge,
--      providing idempotency for the saga compensation path
--
-- The transaction table remains for CHARGE records only.
-- ============================================================

CREATE TABLE refund (
    refund_id                VARCHAR(50)    PRIMARY KEY,
    order_id                 VARCHAR(50)    NOT NULL,
    original_transaction_id  VARCHAR(50)    NOT NULL,
    amount                   DECIMAL(10, 2) NOT NULL,
    currency                 VARCHAR(3)     NOT NULL DEFAULT 'USD',
    status                   VARCHAR(20)    NOT NULL DEFAULT 'COMPLETED',
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- One refund per original charge — prevents double-refund in compensation retries
    CONSTRAINT uq_refund_per_transaction UNIQUE (original_transaction_id)
);

CREATE INDEX idx_refund_order_id ON refund (order_id);

COMMENT ON TABLE refund IS
    'Refund records for saga compensation. '
    'One row per original charge transaction. '
    'UNIQUE (original_transaction_id) makes the refund path idempotent.';