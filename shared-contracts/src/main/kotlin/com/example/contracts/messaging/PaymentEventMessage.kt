package com.example.contracts.messaging

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Messaging envelope for payment-service domain events.
 *
 * Published to topic: payment.events
 * Key: orderId (guarantees per-order ordering within a partition)
 *
 * Consumed by:
 *   - notification-service-group (payment receipts, failure alerts)
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes(
    JsonSubTypes.Type(value = PaymentEventMessage.PaymentChargedMessage::class, name = "PaymentCharged"),
    JsonSubTypes.Type(value = PaymentEventMessage.PaymentFailedMessage::class, name = "PaymentFailed"),
    JsonSubTypes.Type(value = PaymentEventMessage.PaymentRefundedMessage::class, name = "PaymentRefunded"),
)
sealed interface PaymentEventMessage {

    val eventId: UUID
    val orderId: String
    val occurredAt: Instant

    data class PaymentChargedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val transactionId: String,
        val amount: BigDecimal,
        val currency: String,
        override val occurredAt: Instant,
    ) : PaymentEventMessage

    data class PaymentFailedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val reason: String,
        val retryable: Boolean,
        override val occurredAt: Instant,
    ) : PaymentEventMessage

    data class PaymentRefundedMessage(
        override val eventId: UUID,
        override val orderId: String,
        val originalTransactionId: String,
        val refundTransactionId: String,
        val amount: BigDecimal,
        override val occurredAt: Instant,
    ) : PaymentEventMessage
}
