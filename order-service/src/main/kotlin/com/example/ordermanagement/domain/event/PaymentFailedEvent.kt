package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: PaymentFailedEvent
 *
 * Raised after all payment retries are exhausted.
 * Triggers compensation: release inventory → cancel order.
 *
 * The 'retryable' flag indicates whether a RetryPayment signal
 * from the customer could restart the payment attempt.
 */
data class PaymentFailedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val reason: String,
    val retryable: Boolean
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("reason") reason: String,
            @JsonProperty("retryable") retryable: Boolean
        ): PaymentFailedEvent = PaymentFailedEvent(eventId, aggregateId, version, occurredAt, reason, retryable)

        @JvmStatic
        fun create(orderId: OrderId, reason: String, retryable: Boolean, version: Long): PaymentFailedEvent =
            PaymentFailedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), reason, retryable)
    }
}
