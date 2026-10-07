package com.example.ordermanagement.domain.aggregate

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
import com.example.ordermanagement.domain.exception.DomainException
import com.example.ordermanagement.domain.exception.InvalidStateTransitionException
import com.example.ordermanagement.domain.exception.OptimisticLockingException
import com.example.ordermanagement.domain.model.OrderStatus
import com.example.ordermanagement.domain.model.PaymentStatus
import com.example.ordermanagement.domain.model.ShipmentStatus
import com.example.ordermanagement.domain.valueobject.CustomerId
import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderId
import com.example.ordermanagement.domain.valueobject.OrderItem
import java.util.Collections
import java.util.UUID

/**
 * Aggregate Root: Order
 *
 * ═══════════════════════════════════════════════════════════════════
 * WHAT IS AN AGGREGATE ROOT?
 * ═══════════════════════════════════════════════════════════════════
 * An Aggregate Root is the entry point to a cluster of domain objects.
 * All changes to the order and its items MUST go through this class.
 * Nothing should modify Order's internal state from outside.
 *
 * ═══════════════════════════════════════════════════════════════════
 * EVENT SOURCING PATTERN
 * ═══════════════════════════════════════════════════════════════════
 * This aggregate follows the Event Sourcing pattern:
 *
 *   1. COMMAND comes in (e.g., AddItemCommand)
 *   2. Aggregate validates business rules (can we add items in this state?)
 *   3. If valid, create a DomainEvent (ItemAddedEvent)
 *   4. Apply the event to update internal state (apply(event))
 *   5. The event is added to pendingEvents list (NOT yet persisted)
 *   6. The application service saves pending events and clears them
 *
 * This means state is NEVER mutated directly — only through events.
 *
 *   ┌─────────────┐     ┌──────────────┐     ┌──────────────────┐
 *   │   Command   │────▶│  Aggregate   │────▶│  Domain Events   │
 *   └─────────────┘     │  (validate)  │     │  (state change)  │
 *                       └──────────────┘     └──────────────────┘
 *                                                     │
 *                                                     ▼
 *                                            ┌────────────────┐
 *                                            │  Event Store   │
 *                                            │  (PostgreSQL)  │
 *                                            └────────────────┘
 *
 * ═══════════════════════════════════════════════════════════════════
 * OPTIMISTIC LOCKING
 * ═══════════════════════════════════════════════════════════════════
 * The 'version' field implements optimistic locking.
 * When loading: we record the current version
 * When saving:  we check that version in DB == our loaded version
 * If another request modified the aggregate concurrently, the versions
 * won't match and we throw OptimisticLockingException.
 *
 * This prevents "lost updates" without pessimistic DB locks.
 *
 * ═══════════════════════════════════════════════════════════════════
 * INTERACTION WITH TEMPORAL
 * ═══════════════════════════════════════════════════════════════════
 * Temporal does NOT call the aggregate directly.
 * Temporal calls Activities → Activities call Application Services →
 * Application Services call this aggregate via commands.
 *
 * The aggregate is ONLY aware of its domain — it knows nothing about
 * Temporal workflows. This separation is intentional.
 *
 * NOTE ON CONVERSION FROM JAVA:
 * This is intentionally a plain mutable Kotlin `class` (NOT a `data class`).
 * Its fields are mutated exclusively through the private `applyXxx` methods,
 * invoked from `apply()`, which mirrors the original Java design where all
 * mutation is funneled through event application. `id`, `customerId`,
 * `status`, and `shippingAddress` use `lateinit var` because they are always
 * populated by the very first event applied to an instance (an `Order` can
 * only be constructed via `create()`, `reconstitute()`, or
 * `restoreFromSnapshot()` — never bare). The remaining optional fields
 * (`workflowId`, `reservationId`, `transactionId`, `shipmentId`,
 * `trackingNumber`) stay nullable `var`s, matching their Java counterparts
 * which may legitimately remain null for the lifetime of an order.
 */
class Order private constructor() {

    // ─────────────────────────────────────────────────────
    // Identity and versioning
    // ─────────────────────────────────────────────────────

    lateinit var id: OrderId
        private set

    var version: Long = 0
        private set

    // ─────────────────────────────────────────────────────
    // Domain state
    // ─────────────────────────────────────────────────────

    lateinit var customerId: CustomerId
        private set

    lateinit var status: OrderStatus
        private set

    private val mutableItems: MutableList<OrderItem> = mutableListOf()

    val items: List<OrderItem>
        get() = Collections.unmodifiableList(mutableItems)

    var totalAmount: Money = Money.ZERO
        private set

    var paymentStatus: PaymentStatus = PaymentStatus.PENDING
        private set

    var shipmentStatus: ShipmentStatus = ShipmentStatus.NOT_CREATED
        private set

    lateinit var shippingAddress: String
        private set

    var workflowId: String? = null
        private set

    var reservationId: String? = null
        private set

    var transactionId: String? = null
        private set

    var shipmentId: String? = null
        private set

    var trackingNumber: String? = null
        private set

    // ─────────────────────────────────────────────────────
    // Event sourcing infrastructure
    // ─────────────────────────────────────────────────────

    /**
     * Pending events that have been raised but not yet persisted.
     * The application service drains this list and writes to the event store.
     * This pattern is called "domain event collection".
     */
    private val pendingEvents: MutableList<DomainEvent> = mutableListOf()

    // ═══════════════════════════════════════════════════════════════════
    // COMMAND HANDLERS — Public API of the aggregate
    // These validate business rules and raise events.
    // They NEVER directly mutate state — that happens only in apply().
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Adds an item to a DRAFT order.
     * Business rules:
     *   - Order must be in DRAFT status
     *   - Duplicate products are merged (quantity increased)
     */
    fun addItem(item: OrderItem) {
        requireStatus(OrderStatus.DRAFT, "add items to")

        // Check if product already in order — merge quantities
        val existing = mutableItems.find { it.productId == item.productId }

        if (existing != null) {
            // Create a remove + re-add event to update quantity cleanly
            // (In production, use a dedicated UpdateItemQuantityEvent instead)
            val merged = existing.withQuantity(existing.quantity + item.quantity)
            val removeEvent = ItemRemovedEvent.create(id, item.productId, version + 1)
            applyAndRecord(removeEvent)
            val addEvent = ItemAddedEvent.create(id, merged, version + 1)
            applyAndRecord(addEvent)
        } else {
            val event = ItemAddedEvent.create(id, item, version + 1)
            applyAndRecord(event)
        }
    }

    /**
     * Removes an item from a DRAFT order.
     */
    fun removeItem(productId: UUID) {
        requireStatus(OrderStatus.DRAFT, "remove items from")

        val exists = mutableItems.any { it.productId == productId }
        if (!exists) {
            throw DomainException("Product $productId not found in order")
        }

        val event = ItemRemovedEvent.create(id, productId, version + 1)
        applyAndRecord(event)
    }

    /**
     * Confirms the order, triggering the fulfillment workflow.
     * Business rules:
     *   - Must be in DRAFT status
     *   - Must have at least one item
     *   - workflowId is provided externally (from Temporal, before workflow starts)
     */
    fun confirm(workflowId: String) {
        requireStatus(OrderStatus.DRAFT, "confirm")

        if (mutableItems.isEmpty()) {
            throw DomainException("Cannot confirm an empty order")
        }

        val total = calculateTotal()
        if (total.isZero()) {
            throw DomainException("Order total cannot be zero")
        }

        val event = OrderConfirmedEvent.create(id, total, workflowId, version + 1)
        applyAndRecord(event)
    }

    /**
     * Records that inventory was successfully reserved.
     * Called by the application service after Temporal's InventoryActivity succeeds.
     */
    fun reserveInventory(reservationId: String) {
        requireStatus(OrderStatus.CONFIRMED, "reserve inventory for")

        val event = InventoryReservedEvent.create(id, reservationId, version + 1)
        applyAndRecord(event)
    }

    /**
     * Records inventory reservation failure.
     */
    fun failInventoryReservation(reason: String) {
        requireStatus(OrderStatus.CONFIRMED, "fail inventory reservation for")

        val event = InventoryReservationFailedEvent.create(id, reason, version + 1)
        applyAndRecord(event)
    }

    /**
     * Records inventory release (compensation step).
     * Allowed from any state where inventory was previously reserved —
     * compensation may run after payment or shipment steps have already advanced the status.
     */
    fun releaseInventory(reason: String) {
        if (status != OrderStatus.INVENTORY_RESERVED &&
            status != OrderStatus.PAYMENT_PROCESSING &&
            status != OrderStatus.PAYMENT_COMPLETED &&
            status != OrderStatus.SHIPPED
        ) {
            throw InvalidStateTransitionException(
                "Cannot release inventory for order in status: $status"
            )
        }
        val event = InventoryReleasedEvent.create(id, reservationId, reason, version + 1)
        applyAndRecord(event)
    }

    /**
     * Records successful payment.
     */
    fun completePayment(transactionId: String, amountCharged: Money) {
        requireStatus(OrderStatus.INVENTORY_RESERVED, "process payment for")

        val event = PaymentCompletedEvent.create(id, transactionId, amountCharged, version + 1)
        applyAndRecord(event)
    }

    /**
     * Records payment failure.
     */
    fun failPayment(reason: String, retryable: Boolean) {
        if (status != OrderStatus.INVENTORY_RESERVED &&
            status != OrderStatus.PAYMENT_PROCESSING
        ) {
            throw InvalidStateTransitionException(
                "Cannot fail payment for order in status: $status"
            )
        }
        val event = PaymentFailedEvent.create(id, reason, retryable, version + 1)
        applyAndRecord(event)
    }

    /**
     * Records refund completion (compensation step after payment or shipment failure).
     * Allowed from PAYMENT_COMPLETED (shipment/cancel after payment) and SHIPPED
     * (cancel after createShipment but before delivery).
     */
    fun completeRefund(refundTxId: String, amountRefunded: Money) {
        if (status != OrderStatus.PAYMENT_COMPLETED &&
            status != OrderStatus.SHIPPED
        ) {
            throw InvalidStateTransitionException(
                "Cannot refund payment for order in status: $status"
            )
        }
        val event = RefundCompletedEvent.create(id, refundTxId, amountRefunded, version + 1)
        applyAndRecord(event)
    }

    /**
     * Records shipment creation.
     */
    fun createShipment(shipmentId: String, trackingNumber: String, carrier: String) {
        requireStatus(OrderStatus.PAYMENT_COMPLETED, "create shipment for")

        val event = ShipmentCreatedEvent.create(id, shipmentId, trackingNumber, carrier, version + 1)
        applyAndRecord(event)
    }

    /**
     * Records delivery confirmation.
     */
    fun deliverOrder(shipmentId: String) {
        requireStatus(OrderStatus.SHIPPED, "deliver")

        val event = ShipmentDeliveredEvent.create(id, shipmentId, version + 1)
        applyAndRecord(event)
    }

    /**
     * Cancels the order — can be called from any non-terminal state.
     */
    fun cancel(reason: String, cancelledBy: String) {
        if (status == OrderStatus.DELIVERED || status == OrderStatus.CANCELLED) {
            throw InvalidStateTransitionException(
                "Cannot cancel an order in terminal state: $status"
            )
        }
        val event = OrderCancelledEvent.create(id, reason, cancelledBy, version + 1)
        applyAndRecord(event)
    }

    // ═══════════════════════════════════════════════════════════════════
    // EVENT APPLICATION — apply() methods
    // These are the ONLY place where state is mutated.
    // They must be side-effect free (no DB calls, no HTTP calls).
    // They are called during both command handling AND event replay.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Dispatches an event to the correct apply() method.
     * Uses Kotlin's `when` pattern matching over the sealed `DomainEvent`
     * interface — exhaustive by construction.
     *
     * WHY PATTERN MATCHING?
     * If a new event type is added to the sealed DomainEvent interface,
     * the compiler forces you to handle it here (non-exhaustive `when`
     * over a sealed type is a compile error). No silent omissions.
     */
    fun apply(event: DomainEvent) {
        when (event) {
            is OrderCreatedEvent -> applyOrderCreated(event)
            is ItemAddedEvent -> applyItemAdded(event)
            is ItemRemovedEvent -> applyItemRemoved(event)
            is OrderConfirmedEvent -> applyOrderConfirmed(event)
            is InventoryReservedEvent -> applyInventoryReserved(event)
            is InventoryReservationFailedEvent -> applyInventoryReservationFailed(event)
            is InventoryReleasedEvent -> applyInventoryReleased(event)
            is PaymentCompletedEvent -> applyPaymentCompleted(event)
            is PaymentFailedEvent -> applyPaymentFailed(event)
            is RefundCompletedEvent -> applyRefundCompleted(event)
            is ShipmentCreatedEvent -> applyShipmentCreated(event)
            is ShipmentDeliveredEvent -> applyShipmentDelivered(event)
            is OrderCancelledEvent -> applyOrderCancelled(event)
        }
        // Version always advances with each event
        this.version = event.version
    }

    private fun applyOrderCreated(event: OrderCreatedEvent) {
        this.id = OrderId.of(event.aggregateId)
        this.customerId = event.customerId
        this.status = OrderStatus.DRAFT
        this.shippingAddress = event.shippingAddress
    }

    private fun applyItemAdded(event: ItemAddedEvent) {
        this.mutableItems.add(event.item)
        this.totalAmount = calculateTotal()
    }

    private fun applyItemRemoved(event: ItemRemovedEvent) {
        this.mutableItems.removeIf { it.productId == event.productId }
        this.totalAmount = calculateTotal()
    }

    private fun applyOrderConfirmed(event: OrderConfirmedEvent) {
        this.status = OrderStatus.CONFIRMED
        this.totalAmount = event.totalAmount
        this.workflowId = event.workflowId
    }

    private fun applyInventoryReserved(event: InventoryReservedEvent) {
        this.status = OrderStatus.INVENTORY_RESERVED
        this.reservationId = event.reservationId
    }

    private fun applyInventoryReservationFailed(event: InventoryReservationFailedEvent) {
        // Status stays CONFIRMED; order will be cancelled in next event
    }

    private fun applyInventoryReleased(event: InventoryReleasedEvent) {
        this.reservationId = null
    }

    private fun applyPaymentCompleted(event: PaymentCompletedEvent) {
        this.status = OrderStatus.PAYMENT_COMPLETED
        this.paymentStatus = PaymentStatus.COMPLETED
        this.transactionId = event.transactionId
    }

    private fun applyPaymentFailed(event: PaymentFailedEvent) {
        this.paymentStatus = PaymentStatus.FAILED
        this.status = OrderStatus.PAYMENT_PROCESSING
    }

    private fun applyRefundCompleted(event: RefundCompletedEvent) {
        this.paymentStatus = PaymentStatus.REFUNDED
    }

    private fun applyShipmentCreated(event: ShipmentCreatedEvent) {
        this.status = OrderStatus.SHIPPED
        this.shipmentStatus = ShipmentStatus.CREATED
        this.shipmentId = event.shipmentId
        this.trackingNumber = event.trackingNumber
    }

    private fun applyShipmentDelivered(event: ShipmentDeliveredEvent) {
        this.status = OrderStatus.DELIVERED
        this.shipmentStatus = ShipmentStatus.DELIVERED
    }

    private fun applyOrderCancelled(event: OrderCancelledEvent) {
        this.status = OrderStatus.CANCELLED
    }

    // ═══════════════════════════════════════════════════════════════════
    // SNAPSHOT SUPPORT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a snapshot of current state for performance optimization.
     * Called after every SNAPSHOT_THRESHOLD events.
     *
     * The snapshot is persisted separately in the order_snapshots table.
     * On future loads, we load the snapshot + events AFTER the snapshot version.
     */
    fun toSnapshot(): OrderSnapshot = OrderSnapshot.of(this)

    // ═══════════════════════════════════════════════════════════════════
    // INFRASTRUCTURE METHODS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Records and immediately applies an event.
     * "Apply first, persist later" pattern ensures the aggregate is
     * in the correct state for subsequent commands in the same request.
     */
    private fun applyAndRecord(event: DomainEvent) {
        apply(event)
        pendingEvents.add(event)
    }

    /**
     * Returns pending events and clears the list.
     * Called by the application service to drain events before persisting.
     * This is a "once per request" operation.
     */
    fun drainPendingEvents(): List<DomainEvent> {
        val events: List<DomainEvent> = java.util.List.copyOf(pendingEvents)
        pendingEvents.clear()
        return events
    }

    /**
     * Validates that the current version matches an expected version.
     * Used for optimistic locking checks.
     *
     * OPTIMISTIC LOCKING FLOW:
     *   1. Load order at version N
     *   2. Handle command, raise events
     *   3. When saving events, WHERE aggregate_id=X AND version=N
     *   4. If 0 rows updated → another request modified it → throw exception
     */
    fun validateVersion(expectedVersion: Long) {
        if (this.version != expectedVersion) {
            throw OptimisticLockingException(
                "Order $id: expected version $expectedVersion but current version is ${this.version}"
            )
        }
    }

    fun hasPendingEvents(): Boolean = pendingEvents.isNotEmpty()

    // ─────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────

    private fun calculateTotal(): Money =
        mutableItems.fold(Money.ZERO) { acc, item -> acc.add(item.totalPrice()) }

    private fun requireStatus(required: OrderStatus, action: String) {
        if (this.status != required) {
            throw InvalidStateTransitionException(
                "Cannot $action order in status ${this.status}. Required: $required"
            )
        }
    }

    companion object {

        // ═══════════════════════════════════════════════════════════════════
        // STATIC FACTORY — creates a new aggregate
        // ═══════════════════════════════════════════════════════════════════

        /**
         * Creates a new Order.
         * This is a STATIC factory — an Order cannot exist without this event.
         *
         * Why static? Because there is no Order instance to call yet.
         * The event IS the creation of the aggregate.
         */
        @JvmStatic
        fun create(orderId: OrderId, customerId: CustomerId, shippingAddress: String?): Order {
            if (shippingAddress.isNullOrBlank()) {
                throw DomainException("Shipping address is required")
            }

            val order = Order()
            // version starts at 0, first event makes it 1
            val event = OrderCreatedEvent.create(orderId, customerId, shippingAddress, 1L)
            order.applyAndRecord(event)
            return order
        }

        // ═══════════════════════════════════════════════════════════════════
        // RECONSTITUTION — Rebuild from events (Replay)
        // ═══════════════════════════════════════════════════════════════════

        /**
         * Rebuilds an Order aggregate from a list of stored events.
         *
         * REPLAY EXPLAINED:
         * Instead of loading "current state" from a table, we replay ALL events
         * from the beginning of time. Each event is applied in sequence.
         * The final state is identical to what you'd get from a traditional DB.
         *
         * Benefits:
         *   - Complete audit trail
         *   - Can replay to any point in time for debugging
         *   - Can build new projections from old events
         *
         * Performance:
         *   - With 50+ events, we first load the latest SNAPSHOT
         *   - Then replay only events AFTER the snapshot
         *   - This bounds replay time to at most ~50 events
         *
         * @param events ordered list of events from version 1 onwards (or since snapshot)
         */
        @JvmStatic
        fun reconstitute(events: List<DomainEvent>): Order {
            if (events.isEmpty()) {
                throw DomainException("Cannot reconstitute an Order from empty event list")
            }

            val order = Order()
            for (event in events) {
                order.apply(event)
            }
            return order
        }

        /**
         * Rebuilds from a snapshot + subsequent events.
         * This is the performance optimization path used when an aggregate
         * has accumulated more than SNAPSHOT_THRESHOLD (50) events.
         */
        @JvmStatic
        fun reconstituteFromSnapshot(snapshot: OrderSnapshot, eventsAfterSnapshot: List<DomainEvent>): Order {
            val order = snapshot.toOrder()
            for (event in eventsAfterSnapshot) {
                order.apply(event)
            }
            return order
        }

        /**
         * Restores an Order from a snapshot without going through event replay.
         * Internal — only OrderSnapshot should call this.
         * Keeps the restoration logic co-located with the snapshot type.
         */
        @JvmStatic
        internal fun restoreFromSnapshot(snapshot: OrderSnapshot): Order {
            val order = Order()
            order.id = OrderId.of(snapshot.aggregateId)
            order.version = snapshot.version
            order.customerId = CustomerId.of(snapshot.customerId)
            order.status = snapshot.status
            order.mutableItems.addAll(snapshot.items ?: emptyList())
            order.totalAmount = snapshot.totalAmount ?: Money.ZERO
            order.paymentStatus = snapshot.paymentStatus ?: PaymentStatus.PENDING
            order.shipmentStatus = snapshot.shipmentStatus ?: ShipmentStatus.NOT_CREATED
            order.shippingAddress = snapshot.shippingAddress
            order.workflowId = snapshot.workflowId
            order.reservationId = snapshot.reservationId
            order.transactionId = snapshot.transactionId
            order.shipmentId = snapshot.shipmentId
            order.trackingNumber = snapshot.trackingNumber
            return order
        }
    }
}
