package com.example.ordermanagement.api.dto.response

import java.math.BigDecimal
import java.time.Instant

/**
 * NOTE ON CONVERSION FROM JAVA:
 * Nullability here mirrors the `order_summary` table schema exactly
 * (V3__create_order_projections.sql) — total_amount, workflow_id,
 * tracking_number, shipping_address, confirmed_at, paid_at, delivered_at,
 * cancelled_at, and cancel_reason are all nullable columns, populated only
 * once the corresponding lifecycle event has occurred. OrderSummaryRepository
 * (infrastructure/projection, a Kotlin file owned by a sibling agent) passes
 * these straight from `ResultSet` reads, so a non-null Kotlin type here would
 * throw a NullPointerException (via Kotlin's generated parameter null-checks)
 * the first time a row with an unset column is mapped.
 *
 * `createdAt`/`updatedAt` are also typed nullable even though their columns
 * are NOT NULL: OrderSummaryRepository.mapRow() (infrastructure/projection,
 * sibling-owned) runs every timestamp column through a shared
 * `toInstant(Timestamp?): Instant?` helper, so the static type reaching this
 * constructor is `Instant?` for all timestamp columns alike. In practice
 * these two are always non-null at runtime.
 */
data class OrderSummaryResponse(
    val orderId: String,
    val customerId: String,
    val status: String,
    val paymentStatus: String,
    val shipmentStatus: String,
    val totalAmount: BigDecimal?,
    val itemCount: Int,
    val shippingAddress: String?,
    val workflowId: String?,
    val trackingNumber: String?,
    val createdAt: Instant?,
    val confirmedAt: Instant?,
    val paidAt: Instant?,
    val deliveredAt: Instant?,
    val cancelledAt: Instant?,
    val cancelReason: String?,
    val updatedAt: Instant?
)
