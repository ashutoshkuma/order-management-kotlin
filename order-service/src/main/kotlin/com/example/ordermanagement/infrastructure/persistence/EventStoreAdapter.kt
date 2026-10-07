package com.example.ordermanagement.infrastructure.persistence

import com.example.ordermanagement.domain.event.DomainEvent
import com.example.ordermanagement.domain.exception.OptimisticLockingException
import com.example.ordermanagement.domain.port.outbound.EventStore
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp

/**
 * Infrastructure Adapter: EventStoreAdapter
 *
 * ═══════════════════════════════════════════════════════════════════
 * RESPONSIBILITY
 * ═══════════════════════════════════════════════════════════════════
 * This is the INFRASTRUCTURE ADAPTER for the EventStore PORT.
 * It provides the PostgreSQL implementation of event storage.
 *
 * Key characteristics:
 *   - APPEND ONLY: no UPDATE or DELETE SQL
 *   - ORDERED: events are always stored and retrieved in version order
 *   - SERIALIZED: events are stored as JSON (JSONB in PostgreSQL)
 *   - IDEMPOTENT: uses unique constraint on (aggregate_id, version) to prevent duplicates
 *
 * ═══════════════════════════════════════════════════════════════════
 * OPTIMISTIC LOCKING IMPLEMENTATION
 * ═══════════════════════════════════════════════════════════════════
 * Before appending events, we check the current version in the DB.
 * If the current version != expectedVersion, another transaction already
 * modified the aggregate. We throw OptimisticLockingException.
 *
 * This check + insert is done in a single DB transaction to be safe.
 * The unique constraint (aggregate_id, version) provides a hard guarantee
 * even if the application-level check is somehow bypassed.
 *
 * ═══════════════════════════════════════════════════════════════════
 * WHY SPRING DATA JDBC (not JPA)?
 * ═══════════════════════════════════════════════════════════════════
 * Event sourcing requires fine-grained control over SQL:
 *   - Explicit append semantics
 *   - Version conflict detection
 *   - No lazy loading, no dirty checking, no proxies
 *
 * Spring Data JDBC gives us a clear, explicit SQL model that matches
 * the append-only event store semantics perfectly.
 *
 * ═══════════════════════════════════════════════════════════════════
 * EVENT SERIALIZATION
 * ═══════════════════════════════════════════════════════════════════
 * Events are serialized to JSON using Jackson.
 * The payload includes the type discriminator (@JsonTypeInfo) so we know
 * which concrete class to deserialize to during replay.
 */
@Repository
class EventStoreAdapter(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : EventStore {

    companion object {
        private val log = LoggerFactory.getLogger(EventStoreAdapter::class.java)

        private val INSERT_EVENT_SQL = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, event_type,
                version, payload, metadata, timestamp
            ) VALUES (
                :eventId, :aggregateId, :aggregateType, :eventType,
                :version, :payload::jsonb, :metadata::jsonb, :timestamp
            )
            """.trimIndent()

        private val LOAD_EVENTS_SQL = """
            SELECT event_id, aggregate_id, aggregate_type, event_type,
                   version, payload, metadata, timestamp
            FROM event_store
            WHERE aggregate_id = :aggregateId
              AND version > :fromVersion
            ORDER BY version ASC
            """.trimIndent()

        private val GET_VERSION_SQL = """
            SELECT COALESCE(MAX(version), 0)
            FROM event_store
            WHERE aggregate_id = :aggregateId
            """.trimIndent()
    }

    /**
     * Appends events to the event store with optimistic locking.
     *
     * OPTIMISTIC LOCKING FLOW:
     * 1. Check current max version for this aggregate
     * 2. Verify it matches expectedVersion
     * 3. Insert all new events atomically
     *
     * The UNIQUE constraint on (aggregate_id, version) provides
     * an additional safety net via DuplicateKeyException.
     */
    override fun appendEvents(aggregateId: String, aggregateType: String, events: List<DomainEvent>, expectedVersion: Long) {
        if (events.isEmpty()) return

        // Optimistic locking check
        val currentVersion = getCurrentVersion(aggregateId)
        if (currentVersion != expectedVersion) {
            throw OptimisticLockingException(
                "Aggregate $aggregateId version conflict: expected=$expectedVersion current=$currentVersion",
            )
        }

        log.debug("Appending {} event(s) to aggregate {} (currentVersion={})", events.size, aggregateId, currentVersion)

        for (event in events) {
            try {
                val payload = objectMapper.writeValueAsString(event)
                val metadata = buildMetadata(event)

                val params = MapSqlParameterSource()
                    .addValue("eventId", event.eventId)
                    .addValue("aggregateId", aggregateId)
                    .addValue("aggregateType", aggregateType)
                    .addValue("eventType", event.eventType())
                    .addValue("version", event.version)
                    .addValue("payload", payload)
                    .addValue("metadata", metadata)
                    .addValue("timestamp", Timestamp.from(event.occurredAt))

                jdbcTemplate.update(INSERT_EVENT_SQL, params)

                log.debug("Stored event {}: type={}, version={}", event.eventId, event.eventType(), event.version)
            } catch (e: DuplicateKeyException) {
                // Another transaction inserted the same version — optimistic lock violation
                throw OptimisticLockingException(
                    "Duplicate event version for aggregate $aggregateId at version ${event.version} " +
                        "(concurrent modification detected)",
                )
            } catch (e: JsonProcessingException) {
                throw RuntimeException("Failed to serialize event: ${event.eventType()}", e)
            }
        }
    }

    override fun loadEvents(aggregateId: String): List<DomainEvent> = loadEvents(aggregateId, 0L)

    /**
     * Loads events for an aggregate starting from a specific version.
     * Used for post-snapshot replay: only load events newer than snapshot.
     */
    override fun loadEvents(aggregateId: String, fromVersion: Long): List<DomainEvent> {
        log.debug("Loading events for aggregate {} from version {}", aggregateId, fromVersion)

        val params = MapSqlParameterSource()
            .addValue("aggregateId", aggregateId)
            .addValue("fromVersion", fromVersion)

        val events = jdbcTemplate.query(LOAD_EVENTS_SQL, params) { rs, rowNum -> mapRowToEvent(rs, rowNum) }

        log.debug("Loaded {} event(s) for aggregate {}", events.size, aggregateId)
        return events
    }

    override fun getCurrentVersion(aggregateId: String): Long {
        val params = MapSqlParameterSource().addValue("aggregateId", aggregateId)

        val version = jdbcTemplate.queryForObject(GET_VERSION_SQL, params, Long::class.java)
        return version ?: 0L
    }

    // ─────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────

    private fun mapRowToEvent(rs: ResultSet, rowNum: Int): DomainEvent {
        try {
            val payload = rs.getString("payload")
            return objectMapper.readValue(payload, DomainEvent::class.java)
        } catch (e: Exception) {
            throw SQLException("Failed to deserialize event at row $rowNum", e)
        }
    }

    private fun buildMetadata(event: DomainEvent): String {
        return try {
            // Include correlation information for tracing
            val metadata = mapOf(
                "eventId" to event.eventId.toString(),
                "aggregateId" to event.aggregateId,
                // In production: add correlationId, causationId from MDC
            )
            objectMapper.writeValueAsString(metadata)
        } catch (e: JsonProcessingException) {
            "{}"
        }
    }
}
