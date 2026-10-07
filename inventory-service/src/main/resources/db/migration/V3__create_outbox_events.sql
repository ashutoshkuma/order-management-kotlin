-- ============================================================
-- Migration: V3 - Create outbox_events table
-- ============================================================
-- Implements the transactional outbox pattern for Kafka publishing.
-- The publisher writes a row here WITHIN the business transaction,
-- then a @TransactionalEventListener(AFTER_COMMIT) reads it and
-- publishes to Kafka — preventing the dual-write problem.
--
-- published_at IS NULL   → pending, not yet sent to Kafka
-- published_at IS NOT NULL → successfully published
-- ============================================================

CREATE TABLE outbox_events (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      UUID         NOT NULL,
    aggregate_id  VARCHAR(255) NOT NULL,   -- e.g. orderId
    event_type    VARCHAR(200) NOT NULL,   -- e.g. "InventoryReserved"
    topic         VARCHAR(100) NOT NULL,   -- e.g. "inventory.events"
    payload       JSONB        NOT NULL,   -- serialized InventoryEventMessage
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ,             -- null until Kafka ACK

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

-- Fast scan for unpublished events (relay / retry queries)
CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_aggregate
    ON outbox_events (aggregate_id);

COMMENT ON TABLE outbox_events IS
    'Transactional outbox for Kafka events. '
    'Rows are inserted in the same DB transaction as the business operation. '
    'The Kafka publisher reads rows where published_at IS NULL after commit.';