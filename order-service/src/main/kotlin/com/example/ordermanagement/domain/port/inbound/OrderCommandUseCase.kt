package com.example.ordermanagement.domain.port.inbound

import com.example.ordermanagement.domain.command.AddItemCommand
import com.example.ordermanagement.domain.command.CancelOrderCommand
import com.example.ordermanagement.domain.command.ConfirmOrderCommand
import com.example.ordermanagement.domain.command.CreateOrderCommand
import com.example.ordermanagement.domain.command.RemoveItemCommand
import com.example.ordermanagement.domain.valueobject.OrderId

/**
 * Inbound Port: OrderCommandUseCase
 *
 * Defines all write operations available on orders.
 * Controllers depend on this interface, not on OrderCommandService directly,
 * keeping the hexagonal boundary intact.
 */
interface OrderCommandUseCase {

    fun createOrder(command: CreateOrderCommand): OrderId

    fun addItem(command: AddItemCommand)

    fun removeItem(command: RemoveItemCommand)

    fun confirmOrder(command: ConfirmOrderCommand)

    fun cancelOrder(command: CancelOrderCommand)

    fun recordPaymentCompleted(orderId: OrderId, transactionId: String)
}
