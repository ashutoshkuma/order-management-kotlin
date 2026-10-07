package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: InventoryReservationFailedEvent
 *
 * Raised when inventory reservation fails after all Temporal retries are exhausted.
 * This event triggers the compensation path in the saga:
 *   → OrderCancelled event follows
 *
 * WHY RECORD FAILURES AS EVENTS?
 * In traditional CRUD, a failure just means "nothing happened".
 * In Event Sourcing, failures are also facts. Knowing THAT a reservation failed,
 * WHEN it failed, and WHY (reason field) is valuable audit information.
 * It also drives state — after this event, the order cannot proceed.
 */
data class InventoryReservationFailedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val reason: String
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("reason") reason: String
        ): InventoryReservationFailedEvent =
            InventoryReservationFailedEvent(eventId, aggregateId, version, occurredAt, reason)

        @JvmStatic
        fun create(orderId: OrderId, reason: String, version: Long): InventoryReservationFailedEvent =
            InventoryReservationFailedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), reason)
    }
}
