package com.example.ordermanagement.domain.aggregate

import com.example.ordermanagement.domain.model.OrderStatus
import com.example.ordermanagement.domain.model.PaymentStatus
import com.example.ordermanagement.domain.model.ShipmentStatus
import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderItem
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * OrderSnapshot — Aggregate state at a specific version
 *
 * ═══════════════════════════════════════════════════════════════════
 * WHY SNAPSHOTS?
 * ═══════════════════════════════════════════════════════════════════
 * Event Sourcing requires replaying all events to rebuild state.
 * For an order with 3 events: fast.
 * For an order with 500 events (customer changed items, multiple retries): slow.
 *
 * Snapshotting solves this:
 *   - Every SNAPSHOT_THRESHOLD (50) events, save current state as snapshot
 *   - Future loads: load snapshot at version N + replay events from N+1 onwards
 *   - Bounded replay: at most 50 events need replaying
 *
 * SCHEMA: order_snapshots table
 *   - aggregate_id
 *   - version (the version at snapshot time)
 *   - snapshot_data (JSON of this record)
 *   - created_at
 *
 * WHEN IS A SNAPSHOT TAKEN?
 * After the application service persists events, it checks:
 *   if (order.getVersion() % SNAPSHOT_THRESHOLD == 0) { takeSnapshot(); }
 *
 * The snapshot captures EXACTLY the state after applying the event at that version.
 *
 * SNAPSHOT EVOLUTION:
 * Old snapshots may have missing fields as the domain evolves.
 * Use @JsonProperty with defaultValue or Optional to handle missing fields gracefully.
 * Never delete old snapshots — they must remain deserializable forever.
 *
 * NOTE ON CONVERSION FROM THE JAVA RECORD:
 * `items`, `totalAmount`, `paymentStatus`, and `shipmentStatus` are nullable here
 * (mirroring the original record, whose components carried no null-safety guarantee)
 * because `Order.restoreFromSnapshot` falls back to sensible defaults for exactly
 * these four fields when reading an older snapshot that predates them.
 */
data class OrderSnapshot(
    val snapshotId: UUID,
    val aggregateId: String,
    val version: Long,
    val takenAt: Instant,
    val customerId: String,
    val status: OrderStatus,
    val items: List<OrderItem>?,
    val totalAmount: Money?,
    val paymentStatus: PaymentStatus?,
    val shipmentStatus: ShipmentStatus?,
    val shippingAddress: String,
    val workflowId: String?,
    val reservationId: String?,
    val transactionId: String?,
    val shipmentId: String?,
    val trackingNumber: String?
) {

    companion object {

        const val SNAPSHOT_THRESHOLD: Int = 50

        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("snapshotId") snapshotId: UUID,
            @JsonProperty("aggregateId") aggregateId: String,
            @JsonProperty("version") version: Long,
            @JsonProperty("takenAt") takenAt: Instant,
            @JsonProperty("customerId") customerId: String,
            @JsonProperty("status") status: OrderStatus,
            @JsonProperty("items") items: List<OrderItem>?,
            @JsonProperty("totalAmount") totalAmount: Money?,
            @JsonProperty("paymentStatus") paymentStatus: PaymentStatus?,
            @JsonProperty("shipmentStatus") shipmentStatus: ShipmentStatus?,
            @JsonProperty("shippingAddress") shippingAddress: String,
            @JsonProperty("workflowId") workflowId: String?,
            @JsonProperty("reservationId") reservationId: String?,
            @JsonProperty("transactionId") transactionId: String?,
            @JsonProperty("shipmentId") shipmentId: String?,
            @JsonProperty("trackingNumber") trackingNumber: String?
        ): OrderSnapshot = OrderSnapshot(
            snapshotId, aggregateId, version, takenAt, customerId, status, items, totalAmount,
            paymentStatus, shipmentStatus, shippingAddress, workflowId, reservationId,
            transactionId, shipmentId, trackingNumber
        )

        /**
         * Creates a snapshot from an Order aggregate.
         * Called by the infrastructure layer when threshold is reached.
         */
        @JvmStatic
        fun of(order: Order): OrderSnapshot = OrderSnapshot(
            UUID.randomUUID(),
            order.id.toString(),
            order.version,
            Instant.now(),
            order.customerId.toString(),
            order.status,
            ArrayList(order.items),
            order.totalAmount,
            order.paymentStatus,
            order.shipmentStatus,
            order.shippingAddress,
            order.workflowId,
            order.reservationId,
            order.transactionId,
            order.shipmentId,
            order.trackingNumber
        )
    }

    /**
     * Reconstructs a mutable Order aggregate from this snapshot.
     * Used as the starting point for subsequent event replay.
     *
     * This uses reflection-free restoration via the domain events.
     * We use a "restore" method on Order to avoid exposing setters.
     */
    fun toOrder(): Order = Order.restoreFromSnapshot(this)
}
