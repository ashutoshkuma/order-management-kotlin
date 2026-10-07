package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: ShipmentDeliveredEvent
 *
 * Raised when delivery is confirmed.
 * In a real system this could come from a webhook callback from the carrier.
 * Here the Temporal ShippingActivity simulates it.
 */
data class ShipmentDeliveredEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val shipmentId: String,
    val deliveredAt: Instant
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("shipmentId") shipmentId: String,
            @JsonProperty("deliveredAt") deliveredAt: Instant
        ): ShipmentDeliveredEvent =
            ShipmentDeliveredEvent(eventId, aggregateId, version, occurredAt, shipmentId, deliveredAt)

        @JvmStatic
        fun create(orderId: OrderId, shipmentId: String, version: Long): ShipmentDeliveredEvent =
            ShipmentDeliveredEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), shipmentId, Instant.now())
    }
}
