package com.example.ordermanagement.domain.model

/**
 * Enum: OrderStatus
 *
 * Defines valid lifecycle states for an Order aggregate.
 * State transitions are enforced in the aggregate's command handlers.
 *
 * Valid Transitions:
 *   DRAFT → CONFIRMED → INVENTORY_RESERVED → PAYMENT_PROCESSING
 *         → PAYMENT_COMPLETED → SHIPPED → DELIVERED
 *         (at many stages) → CANCELLED
 *
 * Why not use a state machine library?
 * For a PoC, explicit guard clauses in the aggregate are clearer and
 * easier to understand. In production, consider using Spring State Machine
 * or a custom state machine for complex transition rules.
 */
enum class OrderStatus {
    /**
     * Order has been created but not yet confirmed.
     * Items can still be added/removed.
     */
    DRAFT,

    /**
     * Customer has confirmed the order.
     * No further item modifications allowed.
     * Temporal workflow has been initiated.
     */
    CONFIRMED,

    /**
     * Inventory has been reserved for all items.
     * Temporal workflow is proceeding to payment.
     */
    INVENTORY_RESERVED,

    /**
     * Payment is being processed.
     */
    PAYMENT_PROCESSING,

    /**
     * Payment has been successfully completed.
     */
    PAYMENT_COMPLETED,

    /**
     * Shipment has been created and dispatched.
     */
    SHIPPED,

    /**
     * Order has been delivered to the customer.
     */
    DELIVERED,

    /**
     * Order was cancelled — could be by customer signal or compensation.
     * Terminal state.
     */
    CANCELLED
}
