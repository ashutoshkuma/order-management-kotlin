package com.example.ordermanagement.domain.command

import com.example.ordermanagement.domain.valueobject.OrderId

data class ConfirmOrderCommand(val orderId: OrderId)
