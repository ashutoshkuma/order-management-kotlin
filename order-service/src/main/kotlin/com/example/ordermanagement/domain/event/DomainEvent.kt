package com.example.ordermanagement.domain.event

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.util.UUID

/**
 * Sealed Interface: DomainEvent
 *
 * The base contract for ALL domain events in the system.
 *
 * WHY SEALED + PATTERN MATCHING?
 * A Kotlin sealed interface allows the compiler to enforce that all event types
 * are handled in `when` expressions. If you add a new event type and forget to
 * handle it in the aggregate's apply() method, the compiler will fail the build
 * (non-exhaustive `when`). This prevents silent state corruption — a critical
 * guarantee in Event Sourcing.
 *
 * WHY IMMUTABLE?
 * Events represent facts — "OrderCreated at 14:30:00". Facts cannot be changed.
 * Immutability (enforced by data classes) prevents accidental modification of history.
 *
 * WHY JSON ANNOTATIONS?
 * Events are serialized to the event_store table as JSON.
 * When replaying, we deserialize back to the correct type.
 * @JsonTypeInfo/@JsonSubTypes provide polymorphic deserialization.
 *
 * INTERACTION WITH EVENT SOURCING:
 * - Events are appended to the event store
 * - The aggregate is rebuilt by replaying events in order
 * - Events drive state transitions, NOT direct field mutations
 *
 * INTERACTION WITH TEMPORAL:
 * - After Temporal completes an activity, the result is reflected as a domain event
 * - Temporal manages the PROCESS, events manage the STATE
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OrderCreatedEvent::class, name = "OrderCreated"),
    JsonSubTypes.Type(value = ItemAddedEvent::class, name = "ItemAdded"),
    JsonSubTypes.Type(value = ItemRemovedEvent::class, name = "ItemRemoved"),
    JsonSubTypes.Type(value = OrderConfirmedEvent::class, name = "OrderConfirmed"),
    JsonSubTypes.Type(value = InventoryReservedEvent::class, name = "InventoryReserved"),
    JsonSubTypes.Type(value = InventoryReservationFailedEvent::class, name = "InventoryReservationFailed"),
    JsonSubTypes.Type(value = InventoryReleasedEvent::class, name = "InventoryReleased"),
    JsonSubTypes.Type(value = PaymentCompletedEvent::class, name = "PaymentCompleted"),
    JsonSubTypes.Type(value = PaymentFailedEvent::class, name = "PaymentFailed"),
    JsonSubTypes.Type(value = RefundCompletedEvent::class, name = "RefundCompleted"),
    JsonSubTypes.Type(value = ShipmentCreatedEvent::class, name = "ShipmentCreated"),
    JsonSubTypes.Type(value = ShipmentDeliveredEvent::class, name = "ShipmentDelivered"),
    JsonSubTypes.Type(value = OrderCancelledEvent::class, name = "OrderCancelled")
)
sealed interface DomainEvent {

    /** Unique ID for this specific event instance. Used for idempotency. */
    val eventId: UUID

    /** The aggregate this event belongs to */
    val aggregateId: String

    /** The version of the aggregate AFTER this event is applied */
    val version: Long

    /** When this event occurred */
    val occurredAt: Instant

    /** Human-readable event type name for querying and debugging */
    fun eventType(): String = this.javaClass.simpleName
}
