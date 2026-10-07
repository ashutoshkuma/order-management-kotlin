package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.CustomerId
import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: OrderCreatedEvent
 *
 * Raised when a new order is created.
 * This is the "genesis event" for every Order aggregate.
 * Without this event, the aggregate cannot be replayed into existence.
 *
 * Why a data class? Data classes in Kotlin are:
 * - Immutable when declared with `val` (no setters)
 * - Have equals/hashCode based on fields
 * - Have a compact toString
 * Perfect for events which are immutable facts.
 *
 * The @JsonCreator + @JsonProperty annotations allow Jackson to deserialize
 * this data class correctly during event replay from the event store.
 */
data class OrderCreatedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val customerId: CustomerId,
    val shippingAddress: String
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("customerId") customerId: CustomerId,
            @JsonProperty("shippingAddress") shippingAddress: String
        ): OrderCreatedEvent =
            OrderCreatedEvent(eventId, aggregateId, version, occurredAt, customerId, shippingAddress)

        @JvmStatic
        fun create(orderId: OrderId, customerId: CustomerId, shippingAddress: String, version: Long): OrderCreatedEvent =
            OrderCreatedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), customerId, shippingAddress)
    }
}
