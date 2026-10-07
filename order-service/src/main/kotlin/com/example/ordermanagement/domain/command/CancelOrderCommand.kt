package com.example.ordermanagement.domain.command

import com.example.ordermanagement.domain.valueobject.OrderId

data class CancelOrderCommand(
    val orderId: OrderId,
    val reason: String
)
