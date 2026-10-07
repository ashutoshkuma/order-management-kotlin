package com.example.contracts.api

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP API contracts for the Inventory Service.
 *
 * Used by:
 *   - InventoryActivityImpl (order-service) as request/response types
 *   - InventoryController (inventory-service) as endpoint types
 *
 * Keeping them in shared-contracts means both sides always agree
 * on the request/response schema without coupling their domains.
 */
object InventoryContracts {

    data class ReserveInventoryRequest(
        @field:NotBlank val orderId: String,
        @field:NotNull val items: List<LineItem>,
    ) {
        data class LineItem(val productId: UUID, val quantity: Int)
    }

    data class ReserveInventoryResponse(
        val reservationId: String,
        val status: String, // "RESERVED"
        val message: String,
    )

    data class ReleaseInventoryResponse(
        val reservationId: String,
        val status: String, // "RELEASED"
    )
}
