package com.example.contracts.messaging

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.util.UUID

/**
 * Messaging envelope for shipping-service domain events.
 *
 * Published to topic: shipping.events
 * Key: orderId (guarantees per-order ordering within a partition)
 *
 * Consumed by:
 *   - notification-service-group (shipment tracking, delivery confirmation)
 *   - order-service-group        (delivery confirmation -> closes the saga)
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes(
    JsonSubTypes.Type(value = ShippingEventMessage.ShipmentCreatedMessage::class, name = "ShipmentCreated"),
    JsonSubTypes.Type(value = ShippingEventMessage.ShipmentDeliveredMessage::class, name = "ShipmentDelivered"),
)
sealed interface ShippingEventMessage {

    val eventId: UUID
    val orderId: String
    val occurredAt: Instant

    data class ShipmentCreatedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val shipmentId: String,
        val trackingNumber: String,
        val carrier: String,
        override val occurredAt: Instant,
    ) : ShippingEventMessage

    data class ShipmentDeliveredMessage(
        override val eventId: UUID,
        override val orderId: String,
        val shipmentId: String,
        val deliveredAt: Instant,
        override val occurredAt: Instant,
    ) : ShippingEventMessage
}
