package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: OrderConfirmedEvent
 *
 * Raised when the customer confirms the order.
 * At this point:
 * - No more item modifications are allowed
 * - The Temporal OrderFulfillmentWorkflow is started
 * - The total amount is captured as a snapshot
 *
 * WHY CAPTURE TOTAL HERE?
 * While we CAN always recalculate the total by replaying all ItemAdded/Removed events,
 * capturing it at confirmation provides a quick reference without full replay.
 * This is a pragmatic denormalization — the canonical source of truth is still the events.
 */
data class OrderConfirmedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val totalAmount: Money,
    val workflowId: String
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("totalAmount") totalAmount: Money,
            @JsonProperty("workflowId") workflowId: String
        ): OrderConfirmedEvent =
            OrderConfirmedEvent(eventId, aggregateId, version, occurredAt, totalAmount, workflowId)

        @JvmStatic
        fun create(orderId: OrderId, totalAmount: Money, workflowId: String, version: Long): OrderConfirmedEvent =
            OrderConfirmedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), totalAmount, workflowId)
    }
}
