package com.example.ordermanagement.domain.command

import com.example.ordermanagement.domain.valueobject.CustomerId
import com.example.ordermanagement.domain.valueobject.OrderId

/**
 * Command: CreateOrderCommand
 *
 * Commands are INTENT — they represent what a user/system wants to do.
 * Commands can be rejected (validated, business rules checked).
 * Events cannot be rejected — they are facts that already happened.
 *
 * CQS/CQRS Principle:
 *   Commands change state, return nothing (or minimal acknowledgment).
 *   Queries read state, change nothing.
 *
 * NOTE ON CONVERSION FROM THE JAVA RECORD:
 * The original Java record had a compact constructor that substituted a
 * generated OrderId when null was passed. A Kotlin data class cannot
 * reassign a `val` primary-constructor parameter, so the incoming
 * (nullable) constructor parameter is normalized into the `orderId`
 * property below. Because of this, `orderId` is intentionally NOT a
 * primary-constructor `val` — only `customerId` and `shippingAddress`
 * participate in the generated equals/hashCode/toString/copy.
 */
class CreateOrderCommand(
    orderId: OrderId?,
    val customerId: CustomerId,
    val shippingAddress: String
) {
    val orderId: OrderId = orderId ?: OrderId.generate()
}
