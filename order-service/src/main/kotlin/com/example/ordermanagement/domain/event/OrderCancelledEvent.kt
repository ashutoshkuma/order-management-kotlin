package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: OrderCancelledEvent
 *
 * Terminal event — once appended, the order is in CANCELLED state.
 * This can result from:
 *   1. Customer sending a CancelOrder signal to Temporal
 *   2. Saga compensation after inventory/payment/shipping failure
 *
 * The 'cancellationReason' distinguishes these cases, which is important
 * for metrics (how many orders cancelled by customers vs system failures).
 */
data class OrderCancelledEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val cancellationReason: String,
    val cancelledBy: String // "CUSTOMER" | "SYSTEM_COMPENSATION"
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("cancellationReason") cancellationReason: String,
            @JsonProperty("cancelledBy") cancelledBy: String
        ): OrderCancelledEvent =
            OrderCancelledEvent(eventId, aggregateId, version, occurredAt, cancellationReason, cancelledBy)

        @JvmStatic
        fun create(orderId: OrderId, reason: String, cancelledBy: String, version: Long): OrderCancelledEvent =
            OrderCancelledEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), reason, cancelledBy)
    }
}
