package com.example.ordermanagement.infrastructure.persistence

import com.example.ordermanagement.domain.aggregate.OrderSnapshot
import com.example.ordermanagement.domain.port.outbound.SnapshotStore
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.SQLException
import java.sql.Timestamp
import java.util.Optional

/**
 * Infrastructure Adapter: SnapshotStoreAdapter
 *
 * Persists and loads aggregate snapshots.
 * Snapshots are stored in the order_snapshots table as JSON.
 *
 * WHEN TO SNAPSHOT:
 * After every SNAPSHOT_THRESHOLD (50) events are stored for an aggregate.
 * The OrderRepositoryAdapter checks this and calls saveSnapshot() automatically.
 *
 * SNAPSHOT EVOLUTION:
 * As the domain model evolves, old snapshot JSON may be missing new fields.
 * Jackson's DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false (default)
 * handles forward compatibility (new code, old snapshot).
 * Use @JsonProperty(defaultValue = "...") for backward compatibility.
 */
@Repository
class SnapshotStoreAdapter(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : SnapshotStore {

    override fun saveSnapshot(snapshot: OrderSnapshot) {
        try {
            val snapshotData = objectMapper.writeValueAsString(snapshot)

            val params = MapSqlParameterSource()
                .addValue("snapshotId", snapshot.snapshotId)
                .addValue("aggregateId", snapshot.aggregateId)
                .addValue("version", snapshot.version)
                .addValue("snapshotData", snapshotData)
                .addValue("createdAt", Timestamp.from(snapshot.takenAt))

            // Upsert — we only need the latest snapshot
            jdbcTemplate.update(UPSERT_SNAPSHOT_SQL, params)
        } catch (e: JsonProcessingException) {
            throw RuntimeException("Failed to serialize snapshot for aggregate ${snapshot.aggregateId}", e)
        }
    }

    override fun loadLatestSnapshot(aggregateId: String): Optional<OrderSnapshot> {
        val params = MapSqlParameterSource().addValue("aggregateId", aggregateId)

        val snapshots = jdbcTemplate.query(LOAD_LATEST_SNAPSHOT_SQL, params) { rs, _ -> mapRowToSnapshot(rs) }

        return if (snapshots.isEmpty()) Optional.empty() else Optional.of(snapshots.first())
    }

    private fun mapRowToSnapshot(rs: java.sql.ResultSet): OrderSnapshot {
        try {
            return objectMapper.readValue(rs.getString("snapshot_data"), OrderSnapshot::class.java)
        } catch (e: Exception) {
            throw SQLException("Failed to deserialize snapshot", e)
        }
    }

    companion object {
        private val UPSERT_SNAPSHOT_SQL = """
            INSERT INTO order_snapshots (snapshot_id, aggregate_id, version, snapshot_data, created_at)
            VALUES (:snapshotId, :aggregateId, :version, :snapshotData::jsonb, :createdAt)
            ON CONFLICT (aggregate_id)
            DO UPDATE SET
                snapshot_id   = EXCLUDED.snapshot_id,
                version       = EXCLUDED.version,
                snapshot_data = EXCLUDED.snapshot_data,
                created_at    = EXCLUDED.created_at
            """.trimIndent()

        private val LOAD_LATEST_SNAPSHOT_SQL = """
            SELECT snapshot_id, aggregate_id, version, snapshot_data, created_at
            FROM order_snapshots
            WHERE aggregate_id = :aggregateId
            """.trimIndent()
    }
}
