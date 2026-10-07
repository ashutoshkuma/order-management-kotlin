package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.OrderId
import com.example.ordermanagement.domain.valueobject.OrderItem
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: ItemAddedEvent
 *
 * Raised when a product item is added to an order in DRAFT state.
 * The full OrderItem (including price snapshot) is embedded in the event.
 *
 * WHY EMBED THE PRICE?
 * Product prices change over time. By capturing the price at the moment
 * of adding the item, we preserve historical accuracy. This is a key
 * advantage of event sourcing over traditional CRUD — you see EXACTLY
 * what the price was when the customer added the item.
 */
data class ItemAddedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val item: OrderItem
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("item") item: OrderItem
        ): ItemAddedEvent = ItemAddedEvent(eventId, aggregateId, version, occurredAt, item)

        @JvmStatic
        fun create(orderId: OrderId, item: OrderItem, version: Long): ItemAddedEvent =
            ItemAddedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), item)
    }
}
