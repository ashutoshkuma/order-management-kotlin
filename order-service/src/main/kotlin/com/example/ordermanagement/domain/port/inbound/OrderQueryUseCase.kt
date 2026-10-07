package com.example.ordermanagement.domain.port.inbound

import com.example.ordermanagement.api.dto.response.OrderSummaryResponse
import com.example.ordermanagement.domain.aggregate.Order
import com.example.ordermanagement.domain.event.DomainEvent
import com.example.ordermanagement.domain.port.outbound.WorkflowPort
import com.example.ordermanagement.domain.valueobject.OrderId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/**
 * Inbound Port: OrderQueryUseCase
 *
 * Defines all read operations available on orders.
 * Query side of CQRS — read-only, no state changes.
 * Controllers depend on this interface, not on OrderQueryService directly.
 */
interface OrderQueryUseCase {

    fun getOrder(orderId: OrderId): Order

    fun getOrderHistory(orderId: OrderId): List<DomainEvent>

    fun getOrderTimeline(orderId: OrderId): List<TimelineEntry>

    fun getWorkflowStatus(workflowId: String): WorkflowPort.WorkflowStatusResult

    fun listOrders(statuses: List<String>, customerId: String, pageable: Pageable): Page<OrderSummaryResponse>

    data class TimelineEntry(
        val version: Long,
        val eventType: String,
        val occurredAt: String,
        val description: String
    )
}
