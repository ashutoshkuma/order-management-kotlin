package com.example.ordermanagement.api.dto.response

import java.math.BigDecimal
import java.util.UUID

/**
 * REST Response DTO for Order state.
 * This is a "view model" — optimized for API consumers, not domain logic.
 */
data class OrderResponse(
    val orderId: UUID,
    val customerId: UUID,
    val status: String,
    val items: List<OrderItemResponse>,
    val totalAmount: BigDecimal,
    val currency: String,
    val paymentStatus: String,
    val shipmentStatus: String,
    val shippingAddress: String,
    val workflowId: String?,
    val trackingNumber: String?,
    val version: Long
) {
    data class OrderItemResponse(
        val productId: UUID,
        val productName: String,
        val quantity: Int,
        val unitPrice: BigDecimal,
        val totalPrice: BigDecimal
    )
}
