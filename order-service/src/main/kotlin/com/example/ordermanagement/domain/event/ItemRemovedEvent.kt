package com.example.ordermanagement.domain.event

import com.example.ordermanagement.domain.valueobject.OrderId
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Event: ItemRemovedEvent
 *
 * Raised when an item is removed from a DRAFT order.
 * We only store the productId (not the full item) — the aggregate
 * uses this to locate and remove the item from its list.
 */
data class ItemRemovedEvent(
    override val eventId: UUID,
    override val aggregateId: String,
    override val version: Long,
    override val occurredAt: Instant,
    val productId: UUID
) : DomainEvent {

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("eventId") eventId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("occurredAt") occurredAt: Instant,
            @JsonProperty("productId") productId: UUID
        ): ItemRemovedEvent = ItemRemovedEvent(eventId, aggregateId, version, occurredAt, productId)

        @JvmStatic
        fun create(orderId: OrderId, productId: UUID, version: Long): ItemRemovedEvent =
            ItemRemovedEvent(UUID.randomUUID(), orderId.toString(), version, Instant.now(), productId)
    }
}
