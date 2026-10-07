package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: ShipmentCreatedEvent
 *
 * Raised when the ShippingActivity creates a shipment with the carrier.
 * The trackingNumber allows the customer to track the package.
 */
data class ShipmentCreatedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val shipmentId: String,
    val trackingNumber: String,
    val carrier: String
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
            @JsonProperty("trackingNumber") trackingNumber: String,
            @JsonProperty("carrier") carrier: String
        ): ShipmentCreatedEvent =
            ShipmentCreatedEvent(eventId, aggregateId, version, occurredAt, shipmentId, trackingNumber, carrier)

        @JvmStatic
        fun create(orderId: OrderId, shipmentId: String, trackingNumber: String, carrier: String, version: Long): ShipmentCreatedEvent =
            ShipmentCreatedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), shipmentId, trackingNumber, carrier)
    }
}
