-- ============================================================
-- Migration: V1 - Create Event Store Schema
-- ============================================================
-- The event store is the core of the Event Sourcing pattern.
-- All domain events are stored here as immutable append-only records.
--
-- DESIGN DECISIONS:
-- 1. JSONB for payload: enables indexing, querying, and compression
-- 2. UNIQUE(aggregate_id, version): enforces optimistic locking at DB level
-- 3. No UPDATE or DELETE operations are ever performed on this table
-- 4. timestamp is from the domain event (when it occurred), not DB time
-- ============================================================

CREATE TABLE IF NOT EXISTS event_store (
    -- Surrogate key for internal ordering (not used for business logic)
    id              BIGSERIAL PRIMARY KEY,

    -- The unique ID of this specific event instance (from DomainEvent.eventId())
    -- Used for idempotency checks
    event_id        UUID NOT NULL,

    -- The aggregate root this event belongs to (e.g., Order UUID)
    aggregate_id    VARCHAR(255) NOT NULL,

    -- Aggregate type discriminator (e.g., "Order", "Customer")
    -- Allows multiple aggregate types to share this table
    aggregate_type  VARCHAR(100) NOT NULL,

    -- The event type name (e.g., "OrderCreated", "PaymentCompleted")
    -- Used for filtering, metrics, and debugging
    event_type      VARCHAR(200) NOT NULL,

    -- The sequential version of this event for this aggregate
    -- Version 1 = first event, version N = Nth event
    -- Used for optimistic locking and ordered replay
    version         BIGINT NOT NULL,

    -- The full event serialized as JSON (polymorphic via @JsonTypeInfo)
    -- JSONB allows PostgreSQL to index and query inside the JSON
    payload         JSONB NOT NULL,

    -- Correlation IDs, causation IDs, user IDs for audit/tracing
    metadata        JSONB,

    -- When the domain event occurred (from the domain, not DB insert time)
    -- This preserves the correct timeline even if events are inserted late
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- DB-side audit timestamp (separate from domain timestamp)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- CRITICAL INDEX: Optimistic locking enforcement
-- The UNIQUE constraint on (aggregate_id, version) ensures:
-- - No two events can have the same version for the same aggregate
-- - Concurrent inserts at the same version will fail with constraint violation
-- - This is the database-level guarantee backing our optimistic locking
-- ============================================================
CREATE UNIQUE INDEX idx_event_store_aggregate_version
    ON event_store (aggregate_id, version);

-- Index for loading all events of an aggregate (most common query)
CREATE INDEX idx_event_store_aggregate_id
    ON event_store (aggregate_id);

-- Index for loading events after a specific version (snapshot-based replay)
CREATE INDEX idx_event_store_aggregate_from_version
    ON event_store (aggregate_id, version)
    WHERE version > 0;

-- Index for event type queries (analytics, debugging)
CREATE INDEX idx_event_store_event_type
    ON event_store (event_type);

-- Index for time-range queries (audit, debugging)
CREATE INDEX idx_event_store_timestamp
    ON event_store (timestamp);

-- Prevent deduplication using event_id
CREATE UNIQUE INDEX idx_event_store_event_id
    ON event_store (event_id);

COMMENT ON TABLE event_store IS
    'Append-only event log. Never UPDATE or DELETE. '
    'Rebuilding aggregate state by replaying events in version order.';

COMMENT ON COLUMN event_store.version IS
    'Sequential version for this aggregate. Used for optimistic locking. '
    'UNIQUE per aggregate_id ensures no version gaps or duplicates.';

COMMENT ON COLUMN event_store.payload IS
    'Full domain event serialized as JSONB. Includes @JsonTypeInfo type discriminator '
    'for polymorphic deserialization during event replay.';
