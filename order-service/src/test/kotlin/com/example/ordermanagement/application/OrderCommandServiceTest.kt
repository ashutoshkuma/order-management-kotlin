package com.example.ordermanagement.application

import com.example.ordermanagement.application.service.OrderCommandService
import com.example.ordermanagement.domain.aggregate.Order
import com.example.ordermanagement.domain.command.CancelOrderCommand
import com.example.ordermanagement.domain.command.ConfirmOrderCommand
import com.example.ordermanagement.domain.command.CreateOrderCommand
import com.example.ordermanagement.domain.exception.InvalidStateTransitionException
import com.example.ordermanagement.domain.exception.OrderNotFoundException
import com.example.ordermanagement.domain.model.OrderStatus
import com.example.ordermanagement.domain.port.inbound.OrderCommandUseCase
import com.example.ordermanagement.domain.port.outbound.OrderRepository
import com.example.ordermanagement.domain.port.outbound.WorkflowPort
import com.example.ordermanagement.domain.valueobject.CustomerId
import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderId
import com.example.ordermanagement.domain.valueobject.OrderItem
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Kotlin-safe wrappers around Mockito's `any()`/`eq()` matchers.
 *
 * Plain `org.mockito.ArgumentMatchers.any()` returns `null` (its Java signature
 * is an unchecked generic `<T> T any()`). Calling it directly as an argument to
 * a Kotlin method with a non-null parameter — e.g. `save(order: Order)` — makes
 * the Kotlin compiler insert an `Intrinsics.checkNotNullExpressionValue` guard
 * at the call site, which throws immediately since the underlying value really
 * is null. Because that NPE fires AFTER Mockito has already pushed the matcher
 * onto its internal stack, it also corrupts matcher state for whichever test
 * runs next in the same thread (surfacing as unrelated "InvalidUseOfMatchers" /
 * "UnfinishedVerification" failures). Routing through these local wrappers
 * (whose declared return type is an unbounded, nullable-by-default `T`) avoids
 * the compiler-inserted null check entirely — the classic pre-mockito-kotlin
 * idiom for this problem.
 */
private fun <T> any(): T {
    ArgumentMatchers.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

private fun <T> any(type: Class<T>): T {
    ArgumentMatchers.any(type)
    @Suppress("UNCHECKED_CAST")
    return null as T
}

private fun <T> eq(value: T): T {
    ArgumentMatchers.eq(value)
    return value
}

/**
 * Unit Tests: OrderCommandService
 *
 * Tests the application service in isolation by mocking:
 * - OrderRepository (no DB)
 * - WorkflowPort (no Temporal)
 * - MeterRegistry (no metrics infrastructure)
 *
 * This tests the ORCHESTRATION logic: does the service correctly
 * call the aggregate, save events, and trigger workflows?
 */
@DisplayName("OrderCommandService Tests")
class OrderCommandServiceTest {

    lateinit var repository: OrderRepository
    lateinit var workflowPort: WorkflowPort
    lateinit var meterRegistry: MeterRegistry
    lateinit var service: OrderCommandUseCase
    lateinit var serviceImpl: OrderCommandService // needed for internal methods not on the interface

    companion object {
        val ORDER_ID: OrderId = OrderId.generate()
        val CUSTOMER_ID: CustomerId = CustomerId.generate()
    }

    @BeforeEach
    fun setUp() {
        repository = mock(OrderRepository::class.java)
        workflowPort = mock(WorkflowPort::class.java)
        meterRegistry = SimpleMeterRegistry()
        serviceImpl = OrderCommandService(repository, workflowPort, meterRegistry)
        service = serviceImpl
    }

    @Test
    @DisplayName("createOrder: should create order and save")
    fun shouldCreateOrder() {
        val cmd = CreateOrderCommand(ORDER_ID, CUSTOMER_ID, "Test Address")

        val result = service.createOrder(cmd)

        assertThat(result).isEqualTo(ORDER_ID)
        verify(repository, times(1)).save(any(Order::class.java))
        verify(workflowPort, never()).startFulfillmentWorkflow(any(), anyString())
    }

    @Test
    @DisplayName("confirmOrder: should save confirmation and start workflow")
    fun shouldConfirmOrderAndStartWorkflow() {
        val order = Order.create(ORDER_ID, CUSTOMER_ID, "Test Address")
        order.addItem(OrderItem(UUID.randomUUID(), "Widget", 2, Money.of(10.00)))
        order.drainPendingEvents() // Clear so repo returns clean order

        `when`(repository.findById(ORDER_ID)).thenReturn(Optional.of(order))
        `when`(workflowPort.startFulfillmentWorkflow(eq(ORDER_ID), anyString()))
            .thenReturn("order-fulfillment-$ORDER_ID")

        service.confirmOrder(ConfirmOrderCommand(ORDER_ID))

        verify(repository).save(order)
        verify(workflowPort).startFulfillmentWorkflow(eq(ORDER_ID), anyString())
        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
    }

    @Test
    @DisplayName("cancelOrder: should signal Temporal and NOT save directly when workflow is active")
    fun shouldCancelAndSignalWorkflow() {
        val order = Order.create(ORDER_ID, CUSTOMER_ID, "Test Address")
        order.addItem(OrderItem(UUID.randomUUID(), "Widget", 1, Money.of(5.00)))
        order.confirm("workflow-cancel-test")
        order.drainPendingEvents()

        `when`(repository.findById(ORDER_ID)).thenReturn(Optional.of(order))

        service.cancelOrder(CancelOrderCommand(ORDER_ID, "Changed mind"))

        // Delegate entirely to Temporal — workflow owns compensation and the CANCELLED transition.
        // Writing CANCELLED here while mid-flight would break subsequent activity state transitions.
        verify(workflowPort).sendCancelSignal("workflow-cancel-test", "Changed mind")
        verify(repository, never()).save(any())
        // Order stays CONFIRMED in the domain — Temporal calls recordOrderCancelled later
        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
    }

    @Test
    @DisplayName("cancelOrder: should cancel without signal if no workflow")
    fun shouldCancelWithoutSignalIfNoWorkflow() {
        val order = Order.create(ORDER_ID, CUSTOMER_ID, "Test Address")
        order.drainPendingEvents()

        `when`(repository.findById(ORDER_ID)).thenReturn(Optional.of(order))

        service.cancelOrder(CancelOrderCommand(ORDER_ID, "Draft cancelled"))

        verify(workflowPort, never()).sendCancelSignal(any(), any())
    }

    @Test
    @DisplayName("Should throw OrderNotFoundException for missing order")
    fun shouldThrowForMissingOrder() {
        `when`(repository.findById(any())).thenReturn(Optional.empty())

        assertThatThrownBy { service.confirmOrder(ConfirmOrderCommand(ORDER_ID)) }
            .isInstanceOf(OrderNotFoundException::class.java)
    }

    @Test
    @DisplayName("recordInventoryReserved: already in INVENTORY_RESERVED state is handled")
    fun idempotentInventoryReserved() {
        // The service should handle the state gracefully.
        // If the order is already INVENTORY_RESERVED, the aggregate throws
        // InvalidStateTransitionException which is the expected behavior —
        // it means the event was already applied. Callers (Temporal activities)
        // catch this and treat it as idempotent.
        val order = Order.create(ORDER_ID, CUSTOMER_ID, "Test Address")
        order.addItem(OrderItem(UUID.randomUUID(), "Widget", 1, Money.of(5.00)))
        order.confirm("wf-idem")
        order.reserveInventory("RES-ALREADY") // already INVENTORY_RESERVED
        order.drainPendingEvents()

        `when`(repository.findById(ORDER_ID)).thenReturn(Optional.of(order))

        // Service propagates the domain exception — callers handle idempotency
        assertThatThrownBy { serviceImpl.recordInventoryReserved(ORDER_ID, "RES-ALREADY") }
            .isInstanceOf(InvalidStateTransitionException::class.java)
    }
}
