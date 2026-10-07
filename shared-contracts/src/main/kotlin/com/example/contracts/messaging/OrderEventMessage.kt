package com.example.contracts.messaging

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.util.UUID

/**
 * Messaging envelope for all order-related domain events.
 *
 * Published to topic: order.events
 * Key: orderId (guarantees ordering per order within a partition)
 *
 * WHY AN ENVELOPE?
 * We wrap the domain event payload inside this message so that
 * downstream consumers (notification-service etc.) can read the
 * type discriminator without needing to know the full domain model.
 *
 * The 'payload' field contains the JSON of the specific event.
 * Consumers deserialize based on 'eventType'.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes(
    JsonSubTypes.Type(value = OrderEventMessage.OrderCreatedMessage::class, name = "OrderCreated"),
    JsonSubTypes.Type(value = OrderEventMessage.OrderConfirmedMessage::class, name = "OrderConfirmed"),
    JsonSubTypes.Type(value = OrderEventMessage.OrderCancelledMessage::class, name = "OrderCancelled"),
    JsonSubTypes.Type(value = OrderEventMessage.PaymentCompletedMessage::class, name = "PaymentCompleted"),
    JsonSubTypes.Type(value = OrderEventMessage.PaymentFailedMessage::class, name = "PaymentFailed"),
    JsonSubTypes.Type(value = OrderEventMessage.ShipmentCreatedMessage::class, name = "ShipmentCreated"),
    JsonSubTypes.Type(value = OrderEventMessage.ShipmentDeliveredMessage::class, name = "ShipmentDelivered"),
    JsonSubTypes.Type(value = OrderEventMessage.InventoryReservedMessage::class, name = "InventoryReserved"),
    JsonSubTypes.Type(value = OrderEventMessage.InventoryReleasedMessage::class, name = "InventoryReleased"),
    JsonSubTypes.Type(value = OrderEventMessage.ItemAddedMessage::class, name = "ItemAdded"),
    JsonSubTypes.Type(value = OrderEventMessage.ItemRemovedMessage::class, name = "ItemRemoved"),
    JsonSubTypes.Type(value = OrderEventMessage.RefundCompletedMessage::class, name = "RefundCompleted"),
)
sealed interface OrderEventMessage {

    val eventId: UUID
    val orderId: String
    val occurredAt: Instant

    data class OrderCreatedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val customerId: String,
        val shippingAddress: String,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class OrderConfirmedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val customerId: String,
        val totalAmount: Double,
        val workflowId: String,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class OrderCancelledMessage(
        override val eventId: UUID,
        override val orderId: String,
        val reason: String,
        val cancelledBy: String,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class PaymentCompletedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val transactionId: String,
        val amount: Double,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class PaymentFailedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val reason: String,
        val retryable: Boolean,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class ShipmentCreatedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val shipmentId: String,
        val trackingNumber: String,
        val carrier: String,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class ShipmentDeliveredMessage(
        override val eventId: UUID,
        override val orderId: String,
        val shipmentId: String,
        val deliveredAt: Instant,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class InventoryReservedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val reservationId: String,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class InventoryReleasedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val reservationId: String,
        val reason: String,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class ItemAddedMessage(
        override val eventId: UUID,
        override val orderId: String,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class ItemRemovedMessage(
        override val eventId: UUID,
        override val orderId: String,
        override val occurredAt: Instant,
    ) : OrderEventMessage

    data class RefundCompletedMessage(
        override val eventId: UUID,
        override val orderId: String,
        override val occurredAt: Instant,
    ) : OrderEventMessage
}
