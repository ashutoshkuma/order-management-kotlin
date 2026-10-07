package com.example.ordermanagement.domain

import com.example.ordermanagement.domain.aggregate.Order
import com.example.ordermanagement.domain.aggregate.OrderSnapshot
import com.example.ordermanagement.domain.event.DomainEvent
import com.example.ordermanagement.domain.event.InventoryReleasedEvent
import com.example.ordermanagement.domain.event.InventoryReservedEvent
import com.example.ordermanagement.domain.event.ItemAddedEvent
import com.example.ordermanagement.domain.event.OrderCreatedEvent
import com.example.ordermanagement.domain.event.RefundCompletedEvent
import com.example.ordermanagement.domain.exception.DomainException
import com.example.ordermanagement.domain.exception.InvalidStateTransitionException
import com.example.ordermanagement.domain.model.OrderStatus
import com.example.ordermanagement.domain.model.PaymentStatus
import com.example.ordermanagement.domain.valueobject.CustomerId
import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderId
import com.example.ordermanagement.domain.valueobject.OrderItem
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * Unit Tests: OrderAggregate
 *
 * Tests the core domain aggregate in complete isolation.
 * No Spring context, no DB, no Temporal — pure domain logic.
 *
 * TESTING STRATEGY:
 * 1. Command handling tests: verify correct events are raised
 * 2. State reconstruction tests: verify replay works correctly
 * 3. Business rule tests: verify invalid operations are rejected
 * 4. Snapshot tests: verify snapshot round-trip works
 * 5. Optimistic locking tests: verify version conflict detection
 *
 * These tests run extremely fast (<1 second) because there's no infrastructure.
 */
@DisplayName("Order Aggregate Tests")
class OrderAggregateTest {

    companion object {
        val ORDER_ID: OrderId = OrderId.generate()
        val CUSTOMER_ID: CustomerId = CustomerId.generate()
        const val ADDRESS = "123 Test Street, Test City, TC 12345"
    }

    @Nested
    @DisplayName("Order Creation")
    inner class Creation {

        @Test
        @DisplayName("Should create order with DRAFT status")
        fun shouldCreateOrderInDraftStatus() {
            val order = Order.create(ORDER_ID, CUSTOMER_ID, ADDRESS)

            assertThat(order.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(order.id).isEqualTo(ORDER_ID)
            assertThat(order.customerId).isEqualTo(CUSTOMER_ID)
            assertThat(order.items).isEmpty()
            assertThat(order.version).isEqualTo(1L)
        }

        @Test
        @DisplayName("Should raise OrderCreatedEvent on creation")
        fun shouldRaiseOrderCreatedEvent() {
            val order = Order.create(ORDER_ID, CUSTOMER_ID, ADDRESS)
            val events = order.drainPendingEvents()

            assertThat(events).hasSize(1)
            assertThat(events.first()).isInstanceOf(OrderCreatedEvent::class.java)

            val event = events.first() as OrderCreatedEvent
            assertThat(event.aggregateId).isEqualTo(ORDER_ID.toString())
            assertThat(event.customerId).isEqualTo(CUSTOMER_ID)
            assertThat(event.version).isEqualTo(1L)
        }

        @Test
        @DisplayName("Should reject empty shipping address")
        fun shouldRejectEmptyAddress() {
            assertThatThrownBy { Order.create(ORDER_ID, CUSTOMER_ID, "") }
                .isInstanceOf(DomainException::class.java)
                .hasMessageContaining("Shipping address is required")
        }
    }

    @Nested
    @DisplayName("Item Management")
    inner class ItemManagement {

        @Test
        @DisplayName("Should add item to DRAFT order")
        fun shouldAddItemToDraftOrder() {
            val order = createDraftOrder()
            order.drainPendingEvents() // Clear creation event

            val item = createItem("Widget", 2, 10.00)
            order.addItem(item)

            assertThat(order.items).hasSize(1)
            assertThat(order.totalAmount).isEqualTo(Money.of(20.00))

            val events = order.drainPendingEvents()
            assertThat(events.filter { it is ItemAddedEvent }).hasSize(1)
        }

        @Test
        @DisplayName("Should merge duplicate products by increasing quantity")
        fun shouldMergeDuplicateProducts() {
            val order = createDraftOrder()
            val productId = UUID.randomUUID()

            order.addItem(OrderItem(productId, "Widget", 2, Money.of(10.00)))
            order.addItem(OrderItem(productId, "Widget", 3, Money.of(10.00)))
            order.drainPendingEvents()

            // After merge, should have 1 item with quantity 5
            assertThat(order.items).hasSize(1)
            assertThat(order.items.first().quantity).isEqualTo(5)
            assertThat(order.totalAmount).isEqualTo(Money.of(50.00))
        }

        @Test
        @DisplayName("Should reject adding items to CONFIRMED order")
        fun shouldRejectAddingItemsToConfirmedOrder() {
            val order = createConfirmedOrder()

            assertThatThrownBy { order.addItem(createItem("Widget", 1, 10.00)) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        @DisplayName("Should remove item from DRAFT order")
        fun shouldRemoveItemFromDraftOrder() {
            val order = createDraftOrder()
            val productId = UUID.randomUUID()
            order.addItem(OrderItem(productId, "Widget", 2, Money.of(10.00)))
            order.drainPendingEvents()

            order.removeItem(productId)

            assertThat(order.items).isEmpty()
            assertThat(order.totalAmount).isEqualTo(Money.ZERO)
        }

        @Test
        @DisplayName("Should reject removing non-existent item")
        fun shouldRejectRemovingNonExistentItem() {
            val order = createDraftOrder()

            assertThatThrownBy { order.removeItem(UUID.randomUUID()) }
                .isInstanceOf(DomainException::class.java)
                .hasMessageContaining("not found in order")
        }
    }

    @Nested
    @DisplayName("Order Confirmation")
    inner class Confirmation {

        @Test
        @DisplayName("Should confirm order and update status")
        fun shouldConfirmOrder() {
            val order = createDraftOrder()
            order.addItem(createItem("Widget", 2, 10.00))
            order.drainPendingEvents()

            order.confirm("workflow-123")

            assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(order.workflowId).isEqualTo("workflow-123")
        }

        @Test
        @DisplayName("Should reject confirming empty order")
        fun shouldRejectConfirmingEmptyOrder() {
            val order = createDraftOrder()

            assertThatThrownBy { order.confirm("workflow-123") }
                .isInstanceOf(DomainException::class.java)
                .hasMessageContaining("empty order")
        }

        @Test
        @DisplayName("Should reject double confirmation")
        fun shouldRejectDoubleConfirmation() {
            val order = createConfirmedOrder()

            assertThatThrownBy { order.confirm("workflow-456") }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }
    }

    @Nested
    @DisplayName("Fulfillment Steps")
    inner class FulfillmentSteps {

        @Test
        @DisplayName("Should record inventory reservation")
        fun shouldRecordInventoryReservation() {
            val order = createConfirmedOrder()
            order.drainPendingEvents()

            order.reserveInventory("RES-001")

            assertThat(order.status).isEqualTo(OrderStatus.INVENTORY_RESERVED)
            assertThat(order.reservationId).isEqualTo("RES-001")

            val events = order.drainPendingEvents()
            assertThat(events.first()).isInstanceOf(InventoryReservedEvent::class.java)
        }

        @Test
        @DisplayName("Should record payment completion")
        fun shouldRecordPaymentCompletion() {
            val order = createInventoryReservedOrder()
            order.drainPendingEvents()

            order.completePayment("TXN-001", Money.of(20.00))

            assertThat(order.status).isEqualTo(OrderStatus.PAYMENT_COMPLETED)
            assertThat(order.paymentStatus).isEqualTo(PaymentStatus.COMPLETED)
            assertThat(order.transactionId).isEqualTo("TXN-001")
        }

        @Test
        @DisplayName("Should record shipment and delivery")
        fun shouldRecordShipmentAndDelivery() {
            val order = createPaymentCompletedOrder()
            order.drainPendingEvents()

            order.createShipment("SHIP-001", "1ZTRACKING123", "UPS")
            assertThat(order.status).isEqualTo(OrderStatus.SHIPPED)

            order.deliverOrder("SHIP-001")
            assertThat(order.status).isEqualTo(OrderStatus.DELIVERED)
        }
    }

    @Nested
    @DisplayName("Event Replay (Reconstitution)")
    inner class Reconstitution {

        /**
         * This test verifies the CORE principle of event sourcing:
         * State can be rebuilt by replaying events.
         *
         * WHY IS THIS IMPORTANT?
         * If your aggregate can be reconstituted from events, you can:
         * 1. Rebuild state after system failures
         * 2. Travel back in time to any point in the past
         * 3. Build new read models from the same events
         * 4. Audit every state change that ever happened
         */
        @Test
        @DisplayName("Should reconstitute order from events")
        fun shouldReconstituteFromEvents() {
            // Create an order through its lifecycle
            val original = Order.create(ORDER_ID, CUSTOMER_ID, ADDRESS)
            val productId = UUID.randomUUID()
            original.addItem(OrderItem(productId, "Widget", 2, Money.of(15.00)))
            original.confirm("workflow-replay-test")
            original.reserveInventory("RES-REPLAY")
            original.completePayment("TXN-REPLAY", original.totalAmount)
            original.createShipment("SHIP-REPLAY", "TRACK123", "UPS")

            // Collect all events
            val allEvents = original.drainPendingEvents()

            // Reconstitute a new order from those events
            val reconstituted = Order.reconstitute(allEvents)

            // Verify all state matches
            assertThat(reconstituted.id).isEqualTo(original.id)
            assertThat(reconstituted.status).isEqualTo(OrderStatus.SHIPPED)
            assertThat(reconstituted.customerId).isEqualTo(CUSTOMER_ID)
            assertThat(reconstituted.totalAmount).isEqualTo(Money.of(30.00))
            assertThat(reconstituted.reservationId).isEqualTo("RES-REPLAY")
            assertThat(reconstituted.transactionId).isEqualTo("TXN-REPLAY")
            assertThat(reconstituted.trackingNumber).isEqualTo("TRACK123")
            assertThat(reconstituted.version).isEqualTo(original.version)
        }

        @Test
        @DisplayName("Should fail to reconstitute from empty event list")
        fun shouldFailWithEmptyEvents() {
            assertThatThrownBy { Order.reconstitute(emptyList()) }
                .isInstanceOf(DomainException::class.java)
        }
    }

    @Nested
    @DisplayName("Snapshot Support")
    inner class SnapshotSupport {

        /**
         * Verifies the snapshot → event replay optimization works correctly.
         * SNAPSHOT_THRESHOLD events are typically 50 in production.
         * Here we test the round-trip: create snapshot, restore, replay remaining events.
         */
        @Test
        @DisplayName("Should create and restore from snapshot")
        fun shouldCreateAndRestoreFromSnapshot() {
            val original = createShippedOrder()
            original.drainPendingEvents()

            // Take a snapshot
            val snapshot: OrderSnapshot = original.toSnapshot()
            assertThat(snapshot.aggregateId).isEqualTo(ORDER_ID.toString())
            assertThat(snapshot.version).isEqualTo(original.version)
            assertThat(snapshot.status).isEqualTo(OrderStatus.SHIPPED)

            // Restore from snapshot
            val restored = Order.reconstituteFromSnapshot(snapshot, emptyList())

            assertThat(restored.id).isEqualTo(original.id)
            assertThat(restored.status).isEqualTo(original.status)
            assertThat(restored.version).isEqualTo(original.version)
            assertThat(restored.totalAmount).isEqualTo(original.totalAmount)
        }

        @Test
        @DisplayName("Should restore from snapshot and replay subsequent events")
        fun shouldRestoreAndReplayEvents() {
            val order = createShippedOrder()
            order.drainPendingEvents()

            // Simulate: take snapshot at version 5, then deliver (version 6)
            val snapshot = order.toSnapshot()
            order.deliverOrder(order.shipmentId!!)
            val newEvents = order.drainPendingEvents()

            // Reconstitute from snapshot + new events
            val restored = Order.reconstituteFromSnapshot(snapshot, newEvents)

            assertThat(restored.status).isEqualTo(OrderStatus.DELIVERED)
            assertThat(restored.version).isEqualTo(order.version)
        }
    }

    @Nested
    @DisplayName("Saga Compensation")
    inner class SagaCompensation {

        @Test
        @DisplayName("Should cancel order from any non-terminal state")
        fun shouldCancelFromAnyState() {
            // Cancel from DRAFT
            val draft = createDraftOrder()
            draft.cancel("Changed mind", "CUSTOMER")
            assertThat(draft.status).isEqualTo(OrderStatus.CANCELLED)

            // Cancel from CONFIRMED
            val confirmed = createConfirmedOrder()
            confirmed.cancel("Inventory failed", "SYSTEM_COMPENSATION")
            assertThat(confirmed.status).isEqualTo(OrderStatus.CANCELLED)

            // Cancel from INVENTORY_RESERVED
            val reserved = createInventoryReservedOrder()
            reserved.cancel("Payment failed", "SYSTEM_COMPENSATION")
            assertThat(reserved.status).isEqualTo(OrderStatus.CANCELLED)
        }

        @Test
        @DisplayName("Should reject cancellation from terminal states")
        fun shouldRejectCancellingTerminalOrder() {
            val delivered = createDeliveredOrder()

            assertThatThrownBy { delivered.cancel("Too late", "CUSTOMER") }
                .isInstanceOf(InvalidStateTransitionException::class.java)
                .hasMessageContaining("terminal state")
        }

        @Test
        @DisplayName("Should release inventory as compensation")
        fun shouldReleaseInventoryAsCompensation() {
            val order = createInventoryReservedOrder()
            order.drainPendingEvents()

            order.releaseInventory("Payment failed")

            val events = order.drainPendingEvents()
            assertThat(events.first()).isInstanceOf(InventoryReleasedEvent::class.java)
        }

        @Test
        @DisplayName("Should refund payment as compensation")
        fun shouldRefundPaymentAsCompensation() {
            val order = createPaymentCompletedOrder()
            order.drainPendingEvents()

            order.completeRefund("REFUND-001", Money.of(30.00))

            val events = order.drainPendingEvents()
            assertThat(events.first()).isInstanceOf(RefundCompletedEvent::class.java)
            assertThat(order.paymentStatus).isEqualTo(PaymentStatus.REFUNDED)
        }
    }

    // ─────────────────────────────────────────────────────
    // Test Fixture Helpers
    // ─────────────────────────────────────────────────────

    private fun createDraftOrder(): Order = Order.create(ORDER_ID, CUSTOMER_ID, ADDRESS)

    private fun createConfirmedOrder(): Order {
        val order = createDraftOrder()
        order.addItem(createItem("Widget", 2, 15.00))
        order.confirm("workflow-test")
        return order
    }

    private fun createInventoryReservedOrder(): Order {
        val order = createConfirmedOrder()
        order.reserveInventory("RES-TEST")
        return order
    }

    private fun createPaymentCompletedOrder(): Order {
        val order = createInventoryReservedOrder()
        order.completePayment("TXN-TEST", Money.of(30.00))
        return order
    }

    private fun createShippedOrder(): Order {
        val order = createPaymentCompletedOrder()
        order.createShipment("SHIP-TEST", "TRACK-TEST", "UPS")
        return order
    }

    private fun createDeliveredOrder(): Order {
        val order = createShippedOrder()
        order.deliverOrder("SHIP-TEST")
        return order
    }

    private fun createItem(name: String, qty: Int, price: Double): OrderItem =
        OrderItem(UUID.randomUUID(), name, qty, Money.of(price))
}
