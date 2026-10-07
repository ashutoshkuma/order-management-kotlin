package com.example.ordermanagement.domain.port.outbound

import com.example.ordermanagement.domain.aggregate.Order
import com.example.ordermanagement.domain.valueobject.OrderId
import java.util.Optional

/**
 * Output Port: OrderRepository
 *
 * ═══════════════════════════════════════════════════════════════════
 * THIS IS NOT A CRUD REPOSITORY
 * ═══════════════════════════════════════════════════════════════════
 * Unlike traditional Spring Data repositories, this repository:
 *   - Has NO update method (state is immutable once set by events)
 *   - Has NO delete method (events are never deleted)
 *   - save() APPENDS new events, does not overwrite state
 *   - findById() REPLAYS events to rebuild the aggregate
 *
 * The repository is a facade over EventStore + SnapshotStore.
 * It hides the event sourcing mechanics from the application service.
 *
 * WHY A REPOSITORY AT ALL, NOT JUST EventStore?
 * The repository speaks the language of the domain (Order aggregate).
 * The EventStore speaks the language of infrastructure (events, versions).
 * This separation allows the domain layer to remain infrastructure-agnostic.
 */
interface OrderRepository {

    /**
     * Persists all pending events from the aggregate.
     * Also triggers snapshot creation if threshold is reached.
     * Enforces optimistic locking via expected version.
     */
    fun save(order: Order)

    /**
     * Rebuilds an Order aggregate from its event history.
     * Loads snapshot if available, then replays subsequent events.
     */
    fun findById(orderId: OrderId): Optional<Order>
}
