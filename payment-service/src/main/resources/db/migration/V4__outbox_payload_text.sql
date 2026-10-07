-- Spring Data JDBC maps String to VARCHAR, not JSONB.
-- TEXT is equivalent for storage; we don't query into the JSON structure.
ALTER TABLE outbox_events ALTER COLUMN payload TYPE TEXT;
