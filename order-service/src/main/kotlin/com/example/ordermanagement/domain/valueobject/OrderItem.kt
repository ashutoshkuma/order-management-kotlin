package com.example.ordermanagement.domain.valueobject

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Value Object: OrderItem
 *
 * Represents a single line item within an order.
 * Note it is a VALUE OBJECT, not an Entity — it has no independent lifecycle.
 * Two OrderItems with identical productId, quantity, and price are equivalent.
 *
 * This is stored as part of the event payload in the event store,
 * NOT as a separate table row — that would require JOIN and break
 * the event sourcing model.
 */
data class OrderItem(
    val productId: UUID,
    val productName: String,
    val quantity: Int,
    val unitPrice: Money
) {

    init {
        require(quantity > 0) { "Quantity must be > 0, got: $quantity" }
        require(!productName.isBlank()) { "productName must not be blank" }
    }

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("productId") productId: UUID,
            @JsonProperty("productName") productName: String,
            @JsonProperty("quantity") quantity: Int,
            @JsonProperty("unitPrice") unitPrice: Money
        ): OrderItem = OrderItem(productId, productName, quantity, unitPrice)
    }

    /** Calculates the total price for this line item */
    fun totalPrice(): Money = unitPrice.multiply(quantity)

    /** Returns a new OrderItem with updated quantity — immutable update pattern */
    fun withQuantity(newQuantity: Int): OrderItem = OrderItem(productId, productName, newQuantity, unitPrice)
}
