package com.example.notification.solace

import com.example.contracts.messaging.OrderEventMessage
import com.example.notification.service.EventIdempotencyService
import com.example.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * OrderEventConsumer — idempotent handler for order.events messages delivered via the
 * "notification-service-group" durable queue.
 *
 * Deduplicates by eventId using Redis (TTL=24h) via EventIdempotencyService.
 *
 * Not a @JmsListener itself — see NotificationQueueListener for why one physical
 * listener fans out to this class and PaymentEventConsumer instead of each owning its
 * own @JmsListener on the shared queue.
 *
 * DLQ hybrid: 2 retries, 2s apart (mirrors the Kafka-era FixedBackOff(2000L, 2)). On
 * exhaustion, publishes explicitly to order.events.dmq via DlqPublisher instead of
 * rethrowing — see DlqPublisher for why.
 */
@Component
class OrderEventConsumer(
    private val notificationService: NotificationService,
    private val idempotencyService: EventIdempotencyService,
    private val dlqPublisher: DlqPublisher,
) {

    companion object {
        private val log = LoggerFactory.getLogger(OrderEventConsumer::class.java)

        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 2000L
    }

    fun handle(message: OrderEventMessage) {
        if (!idempotencyService.isFirstOccurrence(message.eventId)) {
            return
        }

        var attempt = 0
        while (true) {
            try {
                dispatch(message)
                return
            } catch (e: Exception) {
                // Revert the idempotency mark so a retry attempt is not skipped.
                idempotencyService.deleteOccurrence(message.eventId)
                attempt++
                if (attempt > MAX_RETRIES) {
                    log.error(
                        "Failed to process {} for order {} after {} retries — routing to DMQ",
                        message.javaClass.simpleName, message.orderId, MAX_RETRIES, e,
                    )
                    dlqPublisher.publish(
                        SolaceDestinations.ORDER_EVENTS_DMQ,
                        SolaceDestinations.ORDER_EVENTS_TOPIC,
                        message.eventId,
                        message,
                        e,
                    )
                    return
                }
                log.warn(
                    "Failed to process {} for order {} — reverting idempotency mark, retrying ({}/{})",
                    message.javaClass.simpleName, message.orderId, attempt, MAX_RETRIES, e,
                )
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }

    private fun dispatch(message: OrderEventMessage) {
        when (message) {
            is OrderEventMessage.OrderCreatedMessage -> notificationService.notifyOrderCreated(message)
            is OrderEventMessage.OrderConfirmedMessage -> notificationService.notifyOrderConfirmed(message)
            is OrderEventMessage.OrderCancelledMessage -> notificationService.notifyOrderCancelled(message)
            is OrderEventMessage.PaymentCompletedMessage -> notificationService.notifyPaymentCompleted(message)
            is OrderEventMessage.PaymentFailedMessage -> notificationService.notifyPaymentFailed(message)
            is OrderEventMessage.ShipmentCreatedMessage -> notificationService.notifyShipmentCreated(message)
            is OrderEventMessage.ShipmentDeliveredMessage -> notificationService.notifyShipmentDelivered(message)
            is OrderEventMessage.InventoryReservedMessage,
            is OrderEventMessage.InventoryReleasedMessage,
            is OrderEventMessage.ItemAddedMessage,
            is OrderEventMessage.ItemRemovedMessage,
            is OrderEventMessage.RefundCompletedMessage,
            -> Unit
        }
    }
}
