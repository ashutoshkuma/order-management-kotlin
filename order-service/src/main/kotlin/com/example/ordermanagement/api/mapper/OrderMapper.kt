package com.example.ordermanagement.api.mapper

import com.example.ordermanagement.api.dto.response.EventHistoryResponse
import com.example.ordermanagement.api.dto.response.OrderResponse
import com.example.ordermanagement.domain.aggregate.Order
import com.example.ordermanagement.domain.event.DomainEvent
import com.example.ordermanagement.domain.valueobject.OrderItem
import org.springframework.stereotype.Component

/**
 * Mapper: OrderMapper
 *
 * ═══════════════════════════════════════════════════════════════════
 * WHY HAND-WRITTEN INSTEAD OF MAPSTRUCT?
 * ═══════════════════════════════════════════════════════════════════
 * The original Java version was a MapStruct `@Mapper(componentModel =
 * "spring")` interface: MapStruct's annotation processor generated the
 * implementation at compile time from `@Mapping(expression = "java(...)")`
 * strings that called Java-record-style accessors
 * (`order.getId().value()`, `item.unitPrice().amount()`, ...).
 *
 * The MapStruct dependency (and its annotation processor) has been removed
 * from order-service/pom.xml as part of this migration — there is no
 * codegen toolchain left to generate an implementation from those
 * annotations, and MapStruct's generated Java wouldn't type-check against
 * Kotlin `data class` domain types even if it were still wired up. This
 * class does the same field-by-field mapping by hand, using plain Kotlin
 * property access against the now-Kotlin domain model
 * (`Order`, `OrderItem`, `Money`, `OrderId`, `CustomerId`, ...) instead of
 * the old Java-record-accessor expression strings.
 */
@Component
class OrderMapper {

    fun toResponse(order: Order): OrderResponse = OrderResponse(
        orderId = order.id.value,
        customerId = order.customerId.value,
        status = order.status.name,
        items = order.items.map { toItemResponse(it) },
        totalAmount = order.totalAmount.amount,
        currency = order.totalAmount.currencyCode,
        paymentStatus = order.paymentStatus.name,
        shipmentStatus = order.shipmentStatus.name,
        shippingAddress = order.shippingAddress,
        workflowId = order.workflowId,
        trackingNumber = order.trackingNumber,
        version = order.version
    )

    fun toItemResponse(item: OrderItem): OrderResponse.OrderItemResponse = OrderResponse.OrderItemResponse(
        productId = item.productId,
        productName = item.productName,
        quantity = item.quantity,
        unitPrice = item.unitPrice.amount,
        totalPrice = item.totalPrice().amount
    )

    fun toEventHistoryResponse(event: DomainEvent): EventHistoryResponse = EventHistoryResponse(
        eventId = event.eventId,
        aggregateId = event.aggregateId,
        eventType = event.eventType(),
        version = event.version,
        occurredAt = event.occurredAt,
        payload = event // Include full event as payload
    )

    fun toEventHistoryResponses(events: List<DomainEvent>): List<EventHistoryResponse> =
        events.map { toEventHistoryResponse(it) }
}
