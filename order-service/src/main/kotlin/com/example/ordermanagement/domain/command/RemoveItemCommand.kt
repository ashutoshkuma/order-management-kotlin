package com.example.ordermanagement.domain.command

import com.example.ordermanagement.domain.valueobject.OrderId
import java.util.UUID

data class RemoveItemCommand(
    val orderId: OrderId,
    val productId: UUID
)
