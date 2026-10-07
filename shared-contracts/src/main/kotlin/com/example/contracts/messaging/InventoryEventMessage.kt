package com.example.contracts.messaging

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.util.UUID

/**
 * Messaging envelope for inventory-service domain events.
 *
 * Published to topic: inventory.events
 * Key: orderId (guarantees per-order ordering within a partition)
 *
 * Consumed by:
 *   - notification-service-group (alerts on reservation failure)
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes(
    JsonSubTypes.Type(value = InventoryEventMessage.InventoryReservedMessage::class, name = "InventoryReserved"),
    JsonSubTypes.Type(value = InventoryEventMessage.InventoryReservationFailedMessage::class, name = "InventoryReservationFailed"),
    JsonSubTypes.Type(value = InventoryEventMessage.InventoryReleasedMessage::class, name = "InventoryReleased"),
)
sealed interface InventoryEventMessage {

    val eventId: UUID
    val orderId: String
    val occurredAt: Instant

    data class InventoryReservedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val reservationId: String,
        override val occurredAt: Instant,
    ) : InventoryEventMessage

    data class InventoryReservationFailedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val reason: String,
        override val occurredAt: Instant,
    ) : InventoryEventMessage

    data class InventoryReleasedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val reservationId: String,
        val reason: String,
        override val occurredAt: Instant,
    ) : InventoryEventMessage
}
