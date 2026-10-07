package com.example.ordermanagement.domain.port.outbound

import com.example.ordermanagement.domain.event.DomainEvent

/**
 * Output Port: EventStore
 *
 * ═══════════════════════════════════════════════════════════════════
 * HEXAGONAL ARCHITECTURE — PORT EXPLAINED
 * ═══════════════════════════════════════════════════════════════════
 * In Hexagonal Architecture, the domain defines PORTS (interfaces).
 * The infrastructure provides ADAPTERS (implementations).
 *
 * The domain does NOT depend on PostgreSQL, JDBC, or any framework.
 * It only knows about this interface.
 *
 * This allows:
 *   - Testing with an in-memory event store
 *   - Swapping PostgreSQL for EventStoreDB without changing domain code
 *   - Clean separation of concerns
 *
 * ═══════════════════════════════════════════════════════════════════
 * EVENT STORE CONTRACT
 * ═══════════════════════════════════════════════════════════════════
 * The event store is APPEND-ONLY:
 *   - appendEvents: adds new events (never updates)
 *   - loadEvents: returns events in version order (ascending)
 *
 * Optimistic locking is enforced by expectedVersion:
 *   - The store verifies the latest version matches expectedVersion
 *   - If not, OptimisticLockingException is thrown
 */
interface EventStore {

    /**
     * Appends events to the event store for a given aggregate.
     *
     * @param aggregateId    The aggregate root ID
     * @param aggregateType  The aggregate type name (e.g., "Order")
     * @param events         The new events to append
     * @param expectedVersion The version the caller believes the aggregate is at.
     *                       Used for optimistic locking. Pass 0 for new aggregates.
     */
    fun appendEvents(aggregateId: String, aggregateType: String, events: List<DomainEvent>, expectedVersion: Long)

    /**
     * Loads all events for an aggregate in version order.
     *
     * @param aggregateId The aggregate root ID
     * @return Ordered list of events from version 1 onwards
     */
    fun loadEvents(aggregateId: String): List<DomainEvent>

    /**
     * Loads events for an aggregate starting from a specific version.
     * Used when loading after a snapshot to replay only recent events.
     *
     * @param aggregateId  The aggregate root ID
     * @param fromVersion  Load events with version > fromVersion
     */
    fun loadEvents(aggregateId: String, fromVersion: Long): List<DomainEvent>

    /**
     * Returns the current version of an aggregate.
     * Returns 0 if the aggregate does not exist.
     */
    fun getCurrentVersion(aggregateId: String): Long

    /**
     * Checks if any events exist for this aggregate ID.
     * More efficient than loadEvents() when you just need existence check.
     */
    fun exists(aggregateId: String): Boolean = getCurrentVersion(aggregateId) > 0
}
