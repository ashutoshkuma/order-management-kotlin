-- ============================================================
-- Migration: V3 - Create outbox_events table
-- ============================================================
-- Transactional outbox for Kafka publishing.
-- Rows are inserted within the same DB transaction as charge/refund.
-- A @TransactionalEventListener(AFTER_COMMIT) publishes to payment.events.
-- ============================================================

CREATE TABLE outbox_events (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      UUID         NOT NULL,
    aggregate_id  VARCHAR(255) NOT NULL,   -- orderId
    event_type    VARCHAR(200) NOT NULL,   -- "PaymentCharged" | "PaymentFailed" | "PaymentRefunded"
    topic         VARCHAR(100) NOT NULL,   -- "payment.events"
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id);