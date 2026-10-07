-- ============================================================
-- Migration: V2 - Create Snapshot Store
-- ============================================================
-- Snapshots are a performance optimization for event sourcing.
-- Instead of replaying ALL events every time, we:
--   1. Periodically save the aggregate's current state as a snapshot
--   2. On load: restore from snapshot, then replay only recent events
--
-- SNAPSHOT vs EVENT STORE:
-- - Events: immutable facts, append-only, never deleted
-- - Snapshots: expendable cache, can be deleted and regenerated from events
--
-- We only keep ONE snapshot per aggregate (the most recent).
-- The UPSERT in SnapshotStoreAdapter handles this automatically.
-- ============================================================

CREATE TABLE IF NOT EXISTS order_snapshots (
    -- The snapshot's own UUID
    snapshot_id     UUID PRIMARY KEY,

    -- The aggregate this snapshot belongs to
    -- One snapshot per aggregate (enforced by UNIQUE constraint below)
    aggregate_id    VARCHAR(255) NOT NULL,

    -- The aggregate version at the time this snapshot was taken
    -- Events with version > this value need to be replayed after loading snapshot
    version         BIGINT NOT NULL,

    -- Full aggregate state as JSON
    -- This is everything needed to reconstruct the Order object
    snapshot_data   JSONB NOT NULL,

    -- When this snapshot was created
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One snapshot per aggregate — UPSERT replaces on conflict
CREATE UNIQUE INDEX idx_snapshots_aggregate_id
    ON order_snapshots (aggregate_id);

COMMENT ON TABLE order_snapshots IS
    'Aggregate state snapshots for performance optimization. '
    'One snapshot per aggregate (the most recent). '
    'Load snapshot then replay events with version > snapshot.version.';

COMMENT ON COLUMN order_snapshots.version IS
    'The aggregate version at snapshot time. '
    'Load events WHERE version > this value to complete state reconstruction.';
