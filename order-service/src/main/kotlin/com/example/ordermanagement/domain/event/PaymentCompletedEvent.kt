package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: PaymentCompletedEvent
 *
 * Raised when the PaymentActivity successfully processes payment.
 * Captures the transaction ID from the payment provider for audit/reconciliation.
 */
data class PaymentCompletedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val transactionId: String,
    val amountCharged: Money
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("transactionId") transactionId: String,
            @JsonProperty("amountCharged") amountCharged: Money
        ): PaymentCompletedEvent =
            PaymentCompletedEvent(eventId, aggregateId, version, occurredAt, transactionId, amountCharged)

        @JvmStatic
        fun create(orderId: OrderId, transactionId: String, amount: Money, version: Long): PaymentCompletedEvent =
            PaymentCompletedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), transactionId, amount)
    }
}
