package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: RefundCompletedEvent
 *
 * Raised during the shipment-failure compensation path.
 * If shipment fails after payment was taken, we must refund the customer.
 * Temporal calls the RefundPayment activity, and on success this event is appended.
 */
data class RefundCompletedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val refundTransactionId: String,
    val amountRefunded: Money
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("refundTransactionId") refundTransactionId: String,
            @JsonProperty("amountRefunded") amountRefunded: Money
        ): RefundCompletedEvent =
            RefundCompletedEvent(eventId, aggregateId, version, occurredAt, refundTransactionId, amountRefunded)

        @JvmStatic
        fun create(orderId: OrderId, refundTxId: String, amount: Money, version: Long): RefundCompletedEvent =
            RefundCompletedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), refundTxId, amount)
    }
}
