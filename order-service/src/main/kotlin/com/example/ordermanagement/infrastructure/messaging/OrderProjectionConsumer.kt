package com.example.ordermanagement.infrastructure.messaging

import com.example.contracts.messaging.OrderEventMessage
import com.example.ordermanagement.infrastructure.projection.OrderSummaryRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Builds and maintains the order_summary read model by consuming order.events.
 *
 * Consumer group / durable queue: order-projection-group
 * The queue is subscribed to the order.events topic and provisioned idempotently
 * at startup (see TODO below) — this allows full replay from the beginning of the
 * queue's backlog if the order_summary table is ever dropped or needs to be rebuilt.
 *
 * Replaces the previous @TransactionalEventListener approach which was
 * in-process and had no replay capability.
 */
@Component
class OrderProjectionConsumer(
    private val repository: OrderSummaryRepository,
) {

    companion object {
        private val log = LoggerFactory.getLogger(OrderProjectionConsumer::class.java)

        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 2000L
    }

    // TODO(config-agent): provision durable queue "order-projection-group" subscribed
    // to topic "order.events" here (self-provisioned idempotently at startup via
    // SolJmsUtility / Solace-specific Session casts, per architecture.md Solace Rules).
    @JmsListener(
        destination = "order-projection-group",
        containerFactory = "projectionListenerContainerFactory",
    )
    fun onOrderEvent(message: OrderEventMessage) {
        var attempt = 0
        while (true) {
            try {
                applyProjection(message)
                return
            } catch (e: DuplicateKeyException) {
                // OrderCreated replayed — row already exists, safe to skip
                log.debug(
                    "Projection already applied for order {} ({}), skipping (idempotent)",
                    message.orderId, message.javaClass.simpleName,
                )
                return
            } catch (e: Exception) {
                attempt++
                if (attempt > MAX_RETRIES) {
                    throw e
                }
                log.warn(
                    "Retrying projection update for order {} ({}) after failure, attempt {}/{}",
                    message.orderId, message.javaClass.simpleName, attempt, MAX_RETRIES, e,
                )
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }

    private fun applyProjection(message: OrderEventMessage) {
        when (message) {
            is OrderEventMessage.OrderCreatedMessage ->
                repository.insert(message.orderId, message.customerId, message.shippingAddress, message.occurredAt)

            is OrderEventMessage.ItemAddedMessage ->
                repository.incrementItemCount(message.orderId, message.occurredAt)

            is OrderEventMessage.ItemRemovedMessage ->
                repository.decrementItemCount(message.orderId, message.occurredAt)

            is OrderEventMessage.OrderConfirmedMessage ->
                repository.confirmOrder(
                    message.orderId, BigDecimal.valueOf(message.totalAmount),
                    message.workflowId, message.occurredAt,
                )

            is OrderEventMessage.InventoryReservedMessage ->
                repository.updateStatus(message.orderId, "INVENTORY_RESERVED", message.occurredAt)

            is OrderEventMessage.PaymentCompletedMessage ->
                repository.completePayment(message.orderId, message.occurredAt)

            is OrderEventMessage.PaymentFailedMessage ->
                repository.failPayment(message.orderId, message.occurredAt)

            is OrderEventMessage.RefundCompletedMessage ->
                repository.refundPayment(message.orderId, message.occurredAt)

            is OrderEventMessage.ShipmentCreatedMessage ->
                repository.createShipment(message.orderId, message.trackingNumber, message.occurredAt)

            is OrderEventMessage.ShipmentDeliveredMessage ->
                repository.deliverOrder(message.orderId, message.occurredAt)

            is OrderEventMessage.OrderCancelledMessage ->
                repository.cancelOrder(message.orderId, message.reason, message.occurredAt)

            is OrderEventMessage.InventoryReleasedMessage -> {}
        }
    }
}
