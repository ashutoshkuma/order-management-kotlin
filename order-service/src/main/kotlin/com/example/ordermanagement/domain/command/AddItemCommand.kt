package com.example.ordermanagement.domain.command

import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderId
import java.util.UUID

data class AddItemCommand(
    val orderId: OrderId,
    val productId: UUID,
    val productName: String,
    val quantity: Int,
    val unitPrice: Money
)
