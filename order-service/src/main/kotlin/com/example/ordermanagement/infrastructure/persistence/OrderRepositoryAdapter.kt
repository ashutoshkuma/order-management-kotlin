package com.example.ordermanagement.infrastructure.persistence

import com.example.ordermanagement.domain.aggregate.Order
import com.example.ordermanagement.domain.aggregate.OrderSnapshot
import com.example.ordermanagement.domain.port.outbound.EventStore
import com.example.ordermanagement.domain.port.outbound.OrderRepository
import com.example.ordermanagement.domain.port.outbound.SnapshotStore
import com.example.ordermanagement.domain.valueobject.OrderId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * OrderRepositoryAdapter
 *
 * CHANGE FROM MONOLITH:
 * Added ApplicationEventPublisher to publish domain events as Spring
 * application events AFTER they are persisted to the event store.
 *
 * The OrderEventPublisher listens with @TransactionalEventListener(AFTER_COMMIT)
 * and publishes to Solace only after the DB transaction commits.
 *
 * This implements the OUTBOX PATTERN without a separate outbox table:
 *   persist -> spring event -> AFTER_COMMIT -> solace publish
 */
@Repository
class OrderRepositoryAdapter(
    private val eventStore: EventStore,
    private val snapshotStore: SnapshotStore,
    private val applicationEventPublisher: ApplicationEventPublisher,
) : OrderRepository {

    companion object {
        private const val AGGREGATE_TYPE = "Order"
    }

    override fun save(order: Order) {
        val pendingEvents = order.drainPendingEvents()
        if (pendingEvents.isEmpty()) return

        val expectedVersion = order.version - pendingEvents.size

        eventStore.appendEvents(order.id.toString(), AGGREGATE_TYPE, pendingEvents, expectedVersion)

        // Snapshot check
        if (order.version % OrderSnapshot.SNAPSHOT_THRESHOLD == 0L) {
            snapshotStore.saveSnapshot(order.toSnapshot())
        }

        // Publish each event as a Spring application event.
        // OrderEventPublisher picks these up AFTER_COMMIT.
        pendingEvents.forEach { applicationEventPublisher.publishEvent(it) }
    }

    override fun findById(orderId: OrderId): Optional<Order> {
        val id = orderId.toString()
        if (!eventStore.exists(id)) return Optional.empty()

        val snapshotOpt = snapshotStore.loadLatestSnapshot(id)
        val order: Order = if (snapshotOpt.isPresent) {
            val snapshot = snapshotOpt.get()
            val recent = eventStore.loadEvents(id, snapshot.version)
            Order.reconstituteFromSnapshot(snapshot, recent)
        } else {
            Order.reconstitute(eventStore.loadEvents(id))
        }
        return Optional.of(order)
    }
}
