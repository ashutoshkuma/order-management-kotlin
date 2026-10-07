package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: InventoryReleasedEvent
 *
 * Raised during compensation when previously reserved inventory is released.
 * This is the "undo" step in the Saga pattern.
 *
 * SAGA COMPENSATION:
 * If payment fails after inventory was reserved, we must release the inventory.
 * Temporal calls the ReleaseInventory activity, and on success this event is appended.
 * The order then proceeds to CANCELLED state.
 */
data class InventoryReleasedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val reservationId: String?,
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
            @JsonProperty("reservationId") reservationId: String?,
            @JsonProperty("reason") reason: String
        ): InventoryReleasedEvent =
            InventoryReleasedEvent(eventId, aggregateId, version, occurredAt, reservationId, reason)

        @JvmStatic
        fun create(orderId: OrderId, reservationId: String?, reason: String, version: Long): InventoryReleasedEvent =
            InventoryReleasedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), reservationId, reason)
    }
}
