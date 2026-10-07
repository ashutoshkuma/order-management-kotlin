package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: InventoryReservedEvent
 *
 * Raised by the application service after the InventoryActivity
 * in the Temporal workflow succeeds.
 *
 * TEMPORAL <-> EVENT SOURCING BRIDGE:
 * Temporal orchestrates the reservation, but the domain state change
 * (order moving to INVENTORY_RESERVED) is recorded here as a domain event.
 * This separates orchestration concerns (Temporal) from state concerns (Event Store).
 */
data class InventoryReservedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val reservationId: String
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("reservationId") reservationId: String
        ): InventoryReservedEvent = InventoryReservedEvent(eventId, aggregateId, version, occurredAt, reservationId)

        @JvmStatic
        fun create(orderId: OrderId, reservationId: String, version: Long): InventoryReservedEvent =
            InventoryReservedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), reservationId)
    }
}
