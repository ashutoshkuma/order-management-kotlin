-- ============================================================
-- Migration: V3 - Create order_summary projection table
-- ============================================================
-- Read model for the CQRS query side.
-- Updated by OrderProjectionUpdater via @TransactionalEventListener
-- after each domain event is persisted to event_store.
--
-- WHY A SEPARATE TABLE?
-- The event_store is append-only and requires full replay to compute
-- current state. For list queries ("all DELIVERED orders") we need
-- a flat, indexed table we can query directly.
-- This table is the projection — always derivable from event_store,
-- never the source of truth.
-- ============================================================

CREATE TABLE order_summary (
    order_id         VARCHAR(36)   PRIMARY KEY,
    customer_id      VARCHAR(36)   NOT NULL,
    status           VARCHAR(30)   NOT NULL,
    payment_status   VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    shipment_status  VARCHAR(30)   NOT NULL DEFAULT 'NOT_CREATED',
    total_amount     DECIMAL(19,2),
    item_count       INT           NOT NULL DEFAULT 0,
    shipping_address VARCHAR(500),
    workflow_id      VARCHAR(100),
    tracking_number  VARCHAR(100),
    created_at       TIMESTAMPTZ   NOT NULL,
    confirmed_at     TIMESTAMPTZ,
    paid_at          TIMESTAMPTZ,
    delivered_at     TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    cancel_reason    VARCHAR(500),
    updated_at       TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_order_summary_status         ON order_summary (status);
CREATE INDEX idx_order_summary_customer        ON order_summary (customer_id);
CREATE INDEX idx_order_summary_customer_status ON order_summary (customer_id, status);
CREATE INDEX idx_order_summary_created         ON order_summary (created_at DESC);

COMMENT ON TABLE order_summary IS
    'CQRS read model. Rebuilt from event_store on replay. '
    'Never update directly — only OrderProjectionUpdater writes here.';