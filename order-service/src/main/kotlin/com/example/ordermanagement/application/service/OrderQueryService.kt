package com.example.ordermanagement.application.service

import com.example.ordermanagement.api.dto.response.OrderSummaryResponse
import com.example.ordermanagement.domain.aggregate.Order
import com.example.ordermanagement.domain.event.DomainEvent
import com.example.ordermanagement.domain.event.InventoryReleasedEvent
import com.example.ordermanagement.domain.event.InventoryReservationFailedEvent
import com.example.ordermanagement.domain.event.InventoryReservedEvent
import com.example.ordermanagement.domain.event.ItemAddedEvent
import com.example.ordermanagement.domain.event.ItemRemovedEvent
import com.example.ordermanagement.domain.event.OrderCancelledEvent
import com.example.ordermanagement.domain.event.OrderConfirmedEvent
import com.example.ordermanagement.domain.event.OrderCreatedEvent
import com.example.ordermanagement.domain.event.PaymentCompletedEvent
import com.example.ordermanagement.domain.event.PaymentFailedEvent
import com.example.ordermanagement.domain.event.RefundCompletedEvent
import com.example.ordermanagement.domain.event.ShipmentCreatedEvent
import com.example.ordermanagement.domain.event.ShipmentDeliveredEvent
import com.example.ordermanagement.domain.exception.OrderNotFoundException
import com.example.ordermanagement.domain.port.inbound.OrderQueryUseCase
import com.example.ordermanagement.domain.port.outbound.EventStore
import com.example.ordermanagement.domain.port.outbound.OrderRepository
import com.example.ordermanagement.domain.port.outbound.WorkflowPort
import com.example.ordermanagement.domain.valueobject.OrderId
import com.example.ordermanagement.infrastructure.projection.OrderSummaryRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application Service: OrderQueryService
 *
 * ═══════════════════════════════════════════════════════════════════
 * CQRS — QUERY SIDE
 * ═══════════════════════════════════════════════════════════════════
 * In CQRS, we separate read models from write models.
 * The write side (OrderCommandService) uses the domain aggregate.
 * The read side (this service) can use optimized read models.
 *
 * For this PoC, we read from the event store directly for simplicity.
 * In production, you'd maintain a separate READ model (a view table or
 * a projection stored in a read-optimized store) that's updated by
 * listening to the event stream.
 *
 * QUERY SIDE IS READ-ONLY:
 * No @Transactional(readOnly = false), no state changes.
 * Queries cannot fail the system — they only read.
 *
 * INTERACTION WITH TEMPORAL:
 * For workflow queries, we call the WorkflowPort which queries Temporal's
 * in-memory workflow state. This is fast and doesn't need event replay.
 */
@Service
class OrderQueryService(
    private val orderRepository: OrderRepository,
    private val eventStore: EventStore,
    private val workflowPort: WorkflowPort,
    private val summaryRepository: OrderSummaryRepository
) : OrderQueryUseCase {

    /**
     * Returns the current state of an order by replaying its events.
     * Loads snapshot if available for performance.
     */
    @Transactional(readOnly = true)
    override fun getOrder(orderId: OrderId): Order =
        orderRepository.findById(orderId)
            .orElseThrow { OrderNotFoundException(orderId.toString()) }

    /**
     * Returns the complete event history of an order.
     * This is the "audit log" feature of event sourcing.
     *
     * Why is this useful?
     * Traditional CRUD: "The order is CANCELLED" — you have no idea why.
     * Event Sourcing: See every OrderCreated, ItemAdded, PaymentFailed,
     *   InventoryReserved, OrderCancelled event with timestamps and reasons.
     */
    @Transactional(readOnly = true)
    override fun getOrderHistory(orderId: OrderId): List<DomainEvent> {
        if (!eventStore.exists(orderId.toString())) {
            throw OrderNotFoundException(orderId.toString())
        }
        return eventStore.loadEvents(orderId.toString())
    }

    /**
     * Returns a timeline view of order events for display purposes.
     * Same data as getOrderHistory but structured for human-readable output.
     */
    @Transactional(readOnly = true)
    override fun getOrderTimeline(orderId: OrderId): List<OrderQueryUseCase.TimelineEntry> =
        getOrderHistory(orderId).map { event ->
            OrderQueryUseCase.TimelineEntry(
                event.version,
                event.eventType(),
                event.occurredAt.toString(),
                describeEvent(event)
            )
        }

    /**
     * Queries the Temporal workflow for its current execution status.
     * This reads Temporal's internal state, not the event store.
     *
     * TEMPORAL QUERY vs EVENT STORE QUERY:
     * - Temporal query: reflects current workflow execution point (fast, real-time)
     * - Event store query: reflects persisted domain events (authoritative source)
     * Both are valid and complementary.
     */
    override fun getWorkflowStatus(workflowId: String): WorkflowPort.WorkflowStatusResult =
        workflowPort.queryWorkflowStatus(workflowId)

    @Transactional(readOnly = true)
    override fun listOrders(statuses: List<String>, customerId: String, pageable: Pageable): Page<OrderSummaryResponse> {
        // OrderQueryUseCase declares these non-null; empty list / blank string
        // represent "no filter" and are translated to null for the repository.
        val statusFilter = statuses.ifEmpty { null }
        val customerFilter = customerId.ifBlank { null }
        return summaryRepository.findAll(statusFilter, customerFilter, pageable)
    }

    private fun describeEvent(event: DomainEvent): String = when (event) {
        is OrderCreatedEvent -> "Order created for customer ${event.customerId}"
        is ItemAddedEvent -> "Item added: ${event.item.productName} x${event.item.quantity}"
        is ItemRemovedEvent -> "Item removed: productId=${event.productId}"
        is OrderConfirmedEvent -> "Order confirmed. Total: ${event.totalAmount}. Workflow: ${event.workflowId}"
        is InventoryReservedEvent -> "Inventory reserved. ReservationId: ${event.reservationId}"
        is InventoryReservationFailedEvent -> "Inventory reservation FAILED: ${event.reason}"
        is InventoryReleasedEvent -> "Inventory released (compensation). Reason: ${event.reason}"
        is PaymentCompletedEvent -> "Payment completed. Transaction: ${event.transactionId} Amount: ${event.amountCharged}"
        is PaymentFailedEvent -> "Payment FAILED: ${event.reason}" +
            if (event.retryable) " (retryable)" else " (not retryable)"
        is RefundCompletedEvent -> "Refund completed. Amount: ${event.amountRefunded}"
        is ShipmentCreatedEvent -> "Shipment created. Tracking: ${event.trackingNumber} via ${event.carrier}"
        is ShipmentDeliveredEvent -> "Order delivered at ${event.deliveredAt}"
        is OrderCancelledEvent -> "Order CANCELLED by ${event.cancelledBy}: ${event.cancellationReason}"
    }
}
